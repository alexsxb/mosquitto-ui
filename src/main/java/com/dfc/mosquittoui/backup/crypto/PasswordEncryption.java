package com.dfc.mosquittoui.backup.crypto;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Password encryption in the OpenSSL format (AES-256-CBC, key and IV from PBKDF2-HMAC-SHA256 with 600000 iterations),
 * so that a backup can be opened without pdnsadmin:
 * {@code openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -in FILE.enc -out FILE -pass pass:PASSWORD}.
 * Backups up to version 0.32 used 10000 iterations; the format does not store the number, so reading tries both.
 */
public final class PasswordEncryption {

    /** File extension of password-encrypted backups */
    public static final String EXTENSION = ".enc";
    private static final byte[] MAGIC = "Salted__".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_LENGTH = 8;
    /** As recommended by OWASP for PBKDF2-HMAC-SHA256 */
    static final int ITERATIONS = 600_000;
    /** Of older backups */
    static final int LEGACY_ITERATIONS = 10_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordEncryption() {
    }

    /** Encrypts {@code in} to {@code out}; neither stream is closed. */
    public static void encrypt(InputStream in, OutputStream out, String password) throws IOException, GeneralSecurityException {
        encrypt(in, out, password, ITERATIONS);
    }

    static void encrypt(InputStream in, OutputStream out, String password, int iterations) throws IOException, GeneralSecurityException {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        out.write(MAGIC);
        out.write(salt);
        transfer(in, out, cipher(Cipher.ENCRYPT_MODE, password, salt, iterations));
    }

    /**
     * Decrypts {@code in} to {@code out}; neither stream is closed. On failure, {@code out} may already contain
     * partial output and must be discarded. A wrong password is detected by the padding check, which (as with
     * OpenSSL) misses it in about one of 256 cases.
     *
     * @throws IOException              if the input is not in the OpenSSL format
     * @throws GeneralSecurityException if the password is wrong or the file is damaged
     */
    public static void decrypt(InputStream in, OutputStream out, String password) throws IOException, GeneralSecurityException {
        decrypt(in, out, password, ITERATIONS);
    }

    private static void decrypt(InputStream in, OutputStream out, String password, int iterations) throws IOException, GeneralSecurityException {
        byte[] header = in.readNBytes(MAGIC.length + SALT_LENGTH);
        if (!isEncrypted(header) || header.length < MAGIC.length + SALT_LENGTH) {
            throw new IOException("Not a password-encrypted file (OpenSSL format)");
        }
        transfer(in, out, cipher(Cipher.DECRYPT_MODE, password, Arrays.copyOfRange(header, MAGIC.length, header.length), iterations));
    }

    /**
     * Decrypts a file of this or an older version: with the current number of iterations, otherwise with the old one.
     * Since a wrong key passes the padding check in about one of 256 cases, {@code plausible} tells whether the result
     * is what was expected (e.g. a ZIP).
     *
     * @throws IOException              if the input is not in the OpenSSL format
     * @throws GeneralSecurityException if the password is wrong or the file is damaged
     */
    public static byte[] decrypt(byte[] data, String password, Predicate<byte[]> plausible) throws IOException, GeneralSecurityException {
        GeneralSecurityException failure = new GeneralSecurityException("Wrong password or damaged file");
        for (int iterations : List.of(ITERATIONS, LEGACY_ITERATIONS)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
            try {
                decrypt(new ByteArrayInputStream(data), out, password, iterations);
            } catch (GeneralSecurityException e) {
                failure = e;
                continue;
            }
            byte[] result = out.toByteArray();
            if (plausible.test(result)) {
                return result;
            }
        }
        throw failure;
    }

    /** Whether the data starts like a file written by {@link #encrypt} */
    public static boolean isEncrypted(byte[] firstBytes) {
        return firstBytes != null && firstBytes.length >= MAGIC.length
                && Arrays.equals(firstBytes, 0, MAGIC.length, MAGIC, 0, MAGIC.length);
    }

    public static byte[] encrypt(byte[] data, String password) throws GeneralSecurityException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 32);
        try {
            encrypt(new ByteArrayInputStream(data), out, password);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** @see #decrypt(InputStream, OutputStream, String) */
    public static byte[] decrypt(byte[] data, String password) throws IOException, GeneralSecurityException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        decrypt(new ByteArrayInputStream(data), out, password);
        return out.toByteArray();
    }

    private static Cipher cipher(int mode, String password, byte[] salt, int iterations) throws GeneralSecurityException {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("No password given");
        }
        byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 48 * 8)).getEncoded();
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(Arrays.copyOfRange(derived, 0, 32), "AES"),
                new IvParameterSpec(Arrays.copyOfRange(derived, 32, 48)));
        return cipher;
    }

    private static void transfer(InputStream in, OutputStream out, Cipher cipher) throws IOException, GeneralSecurityException {
        byte[] buffer = new byte[1 << 16];
        int n;
        while ((n = in.read(buffer)) > 0) {
            byte[] chunk = cipher.update(buffer, 0, n);
            if (chunk != null) {
                out.write(chunk);
            }
        }
        try {
            out.write(cipher.doFinal());
        } catch (BadPaddingException | IllegalBlockSizeException e) {
            throw new GeneralSecurityException("Wrong password or damaged file", e);
        }
    }
}
