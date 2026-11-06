package com.dfc.mosquittoui.api;

import com.github.dockerjava.api.model.VolumesFrom;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;

/**
 * A directory the tests share with a container. In CI the tests run in a job container that talks to the Docker daemon of the host, which does not
 * know its paths, so a bind mount would stay empty on one side. The workspace is a volume there, which a container gets with volumes-from, at the same
 * path. MOSQUITTO_UI_TEST_CONTAINER names the job container; without it, this is an ordinary bind mount.
 */
final class SharedDirectory {

    private static final String JOB_CONTAINER = System.getenv("MOSQUITTO_UI_TEST_CONTAINER");

    private SharedDirectory() {
    }

    static boolean inJobContainer() {
        return JOB_CONTAINER != null && !JOB_CONTAINER.isBlank();
    }

    /** Open to all: the broker runs as another user. */
    static Path create(String prefix) {
        try {
            Path parent = inJobContainer() ? Files.createDirectories(Path.of("target/test-shared").toAbsolutePath()) : Path.of(System.getProperty("java.io.tmpdir"));
            Path directory = Files.createTempDirectory(parent, prefix);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs the command with the directory at target. */
    static void mount(GenericContainer<?> container, Path directory, String target, String... command) {
        if (inJobContainer()) {
            container.withCreateContainerCmdModifier(create -> create.getHostConfig().withVolumesFrom(new VolumesFrom(JOB_CONTAINER)));
            container.withCommand("sh", "-c", "ln -s " + directory + " " + target + " && exec " + String.join(" ", List.of(command)));
        } else {
            container.withFileSystemBind(directory.toString(), target, BindMode.READ_WRITE);
            container.withCommand(command);
        }
    }
}
