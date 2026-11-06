package com.dfc.mosquittoui.backup;

import com.dfc.mosquittoui.audit.AuditLog;
import com.dfc.mosquittoui.backup.crypto.Gpg;
import com.dfc.mosquittoui.backup.crypto.PasswordEncryption;
import com.dfc.mosquittoui.broker.MosquittoConfig;
import com.dfc.mosquittoui.certificate.CertificateService;
import com.dfc.mosquittoui.certificate.CertificateService.SetBackup;
import com.dfc.mosquittoui.config.BridgeService;
import com.dfc.mosquittoui.config.BrokerAgent;
import com.dfc.mosquittoui.config.ConfigService;
import com.dfc.mosquittoui.config.VictronStore;
import com.dfc.mosquittoui.tokenauth.TokenAuthService;
import com.dfc.mosquittoui.importer.DbFile;
import com.dfc.mosquittoui.importer.ImportService;
import com.dfc.mosquittoui.notify.NotificationStore;
import com.dfc.mosquittoui.security.SecretCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.crypto.Mac;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * Backup archives: a ZIP of the chosen parts with a manifest, signed with a key derived from the secret key. What only the broker can read
 * comes through the agent. An archive holds secrets in plain text, which is why parts with secrets are only written to encrypted backups.
 */
@ApplicationScoped
public class ArchiveService {

    /** VALID: from an installation with the same secret key. MISSING: not signed. INVALID: changed after signing, or signed with another key. */
    public enum Signature { VALID, MISSING, INVALID }

    public record Manifest(String format, int version, Instant createdAt, String createdBy, Set<BackupSection> sections) {
    }

    public record Archive(Manifest manifest, Signature signature, boolean encrypted, Map<String, byte[]> entries) {
    }

    /** count: clients, bridges or certificate sets; null where there is nothing to count. */
    public record SectionInfo(BackupSection section, @Schema(nullable = true) Integer count) {
    }

    public record ArchiveInfo(boolean encrypted, Signature signature, Instant createdAt, @Schema(nullable = true) String createdBy, List<SectionInfo> sections) {
    }

    public record ArchiveRestoreReport(List<BackupSection> restored, @Schema(nullable = true) String error) {
    }

    private record CertificateIndex(String active, List<CertificateEntry> sets) {
    }

    /** payload: Base64, as a message can be binary. */
    private record RetainedEntry(String topic, String payload) {
    }

    private record CertificateEntry(com.dfc.mosquittoui.certificate.CertificateStore.StoredSet set, String account) {
    }

    private static final Logger LOG = Logger.getLogger(ArchiveService.class);
    private static final String FORMAT = "mosquitto-ui-backup";
    private static final int VERSION = 1;
    private static final String MANIFEST = "manifest.json";
    private static final String SIGNATURE = "signature.txt";
    private static final String SIGNATURE_PREFIX = "hmac-sha256:";
    private static final String CONFIG = "config/mosquitto-ui.conf";
    private static final String BRIDGES = "bridges/mosquitto-ui-bridges.conf";
    private static final String PORTALS = "bridges/victron.json";
    private static final String CA_PREFIX = "bridges/ca/";
    private static final String ACCESS = "access/dynamic-security.json";
    /** Since 0.17: the identity providers of the token login, with the keys last fetched */
    private static final String TOKEN_AUTH = "access/token-auth.json";
    private static final String SSO = "access/sso.json";
    private static final String UI_USERS = "access/ui-users.json";
    private static final String MESSAGE_RULES = "access/message-rules.json";
    private static final String SETS = "certificates/sets.json";
    private static final String CERT_PREFIX = "certificates/";
    private static final String BACKUPS = "backups/backup.json";
    private static final String NOTIFICATIONS = "backups/notifications.json";
    private static final String RETAINED = "retained/messages.json";
    private static final String AUDIT = "audit/audit-log.jsonl";
    private static final String DYNSEC = "dynamic-security.json";
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.pem");
    private static final Pattern SET_ID = Pattern.compile("[a-z0-9]+");
    private static final String WRONG_PASSWORD = "Wrong password, or the backup is damaged";

    @Inject
    ObjectMapper mapper;

    @Inject
    SecretCipher cipher;

    @Inject
    BrokerAgent agent;

    @Inject
    ConfigService config;

    @Inject
    BridgeService bridges;

    @Inject
    VictronStore victron;

    @Inject
    TokenAuthService tokenAuth;

    @Inject
    com.dfc.mosquittoui.sso.SsoStore sso;

    @Inject
    com.dfc.mosquittoui.localauth.LocalUsers uiUsers;

    @Inject
    com.dfc.mosquittoui.rules.MessageRulesService messageRules;

    @Inject
    CertificateService certificates;

    @Inject
    ImportService imports;

    @Inject
    MosquittoConfig broker;

    @Inject
    BackupStore store;

    @Inject
    NotificationStore notifications;

    @Inject
    AuditLog audit;

    // --- writing -----------------------------------------------------------------------------------------------------------------------

    /** What is in the broker's own volume, handed over by the agent into a directory that only exists for this. */
    private void readFromBroker(boolean access, boolean certs, Map<String, byte[]> entries) throws IOException {
        if (!access && !certs) {
            return;
        }
        Path export = agent.controlFile("export");
        removeTree(export);
        Files.createDirectory(export, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        try {
            agent.call("backup-export", access ? "access" : "-", certs ? "certs" : "-");
            if (access) {
                entries.put(ACCESS, Files.readAllBytes(export.resolve(DYNSEC)));
                String tokens = tokenAuth.export();
                if (tokens != null) {
                    entries.put(TOKEN_AUTH, tokens.getBytes(StandardCharsets.UTF_8));
                }
                String providers = sso.export();
                if (providers != null) {
                    entries.put(SSO, providers.getBytes(StandardCharsets.UTF_8));
                }
                String users = uiUsers.export();
                if (users != null) {
                    entries.put(UI_USERS, users.getBytes(StandardCharsets.UTF_8));
                }
                String rules = messageRules.export();
                if (rules != null) {
                    entries.put(MESSAGE_RULES, rules.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (certs) {
                writeCertificates(export.resolve("certs"), entries);
            }
        } finally {
            removeTree(export);
        }
    }

    private void writeCertificates(Path brokerCerts, Map<String, byte[]> entries) throws IOException {
        List<SetBackup> sets = certificates.exportSets();
        String active = certificates.activeSet();
        List<CertificateEntry> index = new ArrayList<>();
        for (SetBackup set : sets) {
            index.add(new CertificateEntry(set.set(), set.accountKey()));
            String id = set.set().id();
            // The active set is what the broker uses, and from before there were sets its key is only there
            Path keys = id.equals(active) ? brokerCerts : brokerCerts.resolve("sets").resolve(id);
            for (String file : List.of("fullchain.pem", "privkey.pem")) {
                if (Files.exists(keys.resolve(file))) {
                    entries.put(CERT_PREFIX + id + "/" + file, Files.readAllBytes(keys.resolve(file)));
                }
            }
        }
        entries.put(SETS, mapper.writeValueAsBytes(new CertificateIndex(active, index)));
    }

    private static void removeTree(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    public byte[] createZip(Set<BackupSection> sections, String createdBy) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        readFromBroker(sections.contains(BackupSection.ACCESS), sections.contains(BackupSection.CERTIFICATES), entries);
        if (sections.contains(BackupSection.RETAINED)) {
            List<RetainedEntry> messages = new ArrayList<>();
            imports.readRetained().forEach((topic, payload) -> messages.add(new RetainedEntry(topic, Base64.getEncoder().encodeToString(payload))));
            entries.put(RETAINED, mapper.writeValueAsBytes(messages));
        }
        if (sections.contains(BackupSection.CONFIG)) {
            entries.put(CONFIG, config.current().getBytes(StandardCharsets.UTF_8));
        }
        if (sections.contains(BackupSection.BRIDGES)) {
            entries.put(BRIDGES, config.bridgesText().getBytes(StandardCharsets.UTF_8));
            entries.put(PORTALS, mapper.writeValueAsBytes(victron.portals()));
            victron.certificates().forEach((name, pem) -> entries.put(CA_PREFIX + name, pem.getBytes(StandardCharsets.UTF_8)));
        }
        if (sections.contains(BackupSection.BACKUPS)) {
            entries.put(BACKUPS, store.export().getBytes(StandardCharsets.UTF_8));
            entries.put(NOTIFICATIONS, notifications.export().getBytes(StandardCharsets.UTF_8));
        }
        if (sections.contains(BackupSection.AUDIT)) {
            entries.put(AUDIT, audit.export().getBytes(StandardCharsets.UTF_8));
        }
        entries.put(MANIFEST, mapper.writeValueAsBytes(new Manifest(FORMAT, VERSION, Instant.now(), createdBy, sections)));
        entries.put(SIGNATURE, (SIGNATURE_PREFIX + sign(entries) + "\n").getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /** Whoever can write to a target cannot slip in an archive that passes as a backup of this installation. */
    private String sign(Map<String, byte[]> entries) {
        Mac mac = cipher.mac("mosquitto-ui backup archive v1");
        new TreeMap<>(entries).forEach((name, content) -> {
            if (!name.equals(SIGNATURE)) {
                byte[] encodedName = name.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(2 * Long.BYTES).putLong(encodedName.length).putLong(content.length).array());
                mac.update(encodedName);
                mac.update(content);
            }
        });
        return Base64.getEncoder().encodeToString(mac.doFinal());
    }

    private Signature verify(Map<String, byte[]> entries) {
        byte[] stored = entries.get(SIGNATURE);
        if (stored == null) {
            return Signature.MISSING;
        }
        String text = new String(stored, StandardCharsets.US_ASCII).strip();
        boolean valid = text.startsWith(SIGNATURE_PREFIX) && MessageDigest.isEqual(text.substring(SIGNATURE_PREFIX.length()).getBytes(StandardCharsets.US_ASCII),
                sign(entries).getBytes(StandardCharsets.US_ASCII));
        return valid ? Signature.VALID : Signature.INVALID;
    }

    // --- reading -----------------------------------------------------------------------------------------------------------------------

    private static boolean isZip(byte[] data) {
        return data.length >= 2 && data[0] == 'P' && data[1] == 'K';
    }

    /** Plain, or encrypted with a password. GPG archives have to be decrypted first, as this application has no private key. */
    public Archive read(byte[] data, String password) {
        if (Gpg.isEncrypted(data)) {
            throw BackupException.validation("file", "The backup is encrypted with GPG: decrypt it first (gpg -d FILE.gpg > FILE.zip) and upload the ZIP");
        }
        boolean encrypted = PasswordEncryption.isEncrypted(data);
        byte[] zip = data;
        if (encrypted) {
            if (password == null || password.isEmpty()) {
                throw BackupException.validation("password", "The backup is encrypted: enter its password");
            }
            try {
                zip = PasswordEncryption.decrypt(data, password, ArchiveService::isZip);
            } catch (Exception e) {
                throw BackupException.validation("password", WRONG_PASSWORD);
            }
            // The format has no MAC: about one wrong password in 256 decrypts without a padding error into garbage
            if (!isZip(zip)) {
                throw BackupException.validation("password", WRONG_PASSWORD);
            }
        }
        if (!isZip(zip)) {
            throw BackupException.validation("file", "This is not a backup of mosquitto-ui");
        }
        Map<String, byte[]> entries = unzip(zip);
        try {
            byte[] manifestJson = entries.get(MANIFEST);
            Manifest manifest = manifestJson == null ? null : mapper.readValue(manifestJson, Manifest.class);
            if (manifest == null || !FORMAT.equals(manifest.format())) {
                throw BackupException.validation("file", "This is not a backup of mosquitto-ui");
            }
            if (manifest.version() > VERSION) {
                throw BackupException.validation("file", "The backup comes from a newer version of mosquitto-ui");
            }
            return new Archive(manifest, verify(entries), encrypted, entries);
        } catch (IOException e) {
            throw BackupException.validation("file", "The backup is damaged");
        }
    }

    private static Map<String, byte[]> unzip(byte[] zip) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw BackupException.validation("file", "The backup is damaged");
        }
        return entries;
    }

    /** Reads a fresh archive back, so that a run fails right away instead of on the day it is needed. */
    void check(byte[] zip) {
        Archive archive = read(zip, null);
        if (archive.signature() != Signature.VALID) {
            throw new IllegalStateException("The archive does not pass its own signature");
        }
        info(archive);
    }

    public ArchiveInfo info(Archive archive) {
        List<SectionInfo> sections = new ArrayList<>();
        for (BackupSection section : EnumSet.copyOf(archive.manifest().sections())) {
            sections.add(new SectionInfo(section, count(section, archive.entries())));
        }
        Manifest manifest = archive.manifest();
        return new ArchiveInfo(archive.encrypted(), archive.signature(), manifest.createdAt(), manifest.createdBy(), sections);
    }

    private Integer count(BackupSection section, Map<String, byte[]> entries) {
        try {
            return switch (section) {
                case ACCESS -> mapper.readTree(required(entries, ACCESS)).path("clients").size();
                case RETAINED -> mapper.readTree(required(entries, RETAINED)).size();
                case AUDIT -> (int) text(entries, AUDIT).lines().count();
                case BRIDGES -> com.dfc.mosquittoui.config.BridgeFile.parse(text(entries, BRIDGES)).size();
                case CERTIFICATES -> mapper.readValue(required(entries, SETS), CertificateIndex.class).sets().size();
                case BACKUPS -> mapper.readTree(required(entries, BACKUPS)).path("jobs").size();
                case CONFIG -> {
                    required(entries, CONFIG);
                    yield null;
                }
            };
        } catch (IOException e) {
            throw BackupException.validation("file", "The backup is damaged: " + section);
        }
    }

    private static byte[] required(Map<String, byte[]> entries, String name) {
        byte[] content = entries.get(name);
        if (content == null) {
            throw BackupException.validation("file", "The backup is damaged: " + name + " is missing");
        }
        return content;
    }

    private static String text(Map<String, byte[]> entries, String name) {
        return new String(required(entries, name), StandardCharsets.UTF_8);
    }

    // --- restoring ---------------------------------------------------------------------------------------------------------------------

    /** The chosen parts in their fixed order; it stops at the first that fails, the ones before it stay. */
    public ArchiveRestoreReport restore(Archive archive, Set<BackupSection> sections, boolean trustUnsigned) {
        if (sections == null || sections.isEmpty()) {
            throw BackupException.validation("sections", "Select at least one part of the backup");
        }
        if (archive.signature() != Signature.VALID && !trustUnsigned) {
            throw BackupException.validation("trustUnsigned", archive.signature() == Signature.INVALID
                    ? "The backup was changed after signing, or signed with another secret key: confirm that you trust it"
                    : "The backup is not signed: confirm that you trust it");
        }
        Set<BackupSection> chosen = EnumSet.copyOf(sections);
        if (!archive.manifest().sections().containsAll(chosen)) {
            throw BackupException.validation("sections", "The backup does not contain these parts");
        }
        List<BackupSection> restored = new ArrayList<>();
        for (BackupSection section : chosen) {
            try {
                restore(section, archive.entries());
                restored.add(section);
            } catch (IOException | RuntimeException e) {
                LOG.errorf("Restore stopped at %s: %s", section, e.getMessage());
                return new ArchiveRestoreReport(restored, section + ": " + e.getMessage());
            }
        }
        LOG.warnf("Restored %s from a backup of %s", restored, archive.manifest().createdAt());
        return new ArchiveRestoreReport(restored, null);
    }

    private void restore(BackupSection section, Map<String, byte[]> entries) throws IOException {
        switch (section) {
            case CERTIFICATES -> restoreCertificates(entries);
            case ACCESS -> {
                restoreAccess(required(entries, ACCESS));
                // after the roles it maps to; an older backup without it leaves the token login as it is
                tokenAuth.restore(string(entries, TOKEN_AUTH));
                // likewise: an older backup without them leaves the message rules as they are
                messageRules.restore(string(entries, MESSAGE_RULES));
                // the login of the UI; older backups have none, it stays as it is then
                uiUsers.restore(string(entries, UI_USERS));
                sso.restore(string(entries, SSO));
            }
            case RETAINED -> restoreRetained(required(entries, RETAINED));
            case BRIDGES -> restoreBridges(entries);
            case CONFIG -> config.apply(text(entries, CONFIG));
            case BACKUPS -> {
                store.restore(text(entries, BACKUPS));
                notifications.restore(entries.containsKey(NOTIFICATIONS) ? text(entries, NOTIFICATIONS) : null);
            }
            case AUDIT -> audit.restore(text(entries, AUDIT));
        }
    }

    private void restoreRetained(byte[] json) throws IOException {
        List<RetainedEntry> stored = mapper.readValue(json, new TypeReference<List<RetainedEntry>>() {
        });
        imports.restoreRetained(stored.stream().map(entry -> new DbFile.Message(entry.topic(), Base64.getDecoder().decode(entry.payload()), 1, 0, false)).toList());
    }

    private void restoreBridges(Map<String, byte[]> entries) throws IOException {
        Map<String, String> pems = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().startsWith(CA_PREFIX)) {
                String name = entry.getKey().substring(CA_PREFIX.length());
                if (!FILE_NAME.matcher(name).matches()) {
                    throw new IOException("Unexpected file " + entry.getKey());
                }
                pems.put(name, new String(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        Map<String, String> portals = mapper.readValue(required(entries, PORTALS), new TypeReference<TreeMap<String, String>>() {
        });
        victron.restore(portals, pems);
        bridges.restore(text(entries, BRIDGES));
    }

    private void restoreCertificates(Map<String, byte[]> entries) throws IOException {
        CertificateIndex index = mapper.readValue(required(entries, SETS), CertificateIndex.class);
        List<SetBackup> sets = new ArrayList<>();
        for (CertificateEntry entry : index.sets()) {
            String id = entry.set().id();
            if (!SET_ID.matcher(id).matches()) {
                throw new IOException("Unexpected set " + id);
            }
            sets.add(new SetBackup(entry.set(), entry.account(), string(entries, CERT_PREFIX + id + "/fullchain.pem"), string(entries, CERT_PREFIX + id + "/privkey.pem")));
        }
        certificates.restoreSets(index.active(), sets);
    }

    private static String string(Map<String, byte[]> entries, String name) {
        byte[] content = entries.get(name);
        return content == null ? null : new String(content, StandardCharsets.UTF_8);
    }

    private static JsonNode named(JsonNode list, String key, String name) {
        for (JsonNode node : list) {
            if (name.equals(node.path(key).asText())) {
                return node;
            }
        }
        return null;
    }

    /** The UI keeps its own client as it is now: with the one of an older backup, its password might no longer fit and it would be locked out. */
    private void restoreAccess(byte[] json) throws IOException {
        ObjectNode backup = (ObjectNode) mapper.readTree(json);
        String self = broker.username();
        imports.editDynsecFile(current -> {
            JsonNode own = named(current.path("clients"), "username", self);
            ArrayNode clients = backup.withArray("clients");
            for (int i = clients.size() - 1; i >= 0; i--) {
                if (self.equals(clients.get(i).path("username").asText())) {
                    clients.remove(i);
                }
            }
            if (own != null) {
                clients.add(own);
                keepReferences(own, "roles", "rolename", current, backup);
                keepReferences(own, "groups", "groupname", current, backup);
            }
            current.removeAll();
            current.setAll(backup);
        });
    }

    /** The roles and groups of the own client have to exist in what is taken over. */
    private static void keepReferences(JsonNode client, String list, String key, ObjectNode from, ObjectNode to) {
        Set<String> present = new HashSet<>();
        to.path(list).forEach(node -> present.add(node.path(key).asText()));
        for (JsonNode reference : client.path(list)) {
            String name = reference.path(key).asText();
            JsonNode definition = named(from.path(list), key, name);
            if (present.add(name) && definition != null) {
                to.withArray(list).add(definition);
            }
        }
    }
}
