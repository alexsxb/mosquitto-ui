package com.dfc.mosquittoui.router;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** What the IPv4 address that the router has on its WAN side means for connections from outside. */
public enum AddressKind {
    /** Reachable from the internet, with a port forwarding. */
    PUBLIC,
    /** 100.64.0.0/10: carrier-grade NAT of the provider, nothing comes in. */
    SHARED,
    /** A private address: the router is behind another router or the NAT of a mobile provider. */
    PRIVATE,
    /** DS-Lite: IPv4 only through the tunnel to the provider, nothing comes in. */
    DS_LITE,
    /** No IPv4 at all. */
    NONE;

    static AddressKind of(String address, boolean dsLite) {
        if (dsLite) {
            return DS_LITE;
        }
        if (address == null || address.isBlank() || address.equals("0.0.0.0")) {
            return NONE;
        }
        byte[] bytes;
        try {
            // only literal addresses get here, so nothing is looked up
            bytes = address.matches("[0-9.]+") ? InetAddress.getByName(address).getAddress() : null;
        } catch (UnknownHostException e) {
            bytes = null;
        }
        if (bytes == null || bytes.length != 4) {
            return NONE;
        }
        int a = bytes[0] & 0xff;
        int b = bytes[1] & 0xff;
        int c = bytes[2] & 0xff;
        if (a == 192 && b == 0 && c == 0) {
            // 192.0.0.0/29 is the B4 end of a DS-Lite tunnel, which some routers show as their address
            return DS_LITE;
        }
        if (a == 100 && b >= 64 && b < 128) {
            return SHARED;
        }
        if (a == 10 || (a == 172 && b >= 16 && b < 32) || (a == 192 && b == 168) || (a == 169 && b == 254) || a == 127) {
            return PRIVATE;
        }
        return PUBLIC;
    }
}
