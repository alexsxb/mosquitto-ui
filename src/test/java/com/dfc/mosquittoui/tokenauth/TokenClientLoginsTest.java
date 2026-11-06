package com.dfc.mosquittoui.tokenauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.api.Dtos.MissingClient;
import com.dfc.mosquittoui.broker.ClientLogins;
import com.dfc.mosquittoui.tokenauth.TokenAuthService.TokenClient;
import com.dfc.mosquittoui.tokenauth.TokenClientLogins.SeenTokenClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Clients with a token are missed as those of dynamic security are, unless they cannot come back. */
class TokenClientLoginsTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    @TempDir
    Path dir;

    /** User names that can no longer log in: blocked, their issuer gone, their token taken back */
    private final Set<String> lost = new HashSet<>();
    private TokenClientLogins logins;

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = ClientLogins.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        ClientLogins clientLogins = new ClientLogins();
        set(clientLogins, "steadyAfter", Duration.ofHours(1));
        set(clientLogins, "missingAfter", Duration.ofMinutes(15));
        set(clientLogins, "missingFor", Duration.ofDays(7));
        logins = new TokenClientLogins();
        logins.logins = clientLogins;
        logins.tokenAuth = new TokenAuthService() {
            @Override
            public String lockedOut(String username, String issuer, String sub, String jti) {
                return lost.contains(username) ? "blocked" : null;
            }
        };
        logins.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        logins.stateDir = dir;
    }

    private static TokenClient client(String name) {
        return new TokenClient(name + "/1", name, "https://idp.example.com/realms/devices", "id-of-" + name, "jti-of-" + name, "10.0.0.7", NOW.plus(Duration.ofDays(1)),
                List.of("sensors"));
    }

    private List<String> missing(Instant at) {
        return logins.missing(at).stream().map(MissingClient::username).toList();
    }

    @Test
    void aSteadyTokenClientIsMissedOnceItWasAwayForAWhile() {
        logins.record(List.of(client("steady"), client("sender")), NOW);
        logins.record(List.of(client("steady")), NOW.plus(Duration.ofMinutes(5)));
        logins.record(List.of(client("steady")), NOW.plus(Duration.ofHours(2)));
        logins.record(List.of(), NOW.plus(Duration.ofHours(2)).plusSeconds(15));
        assertEquals(List.of(), missing(NOW.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(10))), "only just gone");
        // the sender was only there for a moment
        assertEquals(List.of("steady"), missing(NOW.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(20))));
        assertEquals(Set.of(), logins.connectedNow());
    }

    @Test
    void oneThatCannotComeBackIsNotMissed() {
        logins.record(List.of(client("blocked")), NOW);
        logins.record(List.of(client("blocked")), NOW.plus(Duration.ofHours(2)));
        logins.record(List.of(), NOW.plus(Duration.ofHours(2)).plusSeconds(15));
        lost.add("blocked");
        assertEquals(List.of(), missing(NOW.plus(Duration.ofHours(3))));
    }

    @Test
    void theLastConnectionsSurviveARestart() throws Exception {
        logins.record(List.of(client("steady")), NOW);
        logins.record(List.of(client("steady")), NOW.plus(Duration.ofHours(2)));
        logins.record(List.of(), NOW.plus(Duration.ofHours(2)).plusSeconds(15));
        TokenAuthService tokenAuth = logins.tokenAuth;
        ClientLogins clientLogins = logins.logins;
        setUp();
        logins.tokenAuth = tokenAuth;
        logins.logins = clientLogins;
        assertNull(logins.connectedNow(), "not looked yet");
        logins.record(List.of(), NOW.plus(Duration.ofHours(3)));
        assertEquals(List.of("steady"), missing(NOW.plus(Duration.ofHours(3))));
    }

    private List<String> seen() {
        return logins.list().stream().map(SeenTokenClient::username).toList();
    }

    @Test
    void oneGoneForLongIsNoLongerMissedButStillSeen() {
        logins.record(List.of(client("retired")), NOW);
        logins.record(List.of(client("retired")), NOW.plus(Duration.ofHours(2)));
        logins.record(List.of(), NOW.plus(Duration.ofDays(8)));
        assertEquals(List.of(), missing(NOW.plus(Duration.ofDays(8))));
        SeenTokenClient retired = logins.list().getFirst();
        assertEquals("retired", retired.username());
        assertFalse(retired.connected());
        assertEquals(NOW.plus(Duration.ofHours(2)), retired.lastSeen());
        assertEquals("retired/1", retired.clientid());
        assertEquals(List.of("sensors"), retired.roles());
        assertNull(retired.lockedOut());
    }

    @Test
    void theConnectedComeFirstAndTheBlockedSayWhy() {
        logins.record(List.of(client("old"), client("blocked")), NOW);
        logins.record(List.of(client("blocked")), NOW.plus(Duration.ofHours(1)));
        logins.record(List.of(client("now")), NOW.plus(Duration.ofHours(2)));
        lost.add("blocked");
        assertEquals(List.of("now", "blocked", "old"), seen());
        assertTrue(logins.list().getFirst().connected());
        assertEquals("blocked", logins.list().get(1).lockedOut());
    }

    @Test
    void aHiddenOneIsGoneUntilItConnectsAgain() throws Exception {
        logins.record(List.of(client("steady")), NOW);
        logins.record(List.of(client("steady")), NOW.plus(Duration.ofHours(2)));
        logins.record(List.of(client("other")), NOW.plus(Duration.ofHours(2)).plusSeconds(15));
        assertThrows(IllegalStateException.class, () -> logins.hide("other"), "connected now");
        assertTrue(logins.hide("steady"));
        assertFalse(logins.hide("steady"));
        assertEquals(List.of("other"), seen());
        assertEquals(List.of(), missing(NOW.plus(Duration.ofHours(3))), "not missed either");
        // also after a restart
        TokenAuthService tokenAuth = logins.tokenAuth;
        ClientLogins clientLogins = logins.logins;
        setUp();
        logins.tokenAuth = tokenAuth;
        logins.logins = clientLogins;
        logins.record(List.of(), NOW.plus(Duration.ofHours(3)));
        assertEquals(List.of("other"), seen());
        logins.record(List.of(client("steady")), NOW.plus(Duration.ofHours(4)));
        assertEquals(List.of("steady", "other"), seen());
    }
}
