package com.dfc.mosquittoui.broker;

/** The change would take away this application's own access to the broker. */
public class LockedException extends RuntimeException {

    public LockedException(String message) {
        super(message);
    }
}
