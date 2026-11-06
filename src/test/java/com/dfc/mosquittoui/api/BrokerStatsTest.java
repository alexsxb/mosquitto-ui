package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/** The names of the values under $SYS are not documented for every version, so this asks the real broker. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class BrokerStatsTest {

    private static final Duration SYS_INTERVAL = Duration.ofSeconds(25);

    @Test
    void whatTheBrokerSaysAndItsHistory() {
        await().atMost(SYS_INTERVAL).untilAsserted(() -> given().get("/api/broker/stats").then().statusCode(200)
                .body("version", equalTo("2.1.2"))
                .body("uptimeSeconds", notNullValue())
                .body("connections", notNullValue())
                .body("subscriptions", notNullValue())
                .body("retained", notNullValue())
                .body("heapBytes", notNullValue())
                .body("messagesIn", notNullValue())
                .body("messagesOut", notNullValue())
                .body("bytesIn", notNullValue())
                .body("bytesOut", notNullValue()));

        await().atMost(SYS_INTERVAL).untilAsserted(() -> given().get("/api/broker/stats/history").then().statusCode(200).body("$", not(hasSize(0))));
        given().get("/api/broker/stats/history").then().body("[0].time", not(emptyOrNullString())).body("[0].heapBytes", notNullValue());
        Path file = ConfigProvider.getConfig().getValue("mosquitto-ui.state-dir", Path.class).resolve("broker-stats.json");
        assertTrue(Files.exists(file));
        given().queryParam("hours", 0).get("/api/broker/stats/history").then().body("$", hasSize(0));
    }
}
