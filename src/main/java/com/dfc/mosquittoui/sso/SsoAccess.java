package com.dfc.mosquittoui.sso;

import com.dfc.mosquittoui.sso.SsoStore.Allowed;
import com.dfc.mosquittoui.sso.SsoStore.Provider;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Who may come in after logging in at a provider: somebody on the list (by e-mail address, domain or user name, maybe as a local
 * user), everyone, a role or group from the tokens, or a member of a GitHub organisation or team. The first that fits counts.
 */
@ApplicationScoped
public class SsoAccess {

    private static final Logger LOG = Logger.getLogger(SsoAccess.class);
    private static final int PAGES = 10;

    /** Who logged in at the provider; email only when the provider does not say it is unverified */
    public record Person(String subject, String username, String email, String name, Set<String> roles) {

        /** How the person is called in the audit log and the header */
        String principal() {
            return username != null ? username : email != null ? email : subject;
        }
    }

    /** localUser: the session is the one of this local user */
    public record Decision(boolean allowed, String localUser, String reason) {
    }

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mosquitto-ui.sso.github-api", defaultValue = "https://api.github.com")
    String githubApi;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public Decision decide(Provider provider, Person person, Optional<String> accessToken) {
        for (Allowed a : provider.allowed) {
            if (matches(a.match, person)) {
                return new Decision(true, a.localUser, "allowed: " + a.match);
            }
        }
        if (provider.allowAll) {
            return new Decision(true, null, "everyone of the provider");
        }
        for (String role : provider.allowRoles) {
            if (person.roles().contains(role)) {
                return new Decision(true, null, "role " + role);
            }
        }
        if (!provider.githubOrgs.isEmpty() && accessToken.isPresent()) {
            Set<String> memberships = githubMemberships(accessToken.get());
            for (String org : provider.githubOrgs) {
                if (memberships.contains(org.toLowerCase(Locale.ROOT))) {
                    return new Decision(true, null, "GitHub " + org);
                }
            }
        }
        return new Decision(false, null, "not allowed");
    }

    static boolean matches(String match, Person person) {
        if (match.startsWith("*@")) {
            return person.email() != null && person.email().toLowerCase(Locale.ROOT).endsWith(match.substring(1).toLowerCase(Locale.ROOT));
        }
        if (match.contains("@") && person.email() != null && person.email().equalsIgnoreCase(match)) {
            return true;
        }
        return person.username() != null && person.username().equalsIgnoreCase(match);
    }

    /** "org" and "org/team" in small letters, for the organisations and teams the token may see (read:org) */
    Set<String> githubMemberships(String token) {
        Set<String> found = new HashSet<>();
        try {
            for (JsonNode org : pages("/user/orgs", token)) {
                found.add(org.path("login").asText().toLowerCase(Locale.ROOT));
            }
            for (JsonNode team : pages("/user/teams", token)) {
                String org = team.path("organization").path("login").asText().toLowerCase(Locale.ROOT);
                found.add(org);
                found.add(org + "/" + team.path("slug").asText().toLowerCase(Locale.ROOT));
            }
        } catch (IOException e) {
            LOG.warnf("GitHub: could not read the organisations: %s", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return found;
    }

    private Iterable<JsonNode> pages(String path, String token) throws IOException, InterruptedException {
        java.util.List<JsonNode> all = new java.util.ArrayList<>();
        for (int page = 1; page <= PAGES; page++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(githubApi + path + "?per_page=100&page=" + page)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json").header("User-Agent", "mosquitto-ui").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException(path + " answered " + response.statusCode());
            }
            JsonNode list = mapper.readTree(response.body());
            list.forEach(all::add);
            if (list.size() < 100) {
                break;
            }
        }
        return all;
    }
}
