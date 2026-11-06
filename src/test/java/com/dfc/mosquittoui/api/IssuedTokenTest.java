package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

/** Tokens that mosquitto-ui signs itself: their roles stay in the UI, by the id of the token, so they can change and be taken back. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class IssuedTokenTest {

    private static final String WRITER = "issued-writer";
    private static final String READER = "issued-reader";

    private static Path state(String name) {
        return ConfigProvider.getConfig().getValue("mosquitto-ui.state-dir", Path.class).resolve(name);
    }

    private static void role(String name, String acltype, String topic) {
        given().contentType(ContentType.JSON).body(Map.of("rolename", name, "allowwildcardsubs", true)).post("/api/roles").then().statusCode(204);
        given().contentType(ContentType.JSON).body(Map.of("acltype", acltype, "topic", topic, "priority", 0, "allow", true))
                .post("/api/roles/" + name + "/acls").then().statusCode(204);
    }

    @BeforeEach
    void roles() {
        role(WRITER, "publishClientSend", "devices/%u/#");
        role(READER, "publishClientSend", "elsewhere/%u/#");
    }

    @AfterEach
    void cleanUp() throws Exception {
        given().delete("/api/roles/" + WRITER).then().statusCode(204);
        given().delete("/api/roles/" + READER).then().statusCode(204);
        Files.deleteIfExists(state("token-auth.json"));
        Files.deleteIfExists(state("token-auth-signing.key"));
        Files.deleteIfExists(ConfigProvider.getConfig().getValue("mosquitto-ui.shared-dir", Path.class).resolve("token-auth/token-auth.json"));
    }

    private static JsonPath issue(String username, List<String> roles) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("username", username, "roles", roles, "expires", Instant.now().plus(Duration.ofDays(30)).toString(), "note", "test"))
                .post("/api/token-auth/issued").then().log().ifValidationFails().statusCode(200).extract().jsonPath();
    }

    private static ExecResult publish(String clientId, String token, String topic) throws Exception {
        return MosquittoResource.broker.execInContainer("mosquitto_pub", "-V", "5", "-i", clientId, "-u", "x", "-P", token, "-t", topic, "-m", "1", "-q", "1");
    }

    private static boolean allowed(String clientId, String token, String topic) throws Exception {
        ExecResult result = publish(clientId, token, topic);
        return result.getExitCode() == 0 && !(result.getStdout() + result.getStderr()).contains("failed");
    }

    private static boolean refused(String clientId, String token) throws Exception {
        ExecResult result = publish(clientId, token, "devices/x");
        return result.getExitCode() != 0 && (result.getStdout() + result.getStderr()).toLowerCase().contains("not authori");
    }

    @Test
    void anIssuedTokenLogsInWithItsRoles() throws Exception {
        JsonPath answer = issue("meter-1", List.of(WRITER));
        String token = answer.getString("token");
        assertEquals("mosquitto-ui", answer.getString("issuer"));
        String[] parts = token.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]));
        assertTrue(claims.contains("\"jti\":\"" + answer.getString("issued.id") + "\""));
        assertFalse(claims.contains(WRITER), "the roles are not in the token");

        await().atMost(Duration.ofSeconds(10)).until(() -> allowed("meter-1", token, "devices/meter-1/power"));
        assertTrue(allowed("meter-1/second", token, "devices/meter-1/power"), "a client id below the user name");
        assertFalse(allowed("meter-1", token, "devices/meter-2/power"), "%u is the name of the token");
        assertTrue(refused("someone-else", token), "the client id is bound to the user name");

        // the token itself is not kept, the private key not in the clear
        assertFalse(Files.readString(state("token-auth.json")).contains(parts[2]));
        assertTrue(Files.readString(state("token-auth-signing.key")).startsWith("enc:"));
        assertFalse(given().get("/api/audit").asString().contains(parts[2]));
        given().get("/api/audit").then().body("action", hasItem("issueToken"));
    }

    @Test
    void itsRolesChangeWithoutANewToken() throws Exception {
        JsonPath answer = issue("meter-2", List.of(WRITER));
        String token = answer.getString("token");
        await().atMost(Duration.ofSeconds(10)).until(() -> allowed("meter-2", token, "devices/meter-2/a"));
        given().contentType(ContentType.JSON).body(List.of(READER)).put("/api/token-auth/issued/" + answer.getString("issued.id")).then().statusCode(200)
                .body("issued.find { it.username == 'meter-2' }.roles", contains(READER));
        await().atMost(Duration.ofSeconds(10)).until(() -> !allowed("meter-2", token, "devices/meter-2/a"));
        assertTrue(allowed("meter-2", token, "elsewhere/meter-2/a"));
    }

    @Test
    void aTokenTakenBackIsOutAtOnce() throws Exception {
        JsonPath answer = issue("meter-3", List.of(WRITER));
        String token = answer.getString("token");
        MosquittoResource.broker.execInContainer("sh", "-c",
                "sleep 30 | mosquitto_pub -V 5 -i meter-3 -u x -P \"$1\" -t devices/meter-3/x -l > /dev/null 2>&1 &", "sh", token);
        await().atMost(Duration.ofSeconds(10)).until(() -> MosquittoResource.broker.getLogs().contains("client meter-3 logged in as meter-3"));
        given().delete("/api/token-auth/issued/" + answer.getString("issued.id")).then().statusCode(200)
                .body("issued.username", not(hasItem("meter-3")));
        await().atMost(Duration.ofSeconds(10)).until(() -> MosquittoResource.broker.getLogs().contains("client meter-3 disconnected"));
        assertTrue(refused("meter-3", token));
    }

    @Test
    void theSigningKeyStaysForTheNextToken() {
        String first = issue("meter-4", List.of(WRITER)).getString("token");
        String second = issue("meter-5", List.of(WRITER)).getString("token");
        assertEquals(new String(Base64.getUrlDecoder().decode(first.split("\\.")[0])), new String(Base64.getUrlDecoder().decode(second.split("\\.")[0])),
                "the same key id");
    }

    @Test
    void tokensThatDoNotHoldAreNotIssued() {
        Map<String, Object> base = Map.of("username", "meter-x", "roles", List.of(WRITER), "expires", Instant.now().plus(Duration.ofDays(1)).toString());
        given().contentType(ContentType.JSON).body(with(base, "username", "admin")).post("/api/token-auth/issued").then().statusCode(400)
                .body("field", equalTo("username"));
        given().contentType(ContentType.JSON).body(with(base, "username", "a/b")).post("/api/token-auth/issued").then().statusCode(400);
        given().contentType(ContentType.JSON).body(with(base, "roles", List.of("no-such-role"))).post("/api/token-auth/issued").then().statusCode(400)
                .body("field", equalTo("roles"));
        given().contentType(ContentType.JSON).body(with(base, "expires", Instant.now().minusSeconds(60).toString())).post("/api/token-auth/issued").then()
                .statusCode(400).body("field", equalTo("expires"));
        // the roles of the UI itself are not for tokens
        String locked = given().get("/api/roles").jsonPath().getString("find { it.locked }.rolename");
        given().contentType(ContentType.JSON).body(with(base, "roles", List.of(locked))).post("/api/token-auth/issued").then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("enabled", true, "issuers", List.of(Map.of("issuer", "mosquitto-ui", "mappings", List.of()))))
                .put("/api/token-auth").then().statusCode(400).body("field", equalTo("issuer"));
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new java.util.HashMap<>(base);
        copy.put(key, value);
        return copy;
    }
}
