package com.dfc.mosquittoui.sso;

import com.dfc.mosquittoui.localauth.LocalAuth;
import com.dfc.mosquittoui.localauth.LocalUsers;
import com.dfc.mosquittoui.sso.SsoStore.Allowed;
import com.dfc.mosquittoui.sso.SsoStore.Denied;
import com.dfc.mosquittoui.sso.SsoStore.Provider;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * The identity providers people log in with, set up in the UI, and whether the local login is on as well. The OIDC settings of the
 * environment (QUARKUS_OIDC_*) become a provider at the first start of this version, and are not read afterwards.
 */
@ApplicationScoped
public class SsoService {

    private static final Logger LOG = Logger.getLogger(SsoService.class);
    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    static final int MAX_DENIED = 20;
    static final int MAX_LIST = 200;
    static final int MAX_TEXT = 512;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record AllowedView(String match, @Schema(nullable = true) String localUser) {
    }

    public record DeniedView(@Schema(nullable = true) String username, @Schema(nullable = true) String email, @Schema(nullable = true) String subject,
            List<String> roles, Instant at) {
    }

    public record ProviderView(String id, String name, String template, boolean enabled, @Schema(nullable = true) String issuer,
            @Schema(nullable = true) String clientId, boolean secretSet, @Schema(nullable = true) String appleKeyId, @Schema(nullable = true) String appleTeamId,
            List<String> scopes, List<String> roleClaims, SsoTemplate.RoleSource roleSource, List<String> allowRoles, boolean allowAll,
            List<AllowedView> allowed, List<String> githubOrgs, List<DeniedView> denied,
            @Schema(description = "taken over from QUARKUS_OIDC_* at the first start") boolean fromEnvironment, Instant created,
            @Schema(nullable = true) Instant lastLogin) {
    }

    /** clientSecret: null leaves it, empty removes it (the Apple key likewise) */
    public record ProviderInput(@Schema(nullable = true) String id, String name, String template, boolean enabled, @Schema(nullable = true) String issuer,
            @Schema(nullable = true) String clientId, @Schema(nullable = true) String clientSecret, @Schema(nullable = true) String appleKeyId,
            @Schema(nullable = true) String appleTeamId, @Schema(nullable = true) List<String> scopes, @Schema(nullable = true) List<String> roleClaims,
            @Schema(nullable = true) SsoTemplate.RoleSource roleSource, @Schema(nullable = true) List<String> allowRoles, boolean allowAll,
            @Schema(nullable = true) List<AllowedView> allowed, @Schema(nullable = true) List<String> githubOrgs) {
    }

    public record SsoSettings(@Schema(description = "people can log in with a user of this application") boolean localLogin,
            @Schema(description = "MOSQUITTO_UI_AUTH=local keeps it on") boolean localLoginForced, List<ProviderView> providers, List<SsoTemplate> templates) {
    }

    /** What the start page shows, open to everyone */
    public record LoginOption(String id, String name, String template) {
    }

    @Inject
    SsoStore store;

    @Inject
    LocalAuth auth;

    @Inject
    LocalUsers users;

    /** Before the local users are looked at (LocalUsers creates the first one only with the local login on) */
    void onStart(@Observes @Priority(1) StartupEvent event) {
        takeEnvironment(ConfigProvider.getConfig());
    }

    void takeEnvironment(Config config) {
        if (store.read(state -> state.environmentTaken)) {
            return;
        }
        Optional<String> url = config.getOptionalValue("quarkus.oidc.auth-server-url", String.class).map(String::strip).filter(s -> !s.isEmpty());
        Optional<String> clientId = config.getOptionalValue("quarkus.oidc.client-id", String.class).map(String::strip).filter(s -> !s.isEmpty());
        store.change(state -> {
            state.environmentTaken = true;
            if (url.isEmpty() || clientId.isEmpty() || auth.forced() || !state.providers.isEmpty()) {
                return;
            }
            boolean keycloak = url.get().contains("/realms/");
            SsoTemplate template = SsoTemplate.find(keycloak ? "keycloak" : SsoTemplate.GENERIC).orElseThrow();
            Provider p = new Provider();
            p.id = keycloak ? "keycloak" : "oidc";
            p.name = keycloak ? "Keycloak" : host(url.get());
            p.template = template.id();
            p.issuer = url.get();
            p.clientId = clientId.get();
            p.clientSecret = config.getOptionalValue("quarkus.oidc.credentials.secret", String.class)
                    .or(() -> config.getOptionalValue("quarkus.oidc.credentials.client-secret.value", String.class)).filter(s -> !s.isBlank()).orElse(null);
            p.scopes = new ArrayList<>(template.scopes());
            p.roleClaims = new ArrayList<>(template.roleClaims());
            p.roleSource = config.getOptionalValue("quarkus.oidc.roles.source", String.class).filter("idtoken"::equalsIgnoreCase).isPresent()
                    ? SsoTemplate.RoleSource.ID_TOKEN : template.roleSource();
            p.allowRoles = new ArrayList<>(List.of(config.getOptionalValue("mosquitto-ui.admin-role", String.class).orElse("mosquitto-admin")));
            p.stateSecret = secret();
            p.fromEnvironment = true;
            p.created = Instant.now();
            state.providers.add(p);
            // as before: only the identity provider
            state.localLogin = false;
            LOG.infof("Login: took over the identity provider %s from QUARKUS_OIDC_*, it is set up on the page Login from now on", url.get());
        });
    }

    private static String host(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? url : host;
        } catch (URISyntaxException e) {
            return url;
        }
    }

    static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // reading

    /** The local login is on when set so, when MOSQUITTO_UI_AUTH=local says so, and always while no provider is on */
    public boolean localLogin() {
        return auth.forced() || store.read(state -> !Boolean.FALSE.equals(state.localLogin) || state.providers.stream().noneMatch(p -> p.enabled));
    }

    public SsoSettings settings() {
        return store.read(state -> new SsoSettings(localLogin(), auth.forced(), state.providers.stream().map(SsoService::view).toList(), SsoTemplate.ALL));
    }

    public List<LoginOption> loginOptions() {
        return store.read(state -> state.providers.stream().filter(p -> p.enabled).map(p -> new LoginOption(p.id, p.name, p.template)).toList());
    }

    /** A copy, safe to use outside of the store */
    public Optional<Provider> provider(String id) {
        return store.read(state -> find(state.providers, id).map(SsoService::copy));
    }

    /** Whether a session from this provider (of this version) still counts */
    public boolean valid(String id, long version) {
        return store.read(state -> find(state.providers, id).filter(p -> p.enabled && p.version == version).isPresent());
    }

    private static Optional<Provider> find(List<Provider> providers, String id) {
        return providers.stream().filter(p -> p.id.equals(id)).findFirst();
    }

    private static Provider copy(Provider p) {
        Provider c = new Provider();
        c.id = p.id;
        c.name = p.name;
        c.template = p.template;
        c.enabled = p.enabled;
        c.issuer = p.issuer;
        c.clientId = p.clientId;
        c.clientSecret = p.clientSecret;
        c.appleKeyId = p.appleKeyId;
        c.appleTeamId = p.appleTeamId;
        c.scopes = List.copyOf(p.scopes);
        c.roleClaims = List.copyOf(p.roleClaims);
        c.roleSource = p.roleSource;
        c.allowRoles = List.copyOf(p.allowRoles);
        c.allowAll = p.allowAll;
        c.allowed = p.allowed.stream().map(a -> new Allowed(a.match, a.localUser)).toList();
        c.githubOrgs = List.copyOf(p.githubOrgs);
        c.stateSecret = p.stateSecret;
        c.version = p.version;
        c.fromEnvironment = p.fromEnvironment;
        c.created = p.created;
        c.lastLogin = p.lastLogin;
        return c;
    }

    static ProviderView view(Provider p) {
        return new ProviderView(p.id, p.name, p.template, p.enabled, p.issuer, p.clientId, p.clientSecret != null, p.appleKeyId, p.appleTeamId,
                List.copyOf(p.scopes), List.copyOf(p.roleClaims), p.roleSource, List.copyOf(p.allowRoles), p.allowAll,
                p.allowed.stream().map(a -> new AllowedView(a.match, a.localUser)).toList(), List.copyOf(p.githubOrgs),
                p.denied.stream().map(d -> new DeniedView(d.username, d.email, d.subject, List.copyOf(d.roles), d.at)).toList(), p.fromEnvironment, p.created,
                p.lastLogin);
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // changing

    public ProviderView create(ProviderInput input) {
        String id = input.id() == null ? "" : input.id().strip().toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new SsoException("validation", "The id is 1 to 32 small letters, digits or dashes", "id");
        }
        Provider p = new Provider();
        p.id = id;
        p.stateSecret = secret();
        p.created = Instant.now();
        p.version = 1;
        apply(p, input, true);
        store.change(state -> {
            if (find(state.providers, id).isPresent()) {
                throw new SsoException("exists", "There is a provider with this id already", "id");
            }
            state.providers.add(p);
        });
        return view(p);
    }

    public ProviderView update(String id, ProviderInput input) {
        Provider[] changed = new Provider[1];
        store.change(state -> {
            Provider p = find(state.providers, id).orElseThrow(() -> new SsoException("not_found", "No such provider", null));
            Provider next = copy(p);
            next.denied = p.denied;
            apply(next, input, false);
            if (!next.enabled && p.enabled) {
                requireOtherWay(state, id);
            }
            if (endsSessions(p, next)) {
                next.version = p.version + 1;
            }
            state.providers.set(state.providers.indexOf(p), next);
            changed[0] = next;
        });
        return view(changed[0]);
    }

    public void delete(String id) {
        store.change(state -> {
            Provider p = find(state.providers, id).orElseThrow(() -> new SsoException("not_found", "No such provider", null));
            if (p.enabled) {
                requireOtherWay(state, id);
            }
            state.providers.remove(p);
        });
    }

    /**
     * Sessions of the provider end (and the extension gets a new tenant) when it connects differently, is turned off, or lets fewer
     * in; a new name or more people allowed leave them.
     */
    static boolean endsSessions(Provider before, Provider after) {
        boolean connection = !Objects.equals(before.issuer, after.issuer) || !Objects.equals(before.clientId, after.clientId)
                || !Objects.equals(before.clientSecret, after.clientSecret) || !Objects.equals(before.appleKeyId, after.appleKeyId)
                || !Objects.equals(before.appleTeamId, after.appleTeamId) || !before.scopes.equals(after.scopes) || !before.roleClaims.equals(after.roleClaims)
                || before.roleSource != after.roleSource || before.enabled && !after.enabled;
        boolean fewer = before.allowAll && !after.allowAll || !after.allowRoles.containsAll(before.allowRoles)
                || !lower(after.githubOrgs).containsAll(lower(before.githubOrgs))
                || before.allowed.stream().anyMatch(b -> after.allowed.stream().noneMatch(a -> a.match.equalsIgnoreCase(b.match) && Objects.equals(a.localUser, b.localUser)));
        return connection || fewer;
    }

    private static List<String> lower(List<String> values) {
        return values.stream().map(v -> v.toLowerCase(Locale.ROOT)).toList();
    }

    /** Nobody must be locked out: without the local login, another provider has to stay on */
    private void requireOtherWay(SsoStore.State state, String id) {
        boolean local = auth.forced() || !Boolean.FALSE.equals(state.localLogin) && !users.all().isEmpty();
        boolean other = state.providers.stream().anyMatch(p -> p.enabled && !p.id.equals(id));
        if (!local && !other) {
            throw new SsoException("last_way", "This is the only way to log in: turn the local login on first", null);
        }
    }

    public SsoSettings setLocalLogin(boolean on) {
        store.change(state -> {
            if (on && users.all().isEmpty()) {
                throw new SsoException("no_local_user", "Create a user on the page UI users first", null);
            }
            if (!on && state.providers.stream().noneMatch(p -> p.enabled)) {
                throw new SsoException("last_way", "Set up an identity provider first", null);
            }
            state.localLogin = on;
        });
        return settings();
    }

    /** Lets in somebody who was turned away, by e-mail address if there is one, else by user name */
    public ProviderView allow(String id, String match, String localUser) {
        Provider[] changed = new Provider[1];
        String entry = match == null ? "" : match.strip();
        String user = blank(localUser);
        validateAllowed(entry, user);
        store.change(state -> {
            Provider p = find(state.providers, id).orElseThrow(() -> new SsoException("not_found", "No such provider", null));
            p.allowed.removeIf(a -> a.match.equalsIgnoreCase(entry));
            p.allowed.add(new Allowed(entry, user));
            p.denied.removeIf(d -> entry.equalsIgnoreCase(d.email) || entry.equalsIgnoreCase(d.username));
            changed[0] = p;
        });
        return view(changed[0]);
    }

    public ProviderView clearDenied(String id) {
        Provider[] changed = new Provider[1];
        store.change(state -> {
            Provider p = find(state.providers, id).orElseThrow(() -> new SsoException("not_found", "No such provider", null));
            p.denied.clear();
            changed[0] = p;
        });
        return view(changed[0]);
    }

    void denied(String id, SsoAccess.Person person) {
        change(id, p -> {
            p.denied.removeIf(d -> Objects.equals(d.subject, person.subject()));
            Denied d = new Denied();
            d.username = person.username();
            d.email = person.email();
            d.subject = person.subject();
            d.roles = new ArrayList<>(person.roles().stream().sorted().limit(30).toList());
            d.at = Instant.now();
            p.denied.addFirst(d);
            while (p.denied.size() > MAX_DENIED) {
                p.denied.removeLast();
            }
        });
    }

    void loggedIn(String id) {
        change(id, p -> p.lastLogin = Instant.now());
    }

    private void change(String id, Consumer<Provider> change) {
        store.change(state -> find(state.providers, id).ifPresent(change));
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // validation

    private void apply(Provider p, ProviderInput input, boolean created) {
        SsoTemplate template = SsoTemplate.find(input.template() == null ? "" : input.template())
                .orElseThrow(() -> new SsoException("validation", "Unknown kind of provider", "template"));
        if (!created && !template.id().equals(p.template)) {
            throw new SsoException("validation", "The kind of a provider cannot change", "template");
        }
        p.template = template.id();
        p.name = text(input.name(), "name", 64);
        p.enabled = input.enabled();
        p.issuer = template.issuer() != null ? template.issuer() : url(input.issuer());
        p.clientId = text(input.clientId(), "clientId", MAX_TEXT);
        if (input.clientSecret() != null) {
            p.clientSecret = blank(input.clientSecret());
        }
        if (template.appleKey()) {
            p.appleKeyId = text(input.appleKeyId(), "appleKeyId", 64);
            p.appleTeamId = text(input.appleTeamId(), "appleTeamId", 64);
            if (p.clientSecret == null || !p.clientSecret.contains("PRIVATE KEY")) {
                throw new SsoException("validation", "The private key (.p8 file) is missing", "clientSecret");
            }
        } else if (p.clientSecret == null) {
            throw new SsoException("validation", "The client secret is missing", "clientSecret");
        }
        p.scopes = list(input.scopes(), template.scopes());
        p.roleClaims = template.roles() ? list(input.roleClaims(), template.roleClaims()) : new ArrayList<>();
        p.roleSource = input.roleSource() == null ? template.roleSource() : input.roleSource();
        p.allowRoles = template.roles() ? list(input.allowRoles(), List.of()) : new ArrayList<>();
        p.allowAll = input.allowAll();
        p.githubOrgs = template.organisations() ? list(input.githubOrgs(), List.of()) : new ArrayList<>();
        for (String org : p.githubOrgs) {
            if (!org.matches("[A-Za-z0-9-]{1,39}(/[A-Za-z0-9_.-]{1,100})?")) {
                throw new SsoException("validation", "An organisation is \"org\" or \"org/team\": " + org, "githubOrgs");
            }
        }
        if (template.organisations() && !p.githubOrgs.isEmpty() && !p.scopes.contains("read:org")) {
            p.scopes.add("read:org");
        }
        List<Allowed> allowed = new ArrayList<>();
        for (AllowedView a : input.allowed() == null ? List.<AllowedView>of() : input.allowed()) {
            String match = a.match() == null ? "" : a.match().strip();
            String user = blank(a.localUser());
            validateAllowed(match, user);
            if (allowed.stream().noneMatch(x -> x.match.equalsIgnoreCase(match))) {
                allowed.add(new Allowed(match, user));
            }
        }
        if (allowed.size() > MAX_LIST) {
            throw new SsoException("validation", "Too many entries", "allowed");
        }
        p.allowed = allowed;
        if (p.enabled && !p.allowAll && p.allowRoles.isEmpty() && p.allowed.isEmpty() && p.githubOrgs.isEmpty()) {
            throw new SsoException("validation", "Say who may log in: a role, people, an organisation or everyone", "allowed");
        }
    }

    private void validateAllowed(String match, String localUser) {
        if (match.isEmpty() || match.length() > 254 || match.chars().anyMatch(Character::isWhitespace)) {
            throw new SsoException("validation", "An e-mail address, *@domain or a user name", "allowed");
        }
        if (match.startsWith("*") && !match.matches("\\*@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")) {
            throw new SsoException("validation", "A whole domain is written *@example.org", "allowed");
        }
        if (localUser != null && users.find(localUser).isEmpty()) {
            throw new SsoException("validation", "There is no UI user " + localUser, "allowed");
        }
    }

    private static String text(String value, String field, int max) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || v.length() > max) {
            throw new SsoException("validation", "Missing or too long", field);
        }
        return v;
    }

    private static String url(String value) {
        String v = text(value, "issuer", MAX_TEXT);
        try {
            URI uri = new URI(v);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new SsoException("validation", "An http(s) address without ? or #", "issuer");
            }
        } catch (URISyntaxException e) {
            throw new SsoException("validation", "Not an address", "issuer");
        }
        return v;
    }

    private static List<String> list(List<String> values, List<String> fallback) {
        List<String> clean = values == null ? new ArrayList<>(fallback)
                : new ArrayList<>(values.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList());
        if (clean.size() > MAX_LIST || clean.stream().anyMatch(s -> s.length() > MAX_TEXT || s.chars().anyMatch(Character::isWhitespace))) {
            throw new SsoException("validation", "Entries without spaces, not too many", null);
        }
        return clean;
    }

    static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
