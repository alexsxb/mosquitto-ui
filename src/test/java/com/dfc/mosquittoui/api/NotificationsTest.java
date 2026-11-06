package com.dfc.mosquittoui.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.backup.ArchiveService;
import com.dfc.mosquittoui.backup.ArchiveService.Archive;
import com.dfc.mosquittoui.backup.BackupSection;
import com.dfc.mosquittoui.backup.BackupStore;
import com.dfc.mosquittoui.notify.FakeSmtp;
import com.dfc.mosquittoui.notify.NotificationStore;
import com.dfc.mosquittoui.notify.NotificationStore.MailSecurity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Settings, their way from the backups to here, and a real mail about a real bridge. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestSecurity(user = "alice", roles = "mosquitto-admin")
class NotificationsTest {

    private static final String SECRET = "mail-password-in-the-clear";

    @Inject
    BackupStore backups;

    @Inject
    NotificationStore store;

    @Inject
    ArchiveService archives;

    @Inject
    ObjectMapper mapper;

    private static Map<String, Object> settings(int port) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("recipients", List.of("ops@example.com"));
        settings.put("mailHost", "127.0.0.1");
        settings.put("mailPort", port);
        settings.put("mailSecurity", "NONE");
        settings.put("mailFrom", "ui@example.com");
        return settings;
    }

    private static void put(Map<String, Object> settings) {
        given().contentType(ContentType.JSON).body(settings).put("/api/notifications").then().statusCode(200);
    }

    /**
     * The old place first, then the file: OutageWatch reads the settings every second, and a read without the file takes over
     * whatever is in the old place at that moment. The other way round, it could take over the old mail server between the two,
     * or nothing where a test wants something.
     */
    @AfterEach
    void cleanUp() {
        // the other tests share the application
        backups.change(state -> {
            state.settings.notifyRecipients = null;
            state.settings.mailHost = null;
            state.settings.mailPort = null;
            state.settings.mailSecurity = null;
            state.settings.mailPassword = null;
            state.settings.mailFrom = null;
        });
        store.clear();
    }

    private static void oldSettings(BackupStore.Settings s) {
        s.notifyRecipients = List.of("old@example.com");
        s.mailHost = "smtp.example.com";
        s.mailPort = 465;
        s.mailSecurity = MailSecurity.TLS;
        s.mailUsername = "old";
        s.mailPassword = SECRET;
        s.mailFrom = "old-from@example.com";
    }

    @Test
    void thePasswordStaysInside() throws Exception {
        Map<String, Object> settings = settings(25);
        settings.put("mailPassword", SECRET);
        settings.put("muted", List.of("CLIENT"));
        settings.put("ignoredClients", List.of("laptop"));
        put(settings);

        given().get("/api/notifications").then().statusCode(200)
                .body("mail.passwordSet", equalTo(true))
                .body("mail.password", nullValue())
                .body("muted", hasItem("CLIENT"))
                .body("ignoredClients", hasItem("laptop"))
                .body("outageAfterMinutes", equalTo(0))
                .body("clientAfterMinutes", equalTo(15));

        settings.remove("mailPassword");
        put(settings);
        given().get("/api/notifications").then().body("mail.passwordSet", equalTo(true));

        Path state = ConfigProvider.getConfig().getValue("mosquitto-ui.state-dir", Path.class);
        assertFalse(Files.readString(state.resolve("notifications.json")).contains(SECRET));
        // the audit log answers with a list of entries, not with an object holding them
        given().get("/api/audit").then().body("action", hasItem("updateNotifications"));
        assertFalse(given().get("/api/audit").asString().contains(SECRET));
    }

    @Test
    void wrongValuesAreRefused() {
        Map<String, Object> settings = settings(25);
        settings.put("recipients", List.of("no address"));
        given().contentType(ContentType.JSON).body(settings).put("/api/notifications").then().statusCode(400);
        Map<String, Object> port = settings(70000);
        given().contentType(ContentType.JSON).body(port).put("/api/notifications").then().statusCode(400);
    }

    @Test
    void theMailServerMovesOutOfTheBackupSettings() {
        // in this order, see cleanUp
        backups.change(state -> oldSettings(state.settings));
        store.clear();

        given().get("/api/notifications").then().statusCode(200)
                .body("recipients", hasItem("old@example.com"))
                .body("mail.host", equalTo("smtp.example.com"))
                .body("mail.port", equalTo(465))
                .body("mail.security", equalTo("TLS"))
                .body("mail.username", equalTo("old"))
                .body("mail.passwordSet", equalTo(true))
                .body("mail.from", equalTo("old-from@example.com"));

        assertNull(backups.read(state -> state.settings.mailPassword));
        assertNull(backups.read(state -> state.settings.mailHost));
        assertEquals(SECRET, store.read(state -> state.settings.mailPassword));
    }

    @Test
    void aBackupOfAnOlderVersionBringsItsMailServer() throws Exception {
        Archive archive = archives.read(archives.createZip(EnumSet.of(BackupSection.BACKUPS), "test"), null);
        Map<String, byte[]> entries = new HashMap<>(archive.entries());
        assertTrue(entries.containsKey("backups/notifications.json"));
        entries.remove("backups/notifications.json");
        ObjectNode old = (ObjectNode) mapper.readTree(entries.get("backups/backup.json"));
        ((ObjectNode) old.path("settings")).put("mailHost", "smtp.example.com").put("mailFrom", "old-from@example.com").put("mailPassword", SECRET)
                .putArray("notifyRecipients").add("old@example.com");
        entries.put("backups/backup.json", mapper.writeValueAsBytes(old));

        archives.restore(new Archive(archive.manifest(), archive.signature(), archive.encrypted(), entries), EnumSet.of(BackupSection.BACKUPS), true);

        given().get("/api/notifications").then()
                .body("recipients", hasItem("old@example.com"))
                .body("mail.host", equalTo("smtp.example.com"))
                .body("mail.passwordSet", equalTo(true));
        assertNull(backups.read(state -> state.settings.mailHost));
    }

    @Test
    void aBackupBringsTheSettings() throws Exception {
        Map<String, Object> settings = settings(25);
        settings.put("mailPassword", SECRET);
        settings.put("muted", List.of("BRIDGE"));
        put(settings);
        byte[] zip = archives.createZip(EnumSet.of(BackupSection.BACKUPS), "test");
        store.clear();
        given().get("/api/notifications").then().body("recipients", hasSize(0));

        archives.restore(archives.read(zip, null), EnumSet.of(BackupSection.BACKUPS), true);

        given().get("/api/notifications").then().body("recipients", hasItem("ops@example.com")).body("muted", hasItem("BRIDGE")).body("mail.passwordSet", equalTo(true));
    }

    @Test
    void theTestMailReachesTheServer() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp()) {
            given().post("/api/notifications/test-mail").then().statusCode(400);
            put(settings(smtp.port()));
            given().post("/api/notifications/test-mail").then().statusCode(204);
            assertEquals(1, smtp.messages.size());
            assertTrue(smtp.messages.getFirst().contains("mosquitto-ui: test message"));
        }
    }

    @Test
    void aBridgeThatCannotConnectGetsItsMail() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp()) {
            Map<String, Object> bridge = new HashMap<>();
            bridge.put("name", "nowhere");
            bridge.put("address", "127.0.0.1:1");
            bridge.put("remoteClientId", "nowhere-id");
            bridge.put("topics", List.of(Map.of("pattern", "a/#", "direction", "out", "qos", 0)));
            bridge.put("extraOptions", List.of());
            given().contentType(ContentType.JSON).body(bridge).post("/api/bridges").then().statusCode(204);
            try {
                await().atMost(Duration.ofSeconds(20)).until(() -> given().get("/api/broker/status").jsonPath().getBoolean("connected"));
                put(settings(smtp.port()));

                await().atMost(Duration.ofSeconds(30)).until(() -> smtp.messages.stream().anyMatch(mail -> mail.contains("bridge nowhere is disconnected")));
                assertEquals(1, store.read(state -> state.reported.stream().filter(r -> r.name().equals("nowhere")).count()).intValue());
            } finally {
                given().delete("/api/bridges/nowhere").then().statusCode(204);
                await().atMost(Duration.ofSeconds(20)).until(() -> given().get("/api/broker/status").jsonPath().getBoolean("connected"));
            }
            // gone, not back: nobody needs a mail about that
            await().atMost(Duration.ofSeconds(10)).until(() -> store.read(state -> state.reported.stream().noneMatch(r -> r.name().equals("nowhere"))));
            assertFalse(smtp.messages.stream().anyMatch(mail -> mail.contains("bridge nowhere is connected again")));
        }
    }
}
