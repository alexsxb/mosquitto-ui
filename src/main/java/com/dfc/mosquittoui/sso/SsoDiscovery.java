package com.dfc.mosquittoui.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The discovery document of a provider: to test the address on the page Login, and where to log out at the provider. */
@ApplicationScoped
public class SsoDiscovery {

    static final String WELL_KNOWN = "/.well-known/openid-configuration";
    private static final Duration KEEP = Duration.ofHours(1);

    public record Discovered(boolean ok, @Schema(nullable = true) String issuer, @Schema(nullable = true) String authorizationEndpoint,
            @Schema(nullable = true) String endSessionEndpoint, @Schema(nullable = true) String error) {
    }

    private record Kept(Discovered discovered, Instant at) {
    }

    @Inject
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, Kept> kept = new ConcurrentHashMap<>();

    public Discovered discover(String issuer) {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + WELL_KNOWN)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Discovered(false, null, null, null, base + WELL_KNOWN + " answered " + response.statusCode());
            }
            JsonNode doc = mapper.readTree(response.body());
            String found = text(doc, "issuer");
            if (found == null || text(doc, "authorization_endpoint") == null) {
                return new Discovered(false, found, null, null, "Not a discovery document of OpenID Connect");
            }
            String error = strip(found).equals(base) ? null : "The provider calls itself " + found;
            return new Discovered(error == null, found, text(doc, "authorization_endpoint"), text(doc, "end_session_endpoint"), error);
        } catch (IOException | IllegalArgumentException e) {
            return new Discovered(false, null, null, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Discovered(false, null, null, null, "interrupted");
        }
    }

    /** Where to log out at the provider, from a discovery document of the last hour */
    public Optional<String> endSession(String issuer) {
        Kept k = kept.get(issuer);
        if (k == null || k.at().plus(KEEP).isBefore(Instant.now())) {
            k = new Kept(discover(issuer), Instant.now());
            kept.put(issuer, k);
        }
        return Optional.ofNullable(k.discovered().endSessionEndpoint());
    }

    private static String strip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String text(JsonNode doc, String name) {
        JsonNode node = doc.get(name);
        return node == null || !node.isTextual() || node.asText().isBlank() ? null : node.asText();
    }
}
