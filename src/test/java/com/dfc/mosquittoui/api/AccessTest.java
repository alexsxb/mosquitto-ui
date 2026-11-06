package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
class AccessTest {

    /** 499 as the frontend knows it: no session, show the start page (LocalAuthMechanism) */
    @Test
    void anonymousGetsNothing() {
        given().get("/api/clients").then().statusCode(499);
        given().get("/api/auth/me").then().statusCode(499);
        given().get("/q/openapi").then().statusCode(499);
    }

    @Test
    void healthIsPublic() {
        given().get("/q/health/live").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "bob")
    void withoutTheRoleOnlyTheOwnAccount() {
        given().get("/api/clients").then().statusCode(403);
        given().get("/api/auth/me").then().statusCode(200)
                .body("username", equalTo("bob"))
                .body("authorized", equalTo(false));
    }

    @Test
    @TestSecurity(user = "alice", roles = "mosquitto-admin")
    void withTheRole() {
        given().get("/api/auth/me").then().statusCode(200).body("authorized", equalTo(true));
    }
}
