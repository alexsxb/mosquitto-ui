package com.dfc.mosquittoui.certificate;

import com.dfc.mosquittoui.api.Dtos.CertificateSettings;
import com.dfc.mosquittoui.security.PrivateFiles;
import com.dfc.mosquittoui.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Files of the certificate sets: certificate-sets.json with settings, API tokens and which set is active, and sets/&lt;id&gt; with
 * the account key and a copy of the certificate of each set. Only this application reads them; what is secret is encrypted.
 */
@ApplicationScoped
public class CertificateStore {

    static final String ACCOUNT_KEY = "account.pem";
    static final String CERTIFICATE = "fullchain.pem";
    private static final String INDEX = "certificate-sets.json";
    private static final String LEGACY_SETTINGS = "certificate.json";
    private static final int ID_LENGTH = 12;

    /** id: letters and digits only, the agent gets it as a directory name. */
    public record StoredSet(String id, String name, CertificateSettings settings) {
    }

    /** active: the set whose certificate the broker uses, null before the first one is installed. */
    record Index(String active, List<StoredSet> sets) {
    }

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    @Inject
    ObjectMapper mapper;

    @Inject
    SecretCipher cipher;

    private Path file(String name) {
        return stateDir.resolve(name);
    }

    private static String setFile(String id, String name) {
        return "sets/" + id + "/" + name;
    }

    private String read(String name) {
        try {
            return Files.exists(file(name)) ? Files.readString(file(name)) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteTree(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private void write(Index index) {
        try {
            PrivateFiles.write(file(INDEX), cipher.seal(mapper.writeValueAsString(index)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The one certificate from before there were sets becomes the active set; its key stays with the broker. */
    private Index migrate(CertificateSettings settings) throws IOException {
        String id = newId();
        Files.createDirectories(file("sets/" + id));
        for (String name : List.of(ACCOUNT_KEY, CERTIFICATE)) {
            if (Files.exists(file(name))) {
                Files.move(file(name), file(setFile(id, name)));
            }
        }
        boolean issued = Files.exists(file(setFile(id, CERTIFICATE)));
        Index index = new Index(issued ? id : null, List.of(new StoredSet(id, settings.domains().getFirst(), settings)));
        write(index);
        Files.delete(file(LEGACY_SETTINGS));
        return index;
    }

    private Index index() {
        try {
            String json = read(INDEX);
            if (json != null) {
                Index index = mapper.readValue(cipher.open(json), Index.class);
                if (!SecretCipher.sealed(json)) {
                    write(index);
                }
                return index;
            }
            String legacy = read(LEGACY_SETTINGS);
            return legacy == null ? new Index(null, List.of()) : migrate(mapper.readValue(legacy, CertificateSettings.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, ID_LENGTH);
    }

    public synchronized List<StoredSet> sets() {
        return index().sets();
    }

    public synchronized String activeId() {
        return index().active();
    }

    public synchronized StoredSet set(String id) {
        return index().sets().stream().filter(set -> set.id().equals(id)).findFirst().orElse(null);
    }

    /** Adds the set, or replaces the one with its id. */
    public synchronized void save(StoredSet set) {
        Index index = index();
        List<StoredSet> sets = new ArrayList<>(index.sets());
        int position = sets.stream().map(StoredSet::id).toList().indexOf(set.id());
        if (position < 0) {
            sets.add(set);
        } else {
            sets.set(position, set);
        }
        write(new Index(index.active(), sets));
    }

    public synchronized void activate(String id) {
        write(new Index(id, index().sets()));
    }

    public synchronized void delete(String id) throws IOException {
        Index index = index();
        write(new Index(index.active(), index.sets().stream().filter(set -> !set.id().equals(id)).toList()));
        deleteTree(file("sets/" + id));
    }

    /** All sets with their tokens, account keys and certificate copies. */
    synchronized void clear() throws IOException {
        // what still lies there from before the sets goes as well
        index();
        Files.deleteIfExists(file(INDEX));
        deleteTree(file("sets"));
    }

    /** A key from before there was encryption gets encrypted on the way. */
    String accountKey(String id) {
        try {
            String stored = read(setFile(id, ACCOUNT_KEY));
            if (stored == null) {
                return null;
            }
            String pem = cipher.open(stored);
            if (!SecretCipher.sealed(stored)) {
                saveAccountKey(id, pem);
            }
            return pem;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void saveAccountKey(String id, String pem) throws IOException {
        PrivateFiles.write(file(setFile(id, ACCOUNT_KEY)), cipher.seal(pem));
    }

    void saveCertificate(String id, String pem) throws IOException {
        PrivateFiles.write(file(setFile(id, CERTIFICATE)), pem);
    }

    /** The chain of a set as stored, null without one. */
    String chain(String id) {
        return read(setFile(id, CERTIFICATE));
    }

    /** The certificate of a set; the agent has moved the key out of reach, so this copy is what can be shown. */
    public X509Certificate certificate(String id) {
        String pem = id == null ? null : read(setFile(id, CERTIFICATE));
        return pem == null ? null : parse(pem);
    }

    /** The first certificate of a chain. */
    static X509Certificate parse(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
