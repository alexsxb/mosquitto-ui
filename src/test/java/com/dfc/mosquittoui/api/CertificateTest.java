package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.notify.MailService;
import com.dfc.mosquittoui.notify.Notice;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.math.BigInteger;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Real certificates from Pebble, served by the real broker. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@QuarkusTestResource(PebbleResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
@TestMethodOrder(OrderAnnotation.class)
class CertificateTest {

    private static final String MAIN = "main";

    @TestHTTPResource("/fake-dns")
    URL fakeDns;

    private String directory() {
        return PebbleResource.directory(PebbleResource.pebble);
    }

    private Map<String, Object> settings(String provider, String url, List<String> domains, String keyType) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("provider", provider);
        settings.put("url", url);
        settings.put("token", FakeDnsResource.KEY);
        settings.put("domains", domains);
        settings.put("directory", directory());
        settings.put("keyType", keyType);
        settings.put("propagationSeconds", 0);
        settings.put("autoRenew", true);
        settings.put("acceptTerms", true);
        return settings;
    }

    private static String id(String name) {
        return given().get("/api/certificate").jsonPath().getString("sets.find { it.name == '" + name + "' }.id");
    }

    private static Map<String, Object> form(String name, Map<String, Object> settings) {
        return Map.of("name", name, "settings", settings);
    }

    /** The set most tests work with. */
    private static void save(Map<String, Object> settings) {
        save(MAIN, settings);
    }

    private static void save(String name, Map<String, Object> settings) {
        String id = id(name);
        if (id == null) {
            given().contentType(ContentType.JSON).body(form(name, settings)).post("/api/certificate/sets").then().statusCode(200);
        } else {
            given().contentType(ContentType.JSON).body(form(name, settings)).put("/api/certificate/sets/" + id).then().statusCode(204);
        }
    }

    private static void startIssue(String name) {
        given().post("/api/certificate/sets/" + id(name) + "/issue").then().statusCode(204);
        await().atMost(Duration.ofSeconds(60)).until(() -> !given().get("/api/certificate").jsonPath().getBoolean("job.running"));
    }

    private static void issue() {
        issue(MAIN);
    }

    private static void issue(String name) {
        startIssue(name);
        given().get("/api/certificate").then().body("job.error", nullValue());
    }

    private static void activate(String name) {
        given().post("/api/certificate/sets/" + id(name) + "/activate").then().log().ifValidationFails().statusCode(204);
    }

    /** Serial of the certificate of a set, as the broker has to present it. */
    private static BigInteger serial(String name) {
        return new BigInteger(given().get("/api/certificate").jsonPath().getString("sets.find { it.name == '" + name + "' }.certificate.serial"), 16);
    }

    private static boolean active(String name) {
        return given().get("/api/certificate").jsonPath().getBoolean("sets.find { it.name == '" + name + "' }.active");
    }

    /** The certificate that the broker presents right now. */
    private static X509Certificate served() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(MosquittoResource.broker.getHost(), MosquittoResource.broker.getMappedPort(8883))) {
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    private static String brokerPid() throws Exception {
        return MosquittoResource.broker.execInContainer("pidof", "mosquitto").getStdout().strip();
    }

    @Test
    @Order(1)
    void nothingBeforeTheSettingsAreComplete() {
        await().until(() -> given().get("/api/certificate").jsonPath().getBoolean("available"));
        given().post("/api/certificate/sets/nothing/issue").then().statusCode(404);
        Map<String, Object> settings = settings("ACME_DNS", fakeDns + "/acme-dns", List.of("mqtt.example.test"), "EC");
        settings.remove("token");
        save(settings);
        given().post("/api/certificate/sets/" + id(MAIN) + "/issue").then().statusCode(409).body("code", equalTo("not_configured"));
        settings.put("token", FakeDnsResource.KEY);
        settings.put("acceptTerms", false);
        save(settings);
        given().post("/api/certificate/sets/" + id(MAIN) + "/issue").then().statusCode(409).body("code", equalTo("terms_required"));
    }

    @Test
    @Order(2)
    void issueWithAcmeDnsAndServeIt() throws Exception {
        save(settings("ACME_DNS", fakeDns + "/acme-dns", List.of("mqtt.example.test"), "EC"));
        issue();
        given().get("/api/certificate").then()
                .body("certificate.domains", hasItems("mqtt.example.test"))
                .body("certificate.notAfter", notNullValue())
                .body("certificate.renewalDue", equalTo(false))
                .body("certificate.renewalFrom", notNullValue())
                .body("sets[0].active", equalTo(true))
                .body("sets[0].settings.token", nullValue())
                .body("sets[0].tokenSet", equalTo(true));

        String certfile = given().get("/api/certificate").jsonPath().getString("certfile");
        String keyfile = given().get("/api/certificate").jsonPath().getString("keyfile");
        String text = "listener 8883\ncertfile " + certfile + "\nkeyfile " + keyfile + "\n";
        given().contentType(ContentType.JSON).body(Map.of("text", text)).put("/api/config").then().log().ifValidationFails().statusCode(200);

        X509Certificate served = served();
        assertEquals(new BigInteger(given().get("/api/certificate").jsonPath().getString("certificate.serial"), 16), served.getSerialNumber());
    }

    @Test
    @Order(3)
    void keyStaysOutOfReachOfTheUi() throws Exception {
        String listing = MosquittoResource.broker.execInContainer("ls", "-ln", "/mosquitto/data/certs").getStdout();
        assertTrue(listing.lines().filter(line -> line.contains("privkey.pem")).allMatch(line -> line.startsWith("-rw------- ") && line.contains(" 1883 ")), listing);
        String stored = MosquittoResource.broker.execInContainer("ls", "-ln", "/mosquitto/data/certs/sets/" + id(MAIN)).getStdout();
        assertTrue(stored.lines().filter(line -> line.contains("privkey.pem")).allMatch(line -> line.startsWith("-rw------- ") && line.contains(" 1883 ")) && stored.contains("privkey.pem"), stored);
        String control = MosquittoResource.broker.execInContainer("ls", "/mosquitto/ui/control").getStdout();
        assertFalse(control.contains("privkey"), control);
    }

    @Test
    @Order(4)
    void renewalReachesClientsWithoutARestart() throws Exception {
        String pid = brokerPid();
        BigInteger before = served().getSerialNumber();

        save(settings("PDNS", fakeDns + "/pdns", List.of("example.test", "*.example.test"), "EC"));
        issue();

        await().atMost(Duration.ofSeconds(10)).until(() -> !served().getSerialNumber().equals(before));
        assertEquals(pid, brokerPid(), "same process");
        given().get("/api/certificate").then()
                .body("certificate.domains", hasItems("example.test", "*.example.test"));
    }

    @Test
    @Order(5)
    void anotherKeyTypeNeedsARestart() throws Exception {
        String pid = brokerPid();
        save(settings("PDNS", fakeDns + "/pdns", List.of("example.test"), "RSA"));
        issue();
        await().atMost(Duration.ofSeconds(10)).ignoreExceptions().until(() -> served().getPublicKey().getAlgorithm().equals("RSA"));
        assertNotEquals(pid, brokerPid());
        given().get("/api/broker/status").then().body("connected", equalTo(true));
    }

    @Test
    @Order(6)
    void tokenStaysWhenNotSentAgain() {
        Map<String, Object> settings = settings("PDNS", fakeDns + "/pdns", List.of("example.test"), "EC");
        settings.remove("token");
        save(settings);
        given().get("/api/certificate").then().body("sets[0].tokenSet", equalTo(true));
        issue();
    }

    @Test
    @Order(7)
    void failureIsReported() {
        save(settings("PDNS", fakeDns + "/pdns", List.of("mqtt.other.test"), "EC"));
        startIssue(MAIN);
        given().get("/api/certificate").then().body("job.error", containsString("No zone found for mqtt.other.test")).body("job.setId", equalTo(id(MAIN)));
        assertNotEquals("", given().get("/api/certificate").jsonPath().getString("certificate.serial"));
    }

    private static void putConfig(String text) {
        given().contentType(ContentType.JSON).body(Map.of("text", text)).put("/api/config").then().log().ifValidationFails().statusCode(200);
    }

    @Test
    @Order(30)
    void theCertificateBringsItsListener() throws Exception {
        String before = given().get("/api/config").jsonPath().getString("text");
        putConfig("");
        given().get("/api/certificate").then().body("tlsPort", nullValue());

        Map<String, Object> settings = settings("PDNS", fakeDns + "/pdns", List.of("example.test"), "EC");
        settings.put("tlsListener", true);
        settings.put("tlsPort", 8883);
        save(settings);
        issue();
        given().get("/api/certificate").then().body("tlsPort", equalTo(8883));
        await().atMost(Duration.ofSeconds(10)).ignoreExceptions().until(() -> served().getSubjectX500Principal() != null);
        assertEquals(new BigInteger(given().get("/api/certificate").jsonPath().getString("certificate.serial"), 16), served().getSerialNumber());

        // a renewal leaves the listener and the clients alone
        String pid = brokerPid();
        issue();
        assertEquals(pid, brokerPid(), "same process");

        // the certificate stays when the listener fails: this port is taken by mosquitto.conf, which only a start finds out
        putConfig("");
        settings.put("tlsPort", 1883);
        save(settings);
        startIssue(MAIN);
        given().get("/api/certificate").then()
                .body("job.error", containsString("TLS listener on port 1883"))
                .body("certificate", notNullValue())
                .body("tlsPort", nullValue());
        await().atMost(Duration.ofSeconds(20)).until(() -> given().get("/api/broker/status").jsonPath().getBoolean("connected"));

        settings.put("tlsListener", false);
        save(settings);
        putConfig(before);
    }

    @Test
    @Order(31)
    void aCaThatWantsAnAccount() throws Exception {
        String config = PebbleResource.pebbleEab.copyFileFromContainer("test/config/pebble-config-external-account-bindings.json", in -> new String(in.readAllBytes()));
        var keys = new com.fasterxml.jackson.databind.ObjectMapper().readTree(config).path("pebble").path("externalAccountMACKeys").properties().iterator().next();
        Map<String, Object> settings = settings("PDNS", fakeDns + "/pdns", List.of("example.test"), "EC");
        settings.put("directory", PebbleResource.directory(PebbleResource.pebbleEab));

        save(settings);
        startIssue(MAIN);
        given().get("/api/certificate").then().body("job.error", notNullValue());

        settings.put("eabKeyId", keys.getKey());
        settings.put("eabHmacKey", keys.getValue().asText());
        save(settings);
        given().get("/api/certificate").then()
                .body("sets[0].eabKeySet", equalTo(true))
                .body("sets[0].settings.eabKeyId", equalTo(keys.getKey()))
                .body("sets[0].settings.eabHmacKey", nullValue());
        settings.remove("eabHmacKey");
        save(settings);
        issue();
        given().get("/api/certificate").then().body("sets[0].eabKeySet", equalTo(true));
    }

    @Test
    @Order(32)
    void aSecondSetWaitsUntilItIsActivated() throws Exception {
        putConfig("");
        Map<String, Object> first = settings("PDNS", fakeDns + "/pdns", List.of("example.test"), "EC");
        first.put("tlsListener", true);
        first.put("tlsPort", 8883);
        save(first);
        issue();

        given().contentType(ContentType.JSON).body(form(" MAIN ", first)).post("/api/certificate/sets").then().statusCode(409).body("code", equalTo("set_exists"));
        Map<String, Object> second = settings("PDNS", fakeDns + "/pdns", List.of("other.example.test"), "EC");
        second.put("tlsListener", true);
        second.put("tlsPort", 8883);
        save("second", second);
        given().post("/api/certificate/sets/" + id("second") + "/activate").then().statusCode(409).body("code", equalTo("no_certificate"));

        // ordering it leaves the broker and the clients alone
        String pid = brokerPid();
        issue("second");
        assertEquals(serial(MAIN), served().getSerialNumber());
        assertFalse(active("second"));
        given().get("/api/certificate").then().body("certificate.serial", equalTo(serial(MAIN).toString(16)));

        // same key type and port: new connections get it without a restart
        activate("second");
        await().atMost(Duration.ofSeconds(10)).until(() -> served().getSerialNumber().equals(serial("second")));
        assertEquals(pid, brokerPid(), "same process");
        assertTrue(active("second"));
        assertFalse(active(MAIN));

        given().delete("/api/certificate/sets/" + id("second")).then().statusCode(409).body("code", equalTo("set_active"));

        activate(MAIN);
        await().atMost(Duration.ofSeconds(10)).until(() -> served().getSerialNumber().equals(serial(MAIN)));

        String secondId = id("second");
        given().delete("/api/certificate/sets/" + secondId).then().statusCode(204);
        assertFalse(MosquittoResource.broker.execInContainer("ls", "/mosquitto/data/certs/sets").getStdout().contains(secondId));
        assertEquals(serial(MAIN), served().getSerialNumber());
    }

    @Test
    @Order(33)
    void activatingAnotherKeyTypeOrPortRestartsTheBroker() throws Exception {
        Map<String, Object> rsa = settings("PDNS", fakeDns + "/pdns", List.of("example.test"), "RSA");
        rsa.put("tlsListener", true);
        rsa.put("tlsPort", 8884);
        save("rsa", rsa);
        issue("rsa");
        String pid = brokerPid();

        activate("rsa");
        assertNotEquals(pid, brokerPid());
        given().get("/api/broker/status").then().body("connected", equalTo(true));
        given().get("/api/certificate").then().body("tlsPort", equalTo(8884)).body("certificate.keyType", equalTo("RSA"));
        // one listener moved, not a second one added
        String text = given().get("/api/config").jsonPath().getString("text");
        assertTrue(text.contains("listener 8884") && !text.contains("listener 8883"), text);

        activate(MAIN);
        given().delete("/api/certificate/sets/" + id("rsa")).then().statusCode(204);
        given().get("/api/certificate").then().body("tlsPort", equalTo(8883));
    }

    @Test
    @Order(34)
    void theCertificateFromBeforeThereWereSetsSurvivesSwitching() throws Exception {
        // the state of an older version: one set of files, and the key only where the broker reads it
        Map<String, Object> settings = settings("PDNS", fakeDns + "/pdns", List.of("example.test"), "EC");
        settings.put("tlsListener", true);
        settings.put("tlsPort", 8883);
        save(settings);
        issue();
        String id = id(MAIN);
        BigInteger original = serial(MAIN);
        Map<String, Object> stored = new java.util.HashMap<>(settings);
        Path state = ConfigProvider.getConfig().getValue("mosquitto-ui.state-dir", Path.class);
        Files.writeString(state.resolve("certificate.json"), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(stored));
        Files.copy(state.resolve("sets/" + id + "/fullchain.pem"), state.resolve("fullchain.pem"));
        Files.copy(state.resolve("sets/" + id + "/account.pem"), state.resolve("account.pem"));
        Files.delete(state.resolve("certificate-sets.json"));
        Files.delete(state.resolve("sets/" + id + "/fullchain.pem"));
        Files.delete(state.resolve("sets/" + id + "/account.pem"));
        Files.delete(state.resolve("sets/" + id));
        MosquittoResource.broker.execInContainer("rm", "-rf", "/mosquitto/data/certs/sets");

        given().get("/api/certificate").then()
                .body("sets", hasSize(1))
                .body("sets[0].name", equalTo("example.test"))
                .body("sets[0].active", equalTo(true))
                .body("sets[0].tokenSet", equalTo(true))
                .body("certificate.serial", equalTo(original.toString(16)));
        assertFalse(Files.exists(state.resolve("certificate.json")));
        assertFalse(Files.exists(state.resolve("fullchain.pem")));

        // the first switch keeps the key that only the broker has, so that the way back works
        save("new", settings("PDNS", fakeDns + "/pdns", List.of("example.test"), "EC"));
        issue("new");
        activate("new");
        await().atMost(Duration.ofSeconds(10)).until(() -> served().getSerialNumber().equals(serial("new")));
        activate("example.test");
        await().atMost(Duration.ofSeconds(10)).until(() -> served().getSerialNumber().equals(original));

        given().delete("/api/certificate/sets/" + id("new")).then().statusCode(204);
        given().contentType(ContentType.JSON).body(form(MAIN, settings)).put("/api/certificate/sets/" + id("example.test")).then().statusCode(204);
        assertTrue(active(MAIN));
    }

    /** The real thing: nobody asks, a certificate comes due, and the broker serves the new one. */
    @Test
    @Order(35)
    void aCertificateThatComesDueRenewsItself() throws Exception {
        String before = given().get("/api/certificate").jsonPath().getString("sets.find { it.active }.name");
        Map<String, Object> settings = settings("PDNS", fakeDns + "/pdns", List.of("short.example.test"), "EC");
        settings.put("directory", PebbleResource.directory(PebbleResource.pebbleShort));
        settings.put("autoRenew", false);
        save("short", settings);
        issue("short");
        activate("short");
        await().atMost(Duration.ofSeconds(10)).until(() -> served().getSerialNumber().equals(serial("short")));
        BigInteger first = serial("short");
        String pid = brokerPid();

        settings.put("autoRenew", true);
        save("short", settings);
        // a third of 90 seconds is left after 60
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(2)).until(() -> !first.equals(serial("short")));
        await().atMost(Duration.ofSeconds(10)).until(() -> served().getSerialNumber().equals(serial("short")));
        assertEquals(pid, brokerPid(), "same process");
        given().get("/api/certificate").then().body("job.error", nullValue());

        // the same when it goes wrong, before an order and during one: someone has to hear of it
        List<String> mails = new CopyOnWriteArrayList<>();
        QuarkusMock.installMockForType(new MailService() {
            @Override
            public void notifyFailure(Notice notice, String subject, String text) {
                mails.add(text);
            }
        }, MailService.class);
        settings.put("acceptTerms", false);
        save("short", settings);
        await().atMost(Duration.ofSeconds(120)).until(() -> mails.stream().anyMatch(mail -> mail.contains("terms of service")));
        given().get("/api/certificate").then().body("job.error", containsString("terms of service"));

        settings.put("acceptTerms", true);
        settings.put("domains", List.of("mqtt.other.test"));
        save("short", settings);
        await().atMost(Duration.ofSeconds(30)).until(() -> mails.stream().anyMatch(mail -> mail.contains("No zone found for mqtt.other.test")));

        settings.put("autoRenew", false);
        save("short", settings);
        await().atMost(Duration.ofSeconds(30)).until(() -> !given().get("/api/certificate").jsonPath().getBoolean("job.running"));
        activate(before);
    }

    @Test
    @Order(40)
    void resetLeavesNoCertificate() throws Exception {
        putConfig("");
        Map<String, Object> settings = settings("PDNS", fakeDns + "/pdns", List.of("example.test"), "EC");
        settings.put("tlsListener", true);
        save(settings);
        issue();
        given().get("/api/certificate").then().body("tlsPort", equalTo(8883));

        // a file of the configuration that the UI does not manage still uses it
        String certfile = given().get("/api/certificate").jsonPath().getString("certfile");
        MosquittoResource.broker.execInContainer("sh", "-c", "cp /mosquitto/config/mosquitto.conf /tmp/main.conf && echo '# " + certfile + "' >> /mosquitto/config/mosquitto.conf");
        given().contentType(ContentType.JSON).body(Map.of("certificate", true)).post("/api/config/reset").then().statusCode(200)
                .body("done", hasSize(0)).body("error", containsString("still uses"));
        assertTrue(MosquittoResource.broker.execInContainer("sh", "-c", "ls /mosquitto/data/certs").getStdout().contains("fullchain.pem"));
        MosquittoResource.broker.execInContainer("cp", "/tmp/main.conf", "/mosquitto/config/mosquitto.conf");

        given().contentType(ContentType.JSON).body(Map.of("certificate", true)).post("/api/config/reset").then().statusCode(200)
                .body("done", hasItems("certificate")).body("error", nullValue());
        await().atMost(Duration.ofSeconds(20)).until(() -> given().get("/api/broker/status").jsonPath().getBoolean("connected"));
        given().get("/api/certificate").then()
                .body("certificate", nullValue())
                .body("sets", hasSize(0))
                .body("tlsPort", nullValue());
        assertEquals("", MosquittoResource.broker.execInContainer("sh", "-c", "ls /mosquitto/data/certs").getStdout().strip());
        given().get("/api/config").then().body("text", not(containsString("8883")));
    }
}
