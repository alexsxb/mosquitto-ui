package com.dfc.mosquittoui.tokenauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwksTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anRsaKeyBecomesTheSamePem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey key = (RSAPublicKey) generator.generateKeyPair().getPublic();
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String jwks = "{\"keys\":[{\"kid\":\"k\",\"kty\":\"RSA\",\"n\":\"" + b64.encodeToString(key.getModulus().toByteArray()) + "\",\"e\":\""
                + b64.encodeToString(key.getPublicExponent().toByteArray()) + "\"}]}";
        var keys = Jwks.keys(JSON.readTree(jwks));
        assertEquals(1, keys.size());
        assertEquals("k", keys.get(0).kid());
        assertEquals(Jwks.pem(key), keys.get(0).pem());
    }

    @Test
    void keysForEncryptionAndOfOtherTypesAreLeftOut() throws Exception {
        assertTrue(Jwks.keys(JSON.readTree("""
                {"keys":[{"kty":"oct","k":"c2VjcmV0"},{"kty":"EC","crv":"P-192","x":"AA","y":"AA"},{"kty":"RSA","use":"enc","n":"AQAB","e":"AQAB"},
                {"kty":"RSA","n":"not base64!","e":"AQAB"}]}""")).isEmpty());
    }
}
