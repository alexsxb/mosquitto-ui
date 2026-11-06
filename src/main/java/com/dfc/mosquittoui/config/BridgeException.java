package com.dfc.mosquittoui.config;

/** A change of the bridges that cannot be done; the frontend translates the code. */
public class BridgeException extends RuntimeException {

    private final String code;

    public BridgeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
