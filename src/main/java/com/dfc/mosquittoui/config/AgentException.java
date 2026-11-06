package com.dfc.mosquittoui.config;

/** The agent in the broker container is not there, or it refused a change; the message then is the reason. */
public class AgentException extends RuntimeException {

    private final boolean unavailable;

    public AgentException(String message, boolean unavailable) {
        super(message);
        this.unavailable = unavailable;
    }

    public boolean unavailable() {
        return unavailable;
    }
}
