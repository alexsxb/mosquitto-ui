package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class DynsecApiTest {

    private static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }

    @BeforeEach
    void connected() {
        await().until(() -> given().get("/api/broker/status").jsonPath().getBoolean("connected"));
    }

    @Test
    void clientLifecycle() {
        json().body(Map.of("rolename", "readers", "textname", "Readers", "allowwildcardsubs", true)).post("/api/roles").then().statusCode(204);
        json().body(Map.of("groupname", "sensors", "roles", List.of())).post("/api/groups").then().statusCode(204);
        json().body(Map.of("username", "sensor1", "password", "secret", "textname", "Sensor", "roles", List.of("readers"), "groups", List.of("sensors")))
                .post("/api/clients").then().statusCode(204);

        given().get("/api/clients").then().statusCode(200).body("username", hasItem("sensor1"));
        given().get("/api/clients/sensor1").then().statusCode(200)
                .body("textname", equalTo("Sensor"))
                .body("disabled", equalTo(false))
                .body("locked", equalTo(false))
                .body("roles", contains("readers"))
                .body("groups", contains("sensors"));
        given().get("/api/groups").then().body("find { it.groupname == 'sensors' }.clients", contains("sensor1"));

        json().body(Map.of("textname", "Renamed", "textdescription", "", "roles", List.of(), "groups", List.of())).put("/api/clients/sensor1").then().statusCode(204);
        given().get("/api/clients/sensor1").then()
                .body("textname", equalTo("Renamed"))
                .body("roles.size()", equalTo(0))
                .body("groups.size()", equalTo(0));

        json().body(Map.of("enabled", false)).put("/api/clients/sensor1/enabled").then().statusCode(204);
        given().get("/api/clients/sensor1").then().body("disabled", equalTo(true));
        json().body(Map.of("enabled", true)).put("/api/clients/sensor1/enabled").then().statusCode(204);
        given().get("/api/clients/sensor1").then().body("disabled", equalTo(false));

        json().body(Map.of("password", "other")).put("/api/clients/sensor1/password").then().statusCode(204);

        given().delete("/api/clients/sensor1").then().statusCode(204);
        given().get("/api/clients").then().body("username", not(hasItem("sensor1")));
    }

    @Test
    void changesAreLoggedWithoutTheirSecrets() {
        String secret = "a-password-nobody-may-read";
        json().body(Map.of("username", "audited", "password", secret, "roles", List.of(), "groups", List.of())).post("/api/clients").then().statusCode(204);
        json().body(Map.of("password", secret)).put("/api/clients/audited/password").then().statusCode(204);
        json().body(Map.of("username", "audited", "password", secret, "roles", List.of(), "groups", List.of())).post("/api/clients").then().statusCode(409);
        json().body(Map.of("text", "sys_interval 10\n")).post("/api/config/check").then().statusCode(200);

        var entries = given().get("/api/audit").then().statusCode(200).extract().jsonPath();
        assertEquals("createClient", entries.getString("find { it.details.username == 'audited' }.action"), "the newest first, here the one that failed");
        assertEquals("alice", entries.getString("find { it.action == 'setClientPassword' }.user"));
        assertNotNull(entries.getString("find { it.action == 'createClient' && it.details.username == 'audited' }.error"), "what failed shows as failed");
        assertNull(entries.getString("find { it.action == 'setClientPassword' }.error"));
        assertFalse(entries.prettify().contains(secret));
        assertFalse(entries.getList("action").contains("checkConfig"), "a check changes nothing");
        given().delete("/api/clients/audited").then().statusCode(204);
    }

    @Test
    void membersCanBeChosenFromTheGroup() {
        json().body(Map.of("username", "member1", "password", "secret", "roles", List.of(), "groups", List.of())).post("/api/clients").then().statusCode(204);
        json().body(Map.of("username", "member2", "password", "secret", "roles", List.of(), "groups", List.of())).post("/api/clients").then().statusCode(204);
        json().body(Map.of("groupname", "crew", "roles", List.of(), "clients", List.of("member1"))).post("/api/groups").then().statusCode(204);
        given().get("/api/groups").then().body("find { it.groupname == 'crew' }.clients", contains("member1"));

        json().body(Map.of("textname", "", "textdescription", "", "roles", List.of(), "clients", List.of("member2"))).put("/api/groups/crew").then().statusCode(204);
        given().get("/api/groups").then().body("find { it.groupname == 'crew' }.clients", contains("member2"));
        given().get("/api/clients/member1").then().body("groups.size()", equalTo(0));

        // without the list, the members stay
        json().body(Map.of("textname", "Crew", "textdescription", "", "roles", List.of())).put("/api/groups/crew").then().statusCode(204);
        given().get("/api/groups").then().body("find { it.groupname == 'crew' }.clients", contains("member2"));

        // the client of this application stays out of it, and nothing changed on the way
        json().body(Map.of("textname", "", "textdescription", "", "roles", List.of(), "clients", List.of("admin"))).put("/api/groups/crew").then().statusCode(409);
        given().get("/api/groups").then().body("find { it.groupname == 'crew' }.clients", contains("member2"));
    }

    @Test
    void theLastConnectionStaysUntilTheClientGoes() throws Exception {
        String name = "last-connection";
        String path = "find { it.username == '" + name + "' }";
        given().contentType(ContentType.JSON).body(Map.of("username", name, "password", "a password of the client", "roles", List.of("client"), "groups", List.of()))
                .post("/api/clients").then().statusCode(204);
        given().get("/api/clients").then().body(path + ".lastLogin", nullValue());

        Thread subscriber = new Thread(() -> {
            try {
                MosquittoResource.broker.execInContainer("mosquitto_sub", "-u", name, "-P", "a password of the client", "-t", "t", "-W", "5");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        subscriber.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> given().get("/api/clients").jsonPath().get(path + ".lastLogin") != null);
        String connectedAt = given().get("/api/clients").jsonPath().getString(path + ".lastLogin.connectedAt");
        given().get("/api/clients").then().body(path + ".lastLogin.address", not(emptyOrNullString()));
        subscriber.join();

        await().atMost(Duration.ofSeconds(10)).until(() -> given().get("/api/clients").jsonPath().getList(path + ".connections").isEmpty());
        given().get("/api/clients").then().body(path + ".lastLogin.connectedAt", equalTo(connectedAt));
        Path file = ConfigProvider.getConfig().getValue("mosquitto-ui.state-dir", Path.class).resolve("client-logins.json");
        await().atMost(Duration.ofSeconds(5)).until(() -> Files.readString(file).contains(name));

        given().delete("/api/clients/" + name).then().statusCode(204);
        await().atMost(Duration.ofSeconds(5)).until(() -> !Files.readString(file).contains(name));
    }

    @Test
    void acls() {
        json().body(Map.of("rolename", "acl-role", "allowwildcardsubs", false)).post("/api/roles").then().statusCode(204);
        json().body(Map.of("acltype", "publishClientSend", "topic", "a/+/#", "priority", 5, "allow", true)).post("/api/roles/acl-role/acls").then().statusCode(204);
        given().get("/api/roles").then()
                .body("find { it.rolename == 'acl-role' }.allowwildcardsubs", equalTo(false))
                .body("find { it.rolename == 'acl-role' }.acls[0].topic", equalTo("a/+/#"))
                .body("find { it.rolename == 'acl-role' }.acls[0].priority", equalTo(5));

        given().queryParam("acltype", "publishClientSend").queryParam("topic", "a/+/#").delete("/api/roles/acl-role/acls").then().statusCode(204);
        given().get("/api/roles").then().body("find { it.rolename == 'acl-role' }.acls.size()", equalTo(0));

        given().delete("/api/roles/acl-role").then().statusCode(204);
    }

    @Test
    void errorsOfTheBroker() {
        given().get("/api/clients/nobody").then().statusCode(404).body("code", equalTo("not_found"));
        json().body(Map.of("username", "dup", "password", "x", "roles", List.of("no-such-role"), "groups", List.of())).post("/api/clients").then()
                .statusCode(404).body("message", equalTo("Role not found"));

        json().body(Map.of("username", "dup", "password", "x", "roles", List.of(), "groups", List.of())).post("/api/clients").then().statusCode(204);
        json().body(Map.of("username", "dup", "password", "x", "roles", List.of(), "groups", List.of())).post("/api/clients").then()
                .statusCode(409).body("code", equalTo("already_exists"));
        given().delete("/api/clients/dup").then().statusCode(204);
    }

    @Test
    void validation() {
        json().body(Map.of("username", "a/b", "password", "x", "roles", List.of(), "groups", List.of())).post("/api/clients").then()
                .statusCode(400).body("code", equalTo("validation")).body("field", equalTo("username"));
        json().body(Map.of("acltype", "bogus", "topic", "a", "priority", 0, "allow", true)).post("/api/roles/client/acls").then()
                .statusCode(400).body("field", equalTo("acltype"));
    }

    @Test
    void ownAccessIsLocked() {
        given().get("/api/clients/admin").then().body("locked", equalTo(true));
        given().get("/api/roles").then().body("find { it.rolename == 'super-admin' }.locked", equalTo(true));

        given().delete("/api/clients/admin").then().statusCode(409).body("code", equalTo("locked"));
        json().body(Map.of("enabled", false)).put("/api/clients/admin/enabled").then().statusCode(409);
        json().body(Map.of("password", "x")).put("/api/clients/admin/password").then().statusCode(409);
        json().body(Map.of("roles", List.of(), "groups", List.of())).put("/api/clients/admin").then().statusCode(409);
        given().delete("/api/roles/super-admin").then().statusCode(409);
        json().body(Map.of("acltype", "subscribePattern", "topic", "#", "priority", 99, "allow", false)).post("/api/roles/super-admin/acls").then().statusCode(409);
        given().queryParam("acltype", "publishClientSend").queryParam("topic", "$CONTROL/#").delete("/api/roles/super-admin/acls").then().statusCode(409);

        given().get("/api/broker/status").then().body("connected", equalTo(true));
    }
}
