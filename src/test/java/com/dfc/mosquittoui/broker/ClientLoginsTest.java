package com.dfc.mosquittoui.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dfc.mosquittoui.api.Dtos.ClientLogin;
import com.dfc.mosquittoui.api.Dtos.ClientView;
import com.dfc.mosquittoui.api.Dtos.MissingClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientLoginsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private final ClientLogins logins = new ClientLogins();

    @BeforeEach
    void limits() {
        logins.steadyAfter = Duration.ofHours(1);
        logins.missingAfter = Duration.ofMinutes(15);
        logins.missingFor = Duration.ofDays(7);
    }

    private static ClientLogin login(Duration connected, Duration gone) {
        return new ClientLogin(NOW.minus(gone).minus(connected), NOW.minus(gone), "10.0.0.1");
    }

    private static ClientView client(String name, ClientLogin login, List<String> connections, boolean disabled, boolean locked) {
        return new ClientView(name, "", "", disabled, List.of(), List.of(), connections, locked, login);
    }

    private static ClientView offline(String name, ClientLogin login) {
        return client(name, login, List.of(), false, false);
    }

    private List<String> missing(ClientView... clients) {
        return logins.missing(List.of(clients), NOW).stream().map(MissingClient::username).toList();
    }

    @Test
    void aSteadyClientIsMissedOnceItWasAwayForAWhile() {
        assertEquals(List.of("gone"), missing(offline("gone", login(Duration.ofHours(1), Duration.ofMinutes(16)))));
        assertEquals(List.of(), missing(offline("just-left", login(Duration.ofHours(1), Duration.ofMinutes(14)))));
    }

    @Test
    void devicesThatOnlyDropByAreNotMissed() {
        assertEquals(List.of(), missing(offline("sender", login(Duration.ofMinutes(59), Duration.ofHours(2)))));
    }

    @Test
    void whatIsForgottenIsNoLongerMissed() {
        assertEquals(List.of(), missing(offline("retired", login(Duration.ofDays(30), Duration.ofDays(8)))));
    }

    @Test
    void onlyClientsThatShouldBeThere() {
        ClientLogin steady = login(Duration.ofHours(2), Duration.ofHours(1));
        assertEquals(List.of(), missing(
                client("connected", steady, List.of("10.0.0.1"), false, false),
                client("disabled", steady, List.of(), true, false),
                client("ours", steady, List.of(), false, true),
                offline("never-seen", null)));
    }

    @Test
    void theLatestComesFirst() {
        assertEquals(List.of("recent", "older"), missing(
                offline("older", login(Duration.ofHours(2), Duration.ofDays(2))),
                offline("recent", login(Duration.ofHours(2), Duration.ofHours(1)))));
    }
}
