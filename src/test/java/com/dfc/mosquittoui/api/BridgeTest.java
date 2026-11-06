package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/** A bridge from the broker of the tests to a second, real broker; the Mosquitto-specific behaviour is what matters here. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
@TestMethodOrder(OrderAnnotation.class)
class BridgeTest {

    private static final String FILE = "/mosquitto/ui/conf.d/mosquitto-ui-bridges.conf";
    private static final String PASSWORD = "pa ss#wo=rd\"x";
    private static final Duration CONNECT = Duration.ofSeconds(40);

    static GenericContainer<?> upstream;

    @BeforeAll
    static void startUpstream() {
        upstream = new GenericContainer<>("eclipse-mosquitto:2.1.2-alpine")
                .withNetwork(Network.SHARED)
                .withNetworkAliases("upstream")
                .withCommand("mosquitto", "-c", "/mosquitto-no-auth.conf")
                .waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*", 1));
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    private static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }

    private static Map<String, Object> topic(String pattern, String direction) {
        return Map.of("pattern", pattern, "direction", direction, "qos", 0);
    }

    private static Map<String, Object> bridge(String name, Object... topics) {
        Map<String, Object> bridge = new HashMap<>();
        bridge.put("name", name);
        bridge.put("address", "upstream:1883");
        bridge.put("remoteClientId", name + "-id");
        bridge.put("topics", List.of(topics));
        bridge.put("extraOptions", List.of());
        return bridge;
    }

    @Test
    void aBridgeHoldsOnlyOptionsOfABridge() throws Exception {
        String before = file();
        Map<String, Object> disabled = bridge("sleeper", topic("sleep/#", "out"));
        disabled.put("disabled", true);
        disabled.put("extraOptions", List.of("plugin /mosquitto/ui/x.so"));
        json().body(disabled).post("/api/bridges").then().statusCode(409).body("code", equalTo("validation"));
        Map<String, Object> control = bridge("admin", Map.of("pattern", "dynamic-security/#", "direction", "in", "qos", 0, "localPrefix", "$CONTROL/"));
        json().body(control).post("/api/bridges").then().statusCode(400).body("code", equalTo("config_rejected"));
        assertEquals(before, file());
    }

    private static String brokerPid() throws Exception {
        return MosquittoResource.broker.execInContainer("pidof", "mosquitto").getStdout().strip();
    }

    private static String file() throws Exception {
        return MosquittoResource.broker.execInContainer("cat", FILE).getStdout();
    }

    private static void awaitConnected(String name) {
        await().atMost(CONNECT).until(() -> Boolean.TRUE.equals(given().get("/api/bridges").jsonPath().get("bridges.find { it.bridge.name == '" + name + "' }.connected")));
        assertNotNull(given().get("/api/bridges").jsonPath().get("bridges.find { it.bridge.name == '" + name + "' }.stateSince"));
    }

    /** Whether a message sent from one broker arrives at the other. */
    private static boolean crosses(GenericContainer<?> from, String[] fromAuth, GenericContainer<?> to, String[] toAuth, String topic) throws Exception {
        String[] sub = concat(new String[] {"mosquitto_sub", "-t", topic, "-C", "1", "-W", "4"}, toAuth);
        ExecResult[] received = new ExecResult[1];
        Thread receiver = new Thread(() -> {
            try {
                received[0] = to.execInContainer(sub);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        receiver.start();
        Thread.sleep(1000);
        from.execInContainer(concat(new String[] {"mosquitto_pub", "-t", topic, "-m", "over"}, fromAuth));
        receiver.join();
        return received[0].getStdout().contains("over");
    }

    private static String[] concat(String[] first, String[] second) {
        String[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    /** The admin of the plugin may not publish or subscribe to application topics. */
    private static final String[] DEVICE = {"-u", "device", "-P", "device-password"};
    private static final String[] ANONYMOUS = {};

    private static boolean outgoing(String topic) throws Exception {
        return crosses(MosquittoResource.broker, DEVICE, upstream, ANONYMOUS, topic);
    }

    private static boolean incoming(String topic) throws Exception {
        return crosses(upstream, ANONYMOUS, MosquittoResource.broker, DEVICE, topic);
    }

    @Test
    @Order(1)
    void aNewBridgeNeedsNoRestart() throws Exception {
        await().until(() -> given().get("/api/bridges").jsonPath().getBoolean("available"));
        given().get("/api/bridges").then().body("bridges", hasSize(0));
        json().body(Map.of("username", "device", "password", "device-password", "roles", List.of("client"), "groups", List.of())).post("/api/clients").then().statusCode(204);
        String pid = brokerPid();

        Map<String, Object> bridge = bridge("north", topic("sensors/#", "out"), topic("commands/#", "in"));
        bridge.put("username", "bridge-user");
        bridge.put("password", PASSWORD);
        json().body(bridge).post("/api/bridges").then().log().ifValidationFails().statusCode(204);
        awaitConnected("north");

        assertEquals(pid, brokerPid(), "same process");
        assertTrue(outgoing("sensors/a"), "out");
        assertTrue(incoming("commands/a"), "in");
        assertFalse(outgoing("other/a"), "topic without a rule");

        given().get("/api/bridges").then()
                .body("bridges[0].bridge.password", nullValue())
                .body("bridges[0].passwordSet", equalTo(true))
                .body("bridges[0].bridge.topics.pattern", hasItem("sensors/#"))
                .body("bridges[0].warnings", hasSize(0));
        assertTrue(file().contains("remote_password " + PASSWORD), "a password with spaces and quotes is taken as it is");
    }

    @Test
    @Order(2)
    void aChangeOfABridgeTakesARestart() throws Exception {
        String pid = brokerPid();
        Map<String, Object> changed = bridge("north", topic("sensors/#", "out"), topic("commands/#", "in"), topic("added/#", "out"));
        changed.put("username", "bridge-user");

        json().body(changed).put("/api/bridges/north").then().statusCode(409).body("code", equalTo("restart_required"));
        assertEquals(pid, brokerPid());
        assertFalse(file().contains("added/#"));

        json().body(changed).queryParam("confirmRestart", true).put("/api/bridges/north").then().log().ifValidationFails().statusCode(204);
        assertNotEquals(pid, brokerPid());
        await().atMost(Duration.ofSeconds(20)).until(() -> given().get("/api/broker/status").jsonPath().getBoolean("connected"));
        awaitConnected("north");
        assertTrue(outgoing("added/a"));
        assertTrue(file().contains("remote_password " + PASSWORD), "the password stays when it is not sent");
    }

    @Test
    @Order(3)
    void unchangedNeedsNothing() throws Exception {
        Map<String, Object> same = bridge("north", topic("sensors/#", "out"), topic("commands/#", "in"), topic("added/#", "out"));
        same.put("username", "bridge-user");
        json().body(same).put("/api/bridges/north").then().statusCode(204);
    }

    @Test
    @Order(4)
    void twoBridgesInACircleAreRefused() throws Exception {
        String before = file();
        json().body(bridge("south", topic("sensors/a/#", "in"))).post("/api/bridges").then().statusCode(409).body("code", equalTo("bridge_loop"));
        Map<String, Object> otherSpelling = bridge("south", topic("#", "both"));
        otherSpelling.put("address", "UPSTREAM");
        json().body(otherSpelling).post("/api/bridges").then().statusCode(409).body("code", equalTo("bridge_loop"));
        assertEquals(before, file());
    }

    @Test
    @Order(5)
    void bridgesThatDoNotMeetStayPossible() {
        json().body(bridge("east", topic("sensors/#", "out"))).post("/api/bridges").then().statusCode(204);
        json().body(bridge("west", topic("unrelated/#", "both"))).post("/api/bridges").then().statusCode(204);
        given().delete("/api/bridges/east").then().statusCode(204);
    }

    @Test
    @Order(6)
    void aRenamedBridgeIsANewOneForTheBroker() throws Exception {
        String pid = brokerPid();
        json().body(bridge("west2", topic("unrelated/#", "both"))).put("/api/bridges/west").then().statusCode(204);
        assertEquals(pid, brokerPid());
        given().get("/api/bridges").then().body("bridges.bridge.name", hasItem("west2"));
        given().delete("/api/bridges/west2").then().statusCode(204);
    }

    @Test
    @Order(7)
    void warnings() {
        Map<String, Object> bridge = bridge("noisy", topic("#", "both"), topic("N", "both"));
        bridge.put("address", "other:1883");
        bridge.put("tryPrivate", false);
        json().body(bridge).post("/api/bridges").then().statusCode(204);
        given().get("/api/bridges").then().body("bridges.find { it.bridge.name == 'noisy' }.warnings", equalTo(List.of("overlap", "echo")));
        given().delete("/api/bridges/noisy").then().statusCode(204);
    }

    @Test
    @Order(8)
    void linesCannotBeSmuggledIn() throws Exception {
        Map<String, Object> bridge = bridge("sneaky", topic("t/#", "out"));
        bridge.put("address", "other:1883");
        bridge.put("username", "x\nlistener 1999");
        json().body(bridge).post("/api/bridges").then().statusCode(400).body("code", equalTo("validation"));
        bridge.remove("username");
        bridge.put("extraOptions", List.of("connection second"));
        json().body(bridge).post("/api/bridges").then().statusCode(409).body("code", equalTo("validation"));
        bridge.put("extraOptions", List.of());
        bridge.put("name", "two words");
        json().body(bridge).post("/api/bridges").then().statusCode(400);
    }

    @Test
    @Order(9)
    void whatTheBrokerRefusesStaysOut() throws Exception {
        String before = file();
        Map<String, Object> bridge = bridge("refused", topic("t/#", "out"));
        bridge.put("address", "other:1883");
        bridge.put("extraOptions", List.of("start_type manual"));
        json().body(bridge).post("/api/bridges").then().statusCode(400).body("code", equalTo("config_rejected")).body("message", containsString("Manual start_type"));
        assertEquals(before, file());
    }

    @Test
    @Order(9)
    void aDisabledBridgeStaysButTheBrokerDoesNotUseIt() throws Exception {
        String pid = brokerPid();
        awaitConnected("north");
        json().body(Map.of("enabled", false)).put("/api/bridges/north/enabled").then().statusCode(204);
        await().atMost(CONNECT).ignoreExceptions().until(() -> !outgoing("sensors/disabled"));
        assertEquals(pid, brokerPid(), "no restart");
        given().get("/api/bridges").then()
                .body("bridges[0].bridge.disabled", equalTo(true))
                .body("bridges[0].connected", nullValue())
                .body("bridges[0].passwordSet", equalTo(true));
        assertTrue(file().lines().filter(line -> line.contains("north") || line.contains("remote_password")).allMatch(line -> line.startsWith("#")), file());

        // the broker does not know it, so a change needs no restart and is there once it runs again; the flag only has its own request
        Map<String, Object> changed = bridge("north", topic("moved/#", "out"));
        changed.put("username", "bridge-user");
        changed.put("disabled", false);
        json().body(changed).put("/api/bridges/north").then().statusCode(204);
        given().get("/api/bridges").then().body("bridges[0].bridge.disabled", equalTo(true));
        json().body(bridge("loop", topic("moved/#", "in"))).post("/api/bridges").then().statusCode(204);
        json().body(Map.of("enabled", true)).put("/api/bridges/north/enabled").then().statusCode(409);
        given().delete("/api/bridges/loop").then().statusCode(204);

        json().body(Map.of("enabled", true)).put("/api/bridges/north/enabled").then().statusCode(204);
        awaitConnected("north");
        assertEquals(pid, brokerPid(), "no restart");
        await().atMost(CONNECT).ignoreExceptions().until(() -> outgoing("moved/a"));
        assertFalse(outgoing("sensors/old"), "as changed");
        assertTrue(file().contains("remote_password " + PASSWORD), "the password came along");
    }

    @Test
    @Order(10)
    void deleted() throws Exception {
        String pid = brokerPid();
        given().delete("/api/bridges/north").then().statusCode(204);
        given().delete("/api/bridges/north").then().statusCode(404);
        assertEquals(pid, brokerPid());
        await().atMost(Duration.ofSeconds(10)).ignoreExceptions().until(() -> !outgoing("sensors/gone"));
        given().get("/api/bridges").then().body("bridges", hasSize(0));
        given().delete("/api/clients/device").then().statusCode(204);
    }
}
