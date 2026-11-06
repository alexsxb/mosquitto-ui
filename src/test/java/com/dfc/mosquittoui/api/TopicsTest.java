package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.api.Dtos.AccessDefaults;
import com.dfc.mosquittoui.broker.DynsecService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;

@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestProfile(TopicsTest.ShortIdle.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class TopicsTest {

    @Inject
    DynsecService dynsec;

    public static class ShortIdle implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("mosquitto-ui.topics.idle-stop", "1s");
        }
    }

    @TestHTTPResource("/api/topics/stream")
    URL stream;

    private final Map<String, JsonNode> seen = new ConcurrentHashMap<>();
    private final AtomicBoolean connected = new AtomicBoolean();

    private static ExecResult publish(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 5];
        System.arraycopy(new String[] {"mosquitto_pub", "-u", "topics-device", "-P", "topics-password"}, 0, command, 0, 5);
        System.arraycopy(arguments, 0, command, 5, arguments.length);
        return MosquittoResource.broker.execInContainer(command);
    }

    private void read(InputStream in) {
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    JsonNode batch = mapper.readTree(line.substring(5));
                    connected.set(batch.path("connected").asBoolean());
                    batch.path("updates").forEach(update -> seen.put(update.path("topic").asText(), update));
                }
            }
        } catch (java.io.IOException e) {
            // the stream ended
        }
    }

    private void awaitValue(String topic, String value) {
        await().atMost(Duration.ofSeconds(10)).until(() -> seen.containsKey(topic) && value.equals(seen.get(topic).path("value").asText()));
    }

    @Test
    void worksWhenReceivingIsNotAllowedByDefault() throws Exception {
        AccessDefaults before = dynsec.access().defaults();
        given().contentType(ContentType.JSON).body(Map.of("username", "topics-device", "password", "topics-password", "roles", List.of("client"), "groups", List.of())).post("/api/clients");
        assertEquals(0, publish("-r", "-t", "topics/denied/one", "-m", "seen").getExitCode());
        dynsec.setDefaultAccess(new AccessDefaults(false, false, false, false));
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(stream.toURI()).header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
        Thread reader = new Thread(() -> read(response.body()));
        reader.start();
        try {
            awaitValue("topics/denied/one", "seen");
            assertEquals(1, given().contentType(ContentType.JSON).body(Map.of("topic", "topics/denied")).post("/api/topics/count-retained").jsonPath().getInt("count"));
        } finally {
            dynsec.setDefaultAccess(before);
            response.body().close();
            reader.interrupt();
            publish("-r", "-t", "topics/denied/one", "-n");
            given().delete("/api/clients/topics-device");
            // the next test wants to see retained messages come in fresh
            await().atMost(Duration.ofSeconds(10)).until(() -> given().get("/api/clients/" + DynsecService.MONITOR).statusCode() == 404);
        }
    }

    @Test
    void whatGoesThroughTheBrokerAndNothingBack() throws Exception {
        given().contentType(ContentType.JSON).body(Map.of("username", "topics-device", "password", "topics-password", "roles", List.of("client"), "groups", List.of()))
                .post("/api/clients").then().statusCode(204);
        assertEquals(0, publish("-r", "-t", "topics/retained/one", "-m", "{\"a\":1}").getExitCode());

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(stream.toURI()).header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        Thread reader = new Thread(() -> read(response.body()));
        reader.start();
        try {
            await().atMost(Duration.ofSeconds(10)).until(connected::get);

            awaitValue("topics/retained/one", "{\"a\":1}");
            assertTrue(seen.get("topics/retained/one").path("retained").asBoolean());

            publish("-t", "topics/live", "-m", "first", "-q", "1");
            awaitValue("topics/live", "first");
            assertFalse(seen.get("topics/live").path("retained").asBoolean());
            publish("-t", "topics/live", "-m", "second");
            awaitValue("topics/live", "second");
            assertEquals(2, seen.get("topics/live").path("count").asInt());

            await().atMost(Duration.ofSeconds(15)).until(() -> seen.keySet().stream().anyMatch(topic -> topic.startsWith("$SYS/")));

            publish("-r", "-t", "topics/retained/one", "-n");
            await().atMost(Duration.ofSeconds(10)).until(() -> seen.get("topics/retained/one").path("removed").asBoolean());

            MosquittoResource.broker.execInContainer("sh", "-c", "head -c 300 /dev/urandom > /tmp/binary; head -c 20000 /dev/zero | tr '\\0' 'x' > /tmp/long");
            publish("-t", "topics/binary", "-f", "/tmp/binary");
            await().atMost(Duration.ofSeconds(10)).until(() -> seen.containsKey("topics/binary"));
            assertTrue(seen.get("topics/binary").path("binary").asBoolean());
            assertEquals(300, seen.get("topics/binary").path("size").asInt());
            publish("-t", "topics/long", "-f", "/tmp/long");
            await().atMost(Duration.ofSeconds(10)).until(() -> seen.containsKey("topics/long"));
            assertTrue(seen.get("topics/long").path("cut").asBoolean());
            assertEquals(8192, seen.get("topics/long").path("value").asText().length());

            // publishing through the page, to the broker and back
            MosquittoResource.broker.execInContainer("sh", "-c", "mosquitto_sub -u topics-device -P topics-password -t topics/sent -C 1 -W 6 -v > /tmp/sent &");
            Thread.sleep(1000);
            given().contentType(ContentType.JSON).body(Map.of("topic", "topics/sent", "value", "{\"on\":true}", "qos", 1, "retain", true)).post("/api/topics/publish").then().statusCode(204);
            awaitValue("topics/sent", "{\"on\":true}");
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertTrue(MosquittoResource.broker.execInContainer("cat", "/tmp/sent").getStdout().contains("topics/sent {\"on\":true}")));

            for (String refused : List.of("$CONTROL/dynamic-security/v1", "a/#", "a/+/b", "")) {
                given().contentType(ContentType.JSON).body(Map.of("topic", refused, "value", "x", "qos", 0, "retain", false)).post("/api/topics/publish").then().statusCode(400);
            }

            // a whole branch of retained messages, and only that
            publish("-r", "-t", "topics/tree/a", "-m", "1");
            publish("-r", "-t", "topics/tree/b/c", "-m", "2");
            publish("-t", "topics/tree/live", "-m", "3");
            publish("-r", "-t", "topics/treeish", "-m", "4");
            awaitValue("topics/treeish", "4");
            awaitValue("topics/tree/live", "3");
            given().contentType(ContentType.JSON).body(Map.of("topic", "topics/tree")).post("/api/topics/count-retained").then().statusCode(200).body("count", equalTo(2));
            await().atMost(Duration.ofSeconds(5)).until(() -> seen.get("topics/tree/a").path("retained").asBoolean());
            given().get("/api/topics/retained").then().statusCode(200)
                    .body("$", hasItems("topics/tree/a", "topics/tree/b/c", "topics/treeish", "$SYS/broker/version"))
                    .body("$", not(hasItem("topics/tree/live")));
            // the filter of the page has to find what the dashboard counts; the broker publishes that number now and then
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertEquals(given().get("/api/topics/retained").jsonPath().getList("$").size(),
                    given().get("/api/broker/stats").jsonPath().getInt("retained")));
            given().contentType(ContentType.JSON).body(Map.of("topic", "topics/tree")).post("/api/topics/clear-retained").then().statusCode(200).body("count", equalTo(2));
            await().atMost(Duration.ofSeconds(5)).until(() -> seen.get("topics/tree/b/c").path("removed").asBoolean() && seen.get("topics/tree/a").path("removed").asBoolean());
            ExecResult left = MosquittoResource.broker.execInContainer("mosquitto_sub", "-u", "topics-device", "-P", "topics-password", "-t", "topics/#", "-v", "-W", "2", "--retained-only");
            assertFalse(left.getStdout().contains("topics/tree/"), left.toString());
            assertTrue(left.getStdout().contains("topics/treeish 4"), left.toString());
            given().contentType(ContentType.JSON).body(Map.of("topic", "$SYS/broker")).post("/api/topics/clear-retained").then().statusCode(400);

            given().get("/api/clients").then()
                    .body("find { it.username == 'mosquitto-ui-monitor' }.locked", equalTo(true));
            given().delete("/api/clients/mosquitto-ui-monitor").then().statusCode(409);
        } finally {
            response.body().close();
            http.close();
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> given().get("/api/clients").then().body("username", not(hasItem("mosquitto-ui-monitor"))));
        given().get("/api/roles").then().body("rolename", not(hasItem("mosquitto-ui-monitor")));
        publish("-r", "-t", "topics/sent", "-n");
        publish("-r", "-t", "topics/treeish", "-n");
        given().delete("/api/clients/topics-device").then().statusCode(204);
        given().contentType(ContentType.JSON).body(Map.of("topic", "t", "value", "x", "qos", 0, "retain", false)).post("/api/topics/publish").then().statusCode(503);
    }
}
