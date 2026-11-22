package com.dfc.mosquittoui.router;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AddressKindTest {

    @Test
    void kinds() {
        assertEquals(AddressKind.PUBLIC, AddressKind.of("203.0.113.7", false));
        assertEquals(AddressKind.SHARED, AddressKind.of("100.64.0.1", false));
        assertEquals(AddressKind.SHARED, AddressKind.of("100.127.255.254", false));
        assertEquals(AddressKind.PUBLIC, AddressKind.of("100.128.0.1", false));
        assertEquals(AddressKind.PRIVATE, AddressKind.of("10.30.192.101", false));
        assertEquals(AddressKind.PRIVATE, AddressKind.of("172.16.0.1", false));
        assertEquals(AddressKind.PUBLIC, AddressKind.of("172.32.0.1", false));
        assertEquals(AddressKind.PRIVATE, AddressKind.of("192.168.178.1", false));
        assertEquals(AddressKind.DS_LITE, AddressKind.of("192.0.0.1", false));
        assertEquals(AddressKind.DS_LITE, AddressKind.of("203.0.113.7", true));
        assertEquals(AddressKind.NONE, AddressKind.of("", false));
        assertEquals(AddressKind.NONE, AddressKind.of("0.0.0.0", false));
        assertEquals(AddressKind.NONE, AddressKind.of("example.com", false));
    }
}
