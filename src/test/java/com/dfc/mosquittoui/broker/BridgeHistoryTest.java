package com.dfc.mosquittoui.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeHistoryTest {

    private static final Instant FIRST = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-11-01T11:00:00Z");

    @TempDir
    Path directory;

    private BridgeHistory history() {
        BridgeHistory history = new BridgeHistory();
        history.mapper = new ObjectMapper().findAndRegisterModules();
        history.stateDir = directory;
        return history;
    }

    @Test
    void aStateThatWasAlreadyThereHasNoTime() {
        BridgeHistory history = history();
        history.record("cerbo", true, true, FIRST);
        assertNull(history.get("cerbo").since());
    }

    @Test
    void aChangeHasOne() {
        BridgeHistory history = history();
        history.record("cerbo", false, false, FIRST);
        assertEquals(FIRST, history.get("cerbo").since());
    }

    @Test
    void theTimeSurvivesARestart() {
        history().record("cerbo", false, false, FIRST);
        BridgeHistory restarted = history();
        restarted.record("cerbo", false, true, SECOND);
        assertEquals(FIRST, restarted.get("cerbo").since());
    }

    @Test
    void aChangeWhileAwayCountsWhenItIsNoticed() {
        history().record("cerbo", false, false, FIRST);
        BridgeHistory restarted = history();
        restarted.record("cerbo", true, true, SECOND);
        assertEquals(SECOND, restarted.get("cerbo").since());
    }
}
