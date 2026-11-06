package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@QuarkusTestResource(FakeGxResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
@TestMethodOrder(OrderAnnotation.class)
class VictronTest {

    private static final String[] DEVICE_TOOL = {"--cafile", "/gx/server.crt", "--insecure", "-p", "8883", "-h", "localhost"};
    private static final String[] LOCAL = {"-u", "gx-reader", "-P", "gx-reader-password"};
    private static final Duration CONNECT = Duration.ofSeconds(40);

    private static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }

    private static Map<String, Object> pairing(String name, boolean twoWay) {
        Map<String, Object> pairing = new HashMap<>();
        pairing.put("name", name);
        pairing.put("host", FakeGxResource.gateway());
        pairing.put("port", FakeGxResource.mqttPort());
        pairing.put("secured", true);
        pairing.put("twoWay", twoWay);
        return pairing;
    }

    private static String[] concat(String[] first, String[] second) {
        String[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static boolean crosses(GenericContainer<?> from, String[] fromOptions, GenericContainer<?> to, String[] toOptions, String topic) throws Exception {
        ExecResult[] received = new ExecResult[1];
        Thread receiver = new Thread(() -> {
            try {
                received[0] = to.execInContainer(concat(new String[] {"mosquitto_sub", "-t", topic, "-C", "1", "-W", "4"}, toOptions));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        receiver.start();
        Thread.sleep(1000);
        from.execInContainer(concat(new String[] {"mosquitto_pub", "-t", topic, "-m", "over"}, fromOptions));
        receiver.join();
        return received[0].getStdout().contains("over");
    }

    private static boolean deviceToLocal(String topic, String localTopic) throws Exception {
        ExecResult[] received = new ExecResult[1];
        Thread receiver = new Thread(() -> {
            try {
                received[0] = MosquittoResource.broker.execInContainer(concat(new String[] {"mosquitto_sub", "-t", localTopic, "-C", "1", "-W", "4"}, LOCAL));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        receiver.start();
        Thread.sleep(1000);
        FakeGxResource.device.execInContainer(concat(new String[] {"mosquitto_pub", "-t", topic, "-m", "over"}, DEVICE_TOOL));
        receiver.join();
        return received[0].getStdout().contains("over");
    }

    private static void awaitConnected(String name) {
        await().atMost(CONNECT).until(() -> Boolean.TRUE.equals(given().get("/api/bridges").jsonPath().get("bridges.find { it.bridge.name == '" + name + "' }.connected")));
    }

    private static String bridgeFile() throws Exception {
        return MosquittoResource.broker.execInContainer("cat", "/mosquitto/ui/conf.d/mosquitto-ui-bridges.conf").getStdout();
    }

    @Test
    @Order(0)
    void setUp() throws Exception {
        json().body(Map.of("username", "gx-reader", "password", "gx-reader-password", "roles", List.of("client"), "groups", List.of())).post("/api/clients").then().statusCode(204);
        FakeGxResource.device.execInContainer(concat(new String[] {"mosquitto_pub", "-r", "-t", "N/" + FakeGxResource.PORTAL_ID + "/system/0/Serial", "-m", "{\"value\":\"x\"}"}, DEVICE_TOOL));
    }

    @Test
    @Order(1)
    void withoutPairingModeNothingHappens() {
        FakeGxResource.PAIRING_MODE.set(false);
        json().body(pairing("gx", false)).post("/api/bridges/victron").then().statusCode(409).body("code", equalTo("victron_not_pairing"));
        given().get("/api/bridges").then().body("bridges", hasSize(0));
    }

    @Test
    @Order(2)
    void readOnly() throws Exception {
        FakeGxResource.PAIRING_MODE.set(true);
        json().body(pairing("gx", false)).post("/api/bridges/victron").then().log().ifValidationFails().statusCode(204);
        awaitConnected("gx");

        String body = FakeGxResource.REQUESTS.getLast();
        assertTrue(body.matches("role=mosquittoui&device_id=mui[0-9a-f]{12}"), body);
        String file = bridgeFile();
        assertTrue(file.contains("remote_username token/mosquittoui/"), file);
        assertTrue(file.contains("remote_password gx-token-password"), file);
        assertTrue(file.contains("topic N/# in 0"), file);
        assertFalse(file.contains("topic W/#"), file);
        assertFalse(file.contains("topic R/#"), file);
        given().get("/api/bridges").then()
                .body("bridges[0].victronPortalId", equalTo(FakeGxResource.PORTAL_ID))
                .body("bridges[0].bridge.password", org.hamcrest.Matchers.nullValue())
                .body("bridges[0].warnings", hasSize(0));

        assertTrue(deviceToLocal("N/" + FakeGxResource.PORTAL_ID + "/grid/30/Ac/Power", "N/" + FakeGxResource.PORTAL_ID + "/grid/#"), "values arrive");
        assertFalse(crosses(MosquittoResource.broker, LOCAL, FakeGxResource.device, DEVICE_TOOL, "W/" + FakeGxResource.PORTAL_ID + "/vebus/257/Mode"), "no way to write");
    }

    @Test
    @Order(3)
    void keepalive() throws Exception {
        ExecResult full = FakeGxResource.device.execInContainer(concat(new String[] {"mosquitto_sub", "-t", "R/+/keepalive", "-v", "-W", "6", "-C", "2"}, DEVICE_TOOL));
        assertTrue(full.getStdout().contains("R/" + FakeGxResource.PORTAL_ID + "/keepalive {\"keepalive-options\":[\"suppress-republish\"]}"), full.toString());
    }

    @Test
    @Order(4)
    void noTokenForABridgeThatCannotBeAdded() {
        int before = FakeGxResource.REQUESTS.size();
        json().body(pairing("gx", false)).post("/api/bridges/victron").then().statusCode(409).body("code", equalTo("already_exists"));
        Map<String, Object> back = new HashMap<>(Map.of("name", "back", "address", FakeGxResource.gateway() + ":" + FakeGxResource.mqttPort(), "extraOptions", List.of(),
                "topics", List.of(Map.of("pattern", "N/#", "direction", "out", "qos", 0))));
        json().body(back).post("/api/bridges").then().statusCode(409).body("code", equalTo("bridge_loop"));
        assertEquals(before, FakeGxResource.REQUESTS.size(), "no token for nothing");
    }

    @Test
    @Order(5)
    void removed() {
        given().delete("/api/bridges/gx").then().statusCode(204);
        given().get("/api/bridges").then().body("bridges", hasSize(0));
    }

    @Test
    @Order(6)
    void twoWayWithPrefix() throws Exception {
        Map<String, Object> pairing = pairing("gx", true);
        pairing.put("localPrefix", "victron/");
        json().body(pairing).post("/api/bridges/victron").then().log().ifValidationFails().statusCode(204);
        awaitConnected("gx");
        assertTrue(bridgeFile().contains("topic N/# in 0 victron/ \"\""), bridgeFile());
        // the state of the bridge removed before can still be there, it is the same client id
        await().atMost(CONNECT).pollInterval(Duration.ofSeconds(1)).until(() -> deviceToLocal("N/" + FakeGxResource.PORTAL_ID + "/grid/31/Ac/Power", "victron/N/" + FakeGxResource.PORTAL_ID + "/grid/#"));
        ExecResult[] received = new ExecResult[1];
        Thread receiver = new Thread(() -> {
            try {
                received[0] = FakeGxResource.device.execInContainer(concat(new String[] {"mosquitto_sub", "-t", "W/#", "-v", "-C", "1", "-W", "4"}, DEVICE_TOOL));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        receiver.start();
        Thread.sleep(1000);
        MosquittoResource.broker.execInContainer(concat(new String[] {"mosquitto_pub", "-t", "victron/W/" + FakeGxResource.PORTAL_ID + "/vebus/257/Mode", "-m", "{\"value\":1}"}, LOCAL));
        receiver.join();
        assertTrue(received[0].getStdout().contains("W/" + FakeGxResource.PORTAL_ID + "/vebus/257/Mode"), received[0].toString());
        given().delete("/api/bridges/gx").then().statusCode(204);
        given().delete("/api/clients/gx-reader").then().statusCode(204);
    }

    @Test
    @Order(7)
    void aNewCertificateOfTheDeviceNeedsPairingAgain() throws Exception {
        FakeGxResource.PAIRING_MODE.set(true);
        json().body(pairing("gx", false)).post("/api/bridges/victron").then().log().ifValidationFails().statusCode(204);
        awaitConnected("gx");
        String trusted = given().get("/api/bridges").jsonPath().getString("bridges[0].victronCertificate");
        assertTrue(trusted.matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"), trusted);
        given().get("/api/bridges").then().body("bridges[0].victronCertificateChanged", org.hamcrest.Matchers.nullValue());

        // the keepalive no longer gets in, it trusts only the certificate from pairing, and tells what the device presents now
        String presented = com.dfc.mosquittoui.config.VictronStore.fingerprint(FakeGxResource.newCertificate());
        await().atMost(CONNECT).until(() -> presented.equals(given().get("/api/bridges").jsonPath().getString("bridges[0].victronCertificateChanged")));
        given().get("/api/bridges").then().body("bridges[0].victronCertificate", equalTo(trusted));
        // the bridge of the broker trusts the same certificate, its state follows when it tries again
        await().atMost(CONNECT).until(() -> Boolean.FALSE.equals(given().get("/api/bridges").jsonPath().get("bridges[0].connected")));

        FakeGxResource.PAIRING_MODE.set(false);
        given().post("/api/bridges/gx/victron/pair-again").then().statusCode(409).body("code", equalTo("victron_not_pairing"));
        FakeGxResource.PAIRING_MODE.set(true);
        int requests = FakeGxResource.REQUESTS.size();
        given().post("/api/bridges/gx/victron/pair-again").then().log().ifValidationFails().statusCode(204);
        assertTrue(bridgeFile().contains("remote_username token/mosquittoui/" + (requests + 1)), bridgeFile());
        awaitConnected("gx");
        given().get("/api/bridges").then()
                .body("bridges[0].victronCertificate", equalTo(presented))
                .body("bridges[0].victronCertificateChanged", org.hamcrest.Matchers.nullValue())
                .body("bridges[0].bridge.topics", hasSize(1));
        given().post("/api/bridges/nothing/victron/pair-again").then().statusCode(404);
        given().delete("/api/bridges/gx").then().statusCode(204);
    }
}
