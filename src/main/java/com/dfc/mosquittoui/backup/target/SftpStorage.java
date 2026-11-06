package com.dfc.mosquittoui.backup.target;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchChangedHostKeyException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static com.dfc.mosquittoui.backup.target.TargetStorage.checkName;

/**
 * SFTP with password login; the server key is trusted on first use and compared afterwards, during the key exchange,
 * so that the password never goes to a server with a different key.
 */
final class SftpStorage implements TargetStorage {

    private static final int TIMEOUT_MILLIS = 30_000;

    private final Session session;
    private final ChannelSftp sftp;
    private final String hostKey;

    SftpStorage(Settings settings) throws Exception {
        String expected = settings.hostKey() == null || settings.hostKey().isBlank() ? null : settings.hostKey().strip();
        Fingerprint repository = new Fingerprint(expected);
        session = new JSch().getSession(settings.username(), settings.host(), settings.port() > 0 ? settings.port() : 22);
        session.setPassword(settings.password());
        session.setHostKeyRepository(repository);
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setTimeout(TIMEOUT_MILLIS);
        try {
            try {
                session.connect(TIMEOUT_MILLIS);
            } catch (JSchChangedHostKeyException e) {
                throw new IOException("The SFTP server presents a different key than when it was set up (" + repository.seen
                        + " instead of " + expected + "), connection aborted before the login");
            }
            hostKey = repository.seen;
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(TIMEOUT_MILLIS);
            if (settings.path() != null && !settings.path().isBlank()) {
                changeDirectory(settings.path());
            }
        } catch (Exception e) {
            session.disconnect();
            throw e;
        }
    }

    /** Accepts any key without an expected fingerprint (first contact), otherwise only the expected one. */
    private static final class Fingerprint implements HostKeyRepository {

        private final String expected;
        private volatile String seen;

        Fingerprint(String expected) {
            this.expected = expected;
        }

        @Override
        public int check(String host, byte[] key) {
            try {
                seen = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            return expected == null || expected.equals(seen) ? OK : CHANGED;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
        }

        @Override
        public void remove(String host, String type) {
        }

        @Override
        public void remove(String host, String type, byte[] key) {
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "pdnsadmin";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    /** Changes into the directory, creating missing levels */
    private void changeDirectory(String path) throws SftpException {
        if (path.startsWith("/")) {
            sftp.cd("/");
        }
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                sftp.cd(part);
            } catch (SftpException e) {
                sftp.mkdir(part);
                sftp.cd(part);
            }
        }
    }

    @Override
    public void upload(Path file, String name) throws Exception {
        String part = checkName(name) + TEMPORARY_SUFFIX;
        sftp.put(file.toString(), part, ChannelSftp.OVERWRITE);
        try {
            sftp.rm(name);
        } catch (SftpException e) {
            // did not exist yet
        }
        sftp.rename(part, name);
    }

    @Override
    public List<FileEntry> entries() throws Exception {
        List<FileEntry> result = new ArrayList<>();
        for (Object o : sftp.ls(".")) {
            ChannelSftp.LsEntry e = (ChannelSftp.LsEntry) o;
            if (!e.getAttrs().isDir()) {
                result.add(new FileEntry(e.getFilename(), e.getAttrs().getSize(), Instant.ofEpochSecond(e.getAttrs().getMTime())));
            }
        }
        return result;
    }

    @Override
    public void download(String name, Path destination) throws Exception {
        try (OutputStream out = Files.newOutputStream(destination)) {
            sftp.get(checkName(name), out);
        }
    }

    @Override
    public void delete(String name) throws Exception {
        sftp.rm(checkName(name));
    }

    @Override
    public String hostKey() {
        return hostKey;
    }

    @Override
    public void close() {
        sftp.disconnect();
        session.disconnect();
    }
}
