package com.dfc.mosquittoui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.api.Dtos.BridgeForm;
import com.dfc.mosquittoui.api.Dtos.BridgeTopic;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeFileStateTest {

    private static BridgeForm bridge(String... options) {
        return new BridgeForm("a", "host:1883", null, null, null, null, null, null, null, null, null, List.of(new BridgeTopic("t/#", "out", 0, null, null)), List.of(options), false);
    }

    @Test
    void theStateComesWithTheNotificationOnItsDefaultTopic() {
        assertTrue(BridgeFile.reportsState(bridge()));
        assertTrue(BridgeFile.reportsState(bridge("notifications true", "notifications_local_only true")));
        assertFalse(BridgeFile.reportsState(bridge("notifications false")));
        assertFalse(BridgeFile.reportsState(bridge("notification_topic my/state")));
    }

    @Test
    void aBridgeWithoutItIsPointedOut() {
        assertEquals(List.of("noState"), BridgeFile.warnings(bridge("notifications false")));
        assertEquals(List.of(), BridgeFile.warnings(bridge()));
    }
}
