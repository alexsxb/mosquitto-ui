package com.dfc.mosquittoui.notify;

/** What someone can be told about. */
public enum Notice {
    /** This application has no connection to the broker. */
    BROKER,
    /** A bridge lost its connection. */
    BRIDGE,
    /** A client that is usually connected stayed away. */
    CLIENT,
    /** A renewal of the certificate failed. */
    CERTIFICATE,
    /** A backup failed. */
    BACKUP,
    /** A message rule refused messages, for a rule that says to tell. */
    RULE,
    /** The broker logged an error. */
    LOG_ERROR,
    /** The broker rejected many logins in a short time. */
    LOGIN_REJECTED
}
