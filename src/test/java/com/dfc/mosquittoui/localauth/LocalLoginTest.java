package com.dfc.mosquittoui.localauth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.api.MosquittoResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/** The local login as the browser uses it: the cookie, its end, and the users. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestProfile(LocalLoginTest.Local.class)
class LocalLoginTest {

    static final String FIRST = "first-password-123";

    public static class Local implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // many failures from the one address of the tests; the throttle itself is in LoginThrottleTest
            return Map.of("mosquitto-ui.auth", "local", "mosquitto-ui.local-auth.initial-password", FIRST, "mosquitto-ui.local-auth.max-failures", "1000");
        }
    }

    @TestHTTPResource("/")
    URI base;

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    private static Response login(String user, String password) {
        return given().contentType(ContentType.JSON).body(Map.of("username", user, "password", password)).post("/api/auth/login");
    }

    private static String session(String user, String password) {
        Response response = login(user, password);
        assertEquals(204, response.statusCode(), response.body().asString());
        String cookie = response.cookie(LocalSessions.COOKIE);
        assertNotNull(cookie);
        assertTrue(response.detailedCookie(LocalSessions.COOKIE).isHttpOnly());
        return cookie;
    }

    private static String adminSession() {
        // another test may have changed the password of admin
        Response first = login("admin", FIRST);
        return first.statusCode() == 204 ? first.cookie(LocalSessions.COOKIE) : session("admin", "admin-changed-password");
    }

    @Test
    void modeAndNoSession() throws Exception {
        given().get("/api/auth/mode").then().statusCode(200).body("local", equalTo(true));
        given().get("/api/auth/me").then().statusCode(499);
        // a page, as the browser asks for it: to the start page with the form; the tests send X-Requested-With otherwise
        HttpResponse<String> page = HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve("logs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, page.statusCode());
        assertEquals("/", page.headers().firstValue("Location").orElse(null));
        given().cookie(LocalSessions.COOKIE, "forged").get("/api/auth/me").then().statusCode(499);
        // the first password was given, so it is not written down
        assertFalse(Files.exists(stateDir.resolve(LocalUsers.INITIAL_FILE)));
    }

    @Test
    void wrongPassword() {
        login("admin", "wrong-password-123").then().statusCode(401).body("code", equalTo("login_failed"));
        login("nobody", "wrong-password-123").then().statusCode(401);
    }

    @Test
    void sessionGivesAccess() {
        String cookie = adminSession();
        given().cookie(LocalSessions.COOKIE, cookie).get("/api/auth/me").then().statusCode(200).body("username", equalTo("admin")).body("authorized", equalTo(true));
        given().cookie(LocalSessions.COOKIE, cookie).get("/api/local-users").then().statusCode(200).body("username", hasItem("admin"));
        given().cookie(LocalSessions.COOKIE, cookie).post("/api/auth/logout").then().statusCode(200).body("redirect", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void endedSessionsAndDeletedUsers() {
        String admin = adminSession();
        given().cookie(LocalSessions.COOKIE, admin).contentType(ContentType.JSON).body(Map.of("username", "bob", "password", "short"))
                .post("/api/local-users").then().statusCode(400).body("code", equalTo("password_length"));
        given().cookie(LocalSessions.COOKIE, admin).contentType(ContentType.JSON).body(Map.of("username", "bob", "password", "bob-password-123"))
                .post("/api/local-users").then().statusCode(200).body("self", equalTo(false));
        String bob = session("Bob", "bob-password-123");
        given().cookie(LocalSessions.COOKIE, bob).get("/api/auth/me").then().statusCode(200).body("username", equalTo("bob"));

        given().cookie(LocalSessions.COOKIE, admin).post("/api/local-users/bob/end-sessions").then().statusCode(204);
        given().cookie(LocalSessions.COOKIE, bob).get("/api/auth/me").then().statusCode(499);

        String again = session("bob", "bob-password-123");
        given().cookie(LocalSessions.COOKIE, again).delete("/api/local-users/bob").then().statusCode(409).body("code", equalTo("self"));
        given().cookie(LocalSessions.COOKIE, admin).delete("/api/local-users/bob").then().statusCode(204);
        given().cookie(LocalSessions.COOKIE, again).get("/api/auth/me").then().statusCode(499);
        login("bob", "bob-password-123").then().statusCode(401);
    }

    @Test
    void ownNewPasswordKeepsTheSession() {
        String old = adminSession();
        Response changed = given().cookie(LocalSessions.COOKIE, old).contentType(ContentType.JSON).body(Map.of("password", "admin-changed-password"))
                .put("/api/local-users/admin/password");
        assertEquals(200, changed.statusCode(), changed.body().asString());
        String renewed = changed.cookie(LocalSessions.COOKIE);
        assertNotEquals(old, renewed);
        given().cookie(LocalSessions.COOKIE, old).get("/api/auth/me").then().statusCode(499);
        given().cookie(LocalSessions.COOKIE, renewed).get("/api/auth/me").then().statusCode(200);
        session("admin", "admin-changed-password");
    }
}
