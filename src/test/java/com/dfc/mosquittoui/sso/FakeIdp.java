package com.dfc.mosquittoui.sso;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;

/**
 * An identity provider for the tests, as little as the code flow needs: discovery, authorize (logs in whoever {@link #next} is, at
 * once), token and keys. Signed tokens, so the OIDC extension checks them as it would at a real one.
 */
@Path("/fake-idp")
public class FakeIdp {

    static final String CLIENT = "mosquitto-ui-test";
    static final String SECRET = "fake-idp-secret-1234567890";

    /** Who logs in next: claims of the ID token and of the access token */
    static volatile Map<String, Object> next = Map.of("sub", "u-1", "preferred_username", "alice");

    private static final RsaJsonWebKey KEY;
    private static final Map<String, Map<String, Object>> CODES = new ConcurrentHashMap<>();
    private static final Map<String, String> NONCES = new ConcurrentHashMap<>();

    static {
        try {
            KEY = RsaJwkGenerator.generateJwk(2048);
            KEY.setKeyId("fake-1");
        } catch (JoseException e) {
            throw new IllegalStateException(e);
        }
    }

    @Context
    UriInfo uri;

    private String issuer() {
        return uri.getBaseUri().toString().replaceAll("/$", "") + "/fake-idp";
    }

    @GET
    @Path("/.well-known/openid-configuration")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> discovery() {
        String i = issuer();
        return Map.of("issuer", i, "authorization_endpoint", i + "/authorize", "token_endpoint", i + "/token", "jwks_uri", i + "/jwks",
                "end_session_endpoint", i + "/logout", "response_types_supported", List.of("code"), "subject_types_supported", List.of("public"),
                "id_token_signing_alg_values_supported", List.of("RS256"));
    }

    @GET
    @Path("/authorize")
    public Response authorize(@QueryParam("client_id") String clientId, @QueryParam("redirect_uri") String redirect, @QueryParam("state") String state,
            @QueryParam("nonce") String nonce) {
        if (!CLIENT.equals(clientId)) {
            return Response.status(400).entity("unknown client").build();
        }
        String code = UUID.randomUUID().toString();
        CODES.put(code, next);
        if (nonce != null) {
            NONCES.put(code, nonce);
        }
        return Response.status(302).location(UriBuilder.fromUri(redirect).queryParam("code", code).queryParam("state", state).build()).build();
    }

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response token(@FormParam("code") String code, @FormParam("client_id") String clientId, @FormParam("client_secret") String secret,
            @jakarta.ws.rs.HeaderParam("Authorization") String authorization) throws JoseException {
        boolean basic = authorization != null && authorization.equals("Basic "
                + java.util.Base64.getEncoder().encodeToString((CLIENT + ":" + SECRET).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (!basic && !(CLIENT.equals(clientId) && SECRET.equals(secret))) {
            return Response.status(401).entity(Map.of("error", "invalid_client")).build();
        }
        Map<String, Object> claims = CODES.remove(code);
        if (claims == null) {
            return Response.status(400).entity(Map.of("error", "invalid_grant")).build();
        }
        Map<String, Object> id = new HashMap<>(claims);
        String nonce = NONCES.remove(code);
        if (nonce != null) {
            id.put("nonce", nonce);
        }
        return Response.ok(Map.of("access_token", sign(claims), "id_token", sign(id), "token_type", "Bearer", "expires_in", 300)).build();
    }

    @GET
    @Path("/jwks")
    @Produces(MediaType.APPLICATION_JSON)
    public String jwks() {
        return new JsonWebKeySet(KEY).toJson(org.jose4j.jwk.JsonWebKey.OutputControlLevel.PUBLIC_ONLY);
    }

    private String sign(Map<String, Object> content) throws JoseException {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(issuer());
        claims.setAudience(CLIENT);
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture(5);
        content.forEach(claims::setClaim);
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(KEY.getPrivateKey());
        jws.setKeyIdHeaderValue(KEY.getKeyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        return jws.getCompactSerialization();
    }
}
