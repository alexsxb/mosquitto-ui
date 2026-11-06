package com.dfc.mosquittoui.sso;

import com.dfc.mosquittoui.audit.AuditLog;
import com.dfc.mosquittoui.localauth.LocalSessions;
import com.dfc.mosquittoui.localauth.LocalUsers;
import com.dfc.mosquittoui.sso.SsoAccess.Decision;
import com.dfc.mosquittoui.sso.SsoAccess.Person;
import com.dfc.mosquittoui.sso.SsoStore.Provider;
import io.quarkus.oidc.AccessTokenCredential;
import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.oidc.OidcSession;
import io.quarkus.oidc.UserInfo;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Where the code flow of a provider ends (the OIDC extension does it, SsoTenantResolver): who may come in gets the session of this
 * application, the one of the extension is thrown away. Who may not is noted at the provider, so that they can be let in.
 */
@Path(SsoTenantResolver.PATH + "{id}")
public class SsoLoginResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    OidcSession oidcSession;

    @Inject
    SsoService sso;

    @Inject
    SsoAccess access;

    @Inject
    LocalUsers users;

    @Inject
    LocalSessions sessions;

    @Inject
    AuditLog audit;

    @Inject
    RoutingContext context;

    @GET
    public Response login(@PathParam("id") String id) {
        Optional<Provider> provider = sso.provider(id).filter(p -> p.enabled);
        // the session of the extension is not needed any longer, whatever comes
        oidcSession.logout().await().indefinitely();
        if (provider.isEmpty()) {
            return home(Map.of("sso-error", "unknown"));
        }
        Person person = person(provider.get());
        Optional<String> token = Optional.ofNullable(identity.getCredential(AccessTokenCredential.class)).map(AccessTokenCredential::getToken);
        Decision decision = access.decide(provider.get(), person, token);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("provider", id);
        details.put("address", context.request().remoteAddress() == null ? "-" : context.request().remoteAddress().hostAddress());
        Instant now = Instant.now();
        if (decision.allowed() && decision.localUser() != null) {
            Optional<LocalUsers.User> user = users.find(decision.localUser());
            if (user.isEmpty()) {
                decision = new Decision(false, null, "the UI user " + decision.localUser() + " is gone");
            } else {
                details.put("as", user.get().username());
                audit.record(user.get().username(), "login", details, null);
                sso.loggedIn(id);
                sessions.set(context, sessions.value(new LocalSessions.Content(user.get().username(), user.get().version(), now, now, id, provider.get().version,
                        person.name(), person.email())));
                keepLogoutHint();
                return home(Map.of());
            }
        }
        if (!decision.allowed()) {
            sso.denied(id, person);
            audit.record(person.principal(), "login", details, decision.reason());
            return home(Map.of("sso-denied", provider.get().name, "who", person.principal()));
        }
        audit.record(person.principal(), "login", details, null);
        sso.loggedIn(id);
        sessions.set(context, sessions.value(new LocalSessions.Content(person.principal(), 0, now, now, id, provider.get().version, person.name(), person.email())));
        keepLogoutHint();
        return home(Map.of());
    }

    /** A real ID token (not the one the extension makes for GitHub) lets the provider log out without asking */
    private void keepLogoutHint() {
        IdTokenCredential idToken = identity.getCredential(IdTokenCredential.class);
        sessions.setLogoutHint(context, idToken == null || idToken.isInternal() ? null : idToken.getToken());
    }

    private Person person(Provider provider) {
        UserInfo info = identity.getAttribute("userinfo");
        JsonWebToken token = identity.getPrincipal() instanceof JsonWebToken jwt ? jwt : null;
        boolean github = "github".equals(provider.template);
        String subject = github ? text(info, "id") : first(token == null ? null : token.getSubject(), text(info, "sub"));
        String username = github ? text(info, "login") : first(claim(token, "preferred_username"), text(info, "preferred_username"), claim(token, "nickname"));
        String email = first(claim(token, "email"), text(info, "email"));
        Object verified = token == null ? null : token.getClaim("email_verified");
        if (verified == null && info != null && info.getJsonObject().containsKey("email_verified")) {
            verified = info.getJsonObject().getValue("email_verified");
        }
        if (Boolean.FALSE.equals(verified) || "false".equals(String.valueOf(verified))) {
            email = null;
        }
        String name = first(claim(token, "name"), text(info, "name"));
        return new Person(subject, username, email, name, Set.copyOf(identity.getRoles()));
    }

    private static String claim(JsonWebToken token, String name) {
        Object value = token == null ? null : token.getClaim(name);
        return value == null ? null : SsoService.blank(String.valueOf(value).replaceAll("^\"|\"$", ""));
    }

    private static String text(UserInfo info, String name) {
        if (info == null || !info.getJsonObject().containsKey(name)) {
            return null;
        }
        jakarta.json.JsonValue value = info.getJsonObject().get(name);
        if (value instanceof jakarta.json.JsonString s) {
            return SsoService.blank(s.getString());
        }
        return value == null || value.getValueType() == jakarta.json.JsonValue.ValueType.NULL ? null : SsoService.blank(value.toString());
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static Response home(Map<String, String> query) {
        UriBuilder uri = UriBuilder.fromUri(URI.create("/"));
        query.forEach(uri::queryParam);
        return Response.seeOther(uri.build()).build();
    }
}
