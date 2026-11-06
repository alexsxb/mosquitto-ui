package com.dfc.mosquittoui.backup;

import com.dfc.mosquittoui.notify.NotificationStore.MailSecurity;
import com.dfc.mosquittoui.security.PrivateFiles;
import com.dfc.mosquittoui.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Targets, jobs, runs and settings of the backups, in one file that is encrypted as a whole. */
@ApplicationScoped
public class BackupStore {

    private static final String FILE = "backup.json";

    /** The settings of the backups. The fields of the mail server only come from files and backups of older versions. */
    public static class Settings {
        public String timeZone = "Europe/Berlin";
        public int runRetentionDays = 90;
        public List<String> notifyRecipients;
        public String mailHost;
        public Integer mailPort;
        public MailSecurity mailSecurity;
        public String mailUsername;
        public String mailPassword;
        public String mailFrom;
    }

    /** The mail server as older versions kept it here. */
    public record OldMail(List<String> recipients, String host, Integer port, MailSecurity security, String username, String password, String from) {
    }

    /** All there is. */
    public static class State {
        public long nextId = 1;
        public Settings settings = new Settings();
        public List<BackupTarget> targets = new ArrayList<>();
        public List<BackupJob> jobs = new ArrayList<>();
        public List<BackupRun> runs = new ArrayList<>();

        public long newId() {
            return nextId++;
        }
    }

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    @Inject
    SecretCipher cipher;

    @Inject
    ObjectMapper mapper;

    private State state;

    private State load() {
        if (state == null) {
            state = parse();
        }
        return state;
    }

    private State parse() {
        try {
            Path file = stateDir.resolve(FILE);
            return Files.exists(file) ? mapper.readValue(cipher.open(Files.readString(file)), State.class) : new State();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void save() {
        try {
            PrivateFiles.write(stateDir.resolve(FILE), cipher.seal(mapper.writeValueAsString(state)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the function returns must not be changed outside of {@link #update}. */
    public synchronized <T> T read(Function<State, T> reader) {
        return reader.apply(load());
    }

    /** A change that fails half way leaves the file alone, and the memory with what the file says. */
    public synchronized <T> T update(Function<State, T> change) {
        try {
            T result = change.apply(load());
            save();
            return result;
        } catch (RuntimeException e) {
            state = null;
            throw e;
        }
    }

    public void change(Consumer<State> change) {
        update(state -> {
            change.accept(state);
            return null;
        });
    }

    /** Hands over a mail server of an older version, if there is one, and forgets it once that went through. */
    public synchronized void moveOldMail(Consumer<OldMail> receiver) {
        Settings s = load().settings;
        boolean recipients = s.notifyRecipients != null && !s.notifyRecipients.isEmpty();
        if (s.mailHost == null && s.mailFrom == null && !recipients) {
            return;
        }
        receiver.accept(new OldMail(recipients ? s.notifyRecipients : List.of(), s.mailHost, s.mailPort, s.mailSecurity, s.mailUsername, s.mailPassword, s.mailFrom));
        change(state -> {
            s.notifyRecipients = null;
            s.mailHost = null;
            s.mailPort = null;
            s.mailSecurity = null;
            s.mailUsername = null;
            s.mailPassword = null;
            s.mailFrom = null;
        });
    }

    /** Targets, jobs and settings in plain JSON for a backup. Runs stay behind, they are about this installation. */
    public synchronized String export() {
        try {
            State copy = new State();
            copy.nextId = load().nextId;
            copy.settings = state.settings;
            copy.targets = state.targets;
            copy.jobs = state.jobs;
            return mapper.writeValueAsString(copy);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Takes over what {@link #export} wrote; the runs of this installation stay. */
    public synchronized void restore(String json) {
        try {
            State restored = mapper.readValue(json, State.class);
            restored.runs = load().runs;
            restored.nextId = Math.max(restored.nextId, state.nextId);
            state = restored;
            save();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(stateDir.resolve(FILE));
            state = null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
