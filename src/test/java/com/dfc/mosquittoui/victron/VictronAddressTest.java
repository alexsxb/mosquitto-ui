package com.dfc.mosquittoui.victron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.api.Dtos.VictronPairing;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;

/** The address of a GX device: names, IPv4 and IPv6, never a port in the field of the host. */
class VictronAddressTest {

    private static boolean valid(String host) throws Exception {
        return host.matches(VictronPairing.class.getDeclaredField("host").getAnnotation(Pattern.class).regexp());
    }

    @Test
    void hosts() throws Exception {
        assertTrue(valid("venus.local"));
        assertTrue(valid("192.168.1.20"));
        assertTrue(valid("fd00:dead:beef::1"));
        assertTrue(valid("[fd00:dead:beef::1]"));
        assertTrue(valid("::1"));
        assertFalse(valid("venus.local:8883"), "a port belongs in its own field");
        assertFalse(valid("192.168.1.20:8883"));
        assertFalse(valid("venus/local"));
        assertFalse(valid("[venus.local]"));
    }

    @Test
    void ipv6InSocketsBridgesAndUrls() {
        assertEquals("fd00::1", VictronService.bare("[fd00::1]"));
        assertEquals("fd00::1", VictronService.bare("fd00::1"));
        assertEquals("venus.local", VictronService.bare("venus.local"));
        assertEquals("[fd00::1]", VictronService.inUrl("fd00::1"));
        assertEquals("venus.local", VictronService.inUrl("venus.local"));
    }

    /** As Mosquitto reads the address of a bridge: the port after the last colon. */
    @Test
    void bridgeAddresses() {
        assertEquals(new VictronService.HostPort("fd00:dead:beef::1", 8883), VictronService.address("fd00:dead:beef::1:8883", 1883));
        assertEquals(new VictronService.HostPort("venus.local", 1883), VictronService.address("venus.local:1883 other:1883", 8883));
        assertEquals(new VictronService.HostPort("192.168.1.20", 8883), VictronService.address("192.168.1.20", 8883));
    }
}
