package com.dfc.mosquittoui.rules;

import com.dfc.mosquittoui.backup.BackupException;
import com.dfc.mosquittoui.broker.MosquittoConfig;
import com.dfc.mosquittoui.inspect.InspectService;
import com.dfc.mosquittoui.notify.MailService;
import com.dfc.mosquittoui.notify.Notice;
import com.dfc.mosquittoui.security.PrivateFiles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * The message rules: what a message on a topic has to look like, and how many messages a client may send. Kept in
 * message-rules.json in the state directory and written for the inspect plugin of the broker to rules/rules.json in the
 * shared directory (docker/mosquitto/inspect/rules.c), which refuses what does not hold. The users of this application are
 * never checked: they restore retained messages from backups and publish from the page Topics. A rule with alert sends a mail when it refused messages,
 * at most one an hour.
 */
@ApplicationScoped
public class MessageRulesService {

    public enum RuleAction {
        drop, disconnect
    }

    /** A field the payload must have: path through objects by dots, type number, string, boolean, object, array, null or any. */
    public record FieldCheck(String path, String type) {
    }

    /**
     * Messages on topic (a filter) from clients (empty or * all, a trailing * for a prefix) must be JSON (json), at most
     * maxBytes long, and have the fields.
     */
    public record ContentRule(@Schema(nullable = true) String id, @Schema(nullable = true) String name, String topic,
            @Schema(nullable = true) String clients, boolean json, @Schema(nullable = true) Integer maxBytes, List<FieldCheck> fields,
            RuleAction action, boolean alert, boolean enabled) {
    }

    /** At most perSecond messages from each client of clients, with up to burst at once (else perSecond). */
    public record RateRule(@Schema(nullable = true) String id, @Schema(nullable = true) String name, @Schema(nullable = true) String clients,
            double perSecond, @Schema(nullable = true) Integer burst, RuleAction action, boolean alert, boolean enabled) {
    }

    public record MessageRules(List<ContentRule> content, List<RateRule> rate) {

        public MessageRules {
            content = content == null ? List.of() : List.copyOf(content);
            rate = rate == null ? List.of() : List.copyOf(rate);
        }
    }

    /** What a rule refused since the broker started. */
    public record RuleStats(String id, long dropped, @Schema(nullable = true) Instant lastAt, @Schema(nullable = true) String lastClient,
            @Schema(nullable = true) String lastReason) {
    }

    /**
     * available: whether the plugin could be asked (else reason as InspectService.status); loaded: whether it has the rules;
     * error: why it could not read the file it has.
     */
    public record RulesStatus(boolean available, @Schema(nullable = true) String reason, boolean loaded, @Schema(nullable = true) String error,
            List<RuleStats> stats) {
    }

    private static final Logger LOG = Logger.getLogger(MessageRulesService.class);
    private static final String FILE = "message-rules.json";
    static final int MAX_RULES = 200;
    static final int MAX_FIELDS = 32;
    private static final Set<String> TYPES = Set.of("any", "number", "string", "boolean", "object", "array", "null");
    private static final Duration MAIL_EVERY = Duration.ofHours(1);

    @Inject
    ObjectMapper mapper;

    @Inject
    MosquittoConfig config;

    @Inject
    InspectService inspect;

    @Inject
    MailService mail;

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    @ConfigProperty(name = "mosquitto-ui.shared-dir")
    Path sharedDir;

    /** What each rule had refused at the last look, and when it last sent a mail */
    private final Map<String, Long> seen = new HashMap<>();
    private final Map<String, Instant> mailed = new HashMap<>();
    private boolean looked;

    void onStart(@Observes StartupEvent event) {
        try {
            render(get());
        } catch (RuntimeException e) {
            LOG.warnf("Message rules not written: %s", e.getMessage());
        }
    }

    Path brokerFile() {
        return sharedDir.resolve("rules").resolve("rules.json");
    }

    public synchronized MessageRules get() {
        Path file = stateDir.resolve(FILE);
        if (!Files.exists(file)) {
            return new MessageRules(List.of(), List.of());
        }
        try {
            return mapper.readValue(Files.readString(file), MessageRules.class);
        } catch (IOException e) {
            throw new IllegalStateException("Message rules not readable: " + e.getMessage(), e);
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String id(String id) {
        return blank(id) != null ? id.trim() : UUID.randomUUID().toString();
    }

    /** A topic filter as MQTT allows it: # only as the last level, + only as a whole level. */
    static boolean validFilter(String filter) {
        if (filter == null || filter.isEmpty() || filter.indexOf('\0') >= 0) {
            return false;
        }
        String[] levels = filter.split("/", -1);
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];
            if (level.contains("#") && (!level.equals("#") || i != levels.length - 1)) {
                return false;
            }
            if (level.contains("+") && !level.equals("+")) {
                return false;
            }
        }
        return true;
    }

    private static String clients(String clients, String field) {
        String value = blank(clients);
        if (value == null || value.equals("*")) {
            return null;
        }
        if (value.indexOf('*') >= 0 && value.indexOf('*') != value.length() - 1) {
            throw BackupException.validation(field, "Only a * at the end is allowed");
        }
        return value;
    }

    private static MessageRules checked(MessageRules input) {
        if (input.content().size() > MAX_RULES || input.rate().size() > MAX_RULES) {
            throw BackupException.validation("content", "At most " + MAX_RULES + " rules of each kind");
        }
        List<ContentRule> content = new ArrayList<>();
        for (ContentRule rule : input.content()) {
            String topic = blank(rule.topic());
            if (!validFilter(topic)) {
                throw BackupException.validation("topic", "Not a valid topic filter: " + rule.topic());
            }
            if (rule.maxBytes() != null && rule.maxBytes() < 0) {
                throw BackupException.validation("maxBytes", "Not below 0");
            }
            List<FieldCheck> fields = new ArrayList<>();
            for (FieldCheck field : rule.fields() == null ? List.<FieldCheck>of() : rule.fields()) {
                String path = blank(field.path());
                if (path == null || path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
                    throw BackupException.validation("fields", "Not a path: " + field.path());
                }
                String type = field.type() == null ? "any" : field.type();
                if (!TYPES.contains(type)) {
                    throw BackupException.validation("fields", "Unknown type " + type);
                }
                fields.add(new FieldCheck(path, type));
            }
            if (fields.size() > MAX_FIELDS) {
                throw BackupException.validation("fields", "At most " + MAX_FIELDS + " fields");
            }
            boolean maxBytes = rule.maxBytes() != null && rule.maxBytes() > 0;
            if (!rule.json() && fields.isEmpty() && !maxBytes) {
                throw BackupException.validation("json", "A rule has to check something: JSON, a size or fields");
            }
            content.add(new ContentRule(id(rule.id()), blank(rule.name()), topic, clients(rule.clients(), "clients"), rule.json() || !fields.isEmpty(),
                    maxBytes ? rule.maxBytes() : null, fields, rule.action() == null ? RuleAction.drop : rule.action(), rule.alert(), rule.enabled()));
        }
        List<RateRule> rate = new ArrayList<>();
        for (RateRule rule : input.rate()) {
            if (!(rule.perSecond() > 0) || rule.perSecond() > 100_000) {
                throw BackupException.validation("perSecond", "Between 0 and 100000 messages per second");
            }
            if (rule.burst() != null && (rule.burst() < 1 || rule.burst() > 1_000_000)) {
                throw BackupException.validation("burst", "Between 1 and 1000000 messages");
            }
            rate.add(new RateRule(id(rule.id()), blank(rule.name()), clients(rule.clients(), "clients"), rule.perSecond(), rule.burst(),
                    rule.action() == null ? RuleAction.drop : rule.action(), rule.alert(), rule.enabled()));
        }
        return new MessageRules(content, rate);
    }

    /** Replaces all rules; the broker takes them within a second. */
    public synchronized MessageRules update(MessageRules input) {
        MessageRules rules = checked(input);
        try {
            PrivateFiles.write(stateDir.resolve(FILE), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rules));
        } catch (IOException e) {
            throw new IllegalStateException("Message rules not saved: " + e.getMessage(), e);
        }
        render(rules);
        return rules;
    }

    /** The file of the plugin: the rules that are on, and the broker user of this application, which is never checked. */
    private void render(MessageRules rules) {
        Map<String, Object> file = new LinkedHashMap<>();
        // the users of this application: its own, that of the page Topics, and those that publish retained messages of an import
        // or a backup
        file.put("exemptUsernames", List.of(config.username(), com.dfc.mosquittoui.broker.DynsecService.MONITOR,
                com.dfc.mosquittoui.importer.ImportService.RETAINED_CLIENT + "-*"));
        file.put("content", rules.content().stream().filter(ContentRule::enabled).map(rule -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", rule.id());
            entry.put("topic", rule.topic());
            if (rule.clients() != null) {
                entry.put("clients", rule.clients());
            }
            entry.put("json", rule.json());
            if (rule.maxBytes() != null) {
                entry.put("maxBytes", rule.maxBytes());
            }
            entry.put("fields", rule.fields());
            entry.put("action", rule.action().name());
            return entry;
        }).toList());
        file.put("rate", rules.rate().stream().filter(RateRule::enabled).map(rule -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", rule.id());
            if (rule.clients() != null) {
                entry.put("clients", rule.clients());
            }
            entry.put("perSecond", rule.perSecond());
            if (rule.burst() != null) {
                entry.put("burst", rule.burst());
            }
            entry.put("action", rule.action().name());
            return entry;
        }).toList());
        try {
            String text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(file);
            Path target = brokerFile();
            if (Files.exists(target) && !Files.isSymbolicLink(target) && Files.readString(target).equals(text)) {
                return;
            }
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.deleteIfExists(temporary);
            // read by the broker through the group of the directory
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r-----")));
            Files.writeString(temporary, text, StandardOpenOption.WRITE);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new IllegalStateException("Message rules not written for the broker: " + e.getMessage(), e);
        }
    }

    /** What the plugin counted for each rule since the broker started. */
    public RulesStatus status() {
        InspectService.InspectStatus available = inspect.status();
        if (!available.available()) {
            return new RulesStatus(false, available.reason(), false, null, List.of());
        }
        JsonNode data = inspect.ruleStats();
        List<RuleStats> stats = new ArrayList<>();
        for (String kind : List.of("content", "rate")) {
            for (JsonNode s : data.path(kind)) {
                stats.add(new RuleStats(s.path("id").asText(), s.path("dropped").asLong(),
                        s.path("lastAt").isNumber() ? Instant.ofEpochSecond(s.path("lastAt").asLong()) : null,
                        s.hasNonNull("lastClient") ? s.get("lastClient").asText() : null, s.hasNonNull("lastReason") ? s.get("lastReason").asText() : null));
            }
        }
        return new RulesStatus(true, null, data.path("loaded").asBoolean(), data.hasNonNull("error") ? data.get("error").asText() : null, stats);
    }

    /** Mails for the rules with alert that refused messages since the last look; the first look only takes the counts. */
    @Scheduled(every = "30s", delayed = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void watch() {
        MessageRules rules;
        RulesStatus status;
        try {
            rules = get();
            if (rules.content().stream().noneMatch(ContentRule::alert) && rules.rate().stream().noneMatch(RateRule::alert)) {
                return;
            }
            status = status();
        } catch (RuntimeException e) {
            return;
        }
        if (!status.available()) {
            return;
        }
        Instant now = Instant.now();
        for (RuleStats stats : status.stats()) {
            Long before = seen.put(stats.id(), stats.dropped());
            // a restart of the broker starts again from 0
            long more = before == null ? 0 : stats.dropped() >= before ? stats.dropped() - before : stats.dropped();
            if (!looked || more == 0) {
                continue;
            }
            String name = rules.content().stream().filter(r -> r.id().equals(stats.id()) && r.alert())
                    .map(r -> (r.name() != null ? r.name() + " (" + r.topic() + ")" : r.topic())).findFirst()
                    .orElseGet(() -> rules.rate().stream().filter(r -> r.id().equals(stats.id()) && r.alert())
                            .map(r -> (r.name() != null ? r.name() + " (" : "(") + r.perSecond() + "/s, " + (r.clients() == null ? "*" : r.clients()) + ")")
                            .findFirst().orElse(null));
            if (name == null) {
                continue;
            }
            Instant last = mailed.get(stats.id());
            if (last != null && last.plus(MAIL_EVERY).isAfter(now)) {
                continue;
            }
            mailed.put(stats.id(), now);
            mail.notifyFailure(Notice.RULE, "mosquitto-ui: message rule " + name + " refused messages",
                    "The message rule " + name + " refused " + more + " message(s) since the last look, " + stats.dropped() + " since the broker started.\n\n"
                            + "Last: client " + stats.lastClient() + ", " + stats.lastReason() + ".\n\nAt most one mail an hour for each rule.");
        }
        looked = true;
    }

    /** For the backup: the rules as they are kept, or null without any. */
    public String export() {
        MessageRules rules = get();
        if (rules.content().isEmpty() && rules.rate().isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(rules);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** From a backup; one without rules leaves them as they are. */
    public void restore(String json) {
        if (json == null) {
            return;
        }
        try {
            update(mapper.readValue(json, MessageRules.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("Message rules in the backup are not readable", e);
        }
    }
}
