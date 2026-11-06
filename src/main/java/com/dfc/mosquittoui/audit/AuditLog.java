package com.dfc.mosquittoui.audit;

import com.dfc.mosquittoui.api.Dtos.AuditEntry;
import com.dfc.mosquittoui.security.PrivateFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Who changed what, one JSON object per line, so that an entry costs one line and a crash at most the last one. Also in the log of the
 * container, for whoever collects that. A failure to write never stops the change itself.
 */
@ApplicationScoped
public class AuditLog {

    private static final Logger LOG = Logger.getLogger(AuditLog.class);
    private static final String FILE = "audit-log.jsonl";
    /** Thousands of changes by hand are years, and the file stays at a few MB. */
    private static final int KEEP = 20_000;
    private static final int TRIM_EVERY = 1000;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    private List<AuditEntry> entries;

    private List<AuditEntry> entries() {
        if (entries == null) {
            entries = new ArrayList<>();
            Path file = stateDir.resolve(FILE);
            try {
                if (Files.exists(file)) {
                    for (String line : Files.readAllLines(file)) {
                        readLine(line);
                    }
                }
            } catch (IOException e) {
                LOG.warnf("Audit log not read: %s", e.getMessage());
            }
        }
        return entries;
    }

    private void readLine(String line) {
        AuditEntry entry = parse(line);
        if (entry != null) {
            entries.add(entry);
        }
    }

    private AuditEntry parse(String line) {
        try {
            return mapper.readValue(line, AuditEntry.class);
        } catch (IOException e) {
            LOG.warnf("Unreadable line in the audit log: %s", e.getMessage());
            return null;
        }
    }

    public synchronized String export() throws IOException {
        return text(entries());
    }

    /** Adds what is missing: what happened since the backup must not get lost, and neither may a log be replaced by an older one. */
    public synchronized void restore(String text) throws IOException {
        Set<AuditEntry> merged = new LinkedHashSet<>(entries());
        text.lines().map(this::parse).filter(Objects::nonNull).forEach(merged::add);
        List<AuditEntry> sorted = new ArrayList<>(merged);
        sorted.sort(Comparator.comparing(AuditEntry::time));
        entries = new ArrayList<>(sorted.subList(Math.max(0, sorted.size() - KEEP), sorted.size()));
        rewrite(entries);
    }

    public synchronized void record(String user, String action, Map<String, String> details, String error) {
        AuditEntry entry = new AuditEntry(Instant.now(), user, action, details, error);
        LOG.infof("Audit: %s %s %s%s", user, action, details, error == null ? "" : " failed: " + error);
        List<AuditEntry> all = entries();
        all.add(entry);
        try {
            if (all.size() > KEEP + TRIM_EVERY) {
                all.subList(0, all.size() - KEEP).clear();
                rewrite(all);
            } else {
                append(entry);
            }
        } catch (IOException e) {
            LOG.errorf("Audit log not saved: %s", e.getMessage());
        }
    }

    private void append(AuditEntry entry) throws IOException {
        Path file = stateDir.resolve(FILE);
        if (!Files.exists(file)) {
            PrivateFiles.write(file, "");
        }
        Files.writeString(file, mapper.writeValueAsString(entry) + "\n", StandardOpenOption.APPEND);
    }

    private String text(List<AuditEntry> all) throws IOException {
        StringBuilder text = new StringBuilder();
        for (AuditEntry entry : all) {
            text.append(mapper.writeValueAsString(entry)).append('\n');
        }
        return text.toString();
    }

    private void rewrite(List<AuditEntry> all) throws IOException {
        PrivateFiles.write(stateDir.resolve(FILE), text(all));
    }

    /** The newest first. */
    public synchronized List<AuditEntry> latest(int limit) {
        List<AuditEntry> all = entries();
        return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()).reversed());
    }
}
