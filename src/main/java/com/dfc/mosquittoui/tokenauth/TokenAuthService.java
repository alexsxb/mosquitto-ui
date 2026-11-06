package com.dfc.mosquittoui.tokenauth;

import com.dfc.mosquittoui.api.Dtos.AccessDefaults;
import com.dfc.mosquittoui.api.Dtos.ClientView;
import com.dfc.mosquittoui.api.Dtos.RoleView;
import com.dfc.mosquittoui.backup.BackupException;
import com.dfc.mosquittoui.broker.DynsecChanged;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.broker.DynsecService;
import com.dfc.mosquittoui.broker.TokenRejectionsChanged;
import com.dfc.mosquittoui.security.PrivateFiles;
import com.dfc.mosquittoui.security.SecretCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * Login of MQTT clients with an access token: the identity providers and which of their groups get which roles. The plugin of
 * the broker (docker/mosquitto/token-auth) checks the tokens; this writes its file, with the keys of the providers and the rules
 * of the roles taken from dynamic security, and writes it again when either changes.
 */
@ApplicationScoped
public class TokenAuthService {

    public record TokenAuthMapping(String group, List<String> roles) {
    }

    /** Where the keys of an issuer come from. */
    public enum KeySource {
        /** The discovery document at the issuer, an OpenID provider such as Keycloak; the issuer is its address */
        discovery,
        /** A JWKS at its own address, jwksUrl; the issuer is whatever the tokens say in iss */
        jwks,
        /** Keys pasted on the page, staticKeys: PEM blocks of public keys or certificates, or a JWKS as JSON */
        keys
    }

    /**
     * audience: empty takes any; usernamePrefix: put before the name from the token, to keep providers apart; bindClientId: the
     * client id has to be the user name or start with "user name/", so that a token cannot take over the session of another.
     * keySource: null in settings from before it existed, which means discovery.
     */
    public record TokenAuthIssuer(String issuer, @Schema(nullable = true) String audience, String usernameClaim, String groupsClaim,
            @Schema(nullable = true) String usernamePrefix, boolean bindClientId, @Schema(nullable = true) KeySource keySource,
            @Schema(nullable = true) String jwksUrl, @Schema(nullable = true) String staticKeys, List<TokenAuthMapping> mappings) {

        KeySource source() {
            return keySource == null ? KeySource.discovery : keySource;
        }
    }

    /** What a block compares: the user name as the broker sees it (with prefix), the subject, or the id of one token. */
    public enum BlockField {
        username, sub, jti
    }

    /**
     * A token this application issued. Its roles are kept here, not in the token, by its id (jti): they can change, and deleting
     * the entry takes them away, which keeps the token out.
     */
    public record IssuedToken(String id, String username, List<String> roles, Instant created, Instant expires, String createdBy,
            @Schema(nullable = true) String note) {
    }

    public record IssueInput(String username, List<String> roles, Instant expires, @Schema(nullable = true) String note) {
    }

    /** token: shown this once, never kept. */
    public record IssueResult(IssuedToken issued, String token, String issuer) {
    }

    /** issuer: null for every issuer; until: null for good. */
    public record TokenBlock(String id, BlockField field, String value, @Schema(nullable = true) String issuer, @Schema(nullable = true) Instant until,
            @Schema(nullable = true) String reason, Instant created, String createdBy) {
    }

    public record TokenBlockInput(BlockField field, String value, @Schema(nullable = true) String issuer, @Schema(nullable = true) Instant until,
            @Schema(nullable = true) String reason) {
    }

    public record TokenAuthInput(boolean enabled, List<TokenAuthIssuer> issuers) {
    }

    /** The keys last fetched from a provider; error: why the last attempt failed, the keys before stay in force. */
    public record IssuerStatus(String issuer, List<String> kids, int keys, @Schema(nullable = true) Instant fetchedAt, @Schema(nullable = true) String error) {
    }

    /** missingRoles: mapped, but not in dynamic security; writeError: why the file of the broker could not be written. */
    public record TokenAuthSettings(boolean enabled, List<TokenAuthIssuer> issuers, List<IssuerStatus> status, List<TokenBlock> blocks,
            List<IssuedToken> issued, List<String> missingRoles,
            @Schema(nullable = true) Instant written, @Schema(nullable = true) String writeError) {
    }

    /** A client connected with a token, as the plugin tells: dynamic security does not know it. */
    public record TokenClient(String clientid, String username, String issuer, @Schema(nullable = true) String sub, @Schema(nullable = true) String jti,
            String address, Instant expires, List<String> roles) {
    }

    /**
     * A token the plugin did not accept, as it tells. issuer, kid and username are what the token says, whether its signature
     * held or not (verified); sub and jti only when it did. count: how often, at: the last time. reason: a code, see token_auth.c.
     */
    public record TokenRejection(Instant at, int count, String clientid, String address, @Schema(nullable = true) String issuer,
            @Schema(nullable = true) String kid, @Schema(nullable = true) String username, @Schema(nullable = true) String sub,
            @Schema(nullable = true) String jti, boolean verified, String reason) {
    }

    /** What is kept in the state directory. */
    record State(boolean enabled, List<TokenAuthIssuer> issuers, Map<String, KeySet> keys, List<TokenBlock> blocks, List<IssuedToken> issued,
            List<LocalKey> localKeys) {

        /** Files written before blocks or issued tokens existed have none. */
        State {
            issuers = issuers == null ? List.of() : issuers;
            keys = keys == null ? Map.of() : keys;
            blocks = blocks == null ? List.of() : blocks;
            issued = issued == null ? List.of() : issued;
            localKeys = localKeys == null ? List.of() : localKeys;
        }

        static State empty(boolean enabled) {
            return new State(enabled, List.of(), Map.of(), List.of(), List.of(), List.of());
        }

        State withSettings(boolean enabled, List<TokenAuthIssuer> issuers, Map<String, KeySet> keys) {
            return new State(enabled, issuers, keys, blocks, issued, localKeys);
        }

        State withKeys(Map<String, KeySet> keys) {
            return new State(enabled, issuers, keys, blocks, issued, localKeys);
        }

        State withBlocks(List<TokenBlock> blocks) {
            return new State(enabled, issuers, keys, blocks, issued, localKeys);
        }

        State withIssued(List<IssuedToken> issued, List<LocalKey> localKeys) {
            return new State(enabled, issuers, keys, blocks, issued, localKeys);
        }
    }

    /** A public key of this application's own issuer; the private one of the newest is in SIGNING_KEY. */
    record LocalKey(String kid, String pem, Instant created) {
    }

    record KeySet(List<Jwks.Key> keys, Instant fetchedAt, String error, Instant failedAt) {
    }

    static final String DEFAULT_USERNAME_CLAIM = "preferred_username";
    static final String DEFAULT_GROUPS_CLAIM = "groups";
    private static final Pattern CLAIM = Pattern.compile("[A-Za-z0-9_:.\\-]{1,100}");
    private static final int MAX_ISSUERS = 20;
    private static final Duration RETRY = Duration.ofMinutes(1);
    /** A token with a kid not known yet fetches the keys of its issuer again, but not more often than this */
    private static final Duration UNKNOWN_KEY_WAIT = Duration.ofSeconds(30);
    static final String UNKNOWN_KEY = "unknown_key";
    private static final Logger LOG = Logger.getLogger(TokenAuthService.class);
    /** The issuer of the tokens this application signs itself */
    public static final String LOCAL_ISSUER = "mosquitto-ui";
    static final String LOCAL_AUDIENCE = "mqtt";
    private static final String SIGNING_KEY = "token-auth-signing.key";
    /** The names a token may carry: no level of a topic, no wildcard, nothing invisible */
    private static final Pattern USERNAME = Pattern.compile("[^/+#\\p{Cntrl}\\s]{1,100}");
    private static final Duration MAX_LIFETIME = Duration.ofDays(3660);

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    @ConfigProperty(name = "mosquitto-ui.shared-dir")
    Path sharedDir;

    @ConfigProperty(name = "mosquitto-ui.token-auth.key-refresh", defaultValue = "PT15M")
    Duration keyRefresh;

    @Inject
    ObjectMapper mapper;

    @Inject
    DynsecService dynsec;

    @Inject
    DynsecClient broker;

    @Inject
    SecretCipher cipher;

    private Instant written;
    private String writeError;
    /** When a token with an unknown kid last made the keys of an issuer be fetched again */
    private final Map<String, Instant> unknownKeyFetches = new ConcurrentHashMap<>();
    private List<String> missingRoles = List.of();

    private Path stateFile() {
        return stateDir.resolve("token-auth.json");
    }

    Path brokerFile() {
        return sharedDir.resolve("token-auth").resolve("token-auth.json");
    }

    private synchronized State load() {
        try {
            return mapper.readValue(Files.readString(stateFile()), State.class);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void save(State state) {
        try {
            Files.createDirectories(stateDir);
            Files.writeString(stateFile(), mapper.writeValueAsString(state));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Those connected with a token now; empty without the plugin, or when the user of the UI may not read $SYS. */
    public List<TokenClient> clients() {
        List<TokenClient> clients = knownClients();
        return clients == null ? List.of() : clients;
    }

    /** Those connected with a token now; null when that is not known: no plugin, no connection, no access to $SYS. */
    public List<TokenClient> knownClients() {
        String json = broker.sysValue(DynsecClient.TOKEN_CLIENTS);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<TokenClient> clients = new ArrayList<>();
            for (var item : mapper.readTree(json)) {
                List<String> roles = new ArrayList<>();
                item.path("roles").forEach(role -> roles.add(role.asText()));
                clients.add(new TokenClient(item.path("clientid").asText(), item.path("username").asText(), item.path("issuer").asText(),
                        item.hasNonNull("sub") ? item.get("sub").asText() : null, item.hasNonNull("jti") ? item.get("jti").asText() : null,
                        item.path("address").asText(), Instant.ofEpochSecond(item.path("expires").asLong()), roles));
            }
            return clients;
        } catch (IOException e) {
            LOG.warnf("Token login: unreadable list of clients: %s", e.getMessage());
            return null;
        }
    }

    /** The tokens the plugin did not accept since the broker started, the latest first; empty without the plugin. */
    public List<TokenRejection> rejected() {
        return rejections(broker.sysValue(DynsecClient.TOKEN_REJECTED));
    }

    private List<TokenRejection> rejections(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<TokenRejection> rejected = new ArrayList<>();
            for (var item : mapper.readTree(json)) {
                rejected.add(new TokenRejection(Instant.ofEpochSecond(item.path("at").asLong()), item.path("count").asInt(1),
                        item.path("clientid").asText(), item.path("address").asText(), text(item, "issuer"), text(item, "kid"),
                        text(item, "username"), text(item, "sub"), text(item, "jti"), item.path("verified").asBoolean(),
                        item.path("reason").asText()));
            }
            return rejected;
        } catch (IOException e) {
            LOG.warnf("Token login: unreadable list of rejected tokens: %s", e.getMessage());
            return List.of();
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode item, String name) {
        return item.hasNonNull(name) ? item.get(name).asText() : null;
    }

    /**
     * A token signed with a key the plugin does not have: the provider made a new one since the keys were last fetched. Its keys
     * are fetched again now, instead of after up to key-refresh, at most every UNKNOWN_KEY_WAIT, and only for an issuer whose keys
     * come from an address. A client that tries again then gets in.
     */
    void onRejected(@ObservesAsync TokenRejectionsChanged change) {
        try {
            State state = load();
            if (state == null || !state.enabled()) {
                return;
            }
            Instant now = Instant.now();
            Set<String> due = new HashSet<>();
            for (TokenRejection rejection : rejections(change.json())) {
                if (!UNKNOWN_KEY.equals(rejection.reason()) || rejection.issuer() == null) {
                    continue;
                }
                TokenAuthIssuer issuer = state.issuers().stream().filter(i -> i.issuer().equals(rejection.issuer())).findFirst().orElse(null);
                KeySet set = issuer == null ? null : state.keys().get(issuer.issuer());
                Instant last = unknownKeyFetches.get(rejection.issuer());
                // keys fetched after the token came already had their chance; the plugin tells whole seconds, so within the same
                // second the keys may have been fetched before or after it
                boolean newer = set == null || set.fetchedAt() == null || set.fetchedAt().isBefore(rejection.at().plusSeconds(1));
                if (issuer != null && issuer.source() != KeySource.keys && newer && (last == null || last.plus(UNKNOWN_KEY_WAIT).isBefore(now))) {
                    due.add(issuer.issuer());
                }
            }
            if (!due.isEmpty()) {
                due.forEach(issuer -> unknownKeyFetches.put(issuer, now));
                LOG.infof("Token login: a token with a key not known yet, the keys of %s are fetched again", String.join(", ", due));
                refresh(due::contains);
            }
        } catch (RuntimeException e) {
            LOG.warnf("Token login: %s", e.getMessage());
        }
    }

    /**
     * Whether a client last seen with these values could come back: token login on, the issuer still there, not blocked, and a
     * token of this application not taken back. One that cannot is not missed.
     */
    public boolean expected(String username, String issuer, String sub, String jti) {
        return lockedOut(username, issuer, sub, jti) == null;
    }

    /**
     * Why a client with these values can no longer log in, or null when it can: disabled (token login off), issuer (not set up
     * any more), revoked (a token of this application taken back or run out) or blocked.
     */
    public String lockedOut(String username, String issuer, String sub, String jti) {
        State state = load();
        if (state == null || !state.enabled()) {
            return "disabled";
        }
        if (LOCAL_ISSUER.equals(issuer)) {
            if (jti == null || current(state.issued()).stream().noneMatch(token -> token.id().equals(jti))) {
                return "revoked";
            }
        } else if (state.issuers().stream().noneMatch(i -> i.issuer().equals(issuer))) {
            return "issuer";
        }
        boolean blocked = active(state.blocks()).stream().anyMatch(block -> (block.issuer() == null || block.issuer().equals(issuer))
                && block.value().equals(switch (block.field()) {
                    case username -> username;
                    case sub -> sub;
                    case jti -> jti;
                }));
        return blocked ? "blocked" : null;
    }

    public synchronized TokenAuthSettings get() {
        State state = load();
        if (state == null) {
            return new TokenAuthSettings(false, List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
        }
        List<IssuerStatus> status = state.issuers().stream().map(issuer -> {
            KeySet set = state.keys().get(issuer.issuer());
            if (set == null) {
                return new IssuerStatus(issuer.issuer(), List.of(), 0, null, null);
            }
            return new IssuerStatus(issuer.issuer(), set.keys().stream().map(Jwks.Key::kid).filter(Objects::nonNull).toList(), set.keys().size(),
                    set.fetchedAt(), set.error());
        }).toList();
        return new TokenAuthSettings(state.enabled(), state.issuers(), status, active(state.blocks()), current(state.issued()), missingRoles, written,
                writeError);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean httpAddress(String url) {
        try {
            URI uri = url == null ? null : new URI(url);
            return uri != null && uri.isAbsolute() && uri.getHost() != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    && uri.getFragment() == null;
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private TokenAuthIssuer validate(TokenAuthIssuer issuer, Set<String> seen) {
        String url = blank(issuer.issuer());
        KeySource source = issuer.source();
        String jwksUrl = null;
        String staticKeys = null;
        if (source == KeySource.discovery) {
            if (!httpAddress(url) || url.contains("?")) {
                throw BackupException.validation("issuer", "Not an https or http address: " + url);
            }
        } else if (url == null || url.length() > 300 || url.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw BackupException.validation("issuer", "The issuer is the value of iss in the tokens, without spaces");
        }
        if (source == KeySource.jwks) {
            jwksUrl = blank(issuer.jwksUrl());
            if (!httpAddress(jwksUrl)) {
                throw BackupException.validation("jwksUrl", "Not an https or http address: " + jwksUrl);
            }
        }
        if (source == KeySource.keys) {
            staticKeys = blank(issuer.staticKeys());
            if (staticKeys == null || staticKeys.length() > 100_000) {
                throw BackupException.validation("staticKeys", "No keys");
            }
            try {
                Jwks.readKeys(mapper, staticKeys);
            } catch (IllegalArgumentException e) {
                throw BackupException.validation("staticKeys", e.getMessage());
            }
        }
        if (LOCAL_ISSUER.equals(url)) {
            throw BackupException.validation("issuer", LOCAL_ISSUER + " is the issuer of this application");
        }
        if (!seen.add(url)) {
            throw BackupException.validation("issuer", "Twice: " + url);
        }
        String usernameClaim = blank(issuer.usernameClaim()) == null ? DEFAULT_USERNAME_CLAIM : issuer.usernameClaim().strip();
        String groupsClaim = blank(issuer.groupsClaim()) == null ? DEFAULT_GROUPS_CLAIM : issuer.groupsClaim().strip();
        if (!CLAIM.matcher(usernameClaim).matches() || !CLAIM.matcher(groupsClaim).matches()) {
            throw BackupException.validation("claim", "A claim is a name or a path with dots");
        }
        String audience = blank(issuer.audience());
        String prefix = issuer.usernamePrefix() == null || issuer.usernamePrefix().isEmpty() ? null : issuer.usernamePrefix();
        if (audience != null && audience.length() > 200 || prefix != null && (prefix.length() > 50 || prefix.chars().anyMatch(Character::isISOControl))) {
            throw BackupException.validation("issuer", "Audience or prefix too long");
        }
        // one entry per group, its roles in the order given
        Map<String, LinkedHashSet<String>> groups = new LinkedHashMap<>();
        for (TokenAuthMapping mapping : issuer.mappings() == null ? List.<TokenAuthMapping>of() : issuer.mappings()) {
            String group = blank(mapping.group());
            if (group == null || group.length() > 200) {
                throw BackupException.validation("group", "A group needs a name");
            }
            List<String> roles = mapping.roles() == null ? List.of() : mapping.roles().stream().map(TokenAuthService::blank).filter(Objects::nonNull).toList();
            if (roles.isEmpty()) {
                throw BackupException.validation("group", "The group " + group + " has no role");
            }
            groups.computeIfAbsent(group, g -> new LinkedHashSet<>()).addAll(roles);
        }
        return new TokenAuthIssuer(url, audience, usernameClaim, groupsClaim, prefix, issuer.bindClientId(), source, jwksUrl, staticKeys,
                groups.entrySet().stream().map(e -> new TokenAuthMapping(e.getKey(), List.copyOf(e.getValue()))).toList());
    }

    /** Tokens that have not expired; those that have are of no use any more. */
    private static List<IssuedToken> current(List<IssuedToken> issued) {
        Instant now = Instant.now();
        return issued.stream().filter(token -> token.expires().isAfter(now)).toList();
    }

    private List<String> validRoles(List<String> given) {
        List<String> roles = given == null ? List.of() : given.stream().map(TokenAuthService::blank).filter(Objects::nonNull).distinct().toList();
        if (roles.isEmpty()) {
            throw BackupException.validation("roles", "A token needs a role");
        }
        Map<String, RoleView> known = new HashMap<>();
        dynsec.listRoles().forEach(role -> known.put(role.rolename(), role));
        for (String role : roles) {
            if (!known.containsKey(role)) {
                throw BackupException.validation("roles", "No role " + role);
            }
            if (known.get(role).locked()) {
                throw BackupException.validation("roles", "The role " + role + " belongs to this application");
            }
        }
        return roles;
    }

    /** The private key the tokens are signed with, made on first use; its public key goes to the plugin. */
    private java.security.KeyPair signingKey(List<LocalKey> localKeys, List<LocalKey> added) {
        Path file = stateDir.resolve(SIGNING_KEY);
        try {
            if (Files.exists(file) && !localKeys.isEmpty()) {
                String[] parts = cipher.open(Files.readString(file)).split("\n", 2);
                String kid = parts[0];
                if (localKeys.stream().anyMatch(key -> key.kid().equals(kid))) {
                    java.security.KeyFactory factory = java.security.KeyFactory.getInstance("EC");
                    var privateKey = factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(parts[1].strip())));
                    LocalKey local = localKeys.stream().filter(key -> key.kid().equals(kid)).findFirst().orElseThrow();
                    var publicKey = factory.generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getMimeDecoder().decode(
                            local.pem().replaceAll("-----[A-Z ]+-----", ""))));
                    return new java.security.KeyPair(publicKey, privateKey);
                }
            }
            // none yet, or the state came from a backup without its private key: a new one, the old public keys stay valid
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            java.security.KeyPair pair = generator.generateKeyPair();
            String kid = "mosquitto-ui-" + java.time.LocalDate.now() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            PrivateFiles.write(file, cipher.seal(kid + "\n" + java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())));
            added.add(new LocalKey(kid, Jwks.pem(pair.getPublic()), Instant.now()));
            return pair;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Signs a token for a client: user name, audience, expiry and id. Its roles stay here. */
    public IssueResult issue(IssueInput input, String createdBy) {
        String username = blank(input.username());
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw BackupException.validation("username", "A name without /, + or #, at most 100 characters");
        }
        if (dynsec.listClients().stream().anyMatch(client -> client.username().equals(username))) {
            throw BackupException.validation("username", "Taken by a client of dynamic security");
        }
        Instant now = Instant.now();
        if (input.expires() == null || !input.expires().isAfter(now) || input.expires().isAfter(now.plus(MAX_LIFETIME))) {
            throw BackupException.validation("expires", "Between now and ten years from now");
        }
        List<String> roles = validRoles(input.roles());
        String note = blank(input.note());
        if (note != null && note.length() > 500) {
            throw BackupException.validation("note", "Too long");
        }
        synchronized (this) {
            State state = load();
            if (state == null) {
                state = State.empty(true);
            }
            List<LocalKey> added = new ArrayList<>();
            java.security.KeyPair key = signingKey(state.localKeys(), added);
            List<LocalKey> localKeys = new ArrayList<>(state.localKeys());
            localKeys.addAll(added);
            String kid = added.isEmpty() ? newestKid(state.localKeys()) : added.getFirst().kid();
            IssuedToken issued = new IssuedToken(java.util.UUID.randomUUID().toString(), username, roles, now, input.expires(), createdBy, note);
            String token;
            try {
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("alg", "ES256");
                header.put("typ", "JWT");
                header.put("kid", kid);
                Map<String, Object> claims = new LinkedHashMap<>();
                claims.put("iss", LOCAL_ISSUER);
                claims.put("sub", username);
                claims.put("preferred_username", username);
                claims.put("aud", LOCAL_AUDIENCE);
                claims.put("iat", now.getEpochSecond());
                claims.put("exp", input.expires().getEpochSecond());
                claims.put("jti", issued.id());
                String signed = b64(mapper.writeValueAsBytes(header)) + "." + b64(mapper.writeValueAsBytes(claims));
                java.security.Signature signature = java.security.Signature.getInstance("SHA256withECDSAinP1363Format");
                signature.initSign(key.getPrivate());
                signature.update(signed.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                token = signed + "." + b64(signature.sign());
            } catch (IOException | java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
            List<IssuedToken> all = new ArrayList<>(current(state.issued()));
            all.add(issued);
            save(state.withIssued(all, localKeys));
            render();
            return new IssueResult(issued, token, LOCAL_ISSUER);
        }
    }

    private static String newestKid(List<LocalKey> keys) {
        return keys.stream().max(java.util.Comparator.comparing(LocalKey::created)).orElseThrow().kid();
    }

    /** Other roles for a token already handed out; clients connected with it get them at once. */
    public TokenAuthSettings changeIssued(String id, List<String> roles) {
        List<String> valid = validRoles(roles);
        synchronized (this) {
            State state = load();
            if (state == null || state.issued().stream().noneMatch(token -> token.id().equals(id))) {
                throw BackupException.notFound("token");
            }
            save(state.withIssued(state.issued().stream().map(token -> token.id().equals(id)
                    ? new IssuedToken(token.id(), token.username(), valid, token.created(), token.expires(), token.createdBy(), token.note())
                    : token).toList(), state.localKeys()));
            render();
        }
        return get();
    }

    /** Takes a token back: without its roles the plugin keeps it out and disconnects clients connected with it. */
    public TokenAuthSettings revokeIssued(String id) {
        synchronized (this) {
            State state = load();
            if (state == null || state.issued().stream().noneMatch(token -> token.id().equals(id))) {
                throw BackupException.notFound("token");
            }
            save(state.withIssued(state.issued().stream().filter(token -> !token.id().equals(id)).toList(), state.localKeys()));
            render();
        }
        return get();
    }

    /** Blocks whose time is over are of no use any more. */
    private static List<TokenBlock> active(List<TokenBlock> blocks) {
        Instant now = Instant.now();
        return blocks.stream().filter(block -> block.until() == null || block.until().isAfter(now)).toList();
    }

    /** Blocks a user name, subject or token: the plugin disconnects clients it holds for within a second, and lets them in no more. */
    public TokenAuthSettings block(TokenBlockInput input, String createdBy) {
        String value = blank(input.value());
        if (input.field() == null || value == null || value.length() > 300) {
            throw BackupException.validation("value", "A block needs what it compares and a value");
        }
        if (input.until() != null && !input.until().isAfter(Instant.now())) {
            throw BackupException.validation("until", "The end of a block has to be in the future");
        }
        String reason = blank(input.reason());
        if (reason != null && reason.length() > 500) {
            throw BackupException.validation("reason", "Too long");
        }
        synchronized (this) {
            State state = load();
            if (state == null) {
                throw BackupException.validation("value", "Token login is not set up");
            }
            List<TokenBlock> blocks = new ArrayList<>(active(state.blocks()));
            blocks.add(new TokenBlock(java.util.UUID.randomUUID().toString(), input.field(), value, blank(input.issuer()), input.until(), reason,
                    Instant.now(), createdBy));
            save(state.withBlocks(blocks));
            render();
        }
        return get();
    }

    public TokenAuthSettings unblock(String id) {
        synchronized (this) {
            State state = load();
            if (state == null || state.blocks().stream().noneMatch(block -> block.id().equals(id))) {
                throw BackupException.notFound("block");
            }
            save(state.withBlocks(state.blocks().stream().filter(block -> !block.id().equals(id)).toList()));
            render();
        }
        return get();
    }

    /** The settings for a backup, null without any. */
    public synchronized String export() {
        try {
            return Files.readString(stateFile());
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The settings of a backup; null, from a backup without them, leaves the ones here. Keys are fetched again when due. */
    public void restore(String json) {
        if (json == null) {
            return;
        }
        State state;
        try {
            state = mapper.readValue(json, State.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Token login in the backup is not readable", e);
        }
        synchronized (this) {
            // the compact constructor fills in what an older backup does not have
            save(state);
        }
        refresh(false);
    }

    /** Takes the new settings, fetches the keys of providers that are new or failed, and writes the file of the broker. */
    public TokenAuthSettings update(TokenAuthInput input) {
        List<TokenAuthIssuer> given = input.issuers() == null ? List.of() : input.issuers();
        if (given.size() > MAX_ISSUERS) {
            throw BackupException.validation("issuers", "At most " + MAX_ISSUERS + " identity providers");
        }
        Set<String> seen = new HashSet<>();
        List<TokenAuthIssuer> issuers = given.stream().map(issuer -> validate(issuer, seen)).toList();
        // the roles of this application stay with it: a token would get the rights of the UI on the broker
        Set<String> locked = new HashSet<>();
        dynsec.listRoles().stream().filter(RoleView::locked).forEach(role -> locked.add(role.rolename()));
        issuers.forEach(issuer -> issuer.mappings().forEach(mapping -> mapping.roles().forEach(role -> {
            if (locked.contains(role)) {
                throw BackupException.validation("role", "The role " + role + " belongs to this application");
            }
        })));
        synchronized (this) {
            State before = load();
            Map<String, KeySet> keys = new HashMap<>(before == null ? Map.of() : before.keys());
            keys.keySet().retainAll(seen);
            // keys from another source, another JWKS or other pasted keys: fetched anew below, not kept for 15 minutes
            if (before != null) {
                for (TokenAuthIssuer issuer : issuers) {
                    before.issuers().stream().filter(old -> old.issuer().equals(issuer.issuer())).findFirst()
                            .filter(old -> old.source() != issuer.source() || !Objects.equals(old.jwksUrl(), issuer.jwksUrl())
                                    || !Objects.equals(old.staticKeys(), issuer.staticKeys()))
                            .ifPresent(old -> keys.remove(issuer.issuer()));
                }
            }
            save((before == null ? State.empty(input.enabled()) : before).withSettings(input.enabled(), issuers, keys));
        }
        refresh(false);
        return get();
    }

    /** Fetches the keys of every provider again. */
    public TokenAuthSettings refreshAll() {
        refresh(true);
        return get();
    }

    private void refresh(boolean all) {
        refresh(issuer -> all);
    }

    /** Fetches the keys of the issuers that are due, and of those named by force whether due or not, and writes the file. */
    private void refresh(java.util.function.Predicate<String> force) {
        State state = load();
        if (state == null) {
            return;
        }
        Instant now = Instant.now();
        Map<String, KeySet> fetched = new HashMap<>();
        for (TokenAuthIssuer issuer : state.issuers()) {
            KeySet set = state.keys().get(issuer.issuer());
            boolean due = force.test(issuer.issuer()) || set == null || issuer.source() == KeySource.keys
                    || set.error() != null && set.failedAt().plus(RETRY).isBefore(now)
                    || set.error() == null && set.fetchedAt().plus(keyRefresh).isBefore(now);
            if (!due) {
                continue;
            }
            // outside the lock: a provider may take its time
            try {
                List<Jwks.Key> found = switch (issuer.source()) {
                    case discovery -> Jwks.fetch(mapper, issuer.issuer());
                    case jwks -> Jwks.fetchJwks(mapper, issuer.jwksUrl());
                    case keys -> Jwks.readKeys(mapper, issuer.staticKeys());
                };
                fetched.put(issuer.issuer(), new KeySet(found, Instant.now(), null, null));
            } catch (IOException | IllegalArgumentException e) {
                LOG.warnf("Token login: the keys of %s could not be fetched: %s", issuer.issuer(), e.getMessage());
                fetched.put(issuer.issuer(), new KeySet(set == null ? List.of() : set.keys(), set == null ? null : set.fetchedAt(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), Instant.now()));
            }
        }
        synchronized (this) {
            State current = load();
            if (current != null && !fetched.isEmpty()) {
                Map<String, KeySet> keys = new HashMap<>(current.keys());
                fetched.forEach((issuer, set) -> {
                    if (current.issuers().stream().anyMatch(i -> i.issuer().equals(issuer))) {
                        keys.put(issuer, set);
                    }
                });
                save(current.withKeys(keys));
            }
        }
        render();
    }

    /** Roles and their rules changed in dynamic security: the plugin has to know them at once. */
    void onDynsecChanged(@ObservesAsync DynsecChanged change) {
        render();
    }

    /** Keys due for a refresh, and whatever changed in dynamic security without the UI, such as an import. */
    @Scheduled(every = "{mosquitto-ui.token-auth.interval}", delayed = "{mosquitto-ui.token-auth.interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        try {
            refresh(false);
        } catch (RuntimeException e) {
            LOG.warnf("Token login: %s", e.getMessage());
        }
    }

    /** Writes the file of the plugin, if it differs from the one there. Without settings there is no file. */
    public synchronized void render() {
        State state = load();
        if (state == null) {
            return;
        }
        Map<String, Object> file;
        try {
            file = content(state);
        } catch (RuntimeException e) {
            // dynamic security not reachable: the file before stays in force
            writeError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return;
        }
        try {
            String text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(file);
            Path target = brokerFile();
            String current = Files.exists(target) && !Files.isSymbolicLink(target) ? Files.readString(target) : null;
            if (!text.equals(current)) {
                write(target, text);
                written = Instant.now();
            } else if (written == null) {
                written = Files.getLastModifiedTime(target).toInstant();
            }
            writeError = null;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            writeError = e.getMessage();
            LOG.warnf("Token login: %s could not be written: %s", brokerFile(), e.getMessage());
        }
    }

    /** Readable by the broker through the group of the directory, replaced at once, never through a link. */
    private static void write(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r-----")));
        Files.writeString(temporary, text, StandardOpenOption.WRITE);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Map<String, Object> content(State state) {
        Map<String, RoleView> roles = new LinkedHashMap<>();
        // never the roles of this application, whatever is in the settings
        dynsec.listRoles().stream().filter(role -> !role.locked()).forEach(role -> roles.put(role.rolename(), role));
        AccessDefaults defaults = dynsec.access().defaults();
        List<String> reserved = dynsec.listClients().stream().map(ClientView::username).toList();

        // the roles the plugin needs, in the order they are first mapped
        Set<String> used = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        state.issuers().forEach(issuer -> issuer.mappings().forEach(mapping -> mapping.roles().forEach(role -> {
            if (roles.containsKey(role)) {
                used.add(role);
            } else {
                missing.add(role);
            }
        })));
        List<IssuedToken> issuedNow = current(state.issued());
        issuedNow.forEach(token -> token.roles().forEach(role -> {
            if (roles.containsKey(role)) {
                used.add(role);
            } else {
                missing.add(role);
            }
        }));
        missingRoles = List.copyOf(missing);

        Map<String, Object> file = new LinkedHashMap<>();
        file.put("enabled", state.enabled());
        file.put("leeway", 30);
        file.put("defaults", Map.of("publishClientSend", defaults.publishClientSend(), "publishClientReceive", defaults.publishClientReceive(),
                "subscribe", defaults.subscribe(), "unsubscribe", defaults.unsubscribe()));
        file.put("reservedUsernames", reserved);
        file.put("roles", used.stream().map(roles::get).map(role -> Map.of("rolename", role.rolename(), "allowWildcardSubs", role.allowwildcardsubs(),
                // the plugin hands out rules sorted by priority, and checks them in that order
                "acls", role.acls().stream().map(acl -> Map.of("acltype", acl.acltype(), "topic", acl.topic(), "allow", acl.allow())).toList())).toList());
        List<Map<String, Object>> issuers = new ArrayList<>();
        for (TokenAuthIssuer issuer : state.issuers()) {
            KeySet set = state.keys().get(issuer.issuer());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("issuer", issuer.issuer());
            entry.put("audience", issuer.audience() == null ? "" : issuer.audience());
            entry.put("usernameClaim", issuer.usernameClaim());
            entry.put("groupsClaim", issuer.groupsClaim());
            entry.put("usernamePrefix", issuer.usernamePrefix() == null ? "" : issuer.usernamePrefix());
            entry.put("bindClientId", issuer.bindClientId());
            entry.put("keys", set == null ? List.of() : set.keys().stream().map(key -> {
                Map<String, Object> k = new LinkedHashMap<>();
                if (key.kid() != null) {
                    k.put("kid", key.kid());
                }
                k.put("pem", key.pem());
                return k;
            }).toList());
            Map<String, List<String>> groups = new LinkedHashMap<>();
            issuer.mappings().forEach(mapping -> groups.put(mapping.group(), mapping.roles().stream().filter(roles::containsKey).toList()));
            entry.put("groups", groups);
            issuers.add(entry);
        }
        if (!state.localKeys().isEmpty()) {
            // this application's own tokens: each one is its own group, with the roles kept here
            Map<String, Object> local = new LinkedHashMap<>();
            local.put("issuer", LOCAL_ISSUER);
            local.put("audience", LOCAL_AUDIENCE);
            local.put("usernameClaim", "preferred_username");
            local.put("groupsClaim", "jti");
            local.put("usernamePrefix", "");
            local.put("bindClientId", true);
            local.put("keys", state.localKeys().stream().map(key -> Map.of("kid", key.kid(), "pem", key.pem())).toList());
            Map<String, List<String>> groups = new LinkedHashMap<>();
            issuedNow.forEach(token -> groups.put(token.id(), token.roles().stream().filter(roles::containsKey).toList()));
            local.put("groups", groups);
            issuers.add(local);
        }
        file.put("issuers", issuers);
        file.put("blocked", active(state.blocks()).stream().map(block -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", block.field().name());
            entry.put("value", block.value());
            if (block.issuer() != null) {
                entry.put("issuer", block.issuer());
            }
            if (block.until() != null) {
                entry.put("until", block.until().getEpochSecond());
            }
            return entry;
        }).toList());
        return file;
    }
}
