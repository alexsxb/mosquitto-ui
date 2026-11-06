package com.dfc.mosquittoui.localauth;

import com.dfc.mosquittoui.localauth.LocalSessions.Content;
import com.dfc.mosquittoui.sso.SsoService;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Who is logged in, from the session cookie of this application: with the local login, or through an identity provider of the page
 * Login (sso.SsoLoginResource). The OIDC extension only does the code flow on /auth/sso/*, it has no tenant of its own
 * (OidcDefaultTenantOff). Without a session, the API answers 499 like the OIDC extension did, the frontend shows the start page
 * then; pages lead to the start page.
 */
@ApplicationScoped
public class LocalAuthMechanism implements HttpAuthenticationMechanism {

    /** As the OIDC extension answers requests of JavaScript without a session */
    static final int SESSION_EXPIRED = 499;

    @Inject
    LocalSessions sessions;

    @Inject
    LocalUsers users;

    @Inject
    SsoService sso;

    @Inject
    LocalAuth auth;

    @ConfigProperty(name = "mosquitto-ui.admin-role")
    String adminRole;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        Cookie cookie = context.request().getCookie(LocalSessions.COOKIE);
        if (cookie == null) {
            return Uni.createFrom().nullItem();
        }
        Instant now = Instant.now();
        Optional<Content> content = sessions.read(cookie.getValue(), now).filter(this::valid);
        if (content.isEmpty()) {
            sessions.clear(context);
            return Uni.createFrom().nullItem();
        }
        Content c = content.get();
        if (sessions.renew(c, now)) {
            sessions.set(context, sessions.value(c.renewed(now)));
        }
        QuarkusSecurityIdentity.Builder identity = QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(c.u())).addRole(adminRole);
        if (c.n() != null) {
            identity.addAttribute(NAME, c.n());
        }
        if (c.e() != null) {
            identity.addAttribute(EMAIL, c.e());
        }
        if (c.p() != null) {
            identity.addAttribute(PROVIDER, c.p());
        }
        return Uni.createFrom().item(identity.build());
    }

    /** Attributes of the identity for /api/auth/me */
    public static final String NAME = "mosquitto-ui.name";
    public static final String EMAIL = "mosquitto-ui.email";
    public static final String PROVIDER = "mosquitto-ui.provider";

    /**
     * A local session counts while the local login is on and the user has the same version; one of a provider while the provider is
     * on and unchanged, and, mapped to a local user, while that user has the same version.
     */
    private boolean valid(Content c) {
        if (c.p() == null) {
            return auth.local() && users.find(c.u()).filter(u -> u.version() == c.v()).isPresent();
        }
        if (!sso.valid(c.p(), c.pv())) {
            return false;
        }
        return c.v() == 0 || users.find(c.u()).filter(u -> u.version() == c.v()).isPresent();
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        String path = context.normalizedPath();
        if (path.startsWith("/api/") || context.request().getHeader("X-Requested-With") != null) {
            return Uni.createFrom().item(new ChallengeData(SESSION_EXPIRED, null, null));
        }
        return Uni.createFrom().item(new ChallengeData(302, "Location", "/"));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of();
    }

    /** Before the OIDC extension, which only has something to say on /auth/sso/* */
    @Override
    public int getPriority() {
        return 2000;
    }
}
