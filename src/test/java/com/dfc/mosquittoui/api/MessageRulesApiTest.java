package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The page Message rules: the UI writes the rules, the inspect plugin of the broker of the tests refuses what does not hold. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class MessageRulesApiTest {

    private static final String PASSWORD = "rules-device-password";

    private static Map<String, Object> rules() {
        return Map.of(
                "content", List.of(Map.of("id", "values", "name", "Values", "topic", "rules/#", "json", true, "fields",
                        List.of(Map.of("path", "v", "type", "number")), "action", "drop", "alert", false, "enabled", true),
                        // off: never written for the broker
                        Map.of("id", "off", "topic", "#", "json", false, "maxBytes", 1, "fields", List.of(), "action", "drop", "alert", false, "enabled", false)),
                "rate", List.of(Map.of("id", "fast", "clients", "rules-fast*", "perSecond", 1, "burst", 2, "action", "drop", "alert", false, "enabled", true)));
    }

    @BeforeEach
    void client() {
        given().contentType(ContentType.JSON).body(Map.of("username", "rules-dev", "password", PASSWORD, "roles", List.of("client"), "groups", List.of()))
                .post("/api/clients").then().statusCode(204);
    }

    @AfterEach
    void cleanUp() {
        given().contentType(ContentType.JSON).body(Map.of("content", List.of(), "rate", List.of())).put("/api/rules").then().statusCode(200);
        given().delete("/api/clients/rules-dev");
    }

    private static String inBroker(String command) throws Exception {
        return MosquittoResource.broker.execInContainer("sh", "-c", command).getStdout();
    }

    private static long dropped(String id) {
        List<Map<String, Object>> stats = given().get("/api/rules/status").then().statusCode(200).extract().path("stats");
        return stats.stream().filter(s -> s.get("id").equals(id)).mapToLong(s -> ((Number) s.get("dropped")).longValue()).findFirst().orElse(-1);
    }

    @Test
    void theBrokerRefusesWhatDoesNotHold() throws Exception {
        given().contentType(ContentType.JSON).body(rules()).put("/api/rules").then().statusCode(200)
                .body("content.find { it.id == 'values' }.name", equalTo("Values"))
                .body("rate[0].clients", equalTo("rules-fast*"));
        Path file = ConfigProvider.getConfig().getValue("mosquitto-ui.shared-dir", Path.class).resolve("rules/rules.json");
        String written = Files.readString(file);
        assertEquals(true, written.contains("\"values\"") && !written.contains("\"off\"") && written.contains("\"exemptUsernames\""), written);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().get("/api/rules/status").then().body("available", equalTo(true))
                .body("loaded", equalTo(true)).body("stats.id", hasItem("values")).body("stats.id", not(hasItem("off"))));

        String trace = given().contentType(ContentType.JSON).body(Map.of("clientid", "rules-*", "seconds", 60, "maxEvents", 100, "payloadBytes", 64))
                .post("/api/inspect/traces").then().statusCode(200).extract().path("id");
        inBroker("rm -f /tmp/rules.txt; mosquitto_sub -u rules-dev -P " + PASSWORD + " -i rules-watch -t 'rules/#' -W 8 > /tmp/rules.txt 2>/dev/null &");
        Thread.sleep(1000);
        inBroker("mosquitto_pub -u rules-dev -P " + PASSWORD + " -i rules-pub -t rules/a -m '{\"v\": 1}'");
        inBroker("mosquitto_pub -u rules-dev -P " + PASSWORD + " -i rules-pub -t rules/b -m '{\"v\": \"one\"}'");
        inBroker("mosquitto_pub -u rules-dev -P " + PASSWORD + " -i rules-pub -t rules/c -m 'not json'");
        // two in the bucket, the third and fourth too many
        inBroker("printf '{\"v\":1}\\n{\"v\":2}\\n{\"v\":3}\\n{\"v\":4}\\n' | mosquitto_pub -u rules-dev -P " + PASSWORD + " -i rules-fast-1 -t rules/fast -V 5 -q 1 -l");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals(2, dropped("values"));
            assertEquals(2, dropped("fast"));
        });
        given().get("/api/rules/status").then().body("stats.find { it.id == 'values' }.lastReason", equalTo("not valid JSON"))
                .body("stats.find { it.id == 'values' }.lastClient", equalTo("rules-pub"));
        Thread.sleep(8000);
        assertEquals(List.of("{\"v\": 1}", "{\"v\":1}", "{\"v\":2}"), inBroker("cat /tmp/rules.txt").lines().toList());

        given().get("/api/inspect/traces/" + trace).then()
                .body("events.findAll { it.type == 'dropped' }.rule", hasItem("values"))
                .body("events.find { it.type == 'dropped' && it.topic == 'rules/b' }.reasonText", equalTo("v is not of type number"));
        given().post("/api/inspect/traces/" + trace + "/stop").then().statusCode(200);
    }

    @Test
    void rulesThatDoNotHoldAreRefused() {
        given().contentType(ContentType.JSON).body(Map.of("content", List.of(Map.of("topic", "a/#/b", "json", true, "fields", List.of(), "action", "drop",
                "alert", false, "enabled", true)), "rate", List.of())).put("/api/rules").then().statusCode(400).body("field", equalTo("topic"));
        given().contentType(ContentType.JSON).body(Map.of("content", List.of(Map.of("topic", "a/#", "json", false, "fields", List.of(), "action", "drop",
                "alert", false, "enabled", true)), "rate", List.of())).put("/api/rules").then().statusCode(400).body("field", equalTo("json"));
        given().contentType(ContentType.JSON).body(Map.of("content", List.of(Map.of("topic", "a/#", "json", true, "fields",
                List.of(Map.of("path", "x", "type", "date")), "action", "drop", "alert", false, "enabled", true)), "rate", List.of())).put("/api/rules").then()
                .statusCode(400).body("message", containsString("date"));
        given().contentType(ContentType.JSON).body(Map.of("content", List.of(), "rate", List.of(Map.of("clients", "a*b", "perSecond", 1, "action", "drop",
                "alert", false, "enabled", true)))).put("/api/rules").then().statusCode(400).body("field", equalTo("clients"));
        given().contentType(ContentType.JSON).body(Map.of("content", List.of(), "rate", List.of(Map.of("perSecond", 0, "action", "drop",
                "alert", false, "enabled", true)))).put("/api/rules").then().statusCode(400).body("field", equalTo("perSecond"));
    }
}
