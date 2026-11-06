package com.dfc.mosquittoui.backup.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PasswordEncryptionTest {

    @TempDir
    Path tmp;

    @Test
    void roundTrip() throws Exception {
        byte[] data = new byte[200_001];
        new Random(5).nextBytes(data);
        byte[] encrypted = PasswordEncryption.encrypt(data, "pässwörd");
        assertTrue(PasswordEncryption.isEncrypted(encrypted));
        assertFalse(PasswordEncryption.isEncrypted(data));
        assertArrayEquals(data, PasswordEncryption.decrypt(encrypted, "pässwörd"));
        // Random salt: the same input never gives the same output
        assertFalse(java.util.Arrays.equals(encrypted, PasswordEncryption.encrypt(data, "pässwörd")));
        assertArrayEquals(new byte[0], PasswordEncryption.decrypt(PasswordEncryption.encrypt(new byte[0], "x"), "x"));
    }

    @Test
    void wrongPasswordOrDamagedFile() throws Exception {
        byte[] encrypted = PasswordEncryption.encrypt("backup content".getBytes(StandardCharsets.UTF_8), "right");
        // CBC padding misses a wrong password in about 1 of 256 cases, so try a few
        int rejected = 0;
        for (String password : new String[]{"wrong", "wrong2", "wrong3"}) {
            try {
                PasswordEncryption.decrypt(encrypted, password);
            } catch (GeneralSecurityException e) {
                assertEquals("Wrong password or damaged file", e.getMessage());
                rejected++;
            }
        }
        assertTrue(rejected > 0);
        byte[] truncated = java.util.Arrays.copyOf(encrypted, encrypted.length - 3);
        assertThrows(GeneralSecurityException.class, () -> PasswordEncryption.decrypt(truncated, "right"));
        assertThrows(IOException.class, () -> PasswordEncryption.decrypt("plain text file".getBytes(StandardCharsets.UTF_8), "right"));
        assertThrows(IllegalArgumentException.class, () -> PasswordEncryption.encrypt(new byte[1], ""));
    }

    @Test
    void opensslCanDecryptAndEncrypt() throws Exception {
        assumeTrue(available("openssl", "version"), "openssl not installed");
        byte[] data = new byte[100_000];
        new Random(9).nextBytes(data);
        Path plain = Files.write(tmp.resolve("backup.zip"), data);
        Path encrypted = tmp.resolve("backup.zip.enc");
        try (InputStream in = Files.newInputStream(plain); OutputStream out = Files.newOutputStream(encrypted)) {
            PasswordEncryption.encrypt(in, out, "secret pass");
        }
        Path decrypted = tmp.resolve("decrypted.zip");
        run("openssl", "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-iter", "600000", "-md", "sha256", "-in", encrypted.toString(),
                "-out", decrypted.toString(), "-pass", "pass:secret pass");
        assertArrayEquals(data, Files.readAllBytes(decrypted));

        // And the other way round
        Path fromOpenssl = tmp.resolve("openssl.enc");
        run("openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-iter", "600000", "-in", plain.toString(), "-out", fromOpenssl.toString(),
                "-pass", "pass:secret pass");
        assertArrayEquals(data, PasswordEncryption.decrypt(Files.readAllBytes(fromOpenssl), "secret pass"));
    }

    @Test
    void readsOlderBackupsWithTheirIterations() throws Exception {
        byte[] zip = "PK\u0003\u0004 an older backup".getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream legacy = new java.io.ByteArrayOutputStream();
        PasswordEncryption.encrypt(new java.io.ByteArrayInputStream(zip), legacy, "old password", PasswordEncryption.LEGACY_ITERATIONS);
        java.util.function.Predicate<byte[]> isZip = d -> d.length >= 2 && d[0] == 'P' && d[1] == 'K';
        assertArrayEquals(zip, PasswordEncryption.decrypt(legacy.toByteArray(), "old password", isZip));
        assertArrayEquals(zip, PasswordEncryption.decrypt(PasswordEncryption.encrypt(zip, "new password"), "new password", isZip));
        assertThrows(GeneralSecurityException.class, () -> PasswordEncryption.decrypt(legacy.toByteArray(), "wrong password", isZip));
    }

    private static boolean available(String... command) {
        try {
            return new ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), output);
    }
}
