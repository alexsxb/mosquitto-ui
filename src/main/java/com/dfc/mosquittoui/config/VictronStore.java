package com.dfc.mosquittoui.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import com.dfc.mosquittoui.api.Dtos.BridgeForm;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Which bridges lead to a Victron GX device, with the portal id of the device; all else about them is in the bridge. */
@ApplicationScoped
public class VictronStore {

    private static final TypeReference<TreeMap<String, String>> PORTALS = new TypeReference<>() {
    };

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    @ConfigProperty(name = "mosquitto-ui.shared-dir")
    Path sharedDir;

    @ConfigProperty(name = "mosquitto-ui.broker-shared-dir")
    String brokerSharedDir;

    @Inject
    ObjectMapper mapper;

    /** Bridges whose device presents another certificate than the one taken over when pairing, with the fingerprint of the new one. */
    private final Map<String, String> changedCertificates = new ConcurrentHashMap<>();

    private Path file() {
        return stateDir.resolve("victron.json");
    }

    public synchronized Map<String, String> portals() {
        try {
            return mapper.readValue(Files.readString(file()), PORTALS);
        } catch (NoSuchFileException e) {
            return new TreeMap<>();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void save(Map<String, String> portals) {
        try {
            Files.createDirectories(stateDir);
            Files.writeString(file(), mapper.writeValueAsString(portals));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void put(String bridge, String portalId) {
        Map<String, String> portals = portals();
        portals.put(bridge, portalId);
        save(portals);
    }

    private Path certificate(String bridge) {
        return sharedDir.resolve("bridge-ca").resolve(bridge + ".pem");
    }

    /** Where the broker finds it. */
    public String brokerCertificate(String bridge) {
        return brokerSharedDir + "/bridge-ca/" + bridge + ".pem";
    }

    public void saveCertificate(String bridge, String pem) {
        try {
            Files.createDirectories(certificate(bridge).getParent());
            Files.writeString(certificate(bridge), pem);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The file of the certificate the bridge trusts, from its bridge_cafile; null for a bridge without one of these files. */
    private Path pinnedFile(BridgeForm bridge) {
        String prefix = brokerSharedDir + "/bridge-ca/";
        return bridge.extraOptions().stream().map(String::strip).filter(line -> line.startsWith("bridge_cafile "))
                .map(line -> line.substring("bridge_cafile ".length()).strip()).filter(path -> path.startsWith(prefix))
                .map(path -> path.substring(prefix.length())).filter(name -> name.matches("[^/\\\\]+\\.pem"))
                .map(name -> sharedDir.resolve("bridge-ca").resolve(name)).findFirst().orElse(null);
    }

    /** The certificate taken over when pairing, which the bridge and the keepalive trust; null without one. */
    public String pinned(BridgeForm bridge) {
        Path file = pinnedFile(bridge);
        try {
            return file == null ? null : Files.readString(file);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Replaces the certificate the bridge trusts, in the same file, as its bridge_cafile stays. */
    public void replacePinned(BridgeForm bridge, String pem) {
        Path file = pinnedFile(bridge);
        if (file == null) {
            throw new IllegalArgumentException("Bridge " + bridge.name() + " has no certificate of a GX device");
        }
        try {
            Files.writeString(file, pem);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        changedCertificates.remove(bridge.name());
    }

    /** SHA-256 of the first certificate of a PEM, as hex pairs with colons; null if there is none. */
    public static String fingerprint(String pem) {
        if (pem == null) {
            return null;
        }
        int begin = pem.indexOf("-----BEGIN CERTIFICATE-----");
        int end = pem.indexOf("-----END CERTIFICATE-----", begin + 1);
        if (begin < 0 || end < 0) {
            return null;
        }
        byte[] der = Base64.getMimeDecoder().decode(pem.substring(begin + "-----BEGIN CERTIFICATE-----".length(), end));
        try {
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The device presents a certificate with this fingerprint, not the one the bridge trusts. */
    public void certificateChanged(String bridge, String fingerprint) {
        changedCertificates.put(bridge, fingerprint);
    }

    /** It presents the trusted one again, or the bridge is gone. */
    public void certificateConfirmed(String bridge) {
        changedCertificates.remove(bridge);
    }

    /** The fingerprint of the certificate the device presents instead of the trusted one, null if nothing changed as far as known. */
    public String changedCertificate(String bridge) {
        return changedCertificates.get(bridge);
    }

    /** The file of the certificate stays under the old name, the bridge points to it. */
    public synchronized void rename(String from, String to) {
        Map<String, String> portals = portals();
        String portal = portals.remove(from);
        changedCertificates.remove(from);
        if (portal != null) {
            portals.put(to, portal);
            save(portals);
        }
    }

    /** The certificates of GX devices, by file name. */
    public synchronized Map<String, String> certificates() {
        Map<String, String> pems = new TreeMap<>();
        Path certificates = sharedDir.resolve("bridge-ca");
        if (Files.isDirectory(certificates)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(certificates, "*.pem")) {
                for (Path file : files) {
                    pems.put(file.getFileName().toString(), Files.readString(file));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return pems;
    }

    /** Takes over what a backup holds, in place of what is here. */
    public synchronized void restore(Map<String, String> portals, Map<String, String> pems) {
        clear();
        save(portals);
        try {
            Path certificates = sharedDir.resolve("bridge-ca");
            Files.createDirectories(certificates);
            for (Map.Entry<String, String> pem : pems.entrySet()) {
                Files.writeString(certificates.resolve(pem.getKey()), pem.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void clear() {
        changedCertificates.clear();
        try {
            Files.deleteIfExists(file());
            Path certificates = sharedDir.resolve("bridge-ca");
            if (Files.isDirectory(certificates)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(certificates, "*.pem")) {
                    for (Path certificate : files) {
                        Files.delete(certificate);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void remove(String bridge) {
        changedCertificates.remove(bridge);
        Map<String, String> portals = portals();
        if (portals.remove(bridge) != null) {
            save(portals);
        }
    }
}
