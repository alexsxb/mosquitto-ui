package com.dfc.mosquittoui.tokenauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** The public keys of an identity provider: its discovery document, then its JWKS, as PEM for the plugin of the broker. */
public final class Jwks {

    /** A key as the plugin reads it; kid may be null. */
    public record Key(String kid, String pem) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Map<String, String> CURVES = Map.of("P-256", "secp256r1", "P-384", "secp384r1", "P-521", "secp521r1");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();

    private Jwks() {
    }

    private static JsonNode get(ObjectMapper mapper, URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "application/json").GET().build();
        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException(uri + " answered " + response.statusCode());
        }
        if (response.body().length > MAX_BYTES) {
            throw new IOException(uri + " answered with more than 1 MB");
        }
        return mapper.readTree(response.body());
    }

    /** The discovery document of the issuer, which has to name itself as that issuer, then the keys it points to. */
    static List<Key> fetch(ObjectMapper mapper, String issuer) throws IOException {
        URI discovery = URI.create(issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration");
        JsonNode configuration = get(mapper, discovery);
        if (!issuer.equals(configuration.path("issuer").asText())) {
            throw new IOException("The discovery document names another issuer: " + configuration.path("issuer").asText());
        }
        String jwksUri = configuration.path("jwks_uri").asText("");
        if (jwksUri.isEmpty()) {
            throw new IOException("The discovery document has no jwks_uri");
        }
        List<Key> keys = keys(get(mapper, URI.create(jwksUri)));
        if (keys.isEmpty()) {
            throw new IOException("The issuer has no RSA or EC key for signatures");
        }
        return keys;
    }

    /** The JWKS at an address, for an issuer without a discovery document. */
    static List<Key> fetchJwks(ObjectMapper mapper, String jwksUri) throws IOException {
        List<Key> keys = keys(get(mapper, URI.create(jwksUri)));
        if (keys.isEmpty()) {
            throw new IOException("The JWKS has no RSA or EC key for signatures");
        }
        return keys;
    }

    private static final java.util.regex.Pattern PEM = java.util.regex.Pattern.compile(
            "-----BEGIN (PUBLIC KEY|CERTIFICATE)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----");

    /**
     * Keys pasted on the page: one or more PEM blocks of public keys or certificates, or a JWKS (or a single JWK) as JSON. Each
     * has to be RSA of at least 2048 bits or EC on P-256, P-384 or P-521, as the plugin takes nothing else.
     */
    static List<Key> readKeys(ObjectMapper mapper, String text) {
        String trimmed = text == null ? "" : text.strip();
        List<Key> keys = new ArrayList<>();
        if (trimmed.startsWith("{")) {
            JsonNode json;
            try {
                json = mapper.readTree(trimmed);
            } catch (IOException e) {
                throw new IllegalArgumentException("Not readable as JSON: " + e.getMessage().lines().findFirst().orElse(""));
            }
            JsonNode jwks = json.has("keys") ? json : mapper.createObjectNode().set("keys", mapper.createArrayNode().add(json));
            keys.addAll(keys(jwks));
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("No RSA or EC key for signatures in the JSON");
            }
        } else {
            java.util.regex.Matcher matcher = PEM.matcher(trimmed);
            while (matcher.find()) {
                byte[] der = Base64.getMimeDecoder().decode(matcher.group(2).replaceAll("\\s", ""));
                try {
                    PublicKey key = "CERTIFICATE".equals(matcher.group(1))
                            ? java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der)).getPublicKey()
                            : publicKey(der);
                    keys.add(new Key(null, pem(key)));
                } catch (GeneralSecurityException | IllegalArgumentException e) {
                    // what the JDK says here is about its classes, not about the key
                    throw new IllegalArgumentException("Block " + (keys.size() + 1) + " is no public RSA or EC key"
                            + ("CERTIFICATE".equals(matcher.group(1)) ? " in a certificate" : ""));
                }
            }
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("No -----BEGIN PUBLIC KEY----- or -----BEGIN CERTIFICATE----- block");
            }
        }
        for (Key key : keys) {
            check(key.pem());
        }
        return keys;
    }

    private static PublicKey publicKey(byte[] der) throws GeneralSecurityException {
        java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (GeneralSecurityException e) {
            return KeyFactory.getInstance("EC").generatePublic(spec);
        }
    }

    /** What the plugin verifies with: RSA from 2048 bits, EC on the curves of ES256, ES384 and ES512. */
    private static void check(String pem) {
        try {
            PublicKey key = publicKey(Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "")));
            if (key instanceof java.security.interfaces.RSAPublicKey rsa && rsa.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("An RSA key needs at least 2048 bits, this one has " + rsa.getModulus().bitLength());
            }
            if (key instanceof java.security.interfaces.ECPublicKey ec) {
                int bits = ec.getParams().getCurve().getField().getFieldSize();
                if (bits != 256 && bits != 384 && bits != 521) {
                    throw new IllegalArgumentException("An EC key has to be on P-256, P-384 or P-521");
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("A key cannot be read: " + e.getMessage());
        }
    }

    /** RSA and EC keys for signatures; keys for encryption and of other types are left out. */
    static List<Key> keys(JsonNode jwks) {
        List<Key> keys = new ArrayList<>();
        for (JsonNode jwk : jwks.path("keys")) {
            if ("enc".equals(jwk.path("use").asText())) {
                continue;
            }
            try {
                PublicKey key = switch (jwk.path("kty").asText()) {
                    case "RSA" -> KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(number(jwk, "n"), number(jwk, "e")));
                    case "EC" -> ec(jwk);
                    default -> null;
                };
                if (key != null) {
                    keys.add(new Key(jwk.hasNonNull("kid") ? jwk.get("kid").asText() : null, pem(key)));
                }
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                // a key that cannot be read is left out, the others still count
            }
        }
        return keys;
    }

    private static PublicKey ec(JsonNode jwk) throws GeneralSecurityException {
        String curve = CURVES.get(jwk.path("crv").asText());
        if (curve == null) {
            return null;
        }
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(curve));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(number(jwk, "x"), number(jwk, "y")), spec));
    }

    private static BigInteger number(JsonNode jwk, String name) {
        String value = jwk.path(name).asText("");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("No " + name);
        }
        return new BigInteger(1, Base64.getUrlDecoder().decode(value));
    }

    static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded()) + "\n-----END PUBLIC KEY-----\n";
    }
}
