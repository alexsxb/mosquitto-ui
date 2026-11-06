package com.dfc.mosquittoui.backup.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/** Real gpg with its own empty keyring; the developer's keyring stays untouched. */
final class GpgTestHelper {

    private final Path home;

    /** @param home empty directory with a short path (gpg-agent creates a socket there) */
    GpgTestHelper(Path home) throws Exception {
        this.home = home;
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
    }

    static boolean available() {
        try {
            return new ProcessBuilder("gpg", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    String gpg(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("gpg", "--homedir", home.toString(), "--batch", "--yes",
                "--pinentry-mode", "loopback", "--passphrase", ""));
        command.addAll(List.of(arguments));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("gpg " + String.join(" ", arguments) + ": " + output);
        }
        return output;
    }

    /** Creates a key pair and returns the armored public key */
    String generate(String userId, String expiry) throws Exception {
        gpg("--quick-generate-key", userId, "default", "default", expiry);
        return gpg("--armor", "--export", userId);
    }

    void decrypt(Path source, Path destination) throws Exception {
        gpg("--output", destination.toString(), "--decrypt", source.toString());
    }

    void stop() {
        try {
            new ProcessBuilder("gpgconf", "--homedir", home.toString(), "--kill", "all").redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            // the agent ends by itself otherwise
        }
    }
}
