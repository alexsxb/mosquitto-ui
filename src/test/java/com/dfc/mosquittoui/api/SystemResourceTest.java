package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
class SystemResourceTest {

    @Test
    @TestSecurity(user = "alice", roles = "mosquitto-admin")
    void info() {
        given().when().get("/api/system/info").then()
                .statusCode(200)
                .body("name", equalTo("mosquitto-ui"))
                .body("version", notNullValue());
    }
}
