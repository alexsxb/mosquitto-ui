package com.dfc.mosquittoui.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.api.MosquittoResource;
import com.dfc.mosquittoui.localauth.LocalUsers;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An installation of an older version with QUARKUS_OIDC_*: the provider is taken over, the local login stays off as before. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
@TestProfile(SsoTakeOverTest.Profile.class)
class SsoTakeOverTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.http.auth.permission.fake-idp.paths", "/fake-idp/*", "quarkus.http.auth.permission.fake-idp.policy", "permit",
                    "quarkus.oidc.auth-server-url", "http://localhost:8081/fake-idp", "quarkus.oidc.client-id", FakeIdp.CLIENT,
                    "quarkus.oidc.credentials.secret", FakeIdp.SECRET);
        }
    }

    @TestHTTPResource("/")
    URI base;

    @Inject
    SsoService sso;

    @Inject
    LocalUsers users;

    @Test
    void theEnvironmentBecomesAProviderWithTheAdminRole() throws Exception {
        SsoService.ProviderView p = sso.settings().providers().getFirst();
        assertEquals("oidc", p.id());
        assertTrue(p.fromEnvironment());
        assertEquals(List.of("mosquitto-admin"), p.allowRoles());
        assertFalse(sso.localLogin(), "only the identity provider, as before");
        assertTrue(users.all().isEmpty(), "no local user without the local login");

        FakeIdp.next = Map.of("sub", "k-1", "preferred_username", "erin", "groups", List.of("mosquitto-admin"));
        SsoLoginTest.Browser browser = new SsoLoginTest.Browser();
        assertEquals(null, browser.login(base, "oidc").getQuery());
        HttpResponse<String> me = browser.api(base, "/api/auth/me");
        assertTrue(me.body().contains("\"username\":\"erin\""), me.body());
    }
}
