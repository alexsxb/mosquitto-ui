package com.dfc.mosquittoui.backup.target;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.dfc.mosquittoui.backup.target.TargetStorage.checkName;

/**
 * Classic FTP in passive binary mode, optionally with explicit TLS (FTPS); the certificate is checked before the login,
 * see {@link CertificateTrust}.
 */
final class FtpStorage implements TargetStorage {

    private final FTPClient ftp;
    private final CertificateTrust trust;

    FtpStorage(Settings settings, boolean learnCertificate) throws IOException {
        if (settings.tls()) {
            FTPSClient ftps = new FTPSClient(false);
            trust = new CertificateTrust(settings.hostKey(), learnCertificate);
            ftps.setTrustManager(trust);
            ftps.setEndpointCheckingEnabled(true);
            ftp = ftps;
        } else {
            trust = null;
            ftp = new FTPClient();
        }
        ftp.setConnectTimeout(30_000);
        ftp.setDefaultTimeout(30_000);
        ftp.setControlEncoding("UTF-8");
        try {
            ftp.connect(settings.host(), settings.port() > 0 ? settings.port() : 21);
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                throw new IOException("FTP server refuses the connection: " + reply());
            }
            if (!ftp.login(settings.username(), settings.password())) {
                throw new IOException("FTP login failed: " + reply());
            }
            if (ftp instanceof FTPSClient ftps) {
                ftps.execPBSZ(0);
                ftps.execPROT("P");
            }
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            ftp.setSoTimeout(60_000);
            String path = settings.path();
            if (path != null && !path.isBlank()) {
                if (path.startsWith("/")) {
                    ftp.changeWorkingDirectory("/");
                }
                for (String part : path.split("/")) {
                    if (!part.isEmpty() && !ftp.changeWorkingDirectory(part)
                            && !(ftp.makeDirectory(part) && ftp.changeWorkingDirectory(part))) {
                        throw new IOException("FTP directory " + path + " is not reachable: " + reply());
                    }
                }
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private String reply() {
        String reply = ftp.getReplyString();
        return reply == null ? "" : reply.strip();
    }

    @Override
    public void upload(Path file, String name) throws IOException {
        String part = checkName(name) + TEMPORARY_SUFFIX;
        try (InputStream in = Files.newInputStream(file)) {
            if (!ftp.storeFile(part, in)) {
                throw new IOException("FTP upload failed: " + reply());
            }
        }
        ftp.deleteFile(name);
        if (!ftp.rename(part, name)) {
            throw new IOException("FTP rename failed: " + reply());
        }
    }

    /** Plain files from the directory listing; if the server's listing format cannot be parsed, all names. */
    @Override
    public List<FileEntry> entries() throws IOException {
        List<FileEntry> result = new ArrayList<>();
        FTPFile[] entries = ftp.listFiles();
        boolean parsed = entries != null;
        if (entries != null) {
            for (FTPFile f : entries) {
                if (f == null || f.getType() == FTPFile.UNKNOWN_TYPE) {
                    parsed = false;
                    break;
                }
                if (f.isFile()) {
                    result.add(new FileEntry(f.getName().substring(f.getName().lastIndexOf('/') + 1), f.getSize() < 0 ? null : f.getSize(),
                            f.getTimestamp() == null ? null : f.getTimestamp().toInstant()));
                }
            }
        }
        if (!parsed) {
            result.clear();
            String[] names = ftp.listNames();
            if (names != null) {
                for (String n : names) {
                    result.add(new FileEntry(n.substring(n.lastIndexOf('/') + 1), null, null));
                }
            }
        }
        return result;
    }

    @Override
    public void download(String name, Path destination) throws IOException {
        try (OutputStream out = Files.newOutputStream(destination)) {
            if (!ftp.retrieveFile(checkName(name), out)) {
                throw new IOException("FTP download of " + name + " failed: " + reply());
            }
        }
    }

    @Override
    public void delete(String name) throws IOException {
        if (!ftp.deleteFile(checkName(name))) {
            throw new IOException("FTP delete of " + name + " failed: " + reply());
        }
    }

    /** FTPS: fingerprint of a certificate Java does not trust, accepted by its fingerprint */
    @Override
    public String hostKey() {
        return trust == null ? null : trust.fingerprint();
    }

    @Override
    public void close() {
        try {
            if (ftp.isConnected()) {
                ftp.logout();
                ftp.disconnect();
            }
        } catch (IOException e) {
            // connection is gone anyway
        }
    }
}
