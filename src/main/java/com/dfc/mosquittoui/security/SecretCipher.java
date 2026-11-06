package com.dfc.mosquittoui.security;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Encrypts files of the state directory with AES-256-GCM, formatted as {@code enc:v1:<base64(iv|ciphertext)>}. The key comes from
 * mosquitto-ui.secret-key; without it, a key is generated once and kept in the state directory. That needs no setup, but whoever
 * gets the volume gets the key as well.
 */
@ApplicationScoped
public class SecretCipher {

    private static final Logger LOG = Logger.getLogger(SecretCipher.class);
    private static final String PREFIX = "enc:v1:";
    private static final String KEY_FILE = "secret.key";
    private static final int KEY_LENGTH = 32;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    @ConfigProperty(name = "mosquitto-ui.secret-key")
    Optional<String> configuredKey;

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey key;

    private String storedKey() throws IOException {
        Path file = stateDir.resolve(KEY_FILE);
        if (Files.exists(file)) {
            return Files.readString(file).strip();
        }
        byte[] raw = new byte[KEY_LENGTH];
        random.nextBytes(raw);
        String encoded = Base64.getEncoder().encodeToString(raw);
        PrivateFiles.write(file, encoded + "\n");
        LOG.warnf("No MOSQUITTO_UI_SECRET_KEY, generated a key in %s", file);
        return encoded;
    }

    private synchronized SecretKey loadKey() {
        if (key != null) {
            return key;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configuredKey.isPresent() ? configuredKey.get() : storedKey());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The secret key is not valid Base64", e);
        }
        if (raw.length != KEY_LENGTH) {
            throw new IllegalStateException("The secret key must be 32 bytes, Base64-encoded: openssl rand -base64 32");
        }
        key = new SecretKeySpec(raw, "AES");
        return key;
    }

    private SecretKey key() {
        return key != null ? key : loadKey();
    }

    public static boolean sealed(String text) {
        return text.startsWith(PREFIX);
    }

    public String seal(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** A text that is not sealed comes back as it is: files from before there was encryption. */
    public String open(String text) {
        if (!sealed(text)) {
            return text;
        }
        try {
            byte[] data = Base64.getDecoder().decode(text.substring(PREFIX.length()).strip());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
            return new String(cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed, was the secret key changed?", e);
        }
    }

    /** HMAC-SHA256 with a key derived for a purpose, e.g. to sign backups: the same secret key on a new server gives the same result. */
    public Mac mac(String purpose) {
        try {
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(key().getEncoded(), "HmacSHA256"));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derive.doFinal(purpose.getBytes(StandardCharsets.UTF_8)), "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
