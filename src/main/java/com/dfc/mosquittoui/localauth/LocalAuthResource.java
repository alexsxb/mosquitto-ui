package com.dfc.mosquittoui.localauth;

import com.dfc.mosquittoui.api.Dtos.ErrorResponse;
import com.dfc.mosquittoui.audit.AuditLog;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import com.dfc.mosquittoui.sso.SsoDiscovery;
import com.dfc.mosquittoui.sso.SsoService;
import io.vertx.core.http.Cookie;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** How one can log in, the local login and the logout of every session. Open to everyone. */
@Path("/api/auth")
public class LocalAuthResource {

    public record AuthMode(@Schema(description = "users of this application can log in with a password") boolean local,
            @Schema(description = "identity providers, a button each") List<SsoService.LoginOption> providers) {
    }

    public record LoggedOut(@Schema(nullable = true, description = "log out at the identity provider as well, there") String redirect) {
    }

    public record LoginInput(@Schema(nullable = true) String username, @Schema(nullable = true) String password) {
    }

    @Inject
    LocalAuth auth;

    @Inject
    LocalUsers users;

    @Inject
    LocalSessions sessions;

    @Inject
    LoginThrottle throttle;

    @Inject
    AuditLog audit;

    @Inject
    RoutingContext context;

    @Inject
    SsoService sso;

    @Inject
    SsoDiscovery discovery;

    @GET
    @Path("/mode")
    public AuthMode mode() {
        return new AuthMode(auth.local(), sso.loginOptions());
    }

    @POST
    @Path("/login")
    public Response login(LoginInput input) {
        if (!auth.local()) {
            return error(Response.Status.NOT_FOUND, "local_off", "The local login is off, log in with an identity provider");
        }
        String username = input == null || input.username() == null ? "" : input.username().strip();
        String address = context.request().remoteAddress() == null ? null : context.request().remoteAddress().hostAddress();
        Instant now = Instant.now();
        Duration wait = throttle.blocked(username, address, now);
        if (wait != null) {
            long seconds = Math.max(1, wait.toSeconds());
            return Response.status(429).header("Retry-After", seconds).type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorResponse("too_many_attempts", "Too many failed logins, try again in " + seconds + " seconds", null)).build();
        }
        Optional<LocalUsers.User> user = users.check(username, input == null ? null : input.password());
        Map<String, String> details = new LinkedHashMap<>();
        details.put("address", String.valueOf(address));
        if (user.isEmpty()) {
            throttle.failed(username, address, now);
            audit.record(username.isEmpty() ? "-" : username, "login", details, "wrong user name or password");
            return error(Response.Status.UNAUTHORIZED, "login_failed", "Wrong user name or password");
        }
        throttle.succeeded(username);
        audit.record(user.get().username(), "login", details, null);
        sessions.set(context, sessions.value(user.get().username(), user.get().version(), now, now));
        return Response.noContent().build();
    }

    /** Ends the session here; one from a provider that can log out (end_session_endpoint) ends there as well, with a redirect */
    @POST
    @Path("/logout")
    public LoggedOut logout() {
        Cookie cookie = context.request().getCookie(LocalSessions.COOKIE);
        Optional<String> hint = sessions.logoutHint(context);
        sessions.clear(context);
        sessions.clearLogoutHint(context);
        Optional<String> provider = cookie == null ? Optional.empty() : sessions.read(cookie.getValue(), Instant.now()).map(LocalSessions.Content::p);
        return new LoggedOut(provider.flatMap(sso::provider).flatMap(p -> discovery.endSession(p.issuer).map(end -> {
            String home = context.request().scheme() + "://" + context.request().authority() + "/";
            UriBuilder uri = UriBuilder.fromUri(end).queryParam("client_id", p.clientId).queryParam("post_logout_redirect_uri", home);
            hint.ifPresent(token -> uri.queryParam("id_token_hint", token));
            return uri.build().toString();
        })).orElse(null));
    }

    private static Response error(Response.Status status, String code, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(new ErrorResponse(code, message, null)).build();
    }
}
