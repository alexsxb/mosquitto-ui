package com.dfc.mosquittoui.backup.crypto;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** OpenPGP key pairs generated in Java (RSA 2048, no passphrase), and decryption with their private keys. */
final class TestKeys {

    /** A key pair; armored is the public key */
    record Pair(String armored, PGPSecretKeyRing secret) {
    }

    private TestKeys() {
    }

    /**
     * @param created    creation time of the keys
     * @param validFor   null = does not expire
     * @param encryption with an encryption subkey; without, the key can only sign
     */
    static Pair generate(String userId, Instant created, Duration validFor, boolean encryption) throws Exception {
        return generate(userId, created, validFor, encryption, 2048);
    }

    static Pair generate(String userId, Instant created, Duration validFor, boolean encryption, int bits) throws Exception {
        RSAKeyPairGenerator rsa = new RSAKeyPairGenerator();
        rsa.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), bits, 12));
        Date date = Date.from(created);
        PGPKeyPair primary = new BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, rsa.generateKeyPair(), date);
        PGPSignatureSubpacketGenerator primaryFlags = new PGPSignatureSubpacketGenerator();
        primaryFlags.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA);
        if (validFor != null) {
            primaryFlags.setKeyExpirationTime(false, validFor.toSeconds());
        }
        PGPKeyRingGenerator generator = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, primary, userId,
                new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1), primaryFlags.generate(), null,
                new BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA256), null);
        if (encryption) {
            PGPSignatureSubpacketGenerator subFlags = new PGPSignatureSubpacketGenerator();
            subFlags.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE);
            generator.addSubKey(new BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, rsa.generateKeyPair(), date), subFlags.generate(), null);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = new ArmoredOutputStream(out)) {
            generator.generatePublicKeyRing().encode(armor);
        }
        return new Pair(out.toString(StandardCharsets.US_ASCII), generator.generateSecretKeyRing());
    }

    /** Decrypts binary OpenPGP data with the private key and checks its integrity */
    static byte[] decrypt(byte[] data, PGPSecretKeyRing secret) throws Exception {
        BcPGPObjectFactory factory = new BcPGPObjectFactory(PGPUtil.getDecoderStream(new ByteArrayInputStream(data)));
        Object first = factory.nextObject();
        PGPEncryptedDataList list = first instanceof PGPEncryptedDataList l ? l : (PGPEncryptedDataList) factory.nextObject();
        for (PGPEncryptedData encrypted : list) {
            if (!(encrypted instanceof PGPPublicKeyEncryptedData pk)) {
                continue;
            }
            PGPSecretKey key = secret.getSecretKey(pk.getKeyID());
            if (key == null) {
                continue;
            }
            Object content = new BcPGPObjectFactory(pk.getDataStream(new BcPublicKeyDataDecryptorFactory(key.extractPrivateKey(null))))
                    .nextObject();
            byte[] plain = ((PGPLiteralData) content).getInputStream().readAllBytes();
            if (!pk.isIntegrityProtected() || !pk.verify()) {
                throw new IllegalStateException("Integrity check failed");
            }
            return plain;
        }
        throw new IllegalStateException("Not encrypted for this key");
    }
}
