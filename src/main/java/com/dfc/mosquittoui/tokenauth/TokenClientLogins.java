package com.dfc.mosquittoui.tokenauth;

import com.dfc.mosquittoui.api.Dtos.ClientLogin;
import com.dfc.mosquittoui.api.Dtos.MissingClient;
import com.dfc.mosquittoui.broker.ClientLogins;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.security.PrivateFiles;
import com.dfc.mosquittoui.tokenauth.TokenAuthService.TokenClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * When the clients that log in with a token were last connected, as ClientLogins does it for those of dynamic security. Dynamic
 * security does not know them, so they are kept by the user name the plugin gives them, with what decides whether they can come
 * back at all: a client whose issuer is gone, who is blocked or whose token was taken back is not missed. One not seen for
 * missing-for is no longer missed, but stays in the list of those seen until it is hidden there; it shows again when it
 * connects again.
 */
@ApplicationScoped
public class TokenClientLogins {

    /**
     * The last connection of a user name, and the token it came with. clientid, expires and roles are missing in entries saved
     * by 0.19.0.
     */
    public record TokenLogin(ClientLogin login, String issuer, String sub, String jti, String clientid, Instant expires, List<String> roles) {
    }

    /**
     * A user name that connected with a token: now (connected) or last at login.lastSeen. lockedOut: why it can no longer log
     * in (disabled, issuer, revoked, blocked), null when it can.
     */
    public record SeenTokenClient(String username, @Schema(nullable = true) String clientid, String address, String issuer,
            @Schema(nullable = true) String sub, @Schema(nullable = true) String jti, List<String> roles, @Schema(nullable = true) Instant expires,
            Instant connectedAt, Instant lastSeen, boolean connected, @Schema(nullable = true) String lockedOut) {
    }

    private static final Logger LOG = Logger.getLogger(TokenClientLogins.class);
    private static final String FILE = "token-client-logins.json";
    private static final Duration WRITE_EVERY = Duration.ofMinutes(1);
    private static final Duration SAME_CONNECTION = Duration.ofMinutes(10);

    @Inject
    TokenAuthService tokenAuth;

    @Inject
    ClientLogins logins;

    @Inject
    DynsecClient dynsec;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    private Map<String, TokenLogin> known;
    private final Set<String> connected = new HashSet<>();
    private boolean started;
    private Instant written = Instant.EPOCH;

    private Map<String, TokenLogin> known() {
        if (known == null) {
            known = new HashMap<>();
            Path file = stateDir.resolve(FILE);
            try {
                if (Files.exists(file)) {
                    known.putAll(mapper.readValue(Files.readString(file), new TypeReference<Map<String, TokenLogin>>() {
                    }));
                }
            } catch (IOException e) {
                LOG.warnf("Last connections of the token clients not read: %s", e.getMessage());
            }
        }
        return known;
    }

    /** The user names connected with a token now; null when that is not known. */
    public synchronized Set<String> connectedNow() {
        return started ? Set.copyOf(connected) : null;
    }

    /** Token clients that are usually there and have been away for a while, the latest first, as ClientLogins.missing. */
    public synchronized List<MissingClient> missing(Instant now) {
        if (!started) {
            return List.of();
        }
        return known().entrySet().stream()
                .filter(entry -> !connected.contains(entry.getKey()) && logins.isMissing(entry.getValue().login(), now))
                .filter(entry -> tokenAuth.expected(entry.getKey(), entry.getValue().issuer(), entry.getValue().sub(), entry.getValue().jti()))
                .map(entry -> new MissingClient(entry.getKey(), "", entry.getValue().login().lastSeen()))
                .sorted(Comparator.comparing(MissingClient::lastSeen).reversed())
                .toList();
    }

    /**
     * All user names that connected with a token and were not hidden, those connected now first, then the latest. Looks at the
     * broker first, so a client that just connected is in it.
     */
    public List<SeenTokenClient> seen() {
        look();
        return list();
    }

    synchronized List<SeenTokenClient> list() {
        return known().entrySet().stream()
                .map(entry -> {
                    TokenLogin token = entry.getValue();
                    return new SeenTokenClient(entry.getKey(), token.clientid(), token.login().address(), token.issuer(), token.sub(), token.jti(),
                            token.roles() == null ? List.of() : token.roles(), token.expires(), token.login().connectedAt(), token.login().lastSeen(),
                            connected.contains(entry.getKey()), tokenAuth.lockedOut(entry.getKey(), token.issuer(), token.sub(), token.jti()));
                })
                .sorted(Comparator.comparing(SeenTokenClient::connected).thenComparing(SeenTokenClient::lastSeen).reversed())
                .toList();
    }

    /**
     * Takes a user name out of the list of those seen, and so out of those missed; it shows again when it connects again. false
     * when it is not in it; one connected now cannot be hidden.
     */
    public synchronized boolean hide(String username) {
        if (connected.contains(username)) {
            throw new IllegalStateException(username + " is connected");
        }
        if (known().remove(username) == null) {
            return false;
        }
        save(Instant.now());
        return true;
    }

    @Scheduled(every = "{mosquitto-ui.client-logins.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void look() {
        if (!dynsec.isConnected()) {
            return;
        }
        List<TokenClient> clients = tokenAuth.knownClients();
        if (clients != null) {
            record(clients, Instant.now());
        }
    }

    synchronized void record(List<TokenClient> clients, Instant now) {
        Map<String, TokenLogin> all = known();
        // several clients may share a user name, as their client ids may be "<user name>/<anything>": the first one counts
        Map<String, TokenClient> online = clients.stream().collect(Collectors.toMap(TokenClient::username, client -> client, (a, b) -> a));
        boolean changed = false;
        for (TokenClient client : online.values()) {
            TokenLogin old = all.get(client.username());
            boolean same = old != null && (connected.contains(client.username()) || !started && old.login().lastSeen().isAfter(now.minus(SAME_CONNECTION)));
            changed |= !same;
            all.put(client.username(), new TokenLogin(new ClientLogin(same ? old.login().connectedAt() : now, now, client.address()), client.issuer(),
                    client.sub(), client.jti(), client.clientid(), client.expires(), client.roles()));
        }
        changed |= !connected.equals(online.keySet());
        connected.clear();
        connected.addAll(online.keySet());
        started = true;
        if (changed || written.plus(WRITE_EVERY).isBefore(now)) {
            save(now);
        }
    }

    private void save(Instant now) {
        try {
            PrivateFiles.write(stateDir.resolve(FILE), mapper.writeValueAsString(known()));
            written = now;
        } catch (IOException e) {
            LOG.warnf("Last connections of the token clients not saved: %s", e.getMessage());
        }
    }
}
