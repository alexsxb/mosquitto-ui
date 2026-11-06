package com.dfc.mosquittoui.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.dfc.mosquittoui.broker.AclTester.Identity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AclTesterTest {

    private static final Identity DEVICE = new Identity("device", "id-1");

    @ParameterizedTest
    @CsvSource({
            "a/b, a/b, true",
            "a/b, a/c, false",
            "a/b, a/b/c, false",
            "a/+, a/b, true",
            "a/+, a, false",
            "a/+, a/, true",
            "a/#, a, true",
            "a/#, a/b/c, true",
            "a/+/#, a/b, true",
            "+/#, a, true",
            "#, a/b, true",
            "#, $SYS/x, false",
            "+/x, $SYS/x, false",
            "$SYS/#, $SYS/x, true",
            "$SYS/#, a, false",
            "users/%u/#, users/device/x, true",
            "users/%u/#, users/other/x, false",
            "users/%u, users/device, true",
            "ids/%c/x, ids/id-1/x, true",
            "x%u/y, xdevice/y, false",
    })
    void topics(String rule, String topic, boolean expected) {
        assertEquals(expected, AclTester.matches(rule, topic, DEVICE));
    }

    @ParameterizedTest
    @CsvSource({
            "a/#, a/+, true",
            "a/#, a/#, true",
            "a/+, a/+, true",
            "a/+, a/#, false",
            "+/#, #, false",
            "a/b, a/+, false",
            "#, #, true",
    })
    void subscriptions(String rule, String filter, boolean expected) {
        assertEquals(expected, AclTester.matches(rule, filter, DEVICE));
    }

    @Test
    void nobodyHasNoName() {
        Identity nobody = new Identity(null, null);
        assertFalse(AclTester.matches("users/%u", "users/device", nobody));
        assertFalse(AclTester.matches("users/%u", "users/%u", nobody));
        assertFalse(AclTester.matches("ids/%c", "ids/id-1", nobody));
    }

    @ParameterizedTest
    @CsvSource({"a/b, true", "a/+/b, true", "#, true", "a/#, true", "a/#/b, false", "a+/b, false", "a/b#, false", "+a, false"})
    void filters(String filter, boolean expected) {
        assertEquals(expected, AclTester.validFilter(filter));
    }
}
