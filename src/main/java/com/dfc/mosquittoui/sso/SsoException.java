package com.dfc.mosquittoui.sso;

/** A change of the identity providers that is not possible; code as in ErrorResponse. */
public class SsoException extends RuntimeException {

    private final String code;
    private final String field;

    public SsoException(String code, String message, String field) {
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
