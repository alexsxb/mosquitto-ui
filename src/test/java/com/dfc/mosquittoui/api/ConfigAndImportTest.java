package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.Container.ExecResult;

@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
@TestMethodOrder(OrderAnnotation.class)
class ConfigAndImportTest {

    private static final String ACL = """
            # comment
            topic read public/#
            user alice
            topic readwrite home/#
            topic deny home/secret
            user ghost
            topic ghost/#
            pattern write devices/%u/#
            """;

    private static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }

    private static void awaitBroker() {
        await().atMost(Duration.ofSeconds(20)).until(() -> given().get("/api/broker/status").jsonPath().getBoolean("connected"));
    }

    @BeforeEach
    void connected() {
        awaitBroker();
    }

    private static String hash(String name) throws IOException {
        try (var in = ConfigAndImportTest.class.getResourceAsStream("/passwd-hashes.txt")) {
            return new String(in.readAllBytes()).lines().filter(l -> l.startsWith(name + ":")).findFirst().orElseThrow().split(":", 2)[1];
        }
    }

    private static ExecResult publish(String user, String password, String topic) throws Exception {
        return MosquittoResource.broker.execInContainer("mosquitto_pub", "-V", "5", "-u", user, "-P", password, "-t", topic, "-m", "x", "-q", "1");
    }

    private static boolean allowed(String user, String password, String topic) throws Exception {
        ExecResult result = publish(user, password, topic);
        return result.getExitCode() == 0 && !(result.getStdout() + result.getStderr()).contains("failed");
    }

    /** The broker is shared with other test classes. */
    @Test
    @Order(0)
    void emptyConfiguration() {
        await().until(() -> given().get("/api/config").jsonPath().getBoolean("available"));
        json().body(Map.of("text", "")).put("/api/config").then().statusCode(200);
    }

    @Test
    @Order(1)
    void reloadForGlobalOption() {
        String text = "# mine\nsys_interval 5\n";
        json().body(Map.of("text", text)).post("/api/config/check").then().statusCode(200)
                .body("restartRequired", equalTo(false))
                .body("changedOptions", hasItem("sys_interval"));
        json().body(Map.of("text", text)).put("/api/config").then().statusCode(200).body("restarted", equalTo(false));
        given().get("/api/config").then().body("text", equalTo(text)).body("form.options.sys_interval", equalTo("5"))
                .body("mainPorts", equalTo(List.of(1883)))
                .body("mainListeners[0].port", equalTo(1883))
                .body("mainListeners[0].protocol", equalTo("mqtt"))
                .body("mainListeners[0].maxConnections", equalTo(500));
    }

    @Test
    @Order(2)
    void directivesThatReachBeyondTheBrokerAreRefused() {
        String before = given().get("/api/config").jsonPath().getString("text");
        for (String line : new String[] {"pid_file /mosquitto/agent.sh", "log_dest file /mosquitto/data/x", "plugin /mosquitto/ui/x.so"}) {
            json().body(Map.of("text", before + line + "\n")).put("/api/config").then().statusCode(400)
                    .body("code", equalTo("config_rejected")).body("message", containsString("belongs in mosquitto.conf"));
        }
        // a line break in a field of the form would be a line of its own
        Map<String, Object> listener = new java.util.HashMap<>(Map.of("port", 1884, "protocol", "mqtt", "bindAddress", "0.0.0.0\nhttp_dir /mosquitto/data"));
        json().body(Map.of("logTypes", java.util.List.of(), "options", Map.of(), "listeners", java.util.List.of(listener))).post("/api/config/check-form").then().statusCode(400);
        json().body(Map.of("logTypes", java.util.List.of("all\npid_file /x"), "options", Map.of(), "listeners", java.util.List.of())).post("/api/config/check-form")
                .then().statusCode(400);
        given().get("/api/config").then().body("text", equalTo(before));
    }

    @Test
    @Order(2)
    void invalidConfigIsRefusedAndTheOldOneStays() {
        json().body(Map.of("text", "bogus_option 1\n")).put("/api/config").then().statusCode(400)
                .body("code", equalTo("config_rejected"))
                .body("message", org.hamcrest.Matchers.startsWith("Error: Unknown configuration variable 'bogus_option'"));
        given().get("/api/config").then().body("text", containsString("sys_interval 5"));
        given().get("/api/broker/status").then().body("connected", equalTo(true));
    }

    @Test
    @Order(3)
    void restartForListener() throws Exception {
        String text = "sys_interval 5\nlistener 1884\n";
        json().body(Map.of("text", text)).put("/api/config").then().statusCode(200).body("restarted", equalTo(true));
        awaitBroker();
        ExecResult result = MosquittoResource.broker.execInContainer("sh", "-c", "netstat -ltn | grep -c :1884");
        assertTrue(Integer.parseInt(result.getStdout().strip()) > 0, result.toString());
    }

    @Test
    @Order(4)
    void brokerThatDoesNotStartGoesBackToTheOldFile() {
        // the same port twice
        String text = "listener 1884\nlistener 1884\nlistener 1883\n";
        json().body(Map.of("text", text)).put("/api/config").then().statusCode(400).body("code", equalTo("config_rejected"));
        awaitBroker();
        given().get("/api/config").then().body("text", equalTo("sys_interval 5\nlistener 1884\n"));
    }

    @Test
    @Order(5)
    void formKeepsOtherLines() {
        Map<String, Object> form = Map.of("logTypes", java.util.List.of("error"), "options", Map.of("max_packet_size", "100000"),
                "listeners", java.util.List.of(Map.of("port", 1884, "protocol", "mqtt")));
        json().body(form).post("/api/config/check-form").then().statusCode(200)
                .body("text", containsString("log_type error"))
                .body("text", containsString("max_packet_size 100000"))
                .body("text", containsString("listener 1884"));
    }

    @Test
    @Order(6)
    void formRefusesWhatItDoesNotKnow() {
        Map<String, Object> form = Map.of("logTypes", java.util.List.of(), "options", Map.of("password_file", "/x"), "listeners", java.util.List.of());
        json().body(form).post("/api/config/check-form").then().statusCode(400).body("code", equalTo("config_rejected"));
        Map<String, Object> line = Map.of("logTypes", java.util.List.of(), "options", Map.of("sys_interval", "1\nlistener 1999"), "listeners", java.util.List.of());
        json().body(line).post("/api/config/check-form").then().statusCode(400).body("code", equalTo("validation"));
    }

    /** The documentation lists this one as reloaded, but the broker keeps the old value until it starts again. */
    @Test
    @Order(7)
    void aLimitThatAReloadWouldNotChangeRestartsTheBroker() throws Exception {
        String before = given().get("/api/config").jsonPath().getString("text");
        MosquittoResource.broker.execInContainer("sh", "-c", "head -c 5000 /dev/zero | tr '\\0' 'x' > /tmp/big");
        String[] publish = {"mosquitto_pub", "-u", "admin", "-P", MosquittoResource.PASSWORD, "-t", "any", "-f", "/tmp/big", "-q", "1"};
        assertEquals(0, MosquittoResource.broker.execInContainer(publish).getExitCode(), "the broker takes it");

        json().body(Map.of("text", before + "\nmax_packet_size 1000\n")).put("/api/config").then().statusCode(200).body("restarted", equalTo(true));
        awaitBroker();
        assertTrue(MosquittoResource.broker.execInContainer(publish).getExitCode() != 0, "too big now");

        json().body(Map.of("text", before)).put("/api/config").then().statusCode(200);
        awaitBroker();
        assertEquals(0, MosquittoResource.broker.execInContainer(publish).getExitCode());
    }

    /** A reload that does something, seen from the outside. */
    @Test
    @Order(8)
    void aReloadTakesEffect() throws Exception {
        String before = given().get("/api/config").jsonPath().getString("text");
        String pid = MosquittoResource.broker.execInContainer("pidof", "mosquitto").getStdout().strip();
        String[] anonymous = {"mosquitto_pub", "-t", "any", "-m", "x"};
        boolean allowedBefore = MosquittoResource.broker.execInContainer(anonymous).getExitCode() == 0;
        json().body(Map.of("text", before + "\nallow_anonymous " + !allowedBefore + "\n")).put("/api/config").then().statusCode(200).body("restarted", equalTo(false));
        await().atMost(Duration.ofSeconds(5)).until(() -> MosquittoResource.broker.execInContainer(anonymous).getExitCode() == 0 != allowedBefore);
        assertEquals(pid, MosquittoResource.broker.execInContainer("pidof", "mosquitto").getStdout().strip(), "same process");
        json().body(Map.of("text", before)).put("/api/config").then().statusCode(200);
    }

    private static Map<String, Object> file(String name, byte[] content) {
        return Map.of("name", name, "content", content);
    }

    private static Map<String, Object> file(String name, String content) {
        return file(name, content.getBytes(StandardCharsets.UTF_8));
    }

    @SafeVarargs
    private static ValidatableResponse analyze(Map<String, Object>... files) {
        return json().body(Map.of("files", List.of(files))).post("/api/import/analyze").then().log().ifValidationFails().statusCode(200);
    }

    private static ValidatableResponse run(boolean auth, boolean retained, boolean conf, List<Map<String, Object>> files) {
        return json().body(Map.of("files", files, "auth", auth, "retained", retained, "conf", conf)).post("/api/import").then().log().ifValidationFails().statusCode(200);
    }

    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] db() throws IOException {
        try (var in = ConfigAndImportTest.class.getResourceAsStream("/db/1.6.15.db")) {
            return in.readAllBytes();
        }
    }

    @Test
    @Order(10)
    void importOfUsersAndRules() throws Exception {
        String passwd = "alice:" + hash("hash7") + "\nbob:" + hash("hash6") + "\nadmin:" + hash("hash7")
                + "\nnew:$argon2id$v=19$m=65536,t=3,p=1$c2FsdA$aGFzaA\nplain:secret\n";
        List<Map<String, Object>> files = List.of(file("passwd", passwd), file("whatever.txt", ACL));

        analyze(files.get(0), files.get(1))
                .body("files.find { it.name == 'passwd' }.kind", equalTo("PASSWORD"))
                .body("files.find { it.name == 'whatever.txt' }.kind", equalTo("ACL"))
                .body("retained", nullValue())
                .body("conf", nullValue())
                .body("auth.clients.find { it.username == 'alice' }.status", equalTo("NEW"))
                .body("auth.clients.find { it.username == 'alice' }.rules", equalTo(6))
                .body("auth.clients.find { it.username == 'bob' }.hashType", equalTo("sha512"))
                .body("auth.clients.find { it.username == 'admin' }.status", equalTo("EXISTS"))
                .body("auth.clients.find { it.username == 'new' }.status", equalTo("UNSUPPORTED"))
                .body("auth.clients.find { it.username == 'plain' }.status", equalTo("UNSUPPORTED"))
                .body("auth.patternRules", equalTo(1))
                .body("auth.anonymousRules", equalTo(2))
                .body("auth.warnings.find { it.code == 'no_password' }.subject", equalTo("ghost"))
                .body("auth.warnings.code", hasItem("anonymous"));
        given().get("/api/clients").then().body("username.size()", equalTo(1));

        run(true, true, true, files)
                .body("auth.clients", equalTo(2))
                .body("auth.roles", equalTo(3))
                .body("error", nullValue());
        awaitBroker();

        given().get("/api/clients/alice").then().statusCode(200).body("roles", hasItem("imported-alice")).body("roles", hasItem("imported-patterns"));
        given().get("/api/clients/admin").then().body("locked", equalTo(true));

        assertTrue(allowed("alice", "hash-pass", "home/kitchen"), "old password, allowed topic");
        assertFalse(allowed("alice", "hash-pass", "home/secret"), "denied topic");
        assertFalse(allowed("alice", "hash-pass", "other/topic"), "topic without a rule");
        assertTrue(allowed("alice", "hash-pass", "devices/alice/state"), "pattern with %u");
        assertFalse(allowed("alice", "hash-pass", "devices/bob/state"), "pattern of another user");
        assertTrue(allowed("bob", "hash-pass", "devices/bob/state"), "sha512 hash");
        assertFalse(allowed("alice", "wrong", "home/kitchen"), "wrong password");
    }

    @Test
    @Order(11)
    void secondImportChangesNothing() throws Exception {
        analyze(file("passwd", "alice:" + hash("hash7") + "\n"), file("acl", ACL))
                .body("auth.clients.find { it.username == 'alice' }.status", equalTo("EXISTS"));
    }

    @Test
    @Order(20)
    void importOfRetainedMessages() throws Exception {
        analyze(file("backup.bin", db()))
                .body("files[0].kind", equalTo("DB"))
                .body("auth", nullValue())
                .body("retained.version", equalTo(6))
                .body("retained.messages.topic", hasItem("home/kitchen/temp"))
                .body("retained.messages.find { it.topic == 'home/kitchen/temp' }.preview", equalTo("21.5"))
                .body("retained.skipped", equalTo(0));

        run(false, false, false, List.of(file("mosquitto.db", db()))).body("retained", nullValue());
        run(false, true, false, List.of(file("mosquitto.db", db()))).body("retained.published", equalTo(2));

        ExecResult result = MosquittoResource.broker.execInContainer("mosquitto_sub", "-u", "admin", "-P", MosquittoResource.PASSWORD,
                "-t", "home/#", "-v", "-W", "2", "-C", "2");
        assertTrue(result.getStdout().contains("home/kitchen/temp 21.5"), result.toString());
        assertTrue(result.getStdout().contains("home/hall/state {\"on\":true}"), result.toString());
        given().get("/api/clients").then().body("username", not(hasItem("mosquitto-ui-import")));
    }

    @Test
    @Order(21)
    void filesThatAreNothingOfThese() throws IOException {
        byte[] broken = db();
        broken[22] = 99;
        analyze(file("zeros", new byte[100]), file("notes.txt", "some text\nand more"), file("empty.conf", "# nothing\n"), file("old.db", broken), file("first.db", db()),
                file("second.db", db()))
                .body("files.find { it.name == 'zeros' }.kind", equalTo("UNKNOWN"))
                .body("files.find { it.name == 'notes.txt' }.kind", equalTo("UNKNOWN"))
                .body("files.find { it.name == 'empty.conf' }.kind", equalTo("UNKNOWN"))
                .body("files.find { it.name == 'old.db' }.error", containsString("Unsupported version"))
                .body("files.find { it.name == 'first.db' }.error", nullValue())
                .body("files.find { it.name == 'second.db' }.error", equalTo("second_db"))
                .body("retained.messages.size()", equalTo(2));
    }

    @Test
    @Order(22)
    void zipWithEverythingInIt() throws Exception {
        String before = given().get("/api/config").jsonPath().getString("text");
        String conf = """
                listener 1883
                certfile /old/server.crt
                password_file /mosquitto/config/passwd
                persistence true
                persistence_location /var/lib/mosquitto
                plugin /usr/lib/old.so
                max_keepalive 600
                log_type notice
                # a comment
                listener 18884
                allow_anonymous false
                username lost
                """;
        String bridge = """
                # ours
                connection zipped
                address 203.0.113.9:1883
                topic # both 0
                clientid zipped-id
                try_private false
                username zip-user
                password zip-secret-value
                """;
        byte[] zipped = zip(Map.of("etc/mosquitto/mosquitto.conf", conf.getBytes(StandardCharsets.UTF_8), "etc/mosquitto/conf.d/bridge.conf", bridge.getBytes(StandardCharsets.UTF_8),
                "__MACOSX/._bridge.conf", new byte[50], "etc/.DS_Store", new byte[50], "etc/mosquitto/passwd", ("zipuser:" + hash("hash7") + "\n").getBytes(StandardCharsets.UTF_8)));

        String preview = analyze(file("backup.zip", zipped))
                .body("files.size()", equalTo(3))
                .body("files.find { it.name == 'mosquitto.conf' }.kind", equalTo("CONF"))
                .body("files.find { it.name == 'bridge.conf' }.kind", equalTo("CONF"))
                .body("files.find { it.name == 'passwd' }.kind", equalTo("PASSWORD"))
                .body("auth.clients.username", hasItem("zipuser"))
                .body("conf.bridges[0].name", equalTo("zipped"))
                .body("conf.bridges[0].status", equalTo("NEW"))
                .body("conf.bridges[0].warnings", hasItem("echo"))
                .body("conf.options", hasItem("max_keepalive 600"))
                .body("conf.options", hasItem("listener 18884"))
                .body("conf.skipped.find { it.line == 'listener 1883' }.reason", equalTo("listener_in_use"))
                .body("conf.skipped.find { it.line == 'certfile /old/server.crt' }.reason", equalTo("listener_in_use"))
                .body("conf.restartRequired", equalTo(true))
                .extract().asString();
        assertFalse(preview.contains("zip-secret-value"), "no password in the preview");

        run(false, false, true, List.of(file("backup.zip", zipped)))
                .body("conf.bridges", equalTo(1))
                .body("conf.restarted", equalTo(true))
                .body("auth", nullValue())
                .body("error", nullValue());
        awaitBroker();
        given().get("/api/clients").then().body("username", not(hasItem("zipuser")));
        given().get("/api/bridges").then()
                .body("bridges[0].bridge.remoteClientId", equalTo("zipped-id"))
                .body("bridges[0].bridge.password", nullValue())
                .body("bridges[0].passwordSet", equalTo(true));
        given().get("/api/config").then().body("text", containsString("max_keepalive 600")).body("text", not(containsString("password_file")));

        // a second time, the options and the bridge are there already
        analyze(file("backup.zip", zipped))
                .body("conf.bridges[0].status", equalTo("EXISTS"))
                .body("conf.options", not(hasItem("listener 18884")))
                .body("conf.skipped.find { it.line == 'max_keepalive 600' }.reason", equalTo("already_set"));

        given().delete("/api/bridges/zipped").then().statusCode(204);
        json().body(Map.of("text", before)).put("/api/config").then().statusCode(200);
    }

    @Test
    @Order(23)
    void zipWithTooManyFilesIsRefused() throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        for (int i = 0; i <= 500; i++) {
            files.put("file" + i, new byte[1]);
        }
        json().body(Map.of("files", List.of(file("many.zip", zip(files))))).post("/api/import/analyze").then().statusCode(400).body("code", equalTo("invalid_file"));
    }
}
