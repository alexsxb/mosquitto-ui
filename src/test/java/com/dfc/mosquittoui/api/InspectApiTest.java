package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;

import com.dfc.mosquittoui.broker.TraceBatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The pages Sessions and Trace: the inspect plugin in the broker of the tests, asked by the UI. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class InspectApiTest {

    private static final String PASSWORD = "inspect-device-password";

    /** How the batches of the plugin reach InspectService. */
    @Inject
    Event<TraceBatch> batches;

    @BeforeEach
    void clients() {
        given().contentType(ContentType.JSON).body(Map.of("username", "inspect-dev", "password", PASSWORD, "roles", List.of("client"), "groups", List.of()))
                .post("/api/clients").then().statusCode(204);
        // may do nothing at all
        given().contentType(ContentType.JSON).body(Map.of("username", "inspect-none", "password", PASSWORD, "roles", List.of(), "groups", List.of()))
                .post("/api/clients").then().statusCode(204);
    }

    @AfterEach
    void cleanUp() {
        given().delete("/api/clients/inspect-dev");
        given().delete("/api/clients/inspect-none");
    }

    private static void inBroker(String command) throws Exception {
        MosquittoResource.broker.execInContainer("sh", "-c", command);
    }

    @Test
    void theSessionsShowTheirSubscriptionsAndMessages() throws Exception {
        given().get("/api/inspect/status").then().statusCode(200).body("available", equalTo(true)).body("reason", nullValue());
        inBroker("mosquitto_sub -V 5 -u inspect-dev -P " + PASSWORD + " -i inspect-sub -t 'inspect/#' -t 'other/+' -q 1 -W 20 > /dev/null 2>&1 &");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().get("/api/inspect/sessions").then().statusCode(200)
                .body("find { it.clientid == 'inspect-sub' }.connected", equalTo(true))
                .body("find { it.clientid == 'inspect-sub' }.username", equalTo("inspect-dev"))
                .body("find { it.clientid == 'inspect-sub' }.protocol", equalTo("5"))
                .body("find { it.clientid == 'inspect-sub' }.kind", equalTo("dynsec"))
                .body("find { it.clientid == 'inspect-sub' }.subscriptionCount", equalTo(2))
                .body("find { it.clientid == 'inspect-sub' }.subscriptions.topic", contains("inspect/#", "other/+"))
                .body("find { it.clientid == 'inspect-sub' }.subscriptions.qos", contains(1, 1)));
        inBroker("mosquitto_pub -u inspect-dev -P " + PASSWORD + " -i inspect-pub-1 -t inspect/a -m twelve-bytes");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().get("/api/inspect/sessions").then()
                .body("find { it.clientid == 'inspect-sub' }.messagesOut", equalTo(1))
                .body("find { it.clientid == 'inspect-sub' }.bytesOut", equalTo(12)));
        // the UI itself is one of them
        given().get("/api/inspect/sessions").then().body("kind", hasItem("ui"));
    }

    @Test
    void aTraceRecordsWhatClientsDoAndWhatTheBrokerRefused() throws Exception {
        String id = given().contentType(ContentType.JSON).body(Map.of("clientid", "inspect-*", "seconds", 60, "maxEvents", 100, "payloadBytes", 4))
                .post("/api/inspect/traces").then().statusCode(200).body("active", equalTo(true)).body("startedBy", equalTo("alice"))
                .extract().path("id");
        inBroker("mosquitto_sub -u inspect-dev -P " + PASSWORD + " -i inspect-trace-sub -t 'trace/#' -W 15 > /dev/null 2>&1 &");
        Thread.sleep(1000);
        inBroker("mosquitto_pub -u inspect-dev -P " + PASSWORD + " -i inspect-trace-pub -t trace/a -m hello");
        inBroker("mosquitto_pub -u inspect-none -P " + PASSWORD + " -i inspect-trace-none -t trace/b -m no");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().get("/api/inspect/traces/" + id).then().statusCode(200)
                .body("events.type", hasItems("connect", "subscribe", "publish", "deliver", "publishDenied", "disconnect"))
                .body("events.find { it.type == 'subscribe' }.topic", equalTo("trace/#"))
                .body("events.find { it.type == 'publish' }.clientid", equalTo("inspect-trace-pub"))
                // "hell", cut at 4 bytes
                .body("events.find { it.type == 'publish' }.payload", equalTo("aGVsbA=="))
                .body("events.find { it.type == 'publish' }.truncated", equalTo(true))
                .body("events.find { it.type == 'publish' }.size", equalTo(5))
                .body("events.find { it.type == 'deliver' }.clientid", equalTo("inspect-trace-sub"))
                .body("events.find { it.type == 'publishDenied' }.clientid", equalTo("inspect-trace-none"))
                .body("events.find { it.type == 'publishDenied' }.topic", equalTo("trace/b")));
        int total = given().get("/api/inspect/traces/" + id).then().extract().path("total");
        given().get("/api/inspect/traces/" + id + "?after=" + total).then().body("events", org.hamcrest.Matchers.empty());
        given().post("/api/inspect/traces/" + id + "/stop").then().statusCode(200).body("active", equalTo(false)).body("end", equalTo("stopped"));
        given().get("/api/inspect/traces").then().body("find { it.id == '" + id + "' }.end", equalTo("stopped"));
        given().delete("/api/inspect/traces/" + id).then().statusCode(204);
        given().get("/api/inspect/traces/" + id).then().statusCode(404);
    }

    /** The broker only says no; the trace adds why, from the rules as they are. */
    @Test
    void aRefusedSubscriptionSaysWhy() throws Exception {
        given().contentType(ContentType.JSON).body(Map.of("rolename", "inspect-no-wildcards", "allowwildcardsubs", false, "acls",
                List.of(Map.of("acltype", "subscribePattern", "topic", "wc/#", "priority", 0, "allow", true)))).post("/api/roles").then().statusCode(204);
        given().contentType(ContentType.JSON).body(Map.of("username", "inspect-wc", "password", PASSWORD, "roles", List.of("inspect-no-wildcards"), "groups", List.of()))
                .post("/api/clients").then().statusCode(204);
        try {
            String id = given().contentType(ContentType.JSON).body(Map.of("clientid", "inspect-wc-*", "seconds", 60, "maxEvents", 100, "payloadBytes", 0))
                    .post("/api/inspect/traces").then().statusCode(200).extract().path("id");
            inBroker("mosquitto_sub -V 5 -u inspect-wc -P " + PASSWORD + " -i inspect-wc-sub -t 'wc/#' -W 3 > /dev/null 2>&1 || true");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().get("/api/inspect/traces/" + id).then().statusCode(200)
                    .body("events.find { it.type == 'subscribeDenied' }.topic", equalTo("wc/#"))
                    .body("events.find { it.type == 'subscribeDenied' }.username", equalTo("inspect-wc"))
                    .body("events.find { it.type == 'subscribeDenied' }.denial.allowed", equalTo(false))
                    .body("events.find { it.type == 'subscribeDenied' }.denial.reason", equalTo("WILDCARDS"))
                    .body("events.find { it.type == 'subscribeDenied' }.denial.role", equalTo("inspect-no-wildcards")));
            given().post("/api/inspect/traces/" + id + "/stop").then().statusCode(200);
            given().delete("/api/inspect/traces/" + id).then().statusCode(204);
        } finally {
            given().delete("/api/clients/inspect-wc");
            given().delete("/api/roles/inspect-no-wildcards");
        }
    }

    /** An inspect plugin before 1.6.1 says neither the username nor that there is none: then no reason, rather than one for a client without a login. */
    @Test
    void noReasonWithoutKnowingWhoAsked() throws Exception {
        String id = given().contentType(ContentType.JSON).body(Map.of("clientid", "inspect-who-*", "seconds", 60, "maxEvents", 100, "payloadBytes", 0))
                .post("/api/inspect/traces").then().statusCode(200).extract().path("id");
        try {
            ObjectMapper mapper = new ObjectMapper();
            batches.fire(new TraceBatch(mapper.readTree("""
                    {"id": "%s", "events": [
                      {"at": 1, "type": "subscribeDenied", "clientid": "inspect-who-old", "topic": "a/#", "qos": 0},
                      {"at": 2, "type": "subscribeDenied", "clientid": "inspect-who-anon", "topic": "a/#", "qos": 0, "anonymous": true}
                    ]}""".formatted(id))));
            given().get("/api/inspect/traces/" + id).then().statusCode(200)
                    .body("events.find { it.clientid == 'inspect-who-old' }.denial", nullValue())
                    .body("events.find { it.clientid == 'inspect-who-anon' }.anonymous", equalTo(true))
                    .body("events.find { it.clientid == 'inspect-who-anon' }.denial.access", equalTo("subscribe"));
        } finally {
            given().post("/api/inspect/traces/" + id + "/stop");
            given().delete("/api/inspect/traces/" + id);
        }
    }

    @Test
    void aTraceNeedsAClientOrATopic() {
        given().contentType(ContentType.JSON).body(Map.of("seconds", 60, "maxEvents", 100, "payloadBytes", 0)).post("/api/inspect/traces").then()
                .statusCode(400).body("field", equalTo("clientid"));
        given().contentType(ContentType.JSON).body(Map.of("clientid", "a*b", "seconds", 60, "maxEvents", 100, "payloadBytes", 0)).post("/api/inspect/traces")
                .then().statusCode(400).body("field", equalTo("clientid"));
        given().contentType(ContentType.JSON).body(Map.of("topic", "a/#", "seconds", 7200, "maxEvents", 100, "payloadBytes", 0)).post("/api/inspect/traces")
                .then().statusCode(400).body("field", equalTo("seconds"));
        given().contentType(ContentType.JSON).body(Map.of("topic", "a/#/b", "seconds", 60, "maxEvents", 100, "payloadBytes", 0)).post("/api/inspect/traces")
                .then().statusCode(400);
    }
}
