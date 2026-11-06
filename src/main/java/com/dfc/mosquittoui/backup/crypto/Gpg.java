package com.dfc.mosquittoui.backup.crypto;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SignatureSubpacketTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encrypts backups for OpenPGP public keys. The server only knows the public keys; only the holder of a private key
 * can open the backup: {@code gpg -d FILE.gpg > FILE}. Keys can be looked up on a keyserver with the VKS API of
 * keys.openpgp.org.
 */
public final class Gpg {

    /** File extension of OpenPGP-encrypted backups */
    public static final String EXTENSION = ".gpg";

    /**
     * @param fingerprint upper-case hex fingerprint of the primary key
     * @param names       user IDs (name and e-mail); empty if the keyserver does not publish them
     * @param validUntil  null = does not expire
     * @param armored     the public key in ASCII armor, without headers
     * @param problem     why the key cannot be used for encryption right now (expired, revoked, no encryption key); null if usable
     */
    public record Key(String fingerprint, List<String> names, Instant createdAt, Instant validUntil, String algorithm,
                      String armored, String problem) {
    }

    private static final Pattern BLOCK = Pattern.compile(
            "-----BEGIN PGP PUBLIC KEY BLOCK-----.*?-----END PGP PUBLIC KEY BLOCK-----", Pattern.DOTALL);

    private Gpg() {
    }

    /**
     * Reads a public key and checks whether it can be used for encryption today (see {@link Key#problem()}).
     *
     * @throws IllegalArgumentException if the text is not an OpenPGP public key
     */
    public static Key parse(String armored) {
        PGPPublicKeyRing ring = ring(armored);
        PGPPublicKey primary = ring.getPublicKey();
        String problem = null;
        if (primary.hasRevocation()) {
            problem = "The key has been revoked";
        } else {
            try {
                encryptionKey(ring);
            } catch (IllegalArgumentException e) {
                problem = e.getMessage();
            }
        }
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = primary.getUserIDs(); it.hasNext(); ) {
            names.add(it.next());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = ArmoredOutputStream.builder().clearHeaders().build(out)) {
            ring.encode(armor);
        } catch (IOException e) {
            throw new IllegalArgumentException("The key cannot be encoded", e);
        }
        return new Key(HexFormat.of().withUpperCase().formatHex(primary.getFingerprint()), List.copyOf(names),
                primary.getCreationTime().toInstant(), expiry(primary, signatures(primary, primary)).orElse(null), algorithm(primary),
                out.toString(StandardCharsets.US_ASCII), problem);
    }

    /**
     * Reads several keys from one text in which armored blocks follow each other; each fingerprint once.
     *
     * @throws IllegalArgumentException if one of them is not an OpenPGP public key
     */
    public static List<Key> parseAll(String concatenatedArmored) {
        Map<String, Key> result = new LinkedHashMap<>();
        for (String block : blocks(concatenatedArmored)) {
            Key key = parse(block);
            result.putIfAbsent(key.fingerprint(), key);
        }
        return List.copyOf(result.values());
    }

    /** The single blocks of a text; without a recognizable block the text itself */
    private static List<String> blocks(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Matcher m = BLOCK.matcher(text);
        while (m.find()) {
            result.add(m.group());
        }
        return result.isEmpty() ? List.of(text.strip()) : result;
    }

    /**
     * Encrypts for all given keys with one session key (AES-256 with integrity protection), binary OpenPGP format.
     * Each recipient can open the result with their private key. Neither stream is closed.
     *
     * @param armoredKeys public keys; an entry may contain several armored blocks
     * @throws IllegalArgumentException if there is no key or a key is invalid, expired, revoked or cannot encrypt
     */
    public static void encrypt(InputStream in, OutputStream out, List<String> armoredKeys) throws IOException {
        PGPEncryptedDataGenerator generator = new PGPEncryptedDataGenerator(new BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true).setSecureRandom(new SecureRandom()));
        int recipients = 0;
        for (String entry : armoredKeys == null ? List.<String>of() : armoredKeys) {
            for (String block : blocks(entry)) {
                // Better fail than encrypt for someone who should no longer be able to open it
                Key key = parse(block);
                if (key.problem() != null) {
                    throw new IllegalArgumentException("Key " + key.fingerprint() + " cannot be used: " + key.problem());
                }
                generator.addMethod(new BcPublicKeyKeyEncryptionMethodGenerator(encryptionKey(ring(block))));
                recipients++;
            }
        }
        if (recipients == 0) {
            throw new IllegalArgumentException("No OpenPGP key given");
        }
        // Closing the generator streams finishes the packets but leaves out open
        try (OutputStream encrypted = generator.open(out, new byte[1 << 16]);
             // No compression: backups are compressed already
             OutputStream literal = new PGPLiteralDataGenerator().open(encrypted, PGPLiteralData.BINARY, "", new Date(), new byte[1 << 16])) {
            in.transferTo(literal);
        } catch (PGPException e) {
            throw new IOException("Encryption with the OpenPGP key failed: " + e.getMessage(), e);
        }
        out.flush();
    }

    /** Whether the data starts with an OpenPGP packet that carries an encrypted session key (public-key or password) */
    public static boolean isEncrypted(byte[] firstBytes) {
        if (firstBytes == null || firstBytes.length == 0 || (firstBytes[0] & 0x80) == 0) {
            return false;
        }
        int b = firstBytes[0] & 0xFF;
        int tag = (b & 0x40) != 0 ? b & 0x3F : (b >> 2) & 0x0F;
        return tag == 1 || tag == 3;
    }

    /**
     * Looks up a key on a keyserver with the VKS API: by e-mail address, fingerprint (40 or 64 hex characters) or key
     * ID (16 hex characters); spaces and a 0x prefix are ignored.
     *
     * @param keyserverBaseUrl e.g. https://keys.openpgp.org
     * @return null if the keyserver does not know the key
     * @throws IllegalArgumentException if the query is neither an e-mail address nor a fingerprint or key ID, or the
     *                                  server returns something that is not a key
     * @throws IOException              if the keyserver is unreachable or answers with an error
     */
    public static Key search(String query, String keyserverBaseUrl, HttpClient client) throws IOException, InterruptedException {
        String q = query == null ? "" : query.strip();
        String hex = q.replaceAll("\\s", "").replaceFirst("^0[xX]", "").toUpperCase(Locale.ROOT);
        String path;
        if (hex.matches("[0-9A-F]{40}") || hex.matches("[0-9A-F]{64}")) {
            path = "by-fingerprint/" + hex;
        } else if (hex.matches("[0-9A-F]{16}")) {
            path = "by-keyid/" + hex;
        } else if (q.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            path = "by-email/" + URLEncoder.encode(q, StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Please enter an e-mail address, a fingerprint (40 characters) or a key ID (16 characters)");
        }
        HttpResponse<String> response;
        try {
            response = client.send(HttpRequest.newBuilder(URI.create(keyserverBaseUrl.strip().replaceFirst("/+$", "") + "/vks/v1/" + path))
                            .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("Keyserver " + keyserverBaseUrl + " is not reachable (" + e.getClass().getSimpleName() + ")", e);
        }
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("Keyserver responds with " + response.statusCode());
        }
        return parse(response.body());
    }

    private static PGPPublicKeyRing ring(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("No key given");
        }
        try {
            PGPPublicKeyRingCollection rings = new PGPPublicKeyRingCollection(
                    PGPUtil.getDecoderStream(new ByteArrayInputStream(text.strip().getBytes(StandardCharsets.UTF_8))),
                    new BcKeyFingerprintCalculator());
            Iterator<PGPPublicKeyRing> it = rings.getKeyRings();
            if (it.hasNext()) {
                return it.next();
            }
        } catch (IOException | PGPException | RuntimeException e) {
            throw new IllegalArgumentException("This is not an OpenPGP public key", e);
        }
        throw new IllegalArgumentException("This is not an OpenPGP public key");
    }

    /**
     * The (sub)key to encrypt with: valid, not revoked, meant for encryption; subkeys take precedence.
     *
     * @throws IllegalArgumentException if there is none
     */
    private static PGPPublicKey encryptionKey(PGPPublicKeyRing ring) {
        PGPPublicKey primary = ring.getPublicKey();
        // If the primary key expires, so do all its subkeys
        if (expired(primary, signatures(primary, primary))) {
            throw new IllegalArgumentException("The key has expired");
        }
        PGPPublicKey chosen = null;
        boolean expired = false;
        boolean weak = false;
        boolean unbound = false;
        for (Iterator<PGPPublicKey> it = ring.getPublicKeys(); it.hasNext(); ) {
            PGPPublicKey k = it.next();
            if (!k.isEncryptionKey() || k.hasRevocation()) {
                continue;
            }
            // Subkeys count only with a binding signature of the primary key, so that nobody can attach their own
            // subkey to a published key; flags and expiry come from those signatures
            List<PGPSignature> signatures = signatures(primary, k);
            if (!k.isMasterKey() && signatures.isEmpty()) {
                unbound = true;
                continue;
            }
            if (!mayEncrypt(signatures)) {
                continue;
            }
            if (tooWeak(k)) {
                weak = true;
                continue;
            }
            if (expired(k, signatures)) {
                expired = true;
                continue;
            }
            if (chosen == null || chosen.isMasterKey()) {
                chosen = k;
            }
        }
        if (chosen == null) {
            throw new IllegalArgumentException(expired ? "The key has expired"
                    : weak ? "The key is too weak (RSA, DSA or ElGamal below " + MIN_BITS + " bits)"
                    : unbound ? "The encryption subkey is not signed by the primary key"
                    : "The key has no encryption subkey");
        }
        return chosen;
    }

    /** RSA, DSA and ElGamal below 2048 bits; elliptic curves are fine */
    private static final int MIN_BITS = 2048;

    private static boolean tooWeak(PGPPublicKey k) {
        return switch (k.getAlgorithm()) {
            case PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_ENCRYPT, PublicKeyAlgorithmTags.RSA_SIGN,
                 PublicKeyAlgorithmTags.DSA, PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT, PublicKeyAlgorithmTags.ELGAMAL_GENERAL ->
                    k.getBitStrength() < MIN_BITS;
            default -> false;
        };
    }

    /**
     * The signatures that describe a key: for the primary key its own, for a subkey only the binding signatures the
     * primary key really made.
     */
    private static List<PGPSignature> signatures(PGPPublicKey primary, PGPPublicKey k) {
        List<PGPSignature> result = new ArrayList<>();
        if (k.isMasterKey()) {
            k.getSignatures().forEachRemaining(result::add);
            return result;
        }
        for (Iterator<PGPSignature> it = k.getSignaturesOfType(PGPSignature.SUBKEY_BINDING); it.hasNext(); ) {
            PGPSignature s = it.next();
            try {
                s.init(new BcPGPContentVerifierBuilderProvider(), primary);
                if (s.verifyCertification(primary, k)) {
                    result.add(s);
                }
            } catch (PGPException | RuntimeException e) {
                // not a valid binding
            }
        }
        return result;
    }

    /** Usage according to the key's signatures; without key flags it counts as allowed */
    private static boolean mayEncrypt(List<PGPSignature> signatures) {
        boolean flags = false;
        for (PGPSignature s : signatures) {
            if (s.getHashedSubPackets() == null || s.getHashedSubPackets().getSubpacket(SignatureSubpacketTags.KEY_FLAGS) == null) {
                continue;
            }
            flags = true;
            if ((s.getHashedSubPackets().getKeyFlags() & (KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE)) != 0) {
                return true;
            }
        }
        return !flags;
    }

    private static boolean expired(PGPPublicKey k, List<PGPSignature> signatures) {
        return expiry(k, signatures).filter(e -> e.isBefore(Instant.now())).isPresent();
    }

    /** Expiry according to the newest of the signatures (for subkeys: the binding to the primary key) */
    private static Optional<Instant> expiry(PGPPublicKey k, List<PGPSignature> signatures) {
        long seconds = k.getValidSeconds();
        if (seconds <= 0) {
            PGPSignature newest = null;
            for (PGPSignature s : signatures) {
                if (s.getHashedSubPackets() != null && (newest == null || s.getCreationTime().after(newest.getCreationTime()))) {
                    newest = s;
                }
            }
            seconds = newest == null ? 0 : newest.getHashedSubPackets().getKeyExpirationTime();
        }
        return seconds > 0 ? Optional.of(k.getCreationTime().toInstant().plusSeconds(seconds)) : Optional.empty();
    }

    private static String algorithm(PGPPublicKey k) {
        return switch (k.getAlgorithm()) {
            case PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_ENCRYPT, PublicKeyAlgorithmTags.RSA_SIGN -> "RSA " + k.getBitStrength();
            case PublicKeyAlgorithmTags.DSA -> "DSA " + k.getBitStrength();
            case PublicKeyAlgorithmTags.ECDSA, PublicKeyAlgorithmTags.ECDH -> "ECC " + k.getBitStrength();
            case PublicKeyAlgorithmTags.EDDSA_LEGACY, PublicKeyAlgorithmTags.Ed25519, PublicKeyAlgorithmTags.X25519 -> "Curve25519";
            case PublicKeyAlgorithmTags.Ed448, PublicKeyAlgorithmTags.X448 -> "Curve448";
            default -> "Algorithm " + k.getAlgorithm();
        };
    }
}
