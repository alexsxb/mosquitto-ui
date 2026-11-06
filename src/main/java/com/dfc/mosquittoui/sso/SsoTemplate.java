package com.dfc.mosquittoui.sso;

import io.quarkus.oidc.runtime.OidcTenantConfig.Provider;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A kind of identity provider with the settings that fit it: where it is (fixed for the big ones), what to ask for, where its roles
 * or groups are. The texts for people (how to set the client up) are in the frontend, by id.
 */
public record SsoTemplate(String id, String name, Group group, @Schema(nullable = true, description = "fixed for providers that only exist once") String issuer,
        @Schema(nullable = true) String issuerExample, List<String> scopes, RoleSource roleSource, List<String> roleClaims,
        @Schema(description = "the provider can put roles or groups into its tokens") boolean roles,
        @Schema(description = "membership in GitHub organisations and teams") boolean organisations,
        @Schema(description = "a private key (Sign in with Apple) instead of a client secret") boolean appleKey,
        @Schema(nullable = true) String docs) {

    public enum Group { SELF_HOSTED, CLOUD, CODE, ENTERPRISE, GENERIC }

    /** Which token the roles come from, as in quarkus.oidc.roles.source */
    public enum RoleSource { ID_TOKEN, ACCESS_TOKEN, USER_INFO }

    /** {clientId} in a claim path is replaced with the client id of the provider */
    static final String CLIENT_ID = "{clientId}";

    static final String GENERIC = "oidc";

    private static final List<String> OPENID = List.of("openid", "profile", "email");

    private static SsoTemplate selfHosted(String id, String name, String example, List<String> scopes, RoleSource source, List<String> claims, String docs) {
        return new SsoTemplate(id, name, Group.SELF_HOSTED, null, example, scopes, source, claims, true, false, false, docs);
    }

    public static final List<SsoTemplate> ALL = List.of(
            selfHosted("keycloak", "Keycloak", "https://sso.example.org/realms/home", OPENID, RoleSource.ACCESS_TOKEN,
                    List.of("groups", "realm_access/roles", "resource_access/" + CLIENT_ID + "/roles"), "https://www.keycloak.org/docs/latest/server_admin/#_oidc_clients"),
            selfHosted("authentik", "authentik", "https://auth.example.org/application/o/mosquitto-ui/", OPENID, RoleSource.ID_TOKEN, List.of("groups"),
                    "https://docs.goauthentik.io/add-secure-apps/providers/oauth2/"),
            selfHosted("authelia", "Authelia", "https://auth.example.org", List.of("openid", "profile", "email", "groups"), RoleSource.ID_TOKEN, List.of("groups"),
                    "https://www.authelia.com/integration/openid-connect/introduction/"),
            selfHosted("zitadel", "ZITADEL", "https://example.zitadel.cloud", List.of("openid", "profile", "email", "urn:zitadel:iam:org:project:roles"),
                    RoleSource.ID_TOKEN, List.of("urn:zitadel:iam:org:project:roles"), "https://zitadel.com/docs/guides/integrate/login/oidc/login-users"),
            selfHosted("pocket-id", "Pocket ID", "https://id.example.org", List.of("openid", "profile", "email", "groups"), RoleSource.ID_TOKEN, List.of("groups"),
                    "https://pocket-id.org/docs/"),
            selfHosted("kanidm", "Kanidm", "https://idm.example.org/oauth2/openid/mosquitto-ui", List.of("openid", "profile", "email", "groups"), RoleSource.ID_TOKEN,
                    List.of("groups"), "https://kanidm.github.io/kanidm/stable/integrations/oauth2.html"),
            new SsoTemplate("google", "Google", Group.CLOUD, "https://accounts.google.com", null, OPENID, RoleSource.ID_TOKEN, List.of(), false, false, false,
                    "https://developers.google.com/identity/openid-connect/openid-connect"),
            new SsoTemplate("entra", "Microsoft Entra ID", Group.CLOUD, null, "https://login.microsoftonline.com/<tenant-id>/v2.0", OPENID, RoleSource.ID_TOKEN,
                    List.of("roles", "groups"), true, false, false, "https://learn.microsoft.com/entra/identity-platform/quickstart-register-app"),
            new SsoTemplate("apple", "Apple", Group.CLOUD, "https://appleid.apple.com", null, List.of("openid", "email", "name"), RoleSource.ID_TOKEN, List.of(), false,
                    false, true, "https://developer.apple.com/sign-in-with-apple/get-started/"),
            new SsoTemplate("github", "GitHub", Group.CODE, "https://github.com/login/oauth", null, List.of("user:email"), RoleSource.USER_INFO, List.of(), false, true,
                    false, "https://docs.github.com/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app"),
            new SsoTemplate("gitlab", "GitLab", Group.CODE, null, "https://gitlab.com", OPENID, RoleSource.ID_TOKEN, List.of("groups_direct"), true, false, false,
                    "https://docs.gitlab.com/integration/openid_connect_provider/"),
            new SsoTemplate("gitea", "Gitea / Forgejo", Group.CODE, null, "https://git.example.org", List.of("openid", "profile", "email", "groups"),
                    RoleSource.ID_TOKEN, List.of("groups"), true, false, false, "https://docs.gitea.com/development/oauth2-provider"),
            new SsoTemplate("okta", "Okta", Group.ENTERPRISE, null, "https://example.okta.com/oauth2/default", List.of("openid", "profile", "email", "groups"),
                    RoleSource.ID_TOKEN, List.of("groups"), true, false, false, "https://developer.okta.com/docs/guides/sign-into-web-app-redirect/"),
            new SsoTemplate("auth0", "Auth0", Group.ENTERPRISE, null, "https://example.eu.auth0.com/", OPENID, RoleSource.ID_TOKEN, List.of(), true, false, false,
                    "https://auth0.com/docs/get-started/applications"),
            new SsoTemplate("cognito", "Amazon Cognito", Group.ENTERPRISE, null, "https://cognito-idp.eu-central-1.amazonaws.com/eu-central-1_example", OPENID,
                    RoleSource.ID_TOKEN, List.of("cognito:groups"), true, false, false, "https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-app-integration.html"),
            new SsoTemplate(GENERIC, "OpenID Connect", Group.GENERIC, null, "https://idp.example.org", OPENID, RoleSource.ID_TOKEN, List.of("groups"), true, false,
                    false, null));

    public static Optional<SsoTemplate> find(String id) {
        return ALL.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** What Quarkus already knows about the provider: endpoints, how its tokens are checked */
    Optional<Provider> quarkusProvider() {
        return switch (id) {
            case "github" -> Optional.of(Provider.GITHUB);
            case "google" -> Optional.of(Provider.GOOGLE);
            case "apple" -> Optional.of(Provider.APPLE);
            default -> Optional.empty();
        };
    }
}
