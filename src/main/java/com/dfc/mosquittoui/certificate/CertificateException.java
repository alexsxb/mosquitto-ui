package com.dfc.mosquittoui.certificate;

/**
 * A certificate or set cannot be handled now; code for the frontend: certificate_running, certificate_expired, listener_failed,
 * no_certificate, not_configured, set_active, set_exists, terms_required.
 */
public class CertificateException extends RuntimeException {

    private final String code;

    public CertificateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
