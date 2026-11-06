package com.dfc.mosquittoui.broker;

/** The broker cannot be reached or does not answer. */
public class BrokerUnavailableException extends RuntimeException {

    public BrokerUnavailableException(String message) {
        super(message);
    }
}
