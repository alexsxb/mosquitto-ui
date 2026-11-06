package com.dfc.mosquittoui.localauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dfc.mosquittoui.security.SecretCipher;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The session of the local login: an encrypted cookie (AES-GCM with the secret key of SecretCipher) with the user, the version
 * of the user and the times. Nothing is kept on the server: a session ends when it is idle for {@code session-timeout}, at the latest
 * {@code session-max} after the login, or when the version of the user changes (new password, sessions ended, user deleted).
 */
@ApplicationScoped
public class LocalSessions {

    public static final String COOKIE = "mosquitto-ui-session";
    /** The ID token of a provider, for its logout (id_token_hint); only sent to the logout */
    public static final String LOGOUT_COOKIE = "mosquitto-ui-logout";
    static final String LOGOUT_PATH = "/api/auth/logout";
    /** Cookies are at most 4 KB, an ID token that does not fit goes without */
    static final int MAX_HINT = 2800;
    /** A cookie is renewed when it is older than this, not with every request */
    static final Duration RENEW_AFTER = Duration.ofMinutes(1);

    /**
     * u: user, v: version of the local user, l: login, i: issued. From an identity provider also p: its id, pv: its version, n:
     * name and e: e-mail address; u is a local user then only if the provider maps the person to one (v counts then), else the
     * name the person has at the provider.
     */
    public record Content(String u, long v, Instant l, Instant i, String p, long pv, String n, String e) {

        Content(String u, long v, Instant l, Instant i) {
            this(u, v, l, i, null, 0, null, null);
        }

        Content renewed(Instant now) {
            return new Content(u, v, l, now, p, pv, n, e);
        }
    }

    @Inject
    SecretCipher cipher;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mosquitto-ui.local-auth.session-timeout", defaultValue = "PT8H")
    Duration timeout;

    @ConfigProperty(name = "mosquitto-ui.local-auth.session-max", defaultValue = "P7D")
    Duration max;

    String value(String username, long version, Instant login, Instant now) {
        return value(new Content(username, version, login, now));
    }

    public String value(Content content) {
        try {
            return cipher.seal(mapper.writeValueAsString(content)).replace("enc:v1:", "");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The content of a cookie that is genuine and still valid, as far as time goes */
    public Optional<Content> read(String value, Instant now) {
        if (value == null || value.isBlank() || value.length() > 4096) {
            return Optional.empty();
        }
        try {
            Content content = mapper.readValue(cipher.open("enc:v1:" + value), Content.class);
            if (content.u() == null || content.l() == null || content.i() == null || content.i().plus(timeout).isBefore(now)
                    || content.l().plus(max).isBefore(now) || content.i().isAfter(now.plusSeconds(60))) {
                return Optional.empty();
            }
            return Optional.of(content);
        } catch (IOException | RuntimeException e) {
            // forged, from another key or broken
            return Optional.empty();
        }
    }

    public void set(RoutingContext context, String value) {
        context.response().addCookie(cookie(context, value).setMaxAge(max.toSeconds()));
    }

    /** Keeps the ID token of a provider, encrypted, so that the logout there needs no question (id_token_hint) */
    public void setLogoutHint(RoutingContext context, String idToken) {
        if (idToken == null || idToken.length() > MAX_HINT) {
            clearLogoutHint(context);
            return;
        }
        context.response().addCookie(cookie(context, LOGOUT_COOKIE, cipher.seal(idToken).replace("enc:v1:", "")).setPath(LOGOUT_PATH).setMaxAge(max.toSeconds()));
    }

    public Optional<String> logoutHint(RoutingContext context) {
        Cookie cookie = context.request().getCookie(LOGOUT_COOKIE);
        if (cookie == null || cookie.getValue().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(cipher.open("enc:v1:" + cookie.getValue()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public void clearLogoutHint(RoutingContext context) {
        context.response().addCookie(cookie(context, LOGOUT_COOKIE, "").setPath(LOGOUT_PATH).setMaxAge(0));
    }

    public void clear(RoutingContext context) {
        context.response().addCookie(cookie(context, "").setMaxAge(0));
    }

    /** Secure as soon as the browser came through HTTPS, also through a proxy that says so (X-Forwarded-Proto, if trusted) */
    private static Cookie cookie(RoutingContext context, String value) {
        return cookie(context, COOKIE, value);
    }

    private static Cookie cookie(RoutingContext context, String name, String value) {
        boolean https = context.request().isSSL() || "https".equalsIgnoreCase(context.request().scheme());
        return Cookie.cookie(name, value).setPath("/").setHttpOnly(true).setSecure(https).setSameSite(CookieSameSite.LAX);
    }

    boolean renew(Content content, Instant now) {
        return content.i().plus(RENEW_AFTER).isBefore(now);
    }
}
