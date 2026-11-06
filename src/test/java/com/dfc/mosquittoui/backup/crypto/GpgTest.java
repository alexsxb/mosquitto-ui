package com.dfc.mosquittoui.backup.crypto;

import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GpgTest {

    static TestKeys.Pair alice;
    static TestKeys.Pair bob;

    @TempDir
    Path tmp;

    @BeforeAll
    static void keys() throws Exception {
        alice = TestKeys.generate("Alice <alice@example.test>", Instant.now().minusSeconds(60), null, true);
        bob = TestKeys.generate("Bob <bob@example.test>", Instant.now().minusSeconds(60), Duration.ofDays(365), true);
    }

    private static byte[] encrypt(byte[] data, List<String> keys) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Gpg.encrypt(new ByteArrayInputStream(data), out, keys);
        return out.toByteArray();
    }

    @Test
    void parse() {
        Gpg.Key key = Gpg.parse(alice.armored());
        assertEquals(40, key.fingerprint().length());
        assertEquals(List.of("Alice <alice@example.test>"), key.names());
        assertEquals("RSA 2048", key.algorithm());
        assertNotNull(key.createdAt());
        assertNull(key.validUntil());
        assertNull(key.problem());
        // The stored armored text can be read again
        assertEquals(key.fingerprint(), Gpg.parse(key.armored()).fingerprint());

        Gpg.Key expiring = Gpg.parse(bob.armored());
        assertNotNull(expiring.validUntil());
        assertTrue(expiring.validUntil().isAfter(Instant.now()));

        List<Gpg.Key> all = Gpg.parseAll(alice.armored() + "\n" + bob.armored() + "\n" + alice.armored());
        assertEquals(List.of(key.fingerprint(), expiring.fingerprint()), all.stream().map(Gpg.Key::fingerprint).toList());
    }

    @Test
    void invalidKeys() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Gpg.parse("not a key"));
        assertThrows(IllegalArgumentException.class, () -> Gpg.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Gpg.parse(null));

        TestKeys.Pair expired = TestKeys.generate("Old <old@example.test>", Instant.now().minus(Duration.ofDays(3)), Duration.ofDays(1), true);
        assertEquals("The key has expired", Gpg.parse(expired.armored()).problem());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> encrypt(new byte[1], List.of(alice.armored(), expired.armored())));
        assertTrue(e.getMessage().contains("expired"), e.getMessage());

        TestKeys.Pair signOnly = TestKeys.generate("Sign <sign@example.test>", Instant.now().minusSeconds(60), null, false);
        assertEquals("The key has no encryption subkey", Gpg.parse(signOnly.armored()).problem());
        assertThrows(IllegalArgumentException.class, () -> encrypt(new byte[1], List.of(signOnly.armored())));
        assertThrows(IllegalArgumentException.class, () -> encrypt(new byte[1], List.of()));
    }

    @Test
    void weakKeysAndForeignSubkeysAreRefused() throws Exception {
        TestKeys.Pair weak = TestKeys.generate("Weak <weak@example.test>", Instant.now().minusSeconds(60), null, true, 1024);
        assertTrue(Gpg.parse(weak.armored()).problem().contains("too weak"), Gpg.parse(weak.armored()).problem());
        assertThrows(IllegalArgumentException.class, () -> encrypt(new byte[1], List.of(weak.armored())));

        // Bob's encryption subkey attached to Alice's key (e.g. on a keyserver): its binding is Bob's, not Alice's
        TestKeys.Pair aliceSignOnly = TestKeys.generate("Alice <alice@example.test>", Instant.now().minusSeconds(60), null, false);
        PGPPublicKeyRing ring = ring(aliceSignOnly.armored());
        PGPPublicKey bobSubkey = null;
        for (var it = ring(bob.armored()).getPublicKeys(); it.hasNext(); ) {
            var k = it.next();
            if (!k.isMasterKey()) {
                bobSubkey = k;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var armor = new ArmoredOutputStream(out)) {
            PGPPublicKeyRing.insertPublicKey(ring, bobSubkey).encode(armor);
        }
        String forged = out.toString(StandardCharsets.US_ASCII);
        assertEquals("The encryption subkey is not signed by the primary key", Gpg.parse(forged).problem());
        assertThrows(IllegalArgumentException.class, () -> encrypt(new byte[1], List.of(forged)));
    }

    private static PGPPublicKeyRing ring(String armored) throws Exception {
        return new PGPPublicKeyRing(PGPUtil.getDecoderStream(
                new ByteArrayInputStream(armored.getBytes(StandardCharsets.US_ASCII))),
                new BcKeyFingerprintCalculator());
    }

    @Test
    void encryptForSeveralRecipients() throws Exception {
        byte[] data = new byte[300_000];
        new Random(7).nextBytes(data);
        byte[] encrypted = encrypt(data, List.of(alice.armored(), bob.armored()));
        assertTrue(Gpg.isEncrypted(encrypted));
        assertFalse(Gpg.isEncrypted(new byte[]{'P', 'K', 3, 4}));
        assertFalse(Gpg.isEncrypted(new byte[0]));
        assertFalse(Gpg.isEncrypted(PasswordEncryption.encrypt(data, "x")));
        assertArrayEquals(data, TestKeys.decrypt(encrypted, alice.secret()));
        assertArrayEquals(data, TestKeys.decrypt(encrypted, bob.secret()));
    }

    @Test
    void gpgDecrypts() throws Exception {
        assumeTrue(GpgTestHelper.available(), "gpg not installed");
        // Short path: the gpg-agent socket does not accept long directory names
        GpgTestHelper gpg = new GpgTestHelper(Files.createTempDirectory("gpgt"));
        try {
            Gpg.Key key = Gpg.parse(gpg.generate("Backup Test <backup@example.test>", "never"));
            assertNull(key.problem());
            assertTrue(gpg.gpg("--fingerprint", "--with-colons").replace(":", "").contains(key.fingerprint()));

            byte[] data = new byte[1_000_000];
            new Random(11).nextBytes(data);
            Path encrypted = tmp.resolve("backup.zip.gpg");
            try (InputStream in = new ByteArrayInputStream(data); OutputStream out = Files.newOutputStream(encrypted)) {
                Gpg.encrypt(in, out, List.of(key.armored(), alice.armored()));
            }
            Path decrypted = tmp.resolve("backup.zip");
            gpg.decrypt(encrypted, decrypted);
            assertArrayEquals(data, Files.readAllBytes(decrypted));
        } finally {
            gpg.stop();
        }
    }

    @Test
    void searchOnKeyserver() throws Exception {
        String fingerprint = Gpg.parse(alice.armored()).fingerprint();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/vks/v1/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            boolean known = path.equals("/vks/v1/by-email/alice%40example.test") || path.equals("/vks/v1/by-fingerprint/" + fingerprint)
                    || path.equals("/vks/v1/by-keyid/" + fingerprint.substring(24));
            byte[] body = (known ? alice.armored() : "No key found").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(known ? 200 : 404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String url = "http://localhost:" + server.getAddress().getPort() + "/";
            assertEquals(fingerprint, Gpg.search(" alice@example.test ", url, client).fingerprint());
            // Fingerprint in the usual notation with spaces, key ID with 0x
            assertEquals(fingerprint, Gpg.search(fingerprint.toLowerCase().replaceAll("(.{4})", "$1 "), url, client).fingerprint());
            assertEquals(fingerprint, Gpg.search("0x" + fingerprint.substring(24), url, client).fingerprint());
            assertNull(Gpg.search("nobody@example.test", url, client));
            assertThrows(IllegalArgumentException.class, () -> Gpg.search("neither address nor fingerprint", url, client));
        } finally {
            server.stop(0);
        }
    }
}
