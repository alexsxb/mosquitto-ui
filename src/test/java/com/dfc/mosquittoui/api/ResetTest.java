package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
@TestMethodOrder(OrderAnnotation.class)
class ResetTest {

    private static ValidatableResponse reset(Map<String, Object> parts) {
        return given().contentType(ContentType.JSON).body(parts).post("/api/config/reset").then().log().ifValidationFails().statusCode(200).body("error", nullValue());
    }

    private static void awaitBroker() {
        await().atMost(Duration.ofSeconds(20)).until(() -> given().get("/api/broker/status").jsonPath().getBoolean("connected"));
    }

    @Test
    @Order(1)
    void onlyWhatIsChosen() throws Exception {
        await().until(() -> given().get("/api/config").jsonPath().getBoolean("available"));
        String before = given().get("/api/config").jsonPath().getString("text");
        given().contentType(ContentType.JSON).body(Map.of("text", "sys_interval 44\n")).put("/api/config").then().statusCode(200);
        given().contentType(ContentType.JSON).body(Map.of("name", "doomed", "address", "other:1883", "extraOptions", List.of(), "topics", List.of(Map.of("pattern", "t/#", "direction", "out", "qos", 0))))
                .post("/api/bridges").then().statusCode(204);
        given().contentType(ContentType.JSON).body(Map.of("rolename", "reset-role", "allowwildcardsubs", true)).post("/api/roles").then().statusCode(204);
        given().contentType(ContentType.JSON).body(Map.of("groupname", "reset-group", "roles", List.of("reset-role"))).post("/api/groups").then().statusCode(204);
        given().contentType(ContentType.JSON).body(Map.of("username", "reset-client", "password", "reset-password", "roles", List.of("reset-role", "client"), "groups", List.of("reset-group")))
                .post("/api/clients").then().statusCode(204);

        reset(Map.of()).body("done", hasSize(0));
        given().get("/api/bridges").then().body("bridges", hasSize(1));

        reset(Map.of("bridges", true)).body("done", equalTo(List.of("bridges")));
        given().get("/api/bridges").then().body("bridges", hasSize(0));
        given().get("/api/config").then().body("text", containsString("sys_interval 44"));

        reset(Map.of("config", true)).body("done", equalTo(List.of("config")));
        given().get("/api/config").then().body("text", equalTo(""));
        given().get("/api/clients").then().body("username", hasItem("reset-client"));
        given().contentType(ContentType.JSON).body(Map.of("text", before)).put("/api/config").then().statusCode(200);
    }

    @Test
    @Order(2)
    void clientsAndWhatTheyUse() throws Exception {
        reset(Map.of("access", true)).body("done", equalTo(List.of("access")));
        awaitBroker();

        given().get("/api/clients").then().body("username", equalTo(List.of("admin")));
        given().get("/api/groups").then().body("groupname", equalTo(List.of("unauthenticated")));
        given().get("/api/roles").then()
                .body("rolename", hasItems("client", "super-admin", "dynsec-admin"))
                .body("rolename", not(hasItem("reset-role")));
        given().get("/api/groups").then().body("find { it.groupname == 'unauthenticated' }.roles", hasSize(0));

        // the application reconnected and can still do its work
        given().contentType(ContentType.JSON).body(Map.of("rolename", "after-reset", "allowwildcardsubs", true)).post("/api/roles").then().statusCode(204);
        given().delete("/api/roles/after-reset").then().statusCode(204);
        var denied = MosquittoResource.broker.execInContainer("mosquitto_pub", "-u", "reset-client", "-P", "reset-password", "-t", "t", "-m", "x");
        assertTrue(denied.getExitCode() != 0, "the client is gone: " + denied);
        assertEquals(0, MosquittoResource.broker.execInContainer("test", "-e", "/mosquitto/data/dynamic-security.json.pre-import").getExitCode(), "the old state stays");
    }
}
