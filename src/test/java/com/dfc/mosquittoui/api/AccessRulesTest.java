package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.api.Dtos.AccessDefaults;
import com.dfc.mosquittoui.api.Dtos.AclDecision;
import com.dfc.mosquittoui.api.Dtos.AclReason;
import com.dfc.mosquittoui.api.Dtos.AclTest;
import com.dfc.mosquittoui.broker.AclTester;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.broker.DynsecService;
import com.dfc.mosquittoui.importer.ImportService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttClient;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Above all: what the tester says against what really happens to clients of a real broker. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class AccessRulesTest {

    private static final String PASSWORD = "probe-password";
    private static final int DENIED = 0x80;
    private static final List<String> MESSAGE_TOPICS = List.of("a/open", "a/secret", "a/b/c", "b/x", "b/y", "g/x", "users/probe/x", "users/other/x", "ids/probe-id/x", "ids/other/x", "open/x",
            "none");
    private static final List<String> SUBSCRIPTIONS = List.of("a/open", "a/secret", "a/+", "a/#", "b/x", "b/y", "b/+", "g/x", "users/probe/x", "users/other/x", "ids/probe-id/x", "open/x",
            "open/#", "open/+", "none", "#", "$SYS/broker/uptime", "$SYS/#", "$share/g/a/open", "$CONTROL/other");

    @Inject
    DynsecClient dynsec;

    @Inject
    DynsecService service;

    @Inject
    AclTester tester;

    @Inject
    ImportService imports;

    @Inject
    Vertx vertx;

    private static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }

    private final Map<String, List<String>> existing = new HashMap<>();

    private List<String> names(String command, String list, String field) {
        List<String> names = new ArrayList<>();
        dynsec.call(dynsec.command(command).put("verbose", true).put("count", -1).put("offset", 0)).path(list).forEach(item -> names.add(item.path(field).asText()));
        return names;
    }

    @BeforeEach
    void connected() {
        await().until(() -> given().get("/api/broker/status").jsonPath().getBoolean("connected"));
        existing.put("clients", names("listClients", "clients", "username"));
        existing.put("groups", names("listGroups", "groups", "groupname"));
        existing.put("roles", names("listRoles", "roles", "rolename"));
    }

    /** The other tests share this broker and count what is on it. */
    @AfterEach
    void removeWhatWasAdded() {
        names("listClients", "clients", "username").stream().filter(name -> !existing.get("clients").contains(name)).forEach(name -> dynsec.call(dynsec.command("deleteClient").put("username", name)));
        names("listGroups", "groups", "groupname").stream().filter(name -> !existing.get("groups").contains(name)).forEach(name -> dynsec.call(dynsec.command("deleteGroup").put("groupname", name)));
        names("listRoles", "roles", "rolename").stream().filter(name -> !existing.get("roles").contains(name)).forEach(name -> dynsec.call(dynsec.command("deleteRole").put("rolename", name)));
    }

    // --- the commands, also with what the pages cannot set ---

    private void role(String rolename, boolean wildcards, Object... acls) {
        dynsec.call(dynsec.command("createRole").put("rolename", rolename).put("allowwildcardsubs", wildcards));
        for (int i = 0; i < acls.length; i += 4) {
            dynsec.call(dynsec.command("addRoleACL").put("rolename", rolename).put("acltype", (String) acls[i]).put("topic", (String) acls[i + 1])
                    .put("allow", (Boolean) acls[i + 2]).put("priority", (Integer) acls[i + 3]));
        }
    }

    private void group(String groupname, Object... rolesAndPriorities) {
        dynsec.call(dynsec.command("createGroup").put("groupname", groupname));
        for (int i = 0; i < rolesAndPriorities.length; i += 2) {
            dynsec.call(dynsec.command("addGroupRole").put("groupname", groupname).put("rolename", (String) rolesAndPriorities[i]).put("priority", (Integer) rolesAndPriorities[i + 1]));
        }
    }

    private void client(String username) {
        dynsec.call(dynsec.command("createClient").put("username", username).put("password", PASSWORD));
    }

    private void clientRole(String username, String rolename, int priority) {
        dynsec.call(dynsec.command("addClientRole").put("username", username).put("rolename", rolename).put("priority", priority));
    }

    private void clientGroup(String username, String groupname, int priority) {
        ObjectNode command = dynsec.command("addGroupClient").put("groupname", groupname).put("username", username).put("priority", priority);
        dynsec.call(command);
    }

    // --- real clients ---

    private static <T> T done(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private MqttClient connect(String username, String clientId, BlockingQueue<String> messages) throws Exception {
        MqttClient client = MqttClient.create(vertx, dynsec.options(username, PASSWORD).setClientId(clientId));
        client.publishHandler(message -> messages.add(message.topicName()));
        done(client.connect(MosquittoResource.broker.getMappedPort(1883), MosquittoResource.broker.getHost()));
        return client;
    }

    /** What a subscription really gets from the broker. */
    private boolean granted(MqttClient client, String filter) throws Exception {
        CompletableFuture<Integer> answer = new CompletableFuture<>();
        client.subscribeCompletionHandler(ack -> answer.complete(ack.grantedQoSLevels().getFirst()));
        done(client.subscribe(filter, MqttQoS.AT_MOST_ONCE.value()));
        boolean granted = answer.get(10, TimeUnit.SECONDS) != DENIED;
        if (granted) {
            done(client.unsubscribe(filter));
        }
        return granted;
    }

    /** Waits for the end marker: all that came before it came through the broker in order. */
    private static boolean seenBefore(BlockingQueue<String> messages, String topic, String marker) throws InterruptedException {
        boolean seen = false;
        while (true) {
            String next = messages.poll(10, TimeUnit.SECONDS);
            assertTrue(next != null, "No end marker " + marker);
            seen |= next.equals(topic);
            if (next.equals(marker)) {
                return seen;
            }
        }
    }

    private void retain(String topic) throws Exception {
        json().body(Map.of("username", "retaining", "password", PASSWORD, "roles", List.of("client"), "groups", List.of())).post("/api/clients");
        assertEquals(0, MosquittoResource.broker.execInContainer("mosquitto_pub", "-u", "retaining", "-P", PASSWORD, "-r", "-t", topic, "-m", "x").getExitCode());
    }

    private AclDecision decision(String username, String clientId, String topic, String access) {
        return tester.test(new AclTest(username, clientId, topic)).decisions().stream().filter(item -> item.access().equals(access)).findFirst().orElseThrow();
    }

    private void compareWithBroker(String username, String receiver, List<String> mismatches) throws Exception {
        BlockingQueue<String> observed = new LinkedBlockingQueue<>();
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        BlockingQueue<String> ignored = new LinkedBlockingQueue<>();
        MqttClient observer = connect("observer", "observer", observed);
        done(observer.subscribe("#", MqttQoS.AT_MOST_ONCE.value()));
        MqttClient probe = connect(username, "probe-id", ignored);
        MqttClient listening = connect(receiver, "probe-id-2", received);
        done(listening.subscribe("#", MqttQoS.AT_MOST_ONCE.value()));
        // the subscriptions above arrive before their answer is read, the first marker shows that they are in place
        Thread.sleep(200);

        for (String filter : SUBSCRIPTIONS) {
            boolean real = granted(probe, filter);
            AclDecision said = decision(username, "probe-id", filter, "subscribe");
            if (!Boolean.valueOf(real).equals(said.allowed())) {
                mismatches.add("subscribe " + filter + ": broker " + real + ", tester " + said);
            }
        }
        for (String topic : MESSAGE_TOPICS) {
            CompletableFuture<Integer> acknowledged = new CompletableFuture<>();
            probe.publishCompletionHandler(acknowledged::complete);
            done(probe.publish(topic, Buffer.buffer("x"), MqttQoS.AT_LEAST_ONCE, false, false));
            acknowledged.get(10, TimeUnit.SECONDS);
            String marker = "marker/" + UUID.randomUUID();
            done(observer.publish(marker, Buffer.buffer("x"), MqttQoS.AT_MOST_ONCE, false, false));
            boolean real = seenBefore(observed, topic, marker);
            AclDecision said = decision(username, "probe-id", topic, "publishClientSend");
            if (!Boolean.valueOf(real).equals(said.allowed())) {
                mismatches.add("publish " + topic + ": broker " + real + ", tester " + said);
            }
        }
        received.clear();
        for (String topic : MESSAGE_TOPICS) {
            String marker = "marker/" + UUID.randomUUID();
            done(observer.publish(topic, Buffer.buffer("x"), MqttQoS.AT_MOST_ONCE, false, false));
            done(observer.publish(marker, Buffer.buffer("x"), MqttQoS.AT_MOST_ONCE, false, false));
            boolean real = seenBefore(received, topic, marker);
            AclDecision said = decision(receiver, "probe-id-2", topic, "publishClientReceive");
            if (!Boolean.valueOf(real).equals(said.allowed())) {
                mismatches.add("receive " + topic + ": broker " + real + ", tester " + said);
            }
        }
        done(probe.disconnect());
        done(listening.disconnect());
        done(observer.disconnect());
    }

    @Test
    void theTesterSaysWhatTheBrokerDoes() throws Exception {
        role("observer-all", true, "publishClientSend", "#", true, 0, "publishClientReceive", "#", true, 0, "subscribePattern", "#", true, 0);
        client("observer");
        clientRole("observer", "observer-all", 0);

        role("high", true,
                "publishClientSend", "a/secret", false, 5, "publishClientSend", "a/#", true, 1,
                "publishClientReceive", "a/secret", false, 5, "publishClientReceive", "a/#", true, 1,
                "subscribePattern", "a/secret", false, 5, "subscribePattern", "a/#", true, 1);
        role("low", true,
                // loses against the role above, whatever its priority
                "publishClientSend", "a/secret", true, 100, "publishClientReceive", "a/secret", true, 100, "subscribePattern", "a/secret", true, 100,
                // the literal rule wins, even with the lower priority
                "subscribeLiteral", "b/x", true, 0, "subscribePattern", "b/#", false, 50);
        role("no-wildcards", false, "subscribePattern", "open/#", true, 0, "publishClientSend", "open/#", true, 0);
        role("first", true, "publishClientSend", "g/x", false, 0, "publishClientReceive", "g/x", false, 0, "subscribePattern", "g/x", false, 0);
        role("second", true, "publishClientSend", "g/x", true, 0, "publishClientReceive", "g/x", true, 0, "subscribePattern", "g/x", true, 0);
        role("patterns", true, "publishClientSend", "users/%u/#", true, 0, "publishClientSend", "ids/%c/#", true, 0,
                "publishClientReceive", "users/%u/#", true, 0, "publishClientReceive", "ids/%c/#", true, 0,
                "subscribePattern", "users/%u/#", true, 0, "subscribePattern", "ids/%c/#", true, 0);
        group("group-first", "first", 0);
        group("group-second", "second", 0, "patterns", 0, "no-wildcards", 0);
        role("receiver", true, "subscribePattern", "#", true, -100, "publishClientReceive", "marker/#", true, 0);

        for (String username : List.of("probe", "probe-receiver")) {
            client(username);
            clientRole(username, "low", 1);
            clientRole(username, "high", 10);
            clientGroup(username, "group-second", 1);
            clientGroup(username, "group-first", 5);
        }
        clientRole("probe-receiver", "receiver", 1000);

        AccessDefaults before = service.access().defaults();
        List<String> mismatches = new ArrayList<>();
        try {
            compareWithBroker("probe", "probe-receiver", mismatches);
            service.setDefaultAccess(new AccessDefaults(!before.publishClientSend(), !before.publishClientReceive(), !before.subscribe(), !before.unsubscribe()));
            compareWithBroker("probe", "probe-receiver", mismatches);
            service.setDefaultAccess(new AccessDefaults(true, true, true, true));
            compareWithBroker("probe", "probe-receiver", mismatches);
        } finally {
            service.setDefaultAccess(before);
        }
        assertEquals(List.of(), mismatches);
    }

    @Test
    void theTesterNamesWhatDecided() {
        role("named", true, "publishClientSend", "named/#", true, 7);
        role("named-group-role", false);
        group("named-group", "named-group-role", 0);
        client("named-client");
        clientRole("named-client", "named", 0);
        clientGroup("named-client", "named-group", 0);

        AclDecision rule = decision("named-client", null, "named/x", "publishClientSend");
        assertEquals(AclReason.RULE, rule.reason());
        assertEquals("named", rule.role());
        assertEquals("named/#", rule.rule().topic());
        assertEquals(7, rule.rule().priority());

        AclDecision wildcards = decision("named-client", null, "x/+", "subscribe");
        assertEquals(false, wildcards.allowed());
        assertEquals(AclReason.WILDCARDS, wildcards.reason());
        assertEquals("named-group", wildcards.group());
        assertEquals("named-group-role", wildcards.role());

        assertEquals(AclReason.INVALID, decision("named-client", null, "x/+", "publishClientSend").reason());
        assertEquals(AclReason.INVALID, decision("named-client", null, "x/#/y", "subscribe").reason());
        assertEquals(AclReason.BROKER, decision("named-client", null, "$SYS/x", "publishClientSend").reason());
        assertEquals(AclReason.DEFAULT, decision("named-client", null, "other", "publishClientReceive").reason());
        assertEquals(AclReason.CONTROL, decision("named-client", null, "$CONTROL/x", "publishClientReceive").reason());
    }

    @Test
    void disconnectingLeavesTheClientAsItIs() throws Exception {
        json().body(Map.of("username", "to-kick", "password", PASSWORD, "textname", "Name", "roles", List.of(), "groups", List.of())).post("/api/clients").then().statusCode(204);
        BlockingQueue<String> ignored = new LinkedBlockingQueue<>();
        MqttClient client = connect("to-kick", "to-kick-id", ignored);
        CompletableFuture<Void> closed = new CompletableFuture<>();
        client.closeHandler(v -> closed.complete(null));

        given().post("/api/clients/to-kick/disconnect").then().statusCode(204);

        closed.get(10, TimeUnit.SECONDS);
        given().get("/api/clients/to-kick").then().statusCode(200).body("disabled", equalTo(false)).body("textname", equalTo("Name"));
        assertTrue(done(MqttClient.create(vertx, dynsec.options("to-kick", PASSWORD)).connect(MosquittoResource.broker.getMappedPort(1883), MosquittoResource.broker.getHost())).code().name().contains("ACCEPTED"));

        given().post("/api/clients/admin/disconnect").then().statusCode(409);
        given().post("/api/clients/nobody-here/disconnect").then().statusCode(404);
    }

    @Test
    void defaultsOfDenyLeaveThisApplicationWorking() throws Exception {
        AccessDefaults before = service.access().defaults();
        retain("access/retained");
        try {
            json().body(new AccessDefaults(false, false, false, false)).put("/api/access/defaults").then().statusCode(204);
            given().get("/api/access").then().statusCode(200).body("defaults.publishClientReceive", equalTo(false)).body("defaults.unsubscribe", equalTo(false));
            given().get("/api/clients").then().statusCode(200);
            assertTrue(imports.readRetained().containsKey("access/retained"));
        } finally {
            service.setDefaultAccess(before);
            MosquittoResource.broker.execInContainer("mosquitto_pub", "-u", "retaining", "-P", PASSWORD, "-r", "-n", "-t", "access/retained");
        }
    }

    @Test
    void theAnonymousGroup() {
        json().body(Map.of("groupname", "guests", "roles", List.of())).post("/api/groups").then().statusCode(204);
        String before = service.anonymousGroup();
        assertFalse(before.isEmpty(), "A group that was set cannot be taken back, so there has to be one to go back to");
        json().body(Map.of("groupname", "guests")).put("/api/access/anonymous-group").then().statusCode(204);
        given().get("/api/access").then().body("anonymousGroup", equalTo("guests"));
        given().get("/api/groups").then().body("find { it.groupname == 'guests' }.anonymous", equalTo(true));
        given().delete("/api/groups/guests").then().statusCode(400);

        json().body(Map.of("groupname", "does-not-exist")).put("/api/access/anonymous-group").then().statusCode(404);
        service.setAnonymousGroup(before);
        assertEquals(before, service.anonymousGroup());
    }

    @Test
    void theGroupOfThisApplicationStaysItsOwn() {
        List<String> groups = service.getClient("admin").groups();
        for (String group : groups) {
            json().body(Map.of("groupname", group)).put("/api/access/anonymous-group").then().statusCode(409);
        }
    }

    @Test
    void changesAreLogged() {
        AccessDefaults before = service.access().defaults();
        json().body(before).put("/api/access/defaults").then().statusCode(204);
        given().get("/api/audit").then().body("find { it.action == 'setDefaultAccess' }.details.subscribe", equalTo(String.valueOf(before.subscribe())));
        json().body(new AclTest(null, null, "x")).post("/api/access/test").then().statusCode(200);
        given().get("/api/audit").then().body("findAll { it.action == 'testAccess' }.size()", equalTo(0));
    }

}
