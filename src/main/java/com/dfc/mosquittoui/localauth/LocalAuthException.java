package com.dfc.mosquittoui.localauth;

/** A change of the local users that is not possible; code as in ErrorResponse. */
public class LocalAuthException extends RuntimeException {

    private final String code;
    private final String field;

    public LocalAuthException(String code, String message, String field) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }
}
