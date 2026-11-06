package com.dfc.mosquittoui.localauth;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;

/**
 * The OIDC extension has no tenant of its own (quarkus.oidc.tenant-enabled=false), whatever QUARKUS_OIDC_* says: the identity
 * providers are set up on the page Login and are tenants on /auth/sso/* only (sso.SsoTenantResolver). QUARKUS_OIDC_* is taken over
 * once (sso.SsoService). Registered in META-INF/services.
 */
@Priority(Priorities.APPLICATION + 100)
public class OidcDefaultTenantOff implements ConfigSourceInterceptor {

    static final String TENANT_ENABLED = "quarkus.oidc.tenant-enabled";

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        if (TENANT_ENABLED.equals(name)) {
            return ConfigValue.builder().withName(name).withValue("false").withConfigSourceName("mosquitto-ui (page Login)").build();
        }
        return context.proceed(name);
    }
}
