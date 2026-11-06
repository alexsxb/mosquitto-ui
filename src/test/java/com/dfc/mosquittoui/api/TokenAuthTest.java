package com.dfc.mosquittoui.api;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

/**
 * Login with an access token, checked by the plugin docker/mosquitto/token-auth in front of dynamic security. The tests write
 * the file of the plugin themselves, as the UI would, sign their own tokens and log in with mosquitto_pub and mosquitto_sub in
 * the broker container.
 */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
class TokenAuthTest {

    private static final String ISSUER = "https://idp.example.com/realms/devices";
    private static final String OTHER_ISSUER = "https://login.example.org/tenant";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final KeyPair RSA = generate("RSA", null);
    private static final KeyPair EC = generate("EC", new ECGenParameterSpec("secp256r1"));
    private static final KeyPair UNKNOWN = generate("RSA", null);

    private static KeyPair generate(String algorithm, ECGenParameterSpec curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            if (curve != null) {
                generator.initialize(curve);
            } else {
                generator.initialize(2048);
            }
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path file() {
        return ConfigProvider.getConfig().getValue("mosquitto-ui.shared-dir", Path.class).resolve("token-auth/token-auth.json");
    }

    private static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded()) + "\n-----END PUBLIC KEY-----\n";
    }

    private static Map<String, Object> acl(String type, String topic, boolean allow) {
        return Map.of("acltype", type, "topic", topic, "allow", allow);
    }

    /** Devices with the group sensor-devices may write below sensors/<their name>, but not secret, and read all of sensors. */
    private static Map<String, Object> config(List<String> reserved, Map<String, List<String>> groups) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", true);
        config.put("leeway", 0);
        config.put("defaults", Map.of("publishClientSend", false, "publishClientReceive", true, "subscribe", false, "unsubscribe", true));
        config.put("reservedUsernames", reserved);
        config.put("roles", List.of(Map.of("rolename", "sensors", "allowWildcardSubs", true, "acls", List.of(
                acl("publishClientSend", "sensors/%u/secret", false),
                acl("publishClientSend", "sensors/%u/#", true),
                acl("subscribePattern", "sensors/#", true)))));
        // changeable, for the tests that bind client ids or block
        config.put("issuers", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("issuer", ISSUER, "audience", "mqtt", "usernameClaim", "preferred_username", "groupsClaim", "realm_access.roles",
                        "keys", List.of(Map.of("kid", "rsa", "pem", pem(RSA.getPublic())), Map.of("kid", "ec", "pem", pem(EC.getPublic()))),
                        "groups", groups)),
                new LinkedHashMap<>(Map.of("issuer", OTHER_ISSUER, "usernameClaim", "sub", "groupsClaim", "groups", "usernamePrefix", "other:",
                        "keys", List.of(Map.of("pem", pem(RSA.getPublic()))), "groups", Map.of("sensor-devices", List.of("sensors")))))));
        return config;
    }

    private static long loads() {
        return MosquittoResource.broker.getLogs().lines().filter(line -> line.contains("token-auth.json with")).count();
    }

    /** As the UI does it: a new file in place of the old one, which the plugin reads within a second. */
    private static void write(Map<String, Object> config) throws Exception {
        long before = loads();
        Path file = file();
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling("token-auth.json.tmp");
        Files.writeString(temporary, JSON.writeValueAsString(config));
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        await().atMost(Duration.ofSeconds(10)).until(() -> loads() > before);
    }

    @BeforeEach
    void standard() throws Exception {
        write(config(List.of("admin"), Map.of("sensor-devices", List.of("sensors"))));
    }

    @AfterEach
    void remove() throws Exception {
        Files.deleteIfExists(file());
    }

    static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String token(String alg, String kid, PrivateKey key, Map<String, Object> claims) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", alg);
        header.put("typ", "JWT");
        if (kid != null) {
            header.put("kid", kid);
        }
        String signed = base64(JSON.writeValueAsBytes(header)) + "." + base64(JSON.writeValueAsBytes(claims));
        Signature signature = switch (alg) {
            case "RS256" -> Signature.getInstance("SHA256withRSA");
            case "ES256" -> Signature.getInstance("SHA256withECDSAinP1363Format");
            case "PS256" -> {
                Signature pss = Signature.getInstance("RSASSA-PSS");
                pss.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
                yield pss;
            }
            default -> throw new IllegalArgumentException(alg);
        };
        signature.initSign(key);
        signature.update(signed.getBytes(StandardCharsets.US_ASCII));
        return signed + "." + base64(signature.sign());
    }

    static Map<String, Object> claims(String name, String issuer, long validSeconds, String audience, List<String> groups) {
        Map<String, Object> claims = new LinkedHashMap<>();
        long now = Instant.now().getEpochSecond();
        claims.put("iss", issuer);
        claims.put("sub", "id-of-" + name);
        claims.put("preferred_username", name);
        claims.put("aud", List.of("account", audience));
        claims.put("iat", now);
        claims.put("exp", now + validSeconds);
        claims.put("realm_access", Map.of("roles", groups));
        claims.put("groups", groups);
        claims.put("jti", "jti-of-" + name);
        return claims;
    }

    private static Map<String, Object> device(String name) {
        return claims(name, ISSUER, 300, "mqtt", List.of("sensor-devices", "offline_access"));
    }

    private static ExecResult publish(String user, String password, String topic) throws Exception {
        return publishAs("token-pub", user, password, topic);
    }

    private static ExecResult publishAs(String clientId, String user, String password, String topic) throws Exception {
        return MosquittoResource.broker.execInContainer("mosquitto_pub", "-V", "5", "-i", clientId, "-u", user, "-P", password, "-t", topic, "-m", "x", "-q", "1");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstIssuer(Map<String, Object> config) {
        return (Map<String, Object>) ((List<Object>) config.get("issuers")).get(0);
    }

    private static Map<String, Object> standardConfig() {
        return config(List.of("admin"), Map.of("sensor-devices", List.of("sensors")));
    }

    private static String output(ExecResult result) {
        return result.getStdout() + result.getStderr();
    }

    /** Logged in, and the broker took the message */
    static boolean allowed(String password, String topic) throws Exception {
        ExecResult result = publish("anyone", password, topic);
        return result.getExitCode() == 0 && !output(result).contains("failed");
    }

    /** Not even logged in */
    static boolean refused(String password) throws Exception {
        ExecResult result = publish("anyone", password, "sensors/x/y");
        return result.getExitCode() != 0 && output(result).toLowerCase().contains("not authori");
    }

    @Test
    void tokensOfTheKeysInTheFileLogIn() throws Exception {
        assertTrue(allowed(token("RS256", "rsa", RSA.getPrivate(), device("dev1")), "sensors/dev1/temperature"), "RS256");
        assertTrue(allowed(token("ES256", "ec", EC.getPrivate(), device("dev1")), "sensors/dev1/temperature"), "ES256");
        assertTrue(allowed(token("PS256", "rsa", RSA.getPrivate(), device("dev1")), "sensors/dev1/temperature"), "PS256");
        // a key without kid in the file is tried for any token of its issuer; the prefix keeps the name apart
        Map<String, Object> other = claims("dev2", OTHER_ISSUER, 300, "anything", List.of("sensor-devices"));
        assertTrue(allowed(token("RS256", null, RSA.getPrivate(), other), "sensors/other:id-of-dev2/temperature"), "second issuer: its claim sub, with prefix");
    }

    @Test
    void theRolesDecideAsInDynamicSecurity() throws Exception {
        String token = token("RS256", "rsa", RSA.getPrivate(), device("dev1"));
        assertTrue(allowed(token, "sensors/dev1/a/b"));
        assertFalse(allowed(token, "sensors/dev1/secret"), "the ACL with the higher priority denies");
        assertFalse(allowed(token, "sensors/dev2/a"), "%u is the user name from the token");
        assertFalse(allowed(token, "elsewhere"), "default: no publishing");
        assertFalse(allowed(token, "$CONTROL/dynamic-security/v1"));
    }

    @Test
    void aClientWithATokenReceivesWhatItMaySubscribeTo() throws Exception {
        String token = token("ES256", "ec", EC.getPrivate(), device("reader"));
        String sender = token("RS256", "rsa", RSA.getPrivate(), device("dev9"));
        ExecResult result = MosquittoResource.broker.execInContainer("sh", "-c",
                "mosquitto_sub -V 5 -i token-sub -u x -P \"$1\" -t 'sensors/#' -C 1 -W 8 & sleep 1; "
                        + "mosquitto_pub -i token-sender -u x -P \"$2\" -t sensors/dev9/temperature -m 21.5; wait",
                "sh", token, sender);
        assertTrue(result.getStdout().contains("21.5"), output(result));
        ExecResult denied = MosquittoResource.broker.execInContainer("mosquitto_sub", "-V", "5", "-u", "x", "-P", token, "-t", "#", "-C", "1", "-W", "3");
        assertTrue(output(denied).toLowerCase().contains("denied"), output(denied));
    }

    @Test
    void tokensThatDoNotHoldAreRefused() throws Exception {
        assertTrue(refused(token("RS256", "rsa", RSA.getPrivate(), claims("dev1", ISSUER, -60, "mqtt", List.of("sensor-devices")))), "expired");
        assertTrue(refused(token("RS256", "rsa", RSA.getPrivate(), claims("dev1", "https://evil.example.com", 300, "mqtt", List.of("sensor-devices")))), "issuer");
        assertTrue(refused(token("RS256", "rsa", UNKNOWN.getPrivate(), device("dev1"))), "key not in the file");
        assertTrue(refused(token("RS256", "rsa", RSA.getPrivate(), claims("dev1", ISSUER, 300, "other-app", List.of("sensor-devices")))), "audience");
        assertTrue(refused(token("RS256", "rsa", RSA.getPrivate(), claims("dev1", ISSUER, 300, "mqtt", List.of("unknown-group")))), "no role");
        assertTrue(refused(token("RS256", "rsa", RSA.getPrivate(), device("admin"))), "the name of a dynamic security client");

        String claims = base64(JSON.writeValueAsBytes(device("dev1")));
        assertTrue(refused(base64("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes()) + "." + claims + "."), "alg none");
        // HS256 with the public key as the secret: the classic confusion of algorithms
        String signed = base64("{\"alg\":\"HS256\",\"kid\":\"rsa\"}".getBytes()) + "." + claims;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pem(RSA.getPublic()).getBytes(), "HmacSHA256"));
        assertTrue(refused(signed + "." + base64(mac.doFinal(signed.getBytes()))), "HS256");
        // an EC token claiming RS256 with the kid of the EC key
        String ec = token("ES256", "ec", EC.getPrivate(), device("dev1"));
        assertTrue(refused(base64("{\"alg\":\"RS256\",\"kid\":\"ec\"}".getBytes()) + ec.substring(ec.indexOf('.'))), "key of another type");
    }

    @Test
    void aTokenNeverGetsTheRightsOfADynamicSecurityClient() throws Exception {
        // without the list of reserved names: the plugin decides the ACLs of the token client, not dynamic security for "admin"
        write(config(List.of(), Map.of("sensor-devices", List.of("sensors"))));
        String token = token("RS256", "rsa", RSA.getPrivate(), device("admin"));
        assertTrue(allowed(token, "sensors/admin/x"), "logged in as admin with the role of the token");
        assertFalse(allowed(token, "anything/admin/may"), "not with the rights of the dynamic security client admin");
        assertFalse(allowed(token, "$CONTROL/dynamic-security/v1"));
    }

    @Test
    void dynamicSecurityClientsAreLeftAlone() throws Exception {
        // dynamic security lets admin in, and decides what it may: here only $CONTROL
        ExecResult result = publish("admin", MosquittoResource.PASSWORD, "anything/at/all");
        assertEquals(0, result.getExitCode(), output(result));
        assertFalse(output(result).toLowerCase().contains("not authorised"), output(result));
        ExecResult wrong = publish("admin", "wrong password", "anything");
        assertTrue(output(wrong).toLowerCase().contains("not authori"), output(wrong));
    }

    @Test
    void anExpiredTokenEndsTheConnection() throws Exception {
        String token = token("RS256", "rsa", RSA.getPrivate(), claims("short", ISSUER, 3, "mqtt", List.of("sensor-devices")));
        ExecResult result = MosquittoResource.broker.execInContainer("sh", "-c",
                "start=$(date +%s); mosquitto_sub -V 5 -i token-short -u x -P \"$1\" -t 'sensors/#' -W 20; echo \"seconds=$(( $(date +%s) - start ))\"",
                "sh", token);
        int seconds = Integer.parseInt(result.getStdout().replaceAll("(?s).*seconds=(\\d+).*", "$1"));
        assertTrue(seconds < 15, "ended by the broker after about 3 seconds, not by -W: " + output(result));
        assertTrue(MosquittoResource.broker.getLogs().contains("client token-short disconnected, its token expired"));
    }

    @Test
    void aChangedFileTakesRightsAwayFromConnectedClients() throws Exception {
        String token = token("RS256", "rsa", RSA.getPrivate(), device("changing"));
        // connected, then the group loses its role
        MosquittoResource.broker.execInContainer("sh", "-c",
                "mosquitto_sub -V 5 -i token-changing -u x -P \"$1\" -t 'sensors/#' -W 20 > /dev/null 2>&1 &", "sh", token);
        await().atMost(Duration.ofSeconds(10)).until(() -> MosquittoResource.broker.getLogs().contains("client token-changing logged in as changing"));
        write(config(List.of("admin"), Map.of()));
        await().atMost(Duration.ofSeconds(10)).until(() -> MosquittoResource.broker.getLogs().contains("client token-changing disconnected"));
        assertTrue(refused(token), "and cannot log in again");
    }

    @Test
    void withoutTheFileTokensAreNoPasswords() throws Exception {
        String token = token("RS256", "rsa", RSA.getPrivate(), device("dev1"));
        assertTrue(allowed(token, "sensors/dev1/x"));
        Files.delete(file());
        await().atMost(Duration.ofSeconds(10)).until(() -> MosquittoResource.broker.getLogs().contains("is gone or no file, token login off"));
        assertTrue(refused(token), "dynamic security knows no such user");
    }

    @Test
    void aBoundClientIdBelongsToItsUser() throws Exception {
        Map<String, Object> config = standardConfig();
        firstIssuer(config).put("bindClientId", true);
        write(config);
        String token = token("RS256", "rsa", RSA.getPrivate(), device("dev1"));
        assertTrue(publishAs("dev1", "x", token, "sensors/dev1/a").getExitCode() == 0, "the user name itself");
        assertTrue(publishAs("dev1/kitchen", "x", token, "sensors/dev1/a").getExitCode() == 0, "below the user name");
        assertTrue(output(publishAs("dev1x", "x", token, "sensors/dev1/a")).toLowerCase().contains("not authori"), "only starting like it");
        assertTrue(output(publishAs("dev2", "x", token, "sensors/dev1/a")).toLowerCase().contains("not authori"), "the client id of another");
        // the other issuer does not bind
        String other = token("RS256", null, RSA.getPrivate(), claims("dev2", OTHER_ISSUER, 300, "x", List.of("sensor-devices")));
        assertTrue(publishAs("anything", "x", other, "sensors/other:id-of-dev2/a").getExitCode() == 0);
    }

    @Test
    void blockedTokensDoNotGetIn() throws Exception {
        long past = Instant.now().getEpochSecond() - 10;
        long future = Instant.now().getEpochSecond() + 3600;
        Map<String, Object> config = standardConfig();
        config.put("blocked", List.of(
                Map.of("field", "username", "value", "blocked-user"),
                Map.of("field", "sub", "value", "id-of-blocked-sub", "until", future),
                Map.of("field", "jti", "value", "stolen-token"),
                Map.of("field", "username", "value", "dev-elsewhere", "issuer", OTHER_ISSUER),
                Map.of("field", "username", "value", "was-blocked", "until", past)));
        write(config);
        assertTrue(refused(token("RS256", "rsa", RSA.getPrivate(), device("blocked-user"))), "user name");
        assertTrue(refused(token("RS256", "rsa", RSA.getPrivate(), device("blocked-sub"))), "sub");
        Map<String, Object> stolen = device("dev1");
        stolen.put("jti", "stolen-token");
        assertTrue(refused(token("RS256", "rsa", RSA.getPrivate(), stolen)), "this one token");
        assertTrue(allowed(token("RS256", "rsa", RSA.getPrivate(), device("dev1")), "sensors/dev1/x"), "another token of the same user");
        assertTrue(allowed(token("RS256", "rsa", RSA.getPrivate(), device("dev-elsewhere")), "sensors/dev-elsewhere/x"), "blocked only at the other issuer");
        assertTrue(allowed(token("RS256", "rsa", RSA.getPrivate(), device("was-blocked")), "sensors/was-blocked/x"), "a block that is over");
    }

    @Test
    void aBlockDisconnectsAConnectedClient() throws Exception {
        String token = token("RS256", "rsa", RSA.getPrivate(), device("to-block"));
        MosquittoResource.broker.execInContainer("sh", "-c",
                "mosquitto_sub -V 5 -i token-to-block -u x -P \"$1\" -t 'sensors/#' -W 20 > /dev/null 2>&1 &", "sh", token);
        await().atMost(Duration.ofSeconds(10)).until(() -> MosquittoResource.broker.getLogs().contains("client token-to-block logged in as to-block"));
        Map<String, Object> config = standardConfig();
        config.put("blocked", List.of(Map.of("field", "jti", "value", "jti-of-to-block")));
        write(config);
        await().atMost(Duration.ofSeconds(10)).until(() -> MosquittoResource.broker.getLogs().contains("client token-to-block disconnected"));
        assertTrue(refused(token));
    }
}
