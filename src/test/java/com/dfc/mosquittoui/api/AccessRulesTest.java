package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.api.Dtos.AccessDefaults;
import com.dfc.mosquittoui.api.Dtos.AclDecision;
import com.dfc.mosquittoui.api.Dtos.AclReason;
import com.dfc.mosquittoui.api.Dtos.AclSource;
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

    /** The tester lists the roles in the order the broker checks them, with the priorities that put them there. */
    @Test
    void theTesterShowsTheOrderOfTheRoles() {
        role("order-low", true);
        role("order-high", true);
        role("order-group-a", true);
        role("order-group-b", false);
        group("order-group", "order-group-a", 1, "order-group-b", 3);
        group("order-other-group", "order-low", 0);
        client("order-client");
        clientRole("order-client", "order-low", 2);
        clientRole("order-client", "order-high", 9);
        clientGroup("order-client", "order-group", 5);
        clientGroup("order-client", "order-other-group", 8);

        List<AclSource> sources = tester.test(new AclTest("order-client", null, "x")).sources();
        assertEquals(List.of(
                new AclSource(null, null, "order-high", 9, true),
                new AclSource(null, null, "order-low", 2, true),
                new AclSource("order-other-group", 8, "order-low", 0, true),
                new AclSource("order-group", 5, "order-group-b", 3, false),
                new AclSource("order-group", 5, "order-group-a", 1, true)), sources);
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

    /** The plugin disconnects on every modifyClient and modifyGroup; saving without a change must not send one. */
    @Test
    void savingWithoutAChangeKeepsClientsConnected() throws Exception {
        role("kept-role", true);
        json().body(Map.of("groupname", "kept-group", "textname", "Group", "roles", List.of("kept-role"), "clients", List.of())).post("/api/groups").then().statusCode(204);
        json().body(Map.of("username", "kept", "password", PASSWORD, "textname", "Name", "roles", List.of("kept-role"), "groups", List.of("kept-group"))).post("/api/clients")
                .then().statusCode(204);
        json().body(Map.of("username", "joining", "password", PASSWORD, "roles", List.of(), "groups", List.of())).post("/api/clients").then().statusCode(204);
        MqttClient client = connect("kept", "kept-id", new LinkedBlockingQueue<>());
        CompletableFuture<Void> closed = new CompletableFuture<>();
        client.closeHandler(v -> closed.complete(null));

        json().body(Map.of("textname", "Name", "textdescription", "", "roles", List.of("kept-role"), "groups", List.of("kept-group"))).put("/api/clients/kept").then().statusCode(204);
        // a new member disconnects only itself, the group itself is unchanged
        json().body(Map.of("textname", "Group", "textdescription", "", "roles", List.of("kept-role"), "clients", List.of("kept", "joining"))).put("/api/groups/kept-group")
                .then().statusCode(204);
        given().get("/api/groups").then().body("find { it.groupname == 'kept-group' }.clients", containsInAnyOrder("kept", "joining"));
        Thread.sleep(2000);
        assertFalse(closed.isDone(), "Saving without a change disconnected the client");

        // a real change still does when it can take a subscription away: the group loses a role that allowed subscribing
        dynsec.call(dynsec.command("addRoleACL").put("rolename", "kept-role").put("acltype", "subscribeLiteral").put("topic", "kept/x").put("allow", true));
        json().body(Map.of("textname", "Group", "textdescription", "changed", "roles", List.of(), "clients", List.of("kept", "joining"))).put("/api/groups/kept-group")
                .then().statusCode(204);
        closed.get(10, TimeUnit.SECONDS);
        given().get("/api/groups").then().body("find { it.groupname == 'kept-group' }.textdescription", equalTo("changed"));
    }

    /** One connection of a client that comes back after the broker disconnected it. */
    private final class Watched {
        private final String username;
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private MqttClient client;
        private CompletableFuture<Void> closed;

        Watched(String username) throws Exception {
            this.username = username;
            open();
        }

        private void open() throws Exception {
            client = connect(username, username + "-id", messages);
            closed = new CompletableFuture<>();
            client.closeHandler(v -> closed.complete(null));
        }

        void kept(String change) throws Exception {
            Thread.sleep(1500);
            assertFalse(closed.isDone(), change + " disconnected the client");
        }

        void disconnected(String change) throws Exception {
            try {
                closed.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new AssertionError(change + " did not disconnect the client", e);
            }
            open();
        }
    }

    private void acl(String command, String rolename, String acltype, String topic, boolean allow) {
        ObjectNode call = dynsec.command(command).put("rolename", rolename).put("acltype", acltype).put("topic", topic);
        if (command.equals("addRoleACL")) {
            call.put("allow", allow);
        }
        dynsec.call(call);
    }

    /**
     * plugin_opt_kick minimal (the default of docker/mosquitto/dynsec/kick-minimal.patch): only what can take a subscription
     * away disconnects, as publishing and receiving are checked on every message.
     */
    @Test
    void onlyWhatCanTakeASubscriptionAwayDisconnects() throws Exception {
        role("kick-role", true, "subscribePattern", "kick/#", true, 0, "publishClientSend", "kick/#", true, 0);
        group("kick-group", "kick-role", 0);
        role("kick-allow", true, "subscribeLiteral", "kick/other", true, 0);
        client("kicked");
        clientRole("kicked", "kick-role", 0);
        clientGroup("kicked", "kick-group", 0);
        // a role of its own, so that the changes below never disconnect it
        role("kick-send", true, "publishClientSend", "kick/#", true, 0);
        client("kick-sender");
        clientRole("kick-sender", "kick-send", 0);
        // the agent tells the UI, which says before saving who gets disconnected
        given().get("/api/access").then().body("kick", equalTo("MINIMAL"));
        Watched watched = new Watched("kicked");
        MqttClient sender = connect("kick-sender", "kick-sender-id", new LinkedBlockingQueue<>());
        assertTrue(granted(watched.client, "kick/x"));

        // what grants more, or is checked on every message, keeps the connection
        acl("addRoleACL", "kick-role", "publishClientSend", "kick/send", true);
        watched.kept("A rule for publishing");
        acl("addRoleACL", "kick-role", "subscribeLiteral", "kick/literal", true);
        watched.kept("A rule allowing to subscribe");
        acl("removeRoleACL", "kick-role", "subscribeLiteral", "kick/literal", true);
        watched.disconnected("Taking away a rule that allowed subscribing");
        acl("addRoleACL", "kick-role", "subscribeLiteral", "kick/denied", false);
        watched.disconnected("A rule forbidding to subscribe");
        acl("removeRoleACL", "kick-role", "subscribeLiteral", "kick/denied", false);
        watched.kept("Taking away a rule that forbade subscribing");
        dynsec.call(dynsec.command("modifyClient").put("username", "kicked").put("textname", "Kicked").put("textdescription", "only text"));
        watched.kept("A new name of the client");
        dynsec.call(dynsec.command("modifyGroup").put("groupname", "kick-group").put("textdescription", "only text"));
        watched.kept("A new description of the group");
        dynsec.call(dynsec.command("addGroupRole").put("groupname", "kick-group").put("rolename", "kick-allow").put("priority", 0));
        watched.kept("A role that only allows");
        dynsec.call(dynsec.command("removeGroupRole").put("groupname", "kick-group").put("rolename", "kick-allow"));
        watched.disconnected("Taking away a role that allowed subscribing");

        // receiving is checked on every message: a new rule forbidding it holds at once, without a new connection
        CompletableFuture<Integer> subscribed = new CompletableFuture<>();
        watched.client.subscribeCompletionHandler(ack -> subscribed.complete(ack.grantedQoSLevels().getFirst()));
        done(watched.client.subscribe("kick/x", MqttQoS.AT_MOST_ONCE.value()));
        assertTrue(subscribed.get(10, TimeUnit.SECONDS) != DENIED);
        done(sender.publish("kick/x", Buffer.buffer("1"), MqttQoS.AT_MOST_ONCE, false, false));
        assertEquals("kick/x", watched.messages.poll(10, TimeUnit.SECONDS));
        acl("addRoleACL", "kick-role", "publishClientReceive", "kick/#", false);
        watched.kept("A rule forbidding to receive");
        done(sender.publish("kick/x", Buffer.buffer("2"), MqttQoS.AT_MOST_ONCE, false, false));
        assertEquals(null, watched.messages.poll(2, TimeUnit.SECONDS), "The rule forbidding to receive did not hold for the subscription");

        // wildcards: allowing them grants, forbidding them takes away
        dynsec.call(dynsec.command("modifyRole").put("rolename", "kick-role").put("allowwildcardsubs", false));
        watched.disconnected("Switching wildcards off");
        dynsec.call(dynsec.command("modifyRole").put("rolename", "kick-role").put("allowwildcardsubs", true));
        watched.kept("Switching wildcards on");

        // a modifyClient without any change disconnects on purpose (the button Disconnect)
        dynsec.call(dynsec.command("modifyClient").put("username", "kicked"));
        watched.disconnected("A modifyClient without a change");
        dynsec.call(dynsec.command("setClientPassword").put("username", "kicked").put("password", PASSWORD));
        watched.disconnected("A new password");
        done(sender.disconnect());
        done(watched.client.disconnect());
    }

    /** All rules of a role at once (the text mode of the rules): only a change of the rules for subscribing that can take something away disconnects. */
    @Test
    void allRulesAtOnceDisconnectOnlyWhenTheyCanTakeASubscriptionAway() throws Exception {
        role("set-kick", true, "subscribePattern", "set/#", true, 0, "publishClientSend", "set/#", true, 0);
        client("set-kicked");
        clientRole("set-kicked", "set-kick", 0);
        Watched watched = new Watched("set-kicked");

        setAcls("set-kick", rule("subscribePattern", "set/#", 0, true), rule("publishClientSend", "set/#", 0, true));
        watched.kept("The same rules again");
        setAcls("set-kick", rule("subscribePattern", "set/#", 0, true), rule("publishClientReceive", "set/#", 0, false));
        watched.kept("Other rules for publishing and receiving");
        setAcls("set-kick", rule("subscribePattern", "set/#", 0, true), rule("publishClientReceive", "set/#", 0, false),
                rule("subscribeLiteral", "set/new", 0, true), rule("subscribeLiteral", "set/old", 0, false));
        watched.disconnected("A new rule forbidding to subscribe");
        setAcls("set-kick", rule("subscribePattern", "set/#", 0, true), rule("subscribeLiteral", "set/new", 0, true));
        watched.kept("Taking away a rule that forbade subscribing");
        setAcls("set-kick", rule("subscribePattern", "set/#", 5, true), rule("subscribeLiteral", "set/new", 0, true));
        watched.disconnected("Another priority of a rule allowing to subscribe");
        setAcls("set-kick", rule("subscribeLiteral", "set/new", 0, true));
        watched.disconnected("Taking away a rule that allowed subscribing");
        done(watched.client.disconnect());
    }

    private void setAcls(String rolename, Map<?, ?>... acls) {
        json().body(Map.of("acls", List.of(acls))).put("/api/roles/" + rolename + "/acls").then().statusCode(200);
    }

    private static Map<String, Object> rule(String acltype, String topic, int priority, boolean allow) {
        return Map.of("acltype", acltype, "topic", topic, "priority", priority, "allow", allow);
    }

    /**
     * The agent tells whether the dynamic security plugin has kick-minimal.patch, by a text only the patch puts into it: the one of this image
     * has it; a Mosquitto of its own (here a file without that text) disconnects on every change, so the UI must not assume minimal.
     */
    @Test
    void theAgentTellsAPluginWithoutThePatch() throws Exception {
        MosquittoResource.broker.execInContainer("sh", "-c", "printf 'no patch in here' > /tmp/mosquitto_dynamic_security.so"
                + " && printf 'plugin /usr/lib/mosquitto_dynamic_security.so\\nplugin_opt_kick minimal\\n' > /tmp/patched.conf"
                + " && printf 'plugin /tmp/mosquitto_dynamic_security.so\\nplugin_opt_kick minimal\\n' > /tmp/unpatched.conf"
                + " && printf 'global_plugin /tmp/mosquitto_dynamic_security.so\\n' > /tmp/global.conf"
                + " && printf 'plugin /usr/lib/mosquitto_dynamic_security.so\\nplugin_opt_kick always\\n' > /tmp/always.conf");
        assertEquals("plugin_opt_kick minimal", agentKick("/tmp/patched.conf"));
        assertEquals("unpatched", agentKick("/tmp/unpatched.conf"));
        assertEquals("unpatched", agentKick("/tmp/global.conf"));
        assertEquals("plugin_opt_kick always", agentKick("/tmp/always.conf"));
    }

    private static String agentKick(String conf) throws Exception {
        var result = MosquittoResource.broker.execInContainer("sh", "/mosquitto/agent.sh", "--dynsec-kick", conf);
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout().strip();
    }

    /** Saving a client sends its whole list of groups: their priorities, which the pages cannot set, must not get lost. */
    @Test
    void savingAClientKeepsThePrioritiesOfItsGroups() {
        role("keep-role", true);
        group("keep-group-a");
        group("keep-group-b");
        client("keep-prio");
        clientGroup("keep-prio", "keep-group-a", 7);
        clientGroup("keep-prio", "keep-group-b", 2);
        json().body(Map.of("roles", List.of("keep-role"), "groups", List.of("keep-group-a", "keep-group-b"))).put("/api/clients/keep-prio").then().statusCode(204);
        json().body(Map.of("roles", List.of("keep-role"), "groups", List.of("keep-group-a"))).put("/api/clients/keep-prio").then().statusCode(204);
        ObjectNode groups = (ObjectNode) dynsec.call(dynsec.command("getClient").put("username", "keep-prio")).path("client");
        assertEquals(List.of("keep-group-a"), groups.path("groups").findValuesAsText("groupname"));
        assertEquals(7, groups.path("groups").get(0).path("priority").asInt());
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
