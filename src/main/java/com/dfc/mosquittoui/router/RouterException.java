package com.dfc.mosquittoui.router;

/** The router does not answer as an internet gateway; code for the frontend: router_unreachable, router_no_upnp. */
public class RouterException extends RuntimeException {

    private final String code;

    public RouterException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
