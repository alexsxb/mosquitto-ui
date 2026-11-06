package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Backups of the real broker to a directory, and back. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@QuarkusTestResource(PebbleResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
@TestMethodOrder(OrderAnnotation.class)
class BackupTest {

    private static final String PASSWORD = "a long enough password";
    private static final String TARGET_SECRET = "target-password-in-the-clear";
    private static final List<String> ALL = List.of("CERTIFICATES", "ACCESS", "BRIDGES", "CONFIG", "BACKUPS");
    private static Path directory;

    @TestHTTPResource("/fake-dns")
    URL fakeDns;

    @BeforeAll
    static void createDirectory() throws Exception {
        Path root = Files.createDirectories(ConfigProvider.getConfig().getValue("mosquitto-ui.backup.local-root", Path.class));
        directory = Files.createTempDirectory(root, "backups");
    }

    private static Path state() {
        return ConfigProvider.getConfig().getValue("mosquitto-ui.state-dir", Path.class);
    }

    private static int target() {
        Map<String, Object> target = new HashMap<>();
        target.put("name", "local");
        target.put("type", "LOCAL");
        target.put("path", directory.toString());
        target.put("password", TARGET_SECRET);
        target.put("retentionDays", 30);
        return given().contentType(ContentType.JSON).body(target).post("/api/backup/targets").then().statusCode(200).extract().path("id");
    }

    private static Map<String, Object> job(String name, int target, List<String> sections, String encryption) {
        Map<String, Object> job = new HashMap<>();
        job.put("name", name);
        job.put("sections", sections);
        job.put("targetId", target);
        job.put("schedule", "MANUAL");
        job.put("encryption", encryption);
        if (encryption.equals("PASSWORD")) {
            job.put("password", PASSWORD);
        }
        return job;
    }

    private static int create(Map<String, Object> job) {
        return given().contentType(ContentType.JSON).body(job).post("/api/backup/jobs").then().log().ifValidationFails().statusCode(200).extract().path("id");
    }

    /** The file the run wrote. */
    private static String run(int job) {
        int run = given().post("/api/backup/jobs/" + job + "/start").then().statusCode(200).extract().path("runId");
        await().atMost(Duration.ofSeconds(60)).until(() -> given().get("/api/backup/runs/" + run).jsonPath().get("success") != null);
        given().get("/api/backup/runs/" + run).then().log().ifValidationFails().body("message", notNullValue()).body("success", equalTo(true));
        return given().get("/api/backup/runs/" + run).jsonPath().getString("fileName");
    }

    private static io.restassured.response.ValidatableResponse restore(int target, String file, List<String> sections, boolean trust) {
        return given().contentType(ContentType.JSON).body(Map.of("password", PASSWORD, "sections", sections, "trustUnsigned", trust))
                .post("/api/backup/targets/" + target + "/files/" + file + "/restore").then().log().ifValidationFails();
    }

    private static void client(String name) {
        given().contentType(ContentType.JSON).body(Map.of("username", name, "password", "a password of the client", "roles", List.of(), "groups", List.of())).post("/api/clients").then()
                .statusCode(204);
    }

    private static List<String> clients() {
        return given().get("/api/clients").jsonPath().getList("username");
    }

    private static String exec(String script) throws Exception {
        return MosquittoResource.broker.execInContainer("sh", "-c", script).getStdout();
    }

    /** More than a subscription with QoS 1 would get, measured at 1040; with a binary value, and what is newer stays. */
    @Test
    @Order(25)
    void retainedMessagesComeBack() throws Exception {
        int count = 1200;
        String password = "a password of the device";
        given().contentType(ContentType.JSON).body(Map.of("username", "retained-device", "password", password, "roles", List.of("client"), "groups", List.of()))
                .post("/api/clients").then().statusCode(204);
        String pub = "mosquitto_pub -u retained-device -P '" + password + "' ";
        String sub = "mosquitto_sub -u retained-device -P '" + password + "' ";
        exec("for i in $(seq 1 " + count + "); do " + pub + "-r -t backup/retained/$i -m value-$i; done");
        exec("printf '\\000\\377\\001' > /tmp/binary && " + pub + "-r -t backup/binary -f /tmp/binary");
        int target = target();
        int job = create(job("retained", target, List.of("RETAINED"), "PASSWORD"));
        // the broker tells its number every 10 seconds, and a run compares with it
        await().atMost(Duration.ofSeconds(25)).until(() -> given().get("/api/broker/stats").jsonPath().getInt("retained") > count);
        String file = run(job);
        given().contentType(ContentType.JSON).body(Map.of("password", PASSWORD, "sections", List.of("RETAINED"), "trustUnsigned", false))
                .post("/api/backup/targets/" + target + "/files/" + file + "/inspect").then().statusCode(200)
                .body("sections.find { it.section == 'RETAINED' }.count", equalTo(count + 1));

        exec(pub + "-r -n -t backup/retained/1; " + pub + "-r -n -t backup/binary; " + pub + "-r -t backup/newer -m stays");
        restore(target, file, List.of("RETAINED"), false).statusCode(200).body("error", nullValue());

        String lines = exec(sub + "-t 'backup/#' -v -W 3 --retained-only | wc -l");
        assertEquals(count + 2, Integer.parseInt(lines.strip()), lines);
        assertEquals("value-1", exec(sub + "-t backup/retained/1 -C 1 --retained-only").strip());
        assertEquals("00ff01", exec(sub + "-t backup/binary -C 1 --retained-only | od -An -tx1 -N3 | tr -d ' \\n'"));
        assertFalse(clients().stream().anyMatch(name -> name.startsWith("mosquitto-ui-import")), "the temporary clients are gone");

        // empty again, the tests after this one count nothing but they share the broker
        exec("for i in $(seq 1 " + count + "); do " + pub + "-r -n -t backup/retained/$i; done; " + pub + "-r -n -t backup/binary; " + pub + "-r -n -t backup/newer");
        given().delete("/api/backup/jobs/" + job).then().statusCode(204);
        given().delete("/api/clients/retained-device").then().statusCode(204);
    }

    @Test
    @Order(26)
    void theAuditLogGoesAlong() {
        int target = target();
        int job = create(job("audit", target, List.of("AUDIT"), "PASSWORD"));
        String file = run(job);
        given().contentType(ContentType.JSON).body(Map.of("password", PASSWORD, "sections", List.of("AUDIT"), "trustUnsigned", false))
                .post("/api/backup/targets/" + target + "/files/" + file + "/inspect").then().statusCode(200)
                .body("sections.find { it.section == 'AUDIT' }.count", greaterThan(0));
        int before = given().get("/api/audit").jsonPath().getList("$").size();
        restore(target, file, List.of("AUDIT"), false).statusCode(200).body("error", nullValue());
        // nothing twice, only the restore itself is new
        assertEquals(before + 1, given().get("/api/audit").jsonPath().getList("$").size());
        given().delete("/api/backup/jobs/" + job).then().statusCode(204);
    }

    @Test
    @Order(1)
    void secretsOnlyGoEncrypted() {
        await().until(() -> given().get("/api/certificate").jsonPath().getBoolean("available"));
        int target = target();
        given().contentType(ContentType.JSON).body(job("plain", target, ALL, "NONE")).post("/api/backup/jobs").then().statusCode(400).body("code", equalTo("validation"))
                .body("field", equalTo("sections"));
        given().contentType(ContentType.JSON).body(Map.of("sections", List.of("ACCESS"))).post("/api/backup/archive").then().statusCode(400);

        int plain = create(job("config only", target, List.of("CONFIG"), "NONE"));
        String file = run(plain);
        assertTrue(Files.exists(directory.resolve(file)));
        given().contentType(ContentType.JSON).body(Map.of("sections", List.of("CONFIG"))).post("/api/backup/archive").then().statusCode(200);
        given().delete("/api/backup/targets/" + target).then().statusCode(409).body("code", equalTo("target_in_use"));
        given().delete("/api/backup/jobs/" + plain).then().statusCode(204);
    }

    @Test
    @Order(2)
    void nothingSecretIsReadableOnTheDisk() throws Exception {
        String stored = Files.readString(state().resolve("backup.json"));
        assertTrue(stored.startsWith("enc:v1:"));
        assertFalse(stored.contains(TARGET_SECRET));
        given().get("/api/backup").then().body("targets[0].passwordSet", equalTo(true)).body("targets[0].password", nullValue());
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(state().resolve("backup.json"))));
    }

    @Test
    @Order(3)
    void everythingComesBack() throws Exception {
        int target = given().get("/api/backup").jsonPath().getInt("targets[0].id");

        // a certificate with its key, a client and a listener-free configuration
        Map<String, Object> settings = new HashMap<>();
        settings.put("provider", "PDNS");
        settings.put("url", fakeDns + "/pdns");
        settings.put("token", FakeDnsResource.KEY);
        settings.put("domains", List.of("example.test"));
        settings.put("directory", PebbleResource.directory(PebbleResource.pebble));
        settings.put("keyType", "EC");
        settings.put("propagationSeconds", 0);
        settings.put("autoRenew", true);
        settings.put("acceptTerms", true);
        String set = given().contentType(ContentType.JSON).body(Map.of("name", "main", "settings", settings)).post("/api/certificate/sets").then().statusCode(200).extract().path("id");
        given().post("/api/certificate/sets/" + set + "/issue").then().statusCode(204);
        await().atMost(Duration.ofSeconds(60)).until(() -> !given().get("/api/certificate").jsonPath().getBoolean("job.running"));
        given().get("/api/certificate").then().body("job.error", nullValue());
        String serial = given().get("/api/certificate").jsonPath().getString("certificate.serial");
        assertTrue(Files.readString(state().resolve("certificate-sets.json")).startsWith("enc:v1:"));
        assertFalse(Files.readString(state().resolve("sets/" + set + "/account.pem")).contains("PRIVATE KEY"));

        client("kept-by-backup");
        given().contentType(ContentType.JSON).body(Map.of("text", "max_inflight_messages 5\n")).put("/api/config").then().statusCode(200);

        int job = create(job("all", target, ALL, "PASSWORD"));
        String file = run(job);
        assertTrue(file.endsWith(".enc"));

        given().contentType(ContentType.JSON).body(Map.of("password", PASSWORD)).post("/api/backup/targets/" + target + "/files/" + file + "/inspect").then().statusCode(200)
                .body("signature", equalTo("VALID")).body("encrypted", equalTo(true)).body("sections.section", hasItems("ACCESS", "CERTIFICATES"))
                .body("sections.find { it.section == 'CERTIFICATES' }.count", equalTo(1));
        given().contentType(ContentType.JSON).body(Map.of("password", "not the password at all")).post("/api/backup/targets/" + target + "/files/" + file + "/inspect").then()
                .statusCode(400).body("field", equalTo("password"));

        // everything changes
        given().delete("/api/clients/kept-by-backup").then().statusCode(204);
        client("after-the-backup");
        given().contentType(ContentType.JSON).body(Map.of("text", "max_inflight_messages 7\n")).put("/api/config").then().statusCode(200);
        given().post("/api/certificate/sets/" + set + "/issue").then().statusCode(204);
        await().atMost(Duration.ofSeconds(60)).until(() -> !given().get("/api/certificate").jsonPath().getBoolean("job.running"));
        assertFalse(serial.equals(given().get("/api/certificate").jsonPath().getString("certificate.serial")));
        Map<String, Object> extra = new HashMap<>(settings);
        given().contentType(ContentType.JSON).body(Map.of("name", "extra", "settings", extra)).post("/api/certificate/sets").then().statusCode(200);

        restore(target, file, ALL, false).statusCode(200).body("error", nullValue()).body("restored", hasSize(5));
        await().atMost(Duration.ofSeconds(20)).until(() -> given().get("/api/broker/status").jsonPath().getBoolean("connected"));

        assertTrue(clients().contains("kept-by-backup"), clients().toString());
        assertFalse(clients().contains("after-the-backup"));
        given().get("/api/config").then().body("text", containsString("max_inflight_messages 5"));
        given().get("/api/certificate").then().body("certificate.serial", equalTo(serial)).body("sets", hasSize(1));
        // the key came back with it: the broker still has a working pair
        String key = MosquittoResource.broker.execInContainer("sh", "-c", "cmp /mosquitto/data/certs/privkey.pem /mosquitto/data/certs/sets/" + set + "/privkey.pem && echo same").getStdout();
        assertTrue(key.contains("same"), key);
        assertFalse(given().get("/api/backup").jsonPath().getList("jobs").isEmpty());
        assertFalse(Files.exists(ConfigProvider.getConfig().getValue("mosquitto-ui.shared-dir", Path.class).resolve("control/export")));
    }

    @Test
    @Order(4)
    void aFileThatWasChangedNeedsConfirmation() throws Exception {
        int target = given().get("/api/backup").jsonPath().getInt("targets[0].id");
        int job = create(job("config", target, List.of("CONFIG"), "NONE"));
        String file = run(job);
        Path zip = directory.resolve(file);
        Path changed = directory.resolve("changed.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(changed)); var in = new java.util.zip.ZipFile(zip.toFile())) {
            for (var entry : java.util.Collections.list(in.entries())) {
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                byte[] content = in.getInputStream(entry).readAllBytes();
                out.write(entry.getName().endsWith(".conf") ? "max_inflight_messages 9\n".getBytes() : content);
                out.closeEntry();
            }
        }
        Files.move(changed, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        given().contentType(ContentType.JSON).body(Map.of()).post("/api/backup/targets/" + target + "/files/" + file + "/inspect").then().statusCode(200).body("signature", equalTo("INVALID"));
        restore(target, file, List.of("CONFIG"), false).statusCode(400).body("field", equalTo("trustUnsigned"));
        given().get("/api/config").then().body("text", containsString("max_inflight_messages 5"));
        restore(target, file, List.of("CONFIG"), true).statusCode(200).body("error", nullValue());
        given().get("/api/config").then().body("text", containsString("max_inflight_messages 9"));
    }

    @Test
    @Order(4)
    void allFilesOfATargetCanBeSeenFetchedAndDeleted() throws Exception {
        int target = target();
        String url = "/api/backup/targets/" + target + "/files/";
        Files.writeString(directory.resolve("not ours \"x\".txt"), "foreign");
        String name = URLEncoder.encode("not ours \"x\".txt", StandardCharsets.UTF_8).replace("+", "%20");

        given().get(url).then().statusCode(200)
                .body("find { it.name == 'not ours \"x\".txt' }.own", equalTo(false))
                .body("find { it.name == 'not ours \"x\".txt' }.size", equalTo(7))
                .body("find { it.name == 'not ours \"x\".txt' }.modified", notNullValue())
                .body("find { it.own }.name", notNullValue());
        assertEquals("foreign", given().urlEncodingEnabled(false).get(url + name).then().statusCode(200)
                .header("Content-Disposition", equalTo("attachment; filename*=UTF-8''" + name)).extract().asString());
        try (var left = Files.list(state())) {
            assertFalse(left.anyMatch(path -> path.getFileName().toString().startsWith("download")), "nothing stays behind");
        }

        given().urlEncodingEnabled(false).contentType(ContentType.JSON).body(Map.of()).post(url + name + "/inspect").then().statusCode(404);
        given().urlEncodingEnabled(false).get(url + "..%2Fbackup.json").then().statusCode(404);
        given().urlEncodingEnabled(false).delete(url + "..%2Fbackup.json").then().statusCode(404);

        given().urlEncodingEnabled(false).delete(url + name).then().statusCode(204);
        assertFalse(Files.exists(directory.resolve("not ours \"x\".txt")));
    }

    @Test
    @Order(4)
    void aLocalTargetStaysBelowItsRoot() throws Exception {
        // the root in the tests is a directory of its own below the temporary one, like the state directory, which lies beside it
        for (String path : new String[] {"/proc/self", state().toString(), directory + "/../../etc"}) {
            Map<String, Object> outside = Map.of("name", "outside", "type", "LOCAL", "path", path, "retentionDays", 30);
            given().contentType(ContentType.JSON).body(outside).post("/api/backup/targets").then().statusCode(400).body("field", equalTo("path"));
        }

        // a link below the root that leads out of it
        Path link = Files.createSymbolicLink(directory.resolveSibling("link-" + System.nanoTime()), state());
        Map<String, Object> linked = Map.of("name", "linked", "type", "LOCAL", "path", link.toString(), "retentionDays", 30);
        int target = given().contentType(ContentType.JSON).body(linked).post("/api/backup/targets").then().statusCode(200).extract().path("id");
        given().get("/api/backup/targets/" + target + "/files").then().statusCode(org.hamcrest.Matchers.not(200));
        given().delete("/api/backup/targets/" + target).then().statusCode(204);

        // a link to a file of the state in the directory of a target
        int own = target();
        Path secret = Files.createSymbolicLink(directory.resolve("secret.key"), state().resolve("backup.json"));
        given().get("/api/backup/targets/" + own + "/files").then().statusCode(200).body("find { it.name == 'secret.key' }", nullValue());
        given().get("/api/backup/targets/" + own + "/files/secret.key").then().statusCode(org.hamcrest.Matchers.not(200));
        Files.delete(secret);
    }

    @Test
    @Order(5)
    void theUiKeepsItsOwnAccess() {
        String user = ConfigProvider.getConfig().getValue("mosquitto.username", String.class);
        assertTrue(clients().contains(user));
        given().get("/api/broker/status").then().body("connected", equalTo(true));
    }

    @Test
    @Order(6)
    void aRunThatFailsIsLoggedAndShows() throws Exception {
        Map<String, Object> target = new HashMap<>();
        target.put("name", "gone");
        target.put("type", "SFTP");
        target.put("host", "127.0.0.1");
        target.put("port", 1);
        target.put("username", "nobody");
        target.put("password", "x");
        int id = given().contentType(ContentType.JSON).body(target).post("/api/backup/targets").then().statusCode(200).extract().path("id");
        given().post("/api/backup/targets/" + id + "/test").then().statusCode(200).body("success", equalTo(false));

        Map<String, Object> job = job("fails", id, List.of("CONFIG"), "NONE");
        job.put("schedule", "DAILY");
        job.put("time", "03:00");
        int created = create(job);
        int run = given().post("/api/backup/jobs/" + created + "/start").then().statusCode(200).extract().path("runId");
        await().atMost(Duration.ofSeconds(30)).until(() -> given().get("/api/backup/runs/" + run).jsonPath().get("success") != null);
        given().get("/api/backup/runs/" + run).then().body("success", equalTo(false)).body("log", containsString("ERROR"));
        given().get("/api/backup").then().body("health.find { it.job == 'fails' }.failed", equalTo(true));
    }

    @Test
    @Order(7)
    void resetRemovesThem() {
        given().contentType(ContentType.JSON).body(Map.of("backups", true)).post("/api/config/reset").then().statusCode(200).body("done", hasItems("backups"));
        given().get("/api/backup").then().body("jobs", hasSize(0)).body("targets", hasSize(0));
        assertFalse(Files.exists(state().resolve("backup.json")));

        // the other tests share the broker and the application
        given().delete("/api/clients/kept-by-backup").then().statusCode(204);
        given().contentType(ContentType.JSON).body(Map.of("config", true, "certificate", true)).post("/api/config/reset").then().statusCode(200).body("error", nullValue());
        await().atMost(Duration.ofSeconds(20)).until(() -> given().get("/api/broker/status").jsonPath().getBoolean("connected"));
    }
}
