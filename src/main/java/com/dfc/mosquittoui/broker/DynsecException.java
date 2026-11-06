package com.dfc.mosquittoui.broker;

/** The dynamic security plugin rejected a command; the message is its error text, e.g. "Client not found". */
public class DynsecException extends RuntimeException {

    public DynsecException(String message) {
        super(message);
    }
}
