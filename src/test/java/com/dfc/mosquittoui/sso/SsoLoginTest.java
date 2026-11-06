package com.dfc.mosquittoui.sso;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.api.MosquittoResource;
import com.dfc.mosquittoui.localauth.LocalUsers;
import com.dfc.mosquittoui.sso.SsoService.AllowedView;
import com.dfc.mosquittoui.sso.SsoService.ProviderInput;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The code flow with a provider of the page Login against FakeIdp, as a browser goes through it, and who comes in. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestProfile(SsoLoginTest.Profile.class)
class SsoLoginTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.auth.permission.fake-idp.paths", "/fake-idp/*", "quarkus.http.auth.permission.fake-idp.policy", "permit");
        }
    }

    @TestHTTPResource("/")
    URI base;

    @Inject
    SsoService sso;

    @Inject
    LocalUsers users;

    /** A browser: keeps cookies, follows no redirect by itself */
    static class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).followRedirects(HttpClient.Redirect.NEVER)
                .build();

        /** Follows the redirects of the code flow, up to the start page of the UI */
        URI login(URI base, String provider) throws Exception {
            URI at = base.resolve("/auth/sso/" + provider);
            for (int i = 0; i < 10; i++) {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(at).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 3) {
                    throw new AssertionError(at + " answered " + response.statusCode() + ": " + response.body());
                }
                at = at.resolve(response.headers().firstValue("Location").orElseThrow());
                if (at.getPath().equals("/")) {
                    return at;
                }
            }
            throw new AssertionError("no end of redirects");
        }

        HttpResponse<String> api(URI base, String path) throws Exception {
            return http.send(HttpRequest.newBuilder(base.resolve(path)).header("X-Requested-With", "JavaScript").GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(URI base, String path) throws Exception {
            return http.send(HttpRequest.newBuilder(base.resolve(path)).header("X-Requested-With", "JavaScript").POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private ProviderInput input(String template, List<String> roles, List<AllowedView> allowed, boolean all) {
        return new ProviderInput("fake", "Fake IdP", template, true, base.resolve("/fake-idp").toString(), FakeIdp.CLIENT, FakeIdp.SECRET, null, null, null, null,
                null, roles, all, allowed, null);
    }

    @AfterEach
    void cleanUp() {
        sso.setLocalLogin(true);
        sso.settings().providers().forEach(p -> sso.delete(p.id()));
        users.find("bob").ifPresent(u -> users.delete("bob"));
    }

    @Test
    void roleFromTheAccessTokenLetsIn() throws Exception {
        sso.create(input("keycloak", List.of("mosquitto-admin"), List.of(), false));
        FakeIdp.next = Map.of("sub", "u-1", "preferred_username", "alice", "name", "Alice A.", "email", "alice@example.org",
                "realm_access", Map.of("roles", List.of("mosquitto-admin")));
        Browser browser = new Browser();
        URI end = browser.login(base, "fake");
        assertEquals(null, end.getQuery());
        HttpResponse<String> me = browser.api(base, "/api/auth/me");
        assertEquals(200, me.statusCode(), me.body());
        assertTrue(me.body().contains("\"username\":\"alice\"") && me.body().contains("Alice A.") && me.body().contains("\"authorized\":true"), me.body());
        assertEquals(200, browser.api(base, "/api/clients").statusCode());
        // the session of the extension is gone, only the one of the application is left
        assertTrue(browser.http.cookieHandler().orElseThrow() instanceof CookieManager cm
                && cm.getCookieStore().getCookies().stream().noneMatch(c -> c.getName().startsWith("q_session")), "session of the extension left");
    }

    @Test
    void withoutTheRoleNobodyComesInButIsNotedAndCanBeLetIn() throws Exception {
        sso.create(input("keycloak", List.of("mosquitto-admin"), List.of(), false));
        FakeIdp.next = Map.of("sub", "u-2", "preferred_username", "mallory", "realm_access", Map.of("roles", List.of("viewer")));
        Browser browser = new Browser();
        URI end = browser.login(base, "fake");
        assertTrue(end.getRawQuery().contains("sso-denied=Fake+IdP") && end.getRawQuery().contains("who=mallory"), end.toString());
        assertEquals(499, browser.api(base, "/api/auth/me").statusCode());
        SsoService.ProviderView view = sso.settings().providers().getFirst();
        assertEquals("mallory", view.denied().getFirst().username());
        assertEquals(List.of("viewer"), view.denied().getFirst().roles());

        sso.allow("fake", "mallory", null);
        assertTrue(sso.settings().providers().getFirst().denied().isEmpty());
        assertEquals(null, browser.login(base, "fake").getQuery());
        assertEquals(200, browser.api(base, "/api/auth/me").statusCode());
    }

    @Test
    void anAddressCanBeALocalUserButOnlyVerified() throws Exception {
        users.add("bob", "bob-password-1234");
        sso.create(input("oidc", List.of(), List.of(new AllowedView("bob@example.org", "bob")), false));
        FakeIdp.next = Map.of("sub", "g-3", "email", "bob@example.org", "email_verified", false);
        Browser browser = new Browser();
        assertTrue(browser.login(base, "fake").getQuery().contains("sso-denied"));

        FakeIdp.next = Map.of("sub", "g-3", "email", "Bob@Example.org", "email_verified", true);
        assertEquals(null, browser.login(base, "fake").getQuery());
        HttpResponse<String> me = browser.api(base, "/api/auth/me");
        assertTrue(me.body().contains("\"username\":\"bob\""), me.body());

        // a new password of bob ends the session as well
        users.setPassword("bob", "bob-password-5678");
        assertEquals(499, browser.api(base, "/api/auth/me").statusCode());
    }

    @Test
    void aDomainAndEveryoneAndAChangeEndsTheSessions() throws Exception {
        sso.create(input("oidc", List.of(), List.of(new AllowedView("*@example.org", null)), false));
        FakeIdp.next = Map.of("sub", "x-1", "email", "carol@sub.example.org");
        Browser browser = new Browser();
        assertTrue(browser.login(base, "fake").getQuery().contains("sso-denied"));
        FakeIdp.next = Map.of("sub", "x-2", "email", "carol@example.org");
        assertEquals(null, browser.login(base, "fake").getQuery());
        assertTrue(browser.api(base, "/api/auth/me").body().contains("carol@example.org"));

        sso.update("fake", input("oidc", List.of(), List.of(new AllowedView("*@example.org", null), new AllowedView("someone@else.test", null)), false));
        assertEquals(200, browser.api(base, "/api/auth/me").statusCode(), "more people allowed: the session stays");
        sso.update("fake", input("oidc", List.of(), List.of(), true));
        assertEquals(499, browser.api(base, "/api/auth/me").statusCode(), "the domain is gone: the sessions of the provider end");
        FakeIdp.next = Map.of("sub", "x-3", "preferred_username", "dave");
        assertEquals(null, browser.login(base, "fake").getQuery());

        HttpResponse<String> out = browser.post(base, "/api/auth/logout");
        assertTrue(out.body().contains(base.resolve("/fake-idp/logout").toString()) && out.body().contains("client_id=" + FakeIdp.CLIENT), out.body());
        assertEquals(499, browser.api(base, "/api/auth/me").statusCode());
    }

    @Test
    void theLocalLoginCanBeTurnedOffButNotTheLastWayIn() throws Exception {
        assertTrue(sso.localLogin(), "on while there is no provider");
        assertThrows(SsoException.class, () -> sso.setLocalLogin(false));
        sso.create(input("oidc", List.of(), List.of(), true));
        sso.setLocalLogin(false);
        assertFalse(sso.localLogin());
        given().contentType(ContentType.JSON).body(Map.of("username", "admin", "password", "whatever-password")).post("/api/auth/login").then().statusCode(404);
        given().get("/api/auth/mode").then().statusCode(200).body("local", org.hamcrest.Matchers.equalTo(false)).body("providers[0].id",
                org.hamcrest.Matchers.equalTo("fake"));
        SsoException last = assertThrows(SsoException.class, () -> sso.delete("fake"));
        assertEquals("last_way", last.code());
        assertThrows(SsoException.class, () -> sso.update("fake", new ProviderInput(null, "Fake IdP", "oidc", false, base.resolve("/fake-idp").toString(),
                FakeIdp.CLIENT, null, null, null, null, null, null, null, true, null, null)));
    }

    @Test
    void anUnknownOrOffProviderHasNoCodeFlow() throws Exception {
        HttpResponse<String> response = new Browser().http.send(HttpRequest.newBuilder(base.resolve("/auth/sso/nothing")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        // no tenant: back to the start page, nothing at a provider
        assertEquals(302, response.statusCode());
        assertEquals("/", URI.create(response.headers().firstValue("Location").orElseThrow()).getPath());
    }

    @Inject
    com.dfc.mosquittoui.backup.ArchiveService archives;

    @Test
    void theBackupOfAccessBringsProvidersAndUiUsersBack() throws Exception {
        users.add("bob", "bob-password-1234");
        sso.create(input("keycloak", List.of("mosquitto-admin"), List.of(new AllowedView("bob@example.org", "bob")), false));
        com.dfc.mosquittoui.backup.ArchiveService.Archive archive = archives.read(
                archives.createZip(java.util.EnumSet.of(com.dfc.mosquittoui.backup.BackupSection.ACCESS), "test"), null);
        assertTrue(archive.entries().containsKey("access/sso.json") && archive.entries().containsKey("access/ui-users.json"));
        assertFalse(new String(archive.entries().get("access/sso.json")).contains("denied\":[{"), "who was turned away stays here");

        sso.delete("fake");
        users.delete("bob");
        archives.restore(archive, java.util.EnumSet.of(com.dfc.mosquittoui.backup.BackupSection.ACCESS), true);
        SsoService.ProviderView back = sso.settings().providers().getFirst();
        assertEquals("fake", back.id());
        assertTrue(back.secretSet());
        assertEquals("bob", back.allowed().getFirst().localUser());
        assertTrue(users.check("bob", "bob-password-1234").isPresent(), "the UI user with its password");
        FakeIdp.next = Map.of("sub", "u-9", "preferred_username", "frank", "realm_access", Map.of("roles", List.of("mosquitto-admin")));
        assertEquals(null, new Browser().login(base, "fake").getQuery(), "the restored provider works");
    }

    @Test
    void aProviderThatDoesNotAnswerLeadsBackWithAMessage() throws Exception {
        sso.create(new ProviderInput("fake", "Fake IdP", "oidc", true, base.resolve("/nowhere").toString(), FakeIdp.CLIENT, FakeIdp.SECRET, null, null, null,
                null, null, null, true, null, null));
        HttpResponse<String> response = new Browser().http.send(HttpRequest.newBuilder(base.resolve("/auth/sso/fake")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(303, response.statusCode(), response.headers().map() + response.body());
        assertEquals("/?sso-error=unreachable", response.headers().firstValue("Location").orElseThrow());
    }

    @Test
    @TestSecurity(user = "admin", roles = "mosquitto-admin")
    void theApiKeepsTheSecret() {
        given().contentType(ContentType.JSON).body(input("oidc", List.of(), List.of(), true)).post("/api/sso/providers").then().statusCode(200)
                .body("secretSet", org.hamcrest.Matchers.equalTo(true)).body("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("clientSecret")));
        String all = given().get("/api/sso").then().statusCode(200).extract().asString();
        assertFalse(all.contains(FakeIdp.SECRET));
        assertTrue(all.contains("\"templates\""));
        given().contentType(ContentType.JSON).body(Map.of("issuer", base.resolve("/fake-idp").toString())).post("/api/sso/discover").then().statusCode(200)
                .body("ok", org.hamcrest.Matchers.equalTo(true));
        given().contentType(ContentType.JSON).body(new ProviderInput("Bad Id", "x", "oidc", true, "https://x.example", "c", "s", null, null, null, null, null, null,
                true, null, null)).post("/api/sso/providers").then().statusCode(400).body("field", org.hamcrest.Matchers.equalTo("id"));
    }
}
