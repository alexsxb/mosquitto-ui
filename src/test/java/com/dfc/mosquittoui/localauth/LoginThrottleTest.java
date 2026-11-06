package com.dfc.mosquittoui.localauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    private static LoginThrottle throttle() {
        LoginThrottle throttle = new LoginThrottle();
        throttle.maxFailures = 3;
        throttle.window = Duration.ofMinutes(15);
        return throttle;
    }

    @Test
    void blocksAName() {
        LoginThrottle throttle = throttle();
        for (int i = 0; i < 3; i++) {
            assertNull(throttle.blocked("Admin", "10.0.0.1", NOW));
            throttle.failed("Admin", "10.0.0.1", NOW.plusSeconds(i));
        }
        // the name from another address, in another spelling
        assertEquals(Duration.ofMinutes(15), throttle.blocked("admin", "10.0.0.2", NOW));
        assertNotNull(throttle.blocked("admin", "10.0.0.2", NOW.plus(Duration.ofMinutes(14))));
        assertNull(throttle.blocked("admin", "10.0.0.2", NOW.plus(Duration.ofMinutes(15)).plusSeconds(1)));
    }

    @Test
    void blocksAnAddressTryingNames() {
        LoginThrottle throttle = throttle();
        throttle.failed("a", "10.0.0.9", NOW);
        throttle.failed("b", "10.0.0.9", NOW);
        throttle.failed("c", "10.0.0.9", NOW);
        assertNotNull(throttle.blocked("admin", "10.0.0.9", NOW));
        assertNull(throttle.blocked("admin", "10.0.0.1", NOW));
    }

    @Test
    void successForgetsTheName() {
        LoginThrottle throttle = throttle();
        throttle.failed("admin", "10.0.0.1", NOW);
        throttle.failed("admin", "10.0.0.2", NOW);
        throttle.succeeded("admin");
        throttle.failed("admin", "10.0.0.3", NOW);
        assertNull(throttle.blocked("admin", "10.0.0.4", NOW));
    }

    @Test
    void passwords() {
        String hash = Passwords.hash("correct horse", 1000);
        assertTrue(Passwords.verify("correct horse", hash));
        assertTrue(!Passwords.verify("correct hors", hash));
        assertTrue(!Passwords.verify("anything", null));
        assertTrue(!Passwords.verify("anything", "garbage"));
        assertEquals(20, Passwords.generate().length());
    }
}
