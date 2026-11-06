package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issuers without a discovery document: keys pasted on the page (PEM, certificate, JWKS) or a JWKS at its own address. The
 * tokens are signed here and logged in at the real broker.
 */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class StaticIssuerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROLE = "static-issuer-fleet";
    private static final String ISSUER = "fleet-signer";

    @BeforeEach
    void role() {
        given().contentType(ContentType.JSON).body(Map.of("rolename", ROLE, "allowwildcardsubs", true)).post("/api/roles").then().statusCode(204);
        given().contentType(ContentType.JSON).body(Map.of("acltype", "publishClientSend", "topic", "fleet/%u/#", "priority", 0, "allow", true))
                .post("/api/roles/" + ROLE + "/acls").then().statusCode(204);
    }

    @AfterEach
    void cleanUp() throws Exception {
        given().delete("/api/roles/" + ROLE).then().statusCode(204);
        Files.deleteIfExists(ConfigProvider.getConfig().getValue("mosquitto-ui.state-dir", Path.class).resolve("token-auth.json"));
        Files.deleteIfExists(ConfigProvider.getConfig().getValue("mosquitto-ui.shared-dir", Path.class).resolve("token-auth/token-auth.json"));
    }

    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded()) + "\n-----END PUBLIC KEY-----\n";
    }

    private static String number(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String jwks(String kid, RSAPublicKey key) throws Exception {
        return JSON.writeValueAsString(Map.of("keys", List.of(Map.of("kid", kid, "kty", "RSA", "use", "sig", "n", number(key.getModulus()), "e",
                number(key.getPublicExponent())))));
    }

    private static Map<String, Object> issuer(String source, String field, String value) {
        Map<String, Object> issuer = new HashMap<>(Map.of("issuer", ISSUER, "audience", "fleet", "usernameClaim", "preferred_username", "groupsClaim",
                "groups", "bindClientId", true, "keySource", source, "mappings", List.of(Map.of("group", "trucks", "roles", List.of(ROLE)))));
        if (field != null) {
            issuer.put(field, value);
        }
        return issuer;
    }

    private static io.restassured.response.ValidatableResponse put(Map<String, Object> issuer) {
        return given().contentType(ContentType.JSON).body(Map.of("enabled", true, "issuers", List.of(issuer))).put("/api/token-auth").then();
    }

    private static String token(String alg, String kid, PrivateKey key, String name) throws Exception {
        return TokenAuthTest.token(alg, kid, key, TokenAuthTest.claims(name, ISSUER, 300, "fleet", List.of("trucks")));
    }

    private static boolean allowed(String name, String token) throws Exception {
        var result = MosquittoResource.broker.execInContainer("mosquitto_pub", "-V", "5", "-i", name, "-u", "x", "-P", token, "-t", "fleet/" + name + "/gps",
                "-m", "1", "-q", "1");
        return result.getExitCode() == 0 && !(result.getStdout() + result.getStderr()).contains("failed");
    }

    @Test
    void aPastedPublicKey() throws Exception {
        KeyPair key = rsa(2048);
        put(issuer("keys", "staticKeys", pem(key.getPublic()))).statusCode(200)
                .body("status[0].keys", equalTo(1)).body("status[0].error", nullValue());
        String token = token("RS256", null, key.getPrivate(), "truck-1");
        await().atMost(Duration.ofSeconds(10)).until(() -> allowed("truck-1", token));
    }

    @Test
    void aPastedCertificate() throws Exception {
        Path dir = Files.createTempDirectory("static-issuer");
        Path store = dir.resolve("signer.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        run(keytool, "-genkeypair", "-keyalg", "EC", "-groupname", "secp256r1", "-alias", "signer", "-dname", "CN=fleet-signer", "-validity", "2",
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "changeit");
        String certificate = run(keytool, "-exportcert", "-rfc", "-alias", "signer", "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass",
                "changeit");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(store.toFile())) {
            keys.load(in, "changeit".toCharArray());
        }
        PrivateKey signer = (PrivateKey) keys.getKey("signer", "changeit".toCharArray());

        put(issuer("keys", "staticKeys", certificate)).statusCode(200).body("status[0].keys", equalTo(1));
        String token = token("ES256", null, signer, "truck-2");
        await().atMost(Duration.ofSeconds(10)).until(() -> allowed("truck-2", token));
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }

    @Test
    void aPastedJwksAndReplacingIt() throws Exception {
        KeyPair first = rsa(2048);
        put(issuer("keys", "staticKeys", jwks("k1", (RSAPublicKey) first.getPublic()))).statusCode(200).body("status[0].kids[0]", equalTo("k1"));
        String old = token("RS256", "k1", first.getPrivate(), "truck-3");
        await().atMost(Duration.ofSeconds(10)).until(() -> allowed("truck-3", old));

        // a new key in place of the old one counts at once, not after the next refresh
        KeyPair second = rsa(2048);
        put(issuer("keys", "staticKeys", jwks("k2", (RSAPublicKey) second.getPublic()))).statusCode(200).body("status[0].kids[0]", equalTo("k2"));
        String fresh = token("RS256", "k2", second.getPrivate(), "truck-3");
        await().atMost(Duration.ofSeconds(10)).until(() -> allowed("truck-3", fresh));
        assertTrue(TokenAuthTest.refused(old), "signed with the key that is gone");
    }

    @Test
    void aJwksAtItsOwnAddress() throws Exception {
        KeyPair key = rsa(2048);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = jwks("j1", (RSAPublicKey) key.getPublic()).getBytes(StandardCharsets.UTF_8);
        server.createContext("/keys.json", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            put(issuer("jwks", "jwksUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/keys.json")).statusCode(200)
                    .body("status[0].kids[0]", equalTo("j1"));
            String token = token("RS256", "j1", key.getPrivate(), "truck-4");
            await().atMost(Duration.ofSeconds(10)).until(() -> allowed("truck-4", token));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keysThatDoNotHoldAreRefused() throws Exception {
        put(issuer("keys", "staticKeys", "not a key")).statusCode(400).body("field", equalTo("staticKeys"));
        put(issuer("keys", "staticKeys", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----")).statusCode(400)
                .body("message", containsString("Block 1 is no public RSA or EC key"));
        put(issuer("keys", "staticKeys", pem(rsa(1024).getPublic()))).statusCode(400).body("message", containsString("2048"));
        put(issuer("keys", "staticKeys", "{\"keys\": [{\"kty\": \"oct\", \"k\": \"c2VjcmV0\"}]}")).statusCode(400).body("field", equalTo("staticKeys"));
        put(issuer("keys", null, null)).statusCode(400).body("field", equalTo("staticKeys"));
        put(issuer("jwks", "jwksUrl", "ftp://keys.example.com")).statusCode(400).body("field", equalTo("jwksUrl"));
        Map<String, Object> spaced = issuer("keys", "staticKeys", pem(rsa(2048).getPublic()));
        spaced.put("issuer", "fleet signer");
        put(spaced).statusCode(400).body("field", equalTo("issuer"));
    }
}
