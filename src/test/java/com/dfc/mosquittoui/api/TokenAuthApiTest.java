package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.backup.ArchiveService;
import com.dfc.mosquittoui.backup.ArchiveService.Archive;
import com.dfc.mosquittoui.backup.BackupSection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The page Token login: an identity provider of its own (discovery document and JWKS over HTTP), the UI fetches its keys,
 * maps its groups to roles of dynamic security and writes the file of the plugin, and the broker takes its tokens.
 */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
@SuppressWarnings("unchecked")
class TokenAuthApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROLE = "token-api-sensors";

    @Inject
    ArchiveService archives;

    private HttpServer server;
    private String issuer;
    private KeyPair rsa;
    private KeyPair ec;

    private static String number(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] coordinate(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] out = new byte[32];
        System.arraycopy(bytes, Math.max(0, bytes.length - 32), out, Math.max(0, 32 - bytes.length), Math.min(32, bytes.length));
        return out;
    }

    /** The keys the provider hands out, which a test may add to, as at a rotation */
    private final List<Map<String, Object>> jwks = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void serve(String path, String body) {
        serve(path, () -> body);
    }

    private void serve(String path, java.util.function.Supplier<String> body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @BeforeEach
    void identityProvider() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        rsa = generator.generateKeyPair();
        KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
        ecGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        ec = ecGenerator.generateKeyPair();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/devices";
        RSAPublicKey rsaKey = (RSAPublicKey) rsa.getPublic();
        ECPublicKey ecKey = (ECPublicKey) ec.getPublic();
        serve("/realms/devices/.well-known/openid-configuration", JSON.writeValueAsString(Map.of("issuer", issuer, "jwks_uri", issuer + "/certs")));
        jwks.clear();
        jwks.addAll(List.of(
                Map.of("kid", "r1", "kty", "RSA", "use", "sig", "alg", "RS256", "n", number(rsaKey.getModulus()), "e", number(rsaKey.getPublicExponent())),
                Map.of("kid", "e1", "kty", "EC", "crv", "P-256",
                        "x", Base64.getUrlEncoder().withoutPadding().encodeToString(coordinate(ecKey.getW().getAffineX())),
                        "y", Base64.getUrlEncoder().withoutPadding().encodeToString(coordinate(ecKey.getW().getAffineY()))),
                // for encryption, left out
                Map.of("kid", "enc", "kty", "RSA", "use", "enc", "n", number(rsaKey.getModulus()), "e", number(rsaKey.getPublicExponent())),
                Map.of("kid", "oct", "kty", "oct", "k", "c2VjcmV0")));
        serve("/realms/devices/certs", () -> {
            try {
                return JSON.writeValueAsString(Map.of("keys", jwks));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        server.start();

        given().contentType(ContentType.JSON).body(Map.of("rolename", ROLE, "allowwildcardsubs", true)).post("/api/roles").then().statusCode(204);
        given().contentType(ContentType.JSON).body(Map.of("acltype", "publishClientSend", "topic", "sensors/%u/#", "priority", 0, "allow", true))
                .post("/api/roles/" + ROLE + "/acls").then().statusCode(204);
    }

    @AfterEach
    void cleanUp() throws Exception {
        // stopped already by a test, which it does not mind
        server.stop(0);
        given().delete("/api/roles/" + ROLE).then().statusCode(204);
        // without settings the UI writes no file, which TokenAuthTest writes itself
        Path state = ConfigProvider.getConfig().getValue("mosquitto-ui.state-dir", Path.class).resolve("token-auth.json");
        Files.deleteIfExists(state);
        Files.deleteIfExists(brokerFile());
    }

    private static Path brokerFile() {
        return ConfigProvider.getConfig().getValue("mosquitto-ui.shared-dir", Path.class).resolve("token-auth/token-auth.json");
    }

    private Map<String, Object> settings(boolean enabled) {
        return Map.of("enabled", enabled, "issuers", List.of(Map.of("issuer", issuer, "audience", "mqtt", "usernameClaim", "", "groupsClaim", "groups",
                "usernamePrefix", "", "mappings", List.of(Map.of("group", "devices", "roles", List.of(ROLE, "no-such-role"))))));
    }

    private String token(String name) throws Exception {
        return TokenAuthTest.token("RS256", "r1", rsa.getPrivate(), TokenAuthTest.claims(name, issuer, 300, "mqtt", List.of("devices")));
    }

    @Test
    void theKeysOfTheProviderAndTheRolesReachTheBroker() throws Exception {
        given().contentType(ContentType.JSON).body(settings(true)).put("/api/token-auth").then().log().ifValidationFails().statusCode(200)
                .body("enabled", equalTo(true))
                .body("issuers[0].usernameClaim", equalTo("preferred_username"))
                .body("status[0].keys", equalTo(2))
                .body("status[0].kids", contains("r1", "e1"))
                .body("status[0].error", nullValue())
                .body("missingRoles", contains("no-such-role"))
                .body("written", notNullValue());

        JsonNode file = JSON.readTree(Files.readString(brokerFile()));
        assertTrue(file.path("enabled").asBoolean());
        assertTrue(file.path("reservedUsernames").toString().contains("\"admin\""), "the clients of dynamic security");
        assertTrue(file.path("roles").get(0).path("rolename").asText().equals(ROLE));

        String token = token("api-dev");
        await().atMost(Duration.ofSeconds(10)).until(() -> TokenAuthTest.allowed(token, "sensors/api-dev/x"));
        assertFalse(TokenAuthTest.allowed(token, "sensors/other/x"));
        String ecToken = TokenAuthTest.token("ES256", "e1", ec.getPrivate(), TokenAuthTest.claims("api-ec", issuer, 300, "mqtt", List.of("devices")));
        assertTrue(TokenAuthTest.allowed(ecToken, "sensors/api-ec/x"));
    }

    @Test
    void aChangedRoleReachesThePluginAtOnce() throws Exception {
        given().contentType(ContentType.JSON).body(settings(true)).put("/api/token-auth").then().statusCode(200);
        String token = token("api-dev");
        await().atMost(Duration.ofSeconds(10)).until(() -> TokenAuthTest.allowed(token, "sensors/api-dev/blocked"));
        given().contentType(ContentType.JSON).body(Map.of("acltype", "publishClientSend", "topic", "sensors/%u/blocked", "priority", 5, "allow", false))
                .post("/api/roles/" + ROLE + "/acls").then().statusCode(204);
        await().atMost(Duration.ofSeconds(10)).until(() -> !TokenAuthTest.allowed(token, "sensors/api-dev/blocked"));
        assertTrue(TokenAuthTest.allowed(token, "sensors/api-dev/open"));
    }

    @Test
    void turnedOffTokensDoNotGetIn() throws Exception {
        given().contentType(ContentType.JSON).body(settings(true)).put("/api/token-auth").then().statusCode(200);
        String token = token("api-dev");
        await().atMost(Duration.ofSeconds(10)).until(() -> TokenAuthTest.allowed(token, "sensors/api-dev/x"));
        given().contentType(ContentType.JSON).body(settings(false)).put("/api/token-auth").then().statusCode(200).body("enabled", equalTo(false));
        await().atMost(Duration.ofSeconds(10)).until(() -> TokenAuthTest.refused(token));
    }

    @Test
    void aProviderThatIsDownKeepsItsKeys() throws Exception {
        given().contentType(ContentType.JSON).body(settings(true)).put("/api/token-auth").then().statusCode(200).body("status[0].keys", equalTo(2));
        server.stop(0);
        given().post("/api/token-auth/refresh").then().statusCode(200)
                .body("status[0].keys", equalTo(2))
                .body("status[0].error", notNullValue());
        assertTrue(Files.readString(brokerFile()).contains("BEGIN PUBLIC KEY"), "the keys before stay in the file");
        String token = token("api-dev");
        assertTrue(TokenAuthTest.allowed(token, "sensors/api-dev/x"));
    }

    @Test
    void clientsWithATokenShowWhileConnected() throws Exception {
        given().contentType(ContentType.JSON).body(settings(true)).put("/api/token-auth").then().statusCode(200);
        String token = token("api-live");
        await().atMost(Duration.ofSeconds(10)).until(() -> TokenAuthTest.allowed(token, "sensors/api-live/x"));
        // dynamic security does not know it; the plugin tells on $SYS, which the UI reads
        // connected for a while: its role may publish, not subscribe
        MosquittoResource.broker.execInContainer("sh", "-c", "sleep 6 | mosquitto_pub -V 5 -i api-live-1 -u x -P \"$1\" -t sensors/api-live/x -l > /dev/null 2>&1 &", "sh", token);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().get("/api/token-auth/clients").then().statusCode(200)
                .body("username", hasItem("api-live"))
                .body("find { it.username == 'api-live' }.clientid", equalTo("api-live-1"))
                .body("find { it.username == 'api-live' }.roles", contains(ROLE))
                .body("find { it.username == 'api-live' }.issuer", equalTo(issuer))
                .body("find { it.username == 'api-live' }.jti", equalTo("jti-of-api-live")));
        // the list of those seen has it too, and it cannot be hidden while it is there
        given().get("/api/token-auth/seen").then().statusCode(200)
                .body("find { it.username == 'api-live' }.connected", equalTo(true))
                .body("find { it.username == 'api-live' }.lockedOut", nullValue());
        given().delete("/api/token-auth/seen/api-live").then().statusCode(409);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> given().get("/api/token-auth/clients").then().body("username", not(hasItem("api-live"))));
        // gone, it stays with when it was last there, until it is hidden
        given().get("/api/token-auth/seen").then().statusCode(200)
                .body("find { it.username == 'api-live' }.connected", equalTo(false))
                .body("find { it.username == 'api-live' }.clientid", equalTo("api-live-1"))
                .body("find { it.username == 'api-live' }.roles", contains(ROLE))
                .body("find { it.username == 'api-live' }.lastSeen", notNullValue());
        given().delete("/api/token-auth/seen/api-live").then().statusCode(200).body("username", not(hasItem("api-live")));
        given().delete("/api/token-auth/seen/api-live").then().statusCode(404);
    }

    @Test
    void aKeyOfTheProviderNotKnownYetIsFetchedAtOnce() throws Exception {
        given().contentType(ContentType.JSON).body(settings(true)).put("/api/token-auth").then().statusCode(200).body("status[0].kids", contains("r1", "e1"));
        // the provider rotates: a new key, long before the keys are due again (key-refresh)
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair rotated = generator.generateKeyPair();
        RSAPublicKey key = (RSAPublicKey) rotated.getPublic();
        jwks.add(Map.of("kid", "r2", "kty", "RSA", "use", "sig", "alg", "RS256", "n", number(key.getModulus()), "e", number(key.getPublicExponent())));
        String token = TokenAuthTest.token("RS256", "r2", rotated.getPrivate(), TokenAuthTest.claims("api-rotated", issuer, 300, "mqtt", List.of("devices")));

        // the first try is refused, and tells the UI which key it lacks
        assertTrue(TokenAuthTest.refused(token));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().get("/api/token-auth/rejected").then().statusCode(200)
                .body("find { it.username == 'api-rotated' }.reason", equalTo("unknown_key"))
                .body("find { it.username == 'api-rotated' }.kid", equalTo("r2"))
                .body("find { it.username == 'api-rotated' }.issuer", equalTo(issuer))
                .body("find { it.username == 'api-rotated' }.clientid", equalTo("token-pub"))
                .body("find { it.username == 'api-rotated' }.verified", equalTo(false))
                .body("find { it.username == 'api-rotated' }.jti", nullValue()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().get("/api/token-auth").then().body("status[0].kids", hasItem("r2")));
        await().atMost(Duration.ofSeconds(10)).until(() -> TokenAuthTest.allowed(token, "sensors/api-rotated/x"));
    }

    @Test
    void rejectedTokensShowWithTheirReason() throws Exception {
        given().contentType(ContentType.JSON).body(settings(true)).put("/api/token-auth").then().statusCode(200);
        String expired = TokenAuthTest.token("RS256", "r1", rsa.getPrivate(), TokenAuthTest.claims("api-late", issuer, -600, "mqtt", List.of("devices")));
        assertTrue(TokenAuthTest.refused(expired));
        assertTrue(TokenAuthTest.refused(expired));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().get("/api/token-auth/rejected").then().statusCode(200)
                .body("find { it.username == 'api-late' }.reason", equalTo("expired"))
                .body("find { it.username == 'api-late' }.verified", equalTo(true))
                .body("find { it.username == 'api-late' }.jti", equalTo("jti-of-api-late"))
                .body("find { it.username == 'api-late' }.count", equalTo(2)));
        // a provider nobody set up: its address shows, which helps to find a typo
        String stranger = TokenAuthTest.token("RS256", "r1", rsa.getPrivate(), TokenAuthTest.claims("api-stranger", "https://elsewhere.example.com", 300, "mqtt",
                List.of("devices")));
        assertTrue(TokenAuthTest.refused(stranger));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().get("/api/token-auth/rejected").then()
                .body("[0].reason", equalTo("unknown_issuer"))
                .body("[0].issuer", equalTo("https://elsewhere.example.com"))
                .body("[0].username", nullValue()));
    }

    @Test
    void theBackupOfAccessBringsTheTokenLoginBack() throws Exception {
        given().contentType(ContentType.JSON).body(settings(true)).put("/api/token-auth").then().statusCode(200);
        Archive archive = archives.read(archives.createZip(EnumSet.of(BackupSection.ACCESS), "test"), null);
        assertTrue(archive.entries().containsKey("access/token-auth.json"));
        given().contentType(ContentType.JSON).body(Map.of("enabled", false, "issuers", List.of())).put("/api/token-auth").then().statusCode(200)
                .body("issuers", empty());
        archives.restore(archive, EnumSet.of(BackupSection.ACCESS), true);
        given().get("/api/token-auth").then().statusCode(200)
                .body("enabled", equalTo(true))
                .body("issuers[0].issuer", equalTo(issuer))
                .body("issuers[0].mappings[0].group", equalTo("devices"));
        String token = token("api-restored");
        await().atMost(Duration.ofSeconds(10)).until(() -> TokenAuthTest.allowed(token, "sensors/api-restored/x"));
    }

    @Test
    void aBlockFromThePageKeepsATokenOut() throws Exception {
        given().contentType(ContentType.JSON).body(settings(true)).put("/api/token-auth").then().statusCode(200);
        String token = token("api-blocked");
        await().atMost(Duration.ofSeconds(10)).until(() -> TokenAuthTest.allowed(token, "sensors/api-blocked/x"));

        String id = given().contentType(ContentType.JSON).body(Map.of("field", "jti", "value", "jti-of-api-blocked", "issuer", issuer, "reason", "lost"))
                .post("/api/token-auth/blocks").then().statusCode(200)
                .body("blocks[0].field", equalTo("jti"))
                .body("blocks[0].createdBy", equalTo("alice"))
                .extract().path("blocks[0].id");
        assertTrue(Files.readString(brokerFile()).contains("jti-of-api-blocked"));
        await().atMost(Duration.ofSeconds(10)).until(() -> TokenAuthTest.refused(token));
        String fresh = TokenAuthTest.token("RS256", "r1", rsa.getPrivate(), Map.of("iss", issuer, "preferred_username", "api-blocked", "aud", "mqtt",
                "exp", java.time.Instant.now().getEpochSecond() + 300, "groups", List.of("devices"), "jti", "a-new-token"));
        assertTrue(TokenAuthTest.allowed(fresh, "sensors/api-blocked/x"), "a new token of the same client");

        given().delete("/api/token-auth/blocks/" + id).then().statusCode(200).body("blocks", empty());
        await().atMost(Duration.ofSeconds(10)).until(() -> TokenAuthTest.allowed(token, "sensors/api-blocked/x"));
        given().get("/api/audit").then().body("action", hasItem("addTokenBlock")).body("action", hasItem("removeTokenBlock"));
    }

    @Test
    void blocksThatDoNotHoldAreRefused() {
        given().contentType(ContentType.JSON).body(settings(true)).put("/api/token-auth").then().statusCode(200);
        given().contentType(ContentType.JSON).body(Map.of("field", "jti", "value", " ")).post("/api/token-auth/blocks").then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("field", "username", "value", "x", "until", "2020-01-01T00:00:00Z"))
                .post("/api/token-auth/blocks").then().statusCode(400).body("field", equalTo("until"));
        given().delete("/api/token-auth/blocks/no-such-block").then().statusCode(404);
    }

    @Test
    void aBoundIssuerReachesThePlugin() throws Exception {
        Map<String, Object> bound = new java.util.HashMap<>(settings(true));
        Map<String, Object> first = new java.util.HashMap<>(((List<Map<String, Object>>) bound.get("issuers")).get(0));
        first.put("bindClientId", true);
        bound.put("issuers", List.of(first));
        given().contentType(ContentType.JSON).body(bound).put("/api/token-auth").then().statusCode(200).body("issuers[0].bindClientId", equalTo(true));
        assertTrue(JSON.readTree(Files.readString(brokerFile())).path("issuers").get(0).path("bindClientId").asBoolean());
    }

    @Test
    void settingsThatDoNotHoldAreRefused() {
        given().contentType(ContentType.JSON).body(Map.of("enabled", true, "issuers", List.of(Map.of("issuer", "ftp://idp.example.com", "mappings", List.of()))))
                .put("/api/token-auth").then().statusCode(400).body("field", equalTo("issuer"));
        given().contentType(ContentType.JSON).body(Map.of("enabled", true, "issuers", List.of(Map.of("issuer", issuer, "usernameClaim", "a b", "mappings", List.of()))))
                .put("/api/token-auth").then().statusCode(400).body("field", equalTo("claim"));
        given().contentType(ContentType.JSON).body(Map.of("enabled", true, "issuers", List.of(Map.of("issuer", issuer,
                "mappings", List.of(Map.of("group", "devices", "roles", List.of())))))).put("/api/token-auth").then().statusCode(400).body("field", equalTo("group"));
    }
}
