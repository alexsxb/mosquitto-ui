package com.dfc.mosquittoui.api;

import com.dfc.mosquittoui.api.Dtos.CurrentUser;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.dfc.mosquittoui.localauth.LocalAuthMechanism;

/** The logged-in user; login and logout: LocalAuthResource and sso.SsoLoginResource. */
@Path("/api/auth")
public class AuthResource {

    @Inject
    SecurityIdentity identity;

    @ConfigProperty(name = "mosquitto-ui.admin-role")
    String adminRole;

    @GET
    @Path("/me")
    public CurrentUser me() {
        String username = identity.getPrincipal().getName();
        String name = identity.getAttribute(LocalAuthMechanism.NAME);
        String email = identity.getAttribute(LocalAuthMechanism.EMAIL);
        return new CurrentUser(username, name, email, identity.hasRole(adminRole));
    }
}
