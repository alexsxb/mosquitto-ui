package com.dfc.mosquittoui.localauth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashes with PBKDF2-HMAC-SHA256 as OWASP recommends it (600 000 iterations), formatted as
 * {@code pbkdf2-sha256$<iterations>$<base64 salt>$<base64 hash>}; the JDK has all it takes.
 */
final class Passwords {

    static final int ITERATIONS = 600_000;
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** For users that do not exist: the check takes as long as for one that does */
    private static final String DUMMY = hash("not a password of anyone", ITERATIONS);

    private Passwords() {
    }

    static String hash(String password) {
        return hash(password, ITERATIONS);
    }

    static String hash(String password, int iterations) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        Base64.Encoder base64 = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + iterations + "$" + base64.encodeToString(salt) + "$" + base64.encodeToString(derive(password, salt, iterations));
    }

    /** @param stored null for a user that does not exist: false, after the same work */
    static boolean verify(String password, String stored) {
        String[] parts = (stored != null ? stored : DUMMY).split("\\$");
        if (parts.length != 4 || !parts[0].equals(PREFIX)) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            boolean same = MessageDigest.isEqual(expected, derive(password, salt, Integer.parseInt(parts[1])));
            return stored != null && same;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** A password to start with: 20 characters out of 56, about 116 bits, without ones that look alike */
    static String generate() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            password.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return password.toString();
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
