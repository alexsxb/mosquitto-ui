package com.dfc.mosquittoui.importer;

import com.dfc.mosquittoui.api.Dtos.Acl;
import com.dfc.mosquittoui.api.Dtos.AuthImportPlan;
import com.dfc.mosquittoui.api.Dtos.AuthImportResult;
import com.dfc.mosquittoui.api.Dtos.ClientView;
import com.dfc.mosquittoui.api.Dtos.ConfImportResult;
import com.dfc.mosquittoui.api.Dtos.ImportAnalysis;
import com.dfc.mosquittoui.api.Dtos.ImportClient;
import com.dfc.mosquittoui.api.Dtos.ImportFile;
import com.dfc.mosquittoui.api.Dtos.ImportFileInfo;
import com.dfc.mosquittoui.api.Dtos.ImportOutcome;
import com.dfc.mosquittoui.api.Dtos.ImportRun;
import com.dfc.mosquittoui.api.Dtos.ImportWarning;
import com.dfc.mosquittoui.api.Dtos.RetainedMessage;
import com.dfc.mosquittoui.api.Dtos.RetainedPlan;
import com.dfc.mosquittoui.api.Dtos.RetainedResult;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.broker.DynsecService;
import com.dfc.mosquittoui.broker.BrokerUnavailableException;
import com.dfc.mosquittoui.broker.DynsecException;
import com.dfc.mosquittoui.config.AgentException;
import com.dfc.mosquittoui.config.BridgeException;
import com.dfc.mosquittoui.config.BrokerAgent;
import com.dfc.mosquittoui.config.ConfImportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Migration of an existing broker: password_file, acl_file, mosquitto.db and configuration files, told apart by content.
 *
 * <p>The control topic takes passwords only in plain text, but the plugin reads hashes from its file. So users and rules go
 * directly into that file while the broker is stopped, in one step and with the hashes unchanged: devices keep their
 * passwords. Retained messages are published like any others, by a temporary client that may publish everywhere.
 */
@ApplicationScoped
public class ImportService {

    private static final Logger LOG = Logger.getLogger(ImportService.class);
    private static final String ROLE_PREFIX = "imported-";
    private static final String PATTERN_ROLE = ROLE_PREFIX + "patterns";
    private static final String ANONYMOUS_ROLE = ROLE_PREFIX + "anonymous";
    /** The user names of the clients it creates for a moment begin with it, and a - */
    public static final String RETAINED_CLIENT = "mosquitto-ui-import";
    private static final int MIN_RETAINED_TOLERANCE = 10;
    private static final int RETAINED_TOLERANCE_DIVISOR = 100;
    private static final int PREVIEW_BYTES = 80;

    @Inject
    DynsecService dynsecService;

    @Inject
    DynsecClient dynsec;

    @Inject
    BrokerAgent agent;

    @Inject
    RetainedPublisher publisher;

    @Inject
    RetainedReader reader;

    @Inject
    ConfImportService confImport;

    @Inject
    ObjectMapper mapper;

    /** Either can be null. */
    record AuthFiles(String passwordFile, String aclFile) {
    }

    private record Plan(AuthImportPlan view, Map<String, String> hashes, AclFile acl) {
    }

    /** Hash type of a line of a password file: Mosquitto reads sha512 ($6$) and sha512-pbkdf2 ($7$). */
    private static String hashType(String hash) {
        if (hash.startsWith("$7$")) {
            return "sha512-pbkdf2";
        }
        if (hash.startsWith("$6$")) {
            return "sha512";
        }
        return hash.startsWith("$argon2") ? "argon2" : null;
    }

    private Plan plan(AuthFiles request) {
        List<ImportWarning> warnings = new ArrayList<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        if (request.passwordFile() != null) {
            for (String raw : request.passwordFile().split("\\R")) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    warnings.add(new ImportWarning("invalid_line", line.length() > 40 ? line.substring(0, 40) : line));
                } else {
                    hashes.put(line.substring(0, colon), line.substring(colon + 1));
                }
            }
        }
        AclFile acl = AclFile.parse(request.aclFile(), warnings);
        Set<String> existing = new HashSet<>();
        dynsecService.listClients().stream().map(ClientView::username).forEach(existing::add);

        List<ImportClient> clients = new ArrayList<>();
        hashes.forEach((user, hash) -> {
            String type = hashType(hash);
            int rules = acl.users.getOrDefault(user, List.of()).size();
            if (type == null) {
                warnings.add(new ImportWarning("not_hashed", user));
            }
            String status = existing.contains(user) ? "EXISTS" : type == null || type.equals("argon2") ? "UNSUPPORTED" : "NEW";
            clients.add(new ImportClient(user, type, status, rules));
        });
        acl.users.keySet().stream().filter(user -> !hashes.containsKey(user)).forEach(user -> warnings.add(new ImportWarning("no_password", user)));
        if (!acl.anonymous.isEmpty()) {
            warnings.add(new ImportWarning("anonymous", ""));
        }
        return new Plan(new AuthImportPlan(clients, acl.patterns.size(), acl.anonymous.size(), warnings), hashes, acl);
    }

    AuthImportPlan previewAuth(AuthFiles request) {
        return plan(request).view();
    }

    /** Stops the broker for a moment; the agent goes back to the old file if it does not start with the new one. */
    AuthImportResult importAuth(AuthFiles request) {
        Plan plan = plan(request);
        List<ImportClient> clients = plan.view().clients().stream().filter(client -> client.status().equals("NEW")).toList();
        int[] roles = new int[1];
        editDynsecFile(config -> roles[0] = merge(config, clients, plan));
        LOG.infof("Imported %d clients and %d roles", clients.size(), roles[0]);
        return new AuthImportResult(clients.size(), roles[0]);
    }

    /** Changes the file of the plugin while the broker is stopped; it stays as .pre-import, and comes back if the broker does not start. */
    public void editDynsecFile(Consumer<ObjectNode> change) {
        agent.call("dynsec-begin");
        try {
            ObjectNode config = (ObjectNode) mapper.readTree(agent.dynsecCopy().toFile());
            change.accept(config);
            mapper.writerWithDefaultPrettyPrinter().writeValue(agent.dynsecCopy().toFile(), config);
            agent.call("dynsec-commit");
            dynsec.awaitConnected();
        } catch (IOException e) {
            agent.call("dynsec-abort");
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            agent.call("dynsec-abort");
            throw e;
        }
    }

    private int merge(ObjectNode config, List<ImportClient> clients, Plan plan) {
        ArrayNode roles = (ArrayNode) config.get("roles");
        Set<String> roleNames = new HashSet<>();
        roles.forEach(role -> roleNames.add(role.path("rolename").asText()));
        int before = roleNames.size();
        AclFile acl = plan.acl();

        boolean patterns = !acl.patterns.isEmpty() && !clients.isEmpty();
        if (patterns) {
            addRole(roles, roleNames, PATTERN_ROLE, acl.patterns);
        }
        ArrayNode configClients = (ArrayNode) config.get("clients");
        for (ImportClient client : clients) {
            ObjectNode entry = configClients.addObject();
            entry.put("username", client.username());
            entry.put("textname", "Imported");
            entry.put("encoded_password", plan.hashes().get(client.username()));
            ArrayNode clientRoles = entry.putArray("roles");
            List<Acl> rules = acl.users.getOrDefault(client.username(), List.of());
            if (!rules.isEmpty()) {
                String role = ROLE_PREFIX + client.username();
                addRole(roles, roleNames, role, rules);
                clientRoles.addObject().put("rolename", role);
            }
            if (patterns) {
                clientRoles.addObject().put("rolename", PATTERN_ROLE);
            }
        }
        if (!acl.anonymous.isEmpty()) {
            addRole(roles, roleNames, ANONYMOUS_ROLE, acl.anonymous);
            String anonymousGroup = config.path("anonymousGroup").asText();
            for (JsonNode group : config.path("groups")) {
                if (group.path("groupname").asText().equals(anonymousGroup)) {
                    ArrayNode groupRoles = group.has("roles") ? (ArrayNode) group.get("roles") : ((ObjectNode) group).putArray("roles");
                    groupRoles.addObject().put("rolename", ANONYMOUS_ROLE);
                }
            }
        }
        return roleNames.size() - before;
    }

    private void addRole(ArrayNode roles, Set<String> names, String name, List<Acl> rules) {
        if (!names.add(name)) {
            return;
        }
        ObjectNode role = roles.addObject();
        role.put("rolename", name);
        role.put("textname", "Imported");
        role.put("allowwildcardsubs", true);
        ArrayNode acls = role.putArray("acls");
        for (Acl rule : rules) {
            acls.addObject().put("acltype", rule.acltype()).put("topic", rule.topic()).put("priority", rule.priority()).put("allow", rule.allow());
        }
    }

    // --- files of an upload ---

    private static String joined(List<UploadedFiles.Entry> entries, UploadedFiles.Kind kind) {
        List<String> texts = entries.stream().filter(entry -> entry.kind() == kind).map(UploadedFiles.Entry::text).toList();
        return texts.isEmpty() ? null : String.join("\n", texts);
    }

    /** The first readable mosquitto.db; a broker has one, and two would decide the same topics. */
    private static byte[] database(List<UploadedFiles.Entry> entries, Map<UploadedFiles.Entry, String> errors) {
        byte[] used = null;
        for (UploadedFiles.Entry entry : entries) {
            if (entry.kind() == UploadedFiles.Kind.DB) {
                try {
                    DbFile.parse(entry.content());
                    if (used == null) {
                        used = entry.content();
                    } else {
                        errors.put(entry, "second_db");
                    }
                } catch (InvalidFileException e) {
                    errors.put(entry, e.getMessage());
                }
            }
        }
        return used;
    }

    /** What the files are and what importing them would do; nothing changes yet. */
    public ImportAnalysis analyze(List<ImportFile> files) {
        List<UploadedFiles.Entry> entries = UploadedFiles.read(files);
        Map<UploadedFiles.Entry, String> errors = new IdentityHashMap<>();
        byte[] database = database(entries, errors);
        String passwords = joined(entries, UploadedFiles.Kind.PASSWORD);
        String acl = joined(entries, UploadedFiles.Kind.ACL);
        String conf = joined(entries, UploadedFiles.Kind.CONF);
        return new ImportAnalysis(
                entries.stream().map(entry -> new ImportFileInfo(entry.name(), entry.kind().name(), errors.get(entry))).toList(),
                passwords == null && acl == null ? null : previewAuth(new AuthFiles(passwords, acl)),
                database == null ? null : previewRetained(database),
                conf == null ? null : confImport.preview(conf));
    }

    /**
     * Users first, as the broker stops for them, and the retained messages before new bridges exist, which would pass them on.
     * A part that fails ends the import; the ones before it stay done.
     */
    public ImportOutcome run(ImportRun run) {
        List<UploadedFiles.Entry> entries = UploadedFiles.read(run.files());
        AuthImportResult auth = null;
        RetainedResult retained = null;
        ConfImportResult conf = null;
        String error = null;
        try {
            String passwords = joined(entries, UploadedFiles.Kind.PASSWORD);
            String acl = joined(entries, UploadedFiles.Kind.ACL);
            if (run.auth() && (passwords != null || acl != null)) {
                auth = importAuth(new AuthFiles(passwords, acl));
            }
            byte[] database = database(entries, new IdentityHashMap<>());
            if (run.retained() && database != null) {
                retained = importRetained(database);
            }
            String text = joined(entries, UploadedFiles.Kind.CONF);
            if (run.conf() && text != null) {
                conf = confImport.run(text);
            }
        } catch (AgentException | BridgeException | BrokerUnavailableException | DynsecException | InvalidFileException e) {
            error = e.getMessage();
            LOG.errorf("Import stopped: %s", error);
        }
        return new ImportOutcome(auth, retained, conf, error);
    }

    // --- retained messages ---

    /** $-topics belong to the broker and cannot be published. */
    private static boolean publishable(DbFile.Message message) {
        boolean expired = message.expiryTime() > 0 && message.expiryTime() < Instant.now().getEpochSecond();
        return !expired && !message.topic().startsWith("$") && message.payload().length > 0;
    }

    private static String preview(byte[] payload) {
        byte[] start = payload.length > PREVIEW_BYTES ? java.util.Arrays.copyOf(payload, PREVIEW_BYTES) : payload;
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(start)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    RetainedPlan previewRetained(byte[] data) {
        DbFile file = DbFile.parse(data);
        List<RetainedMessage> messages = file.retained.stream().filter(ImportService::publishable)
                .map(m -> new RetainedMessage(m.topic(), m.payload().length, m.qos(), preview(m.payload()))).toList();
        int withProperties = (int) file.retained.stream().filter(ImportService::publishable).filter(DbFile.Message::hasProperties).count();
        return new RetainedPlan(file.version, messages, file.retained.size() - messages.size(), withProperties);
    }

    RetainedResult importRetained(byte[] data) {
        List<DbFile.Message> messages = DbFile.parse(data).retained.stream().filter(ImportService::publishable).toList();
        publishRetained(messages);
        return new RetainedResult(messages.size());
    }

    /** With a name of its own each time: a backup may run next to an import. */
    private <T> T asTemporaryClient(List<String> roles, BiFunction<String, String, T> work) {
        if (!dynsec.isConnected()) {
            throw new BrokerUnavailableException("Not connected");
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String password = Base64.getEncoder().encodeToString(random);
        String username = RETAINED_CLIENT + "-" + UUID.randomUUID();
        ObjectNode client = dynsec.command("createClient").put("username", username).put("password", password);
        ArrayNode refs = client.putArray("roles");
        roles.forEach(role -> refs.addObject().put("rolename", role));
        dynsec.call(client);
        try {
            return work.apply(username, password);
        } finally {
            dynsec.call(dynsec.command("deleteClient").put("username", username));
        }
    }

    private void publishRetained(List<DbFile.Message> messages) {
        asTemporaryClient(List.of("client"), (username, password) -> {
            publisher.publish(username, password, messages);
            return null;
        });
    }

    /** After a restart of the broker, as when a backup restores the access first. */
    public int restoreRetained(List<DbFile.Message> messages) {
        dynsec.awaitConnected();
        List<DbFile.Message> publishable = messages.stream().filter(ImportService::publishable).toList();
        publishRetained(publishable);
        return publishable.size();
    }

    /**
     * All retained messages, for a backup. What the broker counts is compared, as a subscriber can lose messages without notice: the
     * number is up to sys_interval old, so a few may differ. Without that number, as the user of this application may not read $SYS,
     * this cannot be checked.
     */
    public Map<String, byte[]> readRetained() {
        Map<String, byte[]> all = asTemporaryClient(List.of("client", "sys-observe"), (username, password) -> reader.read(username, password, List.of("#", "$SYS/#")));
        String counted = dynsec.sysValue("retained messages/count");
        if (counted != null) {
            long expected = Long.parseLong(counted.split(" ", 2)[0]);
            long missing = expected - all.size();
            if (missing > Math.max(MIN_RETAINED_TOLERANCE, expected / RETAINED_TOLERANCE_DIVISOR)) {
                throw new BrokerUnavailableException("Got " + all.size() + " of " + expected + " retained messages, the backup would be incomplete");
            }
        }
        Map<String, byte[]> topics = new TreeMap<>(all);
        topics.keySet().removeIf(topic -> topic.startsWith("$"));
        return topics;
    }
}
