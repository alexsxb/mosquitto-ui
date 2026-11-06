package com.dfc.mosquittoui.localauth;

import com.dfc.mosquittoui.sso.SsoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Whether people can log in with users of this application (LocalUsers): set on the page Login, always while no identity provider is
 * on, and always with mosquitto-ui.auth=local (MOSQUITTO_UI_AUTH), the way back in when nobody can log in at the provider any longer.
 */
@ApplicationScoped
public class LocalAuth {

    public static final String PROPERTY = "mosquitto-ui.auth";
    public static final String LOCAL = "local";

    @ConfigProperty(name = PROPERTY, defaultValue = "oidc")
    String mode;

    @Inject
    SsoService sso;

    /** MOSQUITTO_UI_AUTH=local: the local login stays on, whatever the page Login says */
    public boolean forced() {
        return LOCAL.equalsIgnoreCase(mode.strip());
    }

    public boolean local() {
        return sso.localLogin();
    }
}
