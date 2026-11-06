package com.dfc.mosquittoui.sso;

import io.quarkus.security.AuthenticationRedirectException;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/**
 * A provider that cannot be reached or answers wrongly makes the OIDC extension fail the request: back to the start page with a
 * message instead of an error page, the reason goes to the log.
 */
@ApplicationScoped
public class SsoFailure {

    private static final Logger LOG = Logger.getLogger(SsoFailure.class);

    void routes(@Observes Router router) {
        router.route(SsoTenantResolver.PATH + "*").order(Integer.MIN_VALUE).failureHandler(context -> {
            // the way to the provider comes as a failure as well
            if (context.response().headWritten() || context.failure() instanceof AuthenticationRedirectException) {
                context.next();
                return;
            }
            Throwable failure = context.failure();
            Throwable cause = failure;
            while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            LOG.warnf("Login at %s failed: %s%s", context.normalizedPath(), failure == null ? context.statusCode() : failure.toString(),
                    cause != null && cause != failure ? " (" + cause + ")" : "");
            context.response().setStatusCode(303).putHeader("Location", "/?sso-error=unreachable").putHeader("Cache-Control", "no-store").end();
        });
    }
}
