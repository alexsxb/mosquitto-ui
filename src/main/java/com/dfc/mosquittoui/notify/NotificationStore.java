package com.dfc.mosquittoui.notify;

import com.dfc.mosquittoui.backup.BackupStore;
import com.dfc.mosquittoui.security.PrivateFiles;
import com.dfc.mosquittoui.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The mail server, who gets told what, and what they were told about, in one file that is encrypted as a whole. Older versions kept
 * the mail server with the backups, it moves here the first time this is read.
 */
@ApplicationScoped
public class NotificationStore {

    private static final String FILE = "notifications.json";

    public enum MailSecurity { NONE, STARTTLS, TLS }

    /** What goes into a backup. */
    public static class Settings {
        public List<String> recipients = new ArrayList<>();
        public String mailHost;
        public Integer mailPort;
        public MailSecurity mailSecurity;
        public String mailUsername;
        public String mailPassword;
        public String mailFrom;
        public List<Notice> muted = new ArrayList<>();
        public List<String> ignoredClients = new ArrayList<>();
    }

    /** A problem someone was told about, and will hear of again when it is over. */
    public record Reported(Notice notice, String name, Instant since) {
    }

    /** All there is. */
    public static class State {
        public Settings settings = new Settings();
        public List<Reported> reported = new ArrayList<>();
    }

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    @Inject
    SecretCipher cipher;

    @Inject
    ObjectMapper mapper;

    @Inject
    BackupStore backups;

    private State state;

    private State load() {
        if (state == null) {
            Path file = stateDir.resolve(FILE);
            try {
                if (Files.exists(file)) {
                    state = mapper.readValue(cipher.open(Files.readString(file)), State.class);
                } else {
                    state = new State();
                    takeOver();
                }
            } catch (IOException e) {
                state = null;
                throw new UncheckedIOException(e);
            }
        }
        return state;
    }

    /** The old place is only emptied once the new one is on the disk. */
    private void takeOver() {
        backups.moveOldMail(old -> {
            Settings s = state.settings;
            s.recipients = new ArrayList<>(old.recipients());
            s.mailHost = old.host();
            s.mailPort = old.port();
            s.mailSecurity = old.security();
            s.mailUsername = old.username();
            s.mailPassword = old.password();
            s.mailFrom = old.from();
            save();
        });
    }

    private void save() {
        try {
            PrivateFiles.write(stateDir.resolve(FILE), cipher.seal(mapper.writeValueAsString(state)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the function returns must not be changed outside of {@link #change}. */
    public synchronized <T> T read(Function<State, T> reader) {
        return reader.apply(load());
    }

    /** A change that fails half way leaves the file alone, and the memory with what the file says. */
    public synchronized void change(Consumer<State> change) {
        try {
            change.accept(load());
            save();
        } catch (RuntimeException e) {
            state = null;
            throw e;
        }
    }

    /** The settings in plain JSON for a backup. What was reported stays behind, it is about this installation. */
    public synchronized String export() {
        try {
            return mapper.writeValueAsString(load().settings);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Takes over what {@link #export} wrote.
     *
     * @param json null for a backup of an older version: the backups have just restored the mail server to their old place
     */
    public synchronized void restore(String json) {
        if (json == null) {
            load();
            takeOver();
            return;
        }
        try {
            load().settings = mapper.readValue(json, Settings.class);
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
