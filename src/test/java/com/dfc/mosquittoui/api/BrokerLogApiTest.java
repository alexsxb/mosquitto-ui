package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import com.dfc.mosquittoui.logs.BrokerLogService;
import com.dfc.mosquittoui.logs.BrokerLogService.LogBatch;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** The page Logs: the broker of the tests publishes its log (log_dest topic), the UI keeps the lines. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class BrokerLogApiTest {

    @Inject
    BrokerLogService logs;

    @Test
    void theLinesOfTheBrokerArriveWithoutTheirTimeStamp() throws Exception {
        JsonPath before = given().get("/api/logs").then().statusCode(200).body("readable", equalTo(true)).extract().jsonPath();
        String run = before.getString("run");
        long last = before.getLong("last");

        MosquittoResource.broker.execInContainer("mosquitto_pub", "-i", "logs-probe", "-u", "nobody", "-P", "wrong", "-t", "a", "-m", "x");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> given().queryParam("run", run).queryParam("after", last).get("/api/logs").then()
                .body("lines.text", hasItem(org.hamcrest.Matchers.containsString("logs-probe"))));

        given().queryParam("run", run).queryParam("after", last).get("/api/logs").then()
                .body("run", equalTo(run))
                .body("lines.id", everyItem(greaterThan((int) last)))
                .body("lines.level", hasItem("notice"))
                // the broker writes "1793723172: New connection ...", the time is kept on its own
                .body("lines.text", everyItem(not(startsWith("1"))));
        // another run: all of them
        given().queryParam("run", "other").queryParam("after", last).get("/api/logs").then()
                .body("lines.size()", greaterThan(0)).body("lines[0].id", equalTo(1));
    }

    @Test
    void theStreamSendsAllKeptLinesThenEachNewOne() throws Exception {
        List<LogBatch> got = new CopyOnWriteArrayList<>();
        var subscription = logs.stream().subscribe().with(got::add);
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> !got.isEmpty());
            assertTrue(got.getFirst().reset());
            int kept = got.stream().filter(LogBatch::reset).mapToInt(b -> b.lines().size()).sum();
            MosquittoResource.broker.execInContainer("mosquitto_pub", "-i", "logs-stream", "-u", "nobody", "-P", "wrong", "-t", "a", "-m", "x");
            await().atMost(Duration.ofSeconds(10)).until(() -> got.stream().filter(b -> !b.reset())
                    .anyMatch(b -> b.lines().stream().anyMatch(line -> line.text().contains("logs-stream"))));
            // only one batch with reset: the new lines come on their own
            assertEquals(1, got.stream().filter(LogBatch::reset).count(), "kept " + kept);
            List<Long> ids = got.stream().flatMap(b -> b.lines().stream()).map(BrokerLogService.LogLine::id).toList();
            assertEquals(ids.stream().distinct().sorted().toList(), ids);
        } finally {
            subscription.cancel();
        }
    }
}
