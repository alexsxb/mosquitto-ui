package com.dfc.mosquittoui.backup.target;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Where a backup is stored: SFTP, FTP (optionally with TLS), S3-compatible storage or a directory on the server.
 * Uploads go to a temporary name and are renamed at the end, so an interrupted transfer never leaves a file that
 * looks like a finished backup.
 */
public interface TargetStorage extends AutoCloseable {

    /** EMAIL sends each file as an attachment and has no directory; it is not opened by {@link #open}. */
    enum Type {SFTP, FTP, LOCAL, S3, EMAIL}

    /**
     * @param path     directory on the target (LOCAL: absolute path; S3: key prefix); empty = start directory
     * @param tls      FTP: explicit TLS (FTPS); S3: HTTPS
     * @param hostKey  SFTP: expected server fingerprint (SHA256:...), empty = accept and report on first connect;
     *                 FTPS: fingerprint of a certificate Java does not trust (e.g. self-signed)
     * @param region   S3 only: bucket region; empty = us-east-1
     * @param bucket   S3 only; host is the endpoint there, username/password are access key and secret key
     */
    record Settings(Type type, String host, int port, String username, String password, String path, boolean tls,
                    String hostKey, String region, String bucket) {
    }

    /** A file on the target; size and modified are null if the target does not tell. */
    record FileEntry(String name, Long size, Instant modified) {
    }

    /** Suffix of a file while it is being uploaded */
    String TEMPORARY_SUFFIX = ".part";

    /** Uploads under a temporary name and renames it where the protocol allows; replaces an existing file. */
    void upload(Path file, String name) throws Exception;

    /** The files in the target directory (or directly under the S3 prefix), without subdirectories */
    List<FileEntry> entries() throws Exception;

    /** The names of {@link #entries}. */
    default List<String> files() throws Exception {
        return entries().stream().map(FileEntry::name).toList();
    }

    /** Copies a file from the target to {@code destination}, replacing it */
    void download(String name, Path destination) throws Exception;

    void delete(String name) throws Exception;

    /** SFTP: fingerprint of the server key after connecting; FTPS: of a certificate trusted by fingerprint; otherwise null */
    default String hostKey() {
        return null;
    }

    @Override
    void close();

    /** Connects to the target; fails early if it is unreachable or the credentials are wrong. */
    static TargetStorage open(Settings settings) throws Exception {
        return open(settings, false);
    }

    /**
     * @param learnCertificate FTPS: trust a certificate Java does not trust by its fingerprint when none is stored yet
     *                         (only when an administrator tests the target)
     */
    static TargetStorage open(Settings settings, boolean learnCertificate) throws Exception {
        return switch (settings.type()) {
            case SFTP -> new SftpStorage(settings);
            case FTP -> new FtpStorage(settings, learnCertificate);
            case LOCAL -> new LocalStorage(settings);
            case S3 -> new S3Storage(settings);
            case EMAIL -> throw new IllegalArgumentException("An e-mail target has no connection to open");
        };
    }

    /** Rejects names that would leave the target directory */
    static String checkName(String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Invalid file name: " + name);
        }
        return name;
    }
}
