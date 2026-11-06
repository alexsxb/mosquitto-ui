package com.dfc.mosquittoui.backup.target;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * Server certificates of FTPS: trusted by Java (CA and host name) or pinned by their fingerprint. A certificate Java
 * does not trust (e.g. self-signed) is only remembered when {@code learn} is set, i.e. when an administrator tests the
 * target; a backup run refuses it, before the password is sent.
 */
final class CertificateTrust extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager system;
    private final String pinned;
    private final boolean learn;
    /** Fingerprint of a certificate Java does not trust that was accepted; {@code null} otherwise */
    private volatile String fingerprint;

    CertificateTrust(String pinned, boolean learn) {
        this(systemTrust(), pinned, learn);
    }

    CertificateTrust(X509ExtendedTrustManager system, String pinned, boolean learn) {
        this.system = system;
        this.pinned = pinned == null || pinned.isBlank() ? null : pinned.strip();
        this.learn = learn;
    }

    String fingerprint() {
        return fingerprint;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType, socket);
        } catch (CertificateException e) {
            pinnedOrLearned(chain, e);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType, engine);
        } catch (CertificateException e) {
            pinnedOrLearned(chain, e);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            system.checkServerTrusted(chain, authType);
        } catch (CertificateException e) {
            pinnedOrLearned(chain, e);
        }
    }

    private void pinnedOrLearned(X509Certificate[] chain, CertificateException untrusted) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw untrusted;
        }
        String seen = fingerprint(chain[0]);
        if (seen.equals(pinned) || pinned == null && learn) {
            chain[0].checkValidity();
            fingerprint = seen;
            return;
        }
        throw new CertificateException(pinned == null
                ? "The server certificate is not trusted (" + untrusted.getMessage() + "); test the target to trust "
                + "its fingerprint " + seen
                : "The server presents a different certificate than when it was set up (" + seen + " instead of "
                + pinned + ")", untrusted);
    }

    static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            return "SHA256:" + Base64.getEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new CertificateException(e);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        throw new CertificateException("Client certificates are not accepted");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        throw new CertificateException("Client certificates are not accepted");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Client certificates are not accepted");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return system.getAcceptedIssuers();
    }

    private static X509ExtendedTrustManager systemTrust() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509ExtendedTrustManager x509) {
                    return x509;
                }
            }
            throw new IllegalStateException("No X.509 trust manager");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
