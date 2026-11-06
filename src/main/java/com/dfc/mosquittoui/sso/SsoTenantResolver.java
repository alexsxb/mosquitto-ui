package com.dfc.mosquittoui.sso;

import com.dfc.mosquittoui.sso.SsoStore.Provider;
import io.quarkus.oidc.OidcRequestContext;
import io.quarkus.oidc.OidcTenantConfig;
import io.quarkus.oidc.OidcTenantConfigBuilder;
import io.quarkus.oidc.TenantConfigResolver;
import io.quarkus.oidc.runtime.OidcTenantConfig.ApplicationType;
import io.quarkus.oidc.runtime.OidcTenantConfig.Roles.Source;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * The OIDC extension knows no provider of its own (quarkus.oidc.tenant-enabled=false): a provider of the UI becomes a tenant on
 * /auth/sso/{id}, only for the code flow. Afterwards the session is the one of this application (SsoLoginResource), the one of the
 * extension is thrown away. A changed provider gets a new tenant id with its version.
 */
@ApplicationScoped
public class SsoTenantResolver implements TenantConfigResolver {

    public static final String PATH = "/auth/sso/";

    @Inject
    SsoService sso;

    @Override
    public Uni<OidcTenantConfig> resolve(RoutingContext context, OidcRequestContext<OidcTenantConfig> requestContext) {
        Optional<String> id = id(context.normalizedPath());
        if (id.isEmpty()) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(sso.provider(id.get()).filter(p -> p.enabled).map(SsoTenantResolver::tenant).orElse(null));
    }

    static Optional<String> id(String path) {
        if (path == null || !path.startsWith(PATH)) {
            return Optional.empty();
        }
        String id = path.substring(PATH.length());
        return SsoService.ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }

    /**
     * The extension keeps a tenant by its id: a new one for each change (version), and for a provider deleted and created again with
     * the same id (the state secret is new then)
     */
    static String tenantId(Provider p) {
        return "sso-" + p.id + "-" + p.version + "-" + Integer.toHexString(p.stateSecret.hashCode());
    }

    static OidcTenantConfig tenant(Provider p) {
        SsoTemplate template = SsoTemplate.find(p.template).orElseThrow();
        OidcTenantConfigBuilder builder = OidcTenantConfig.builder().tenantId(tenantId(p)).applicationType(ApplicationType.WEB_APP).clientId(p.clientId);
        template.quarkusProvider().ifPresentOrElse(builder::provider, () -> builder.authServerUrl(p.issuer));
        if (template.appleKey()) {
            builder.credentials().jwt().key(p.clientSecret).tokenKeyId(p.appleKeyId).issuer(p.appleTeamId).subject(p.clientId).end().end();
        } else {
            builder.credentials().secret(p.clientSecret).end();
        }
        String path = PATH + p.id;
        builder.authentication()
                .redirectPath(path)
                .restorePathAfterRedirect(false)
                .removeRedirectParameters(true)
                .scopes(p.scopes)
                .pkceRequired(!template.organisations())
                .stateSecret(p.stateSecret)
                .cookiePath(path)
                .javaScriptAutoRedirect(false)
                .errorPath("/")
                .userInfoRequired(template.organisations() || p.roleSource == SsoTemplate.RoleSource.USER_INFO)
                .end();
        // a cookie per token: with all of them in one, the cookie of a Keycloak is easily over the 4 KB browsers keep
        builder.tokenStateManager().encryptionSecret(p.stateSecret).splitTokens(true).end();
        if (!p.roleClaims.isEmpty()) {
            Source source = switch (p.roleSource == null ? template.roleSource() : p.roleSource) {
                case ACCESS_TOKEN -> Source.accesstoken;
                case USER_INFO -> Source.userinfo;
                case ID_TOKEN -> Source.idtoken;
            };
            builder.roles(source, p.roleClaims.stream().map(c -> c.replace(SsoTemplate.CLIENT_ID, p.clientId)).toArray(String[]::new));
        }
        return builder.build();
    }
}
