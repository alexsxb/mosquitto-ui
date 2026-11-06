package com.dfc.mosquittoui.backup.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.dfc.mosquittoui.backup.target.TargetStorage.FileEntry;
import com.dfc.mosquittoui.backup.target.TargetStorage.Settings;
import com.dfc.mosquittoui.backup.target.TargetStorage.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Against real servers: what a target tells about its files is different for each protocol. */
class TargetStorageTest {

    private static final String CONTENT = "twelve bytes";

    @TempDir
    Path directory;

    private void roundTrip(Settings settings) throws Exception {
        Path file = Files.writeString(directory.resolve("source"), CONTENT);
        try (TargetStorage storage = TargetStorage.open(settings)) {
            storage.upload(file, "a file.txt");

            FileEntry entry = storage.entries().stream().filter(e -> e.name().equals("a file.txt")).findFirst().orElseThrow();
            assertEquals(CONTENT.length(), entry.size());
            assertNotNull(entry.modified());
            assertFalse(entry.modified().isBefore(Instant.now().minus(Duration.ofDays(1))), entry.modified() + " is the time of the upload");

            Path back = directory.resolve("back");
            storage.download("a file.txt", back);
            assertEquals(CONTENT, Files.readString(back));

            storage.delete("a file.txt");
            assertFalse(storage.files().contains("a file.txt"));
        }
    }

    @Test
    void sftp() throws Exception {
        try (GenericContainer<?> server = new GenericContainer<>("atmoz/sftp:alpine").withCommand("backup:secret:1001::upload")
                .withExposedPorts(22).waitingFor(Wait.forListeningPort())) {
            server.start();
            roundTrip(new Settings(Type.SFTP, server.getHost(), server.getMappedPort(22), "backup", "secret", "upload", false, null, null, null));
        }
    }

    @Test
    void s3() throws Exception {
        try (GenericContainer<?> server = new GenericContainer<>("adobe/s3mock:3.12.0").withEnv("initialBuckets", "bucket")
                .withExposedPorts(9090).waitingFor(Wait.forListeningPort())) {
            server.start();
            roundTrip(new Settings(Type.S3, server.getHost(), server.getMappedPort(9090), "access", "secret", "some/prefix", false, null, null, "bucket"));
        }
    }
}
