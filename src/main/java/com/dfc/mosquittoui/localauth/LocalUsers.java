package com.dfc.mosquittoui.localauth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dfc.mosquittoui.security.PrivateFiles;
import com.dfc.mosquittoui.security.SecretCipher;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The users of the local login (mosquitto-ui.auth=local), in users.json in the state directory, encrypted as a whole. Each of them
 * manages the broker. version grows with each change of the password: sessions with an older one end.
 */
@ApplicationScoped
public class LocalUsers {

    private static final Logger LOG = Logger.getLogger(LocalUsers.class);
    static final String FILE = "users.json";
    /** Where a generated first password is, until that user logged in once or changed it */
    static final String INITIAL_FILE = "initial-password";
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@-]{1,64}");
    static final int MIN_PASSWORD = 12;
    static final int MAX_PASSWORD = 256;

    public static class StoredUser {
        public String username;
        public String hash;
        public long version;
        public Instant created;
        public Instant lastLogin;
    }

    public record User(String username, long version, Instant created, Instant lastLogin) {
    }

    @Inject
    ObjectMapper mapper;

    @Inject
    SecretCipher cipher;

    @Inject
    LocalAuth auth;

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    @ConfigProperty(name = "mosquitto-ui.local-auth.initial-user", defaultValue = "admin")
    String initialUser;

    @ConfigProperty(name = "mosquitto-ui.local-auth.initial-password")
    Optional<String> initialPassword;

    /** By lower-case name, read on every request with a session */
    private volatile Map<String, StoredUser> users;

    void onStart(@Observes StartupEvent event) {
        if (!auth.local()) {
            return;
        }
        if (!all().isEmpty()) {
            return;
        }
        String password = initialPassword.filter(p -> !p.isBlank()).orElse(null);
        boolean generated = password == null;
        if (generated) {
            password = Passwords.generate();
        }
        add(initialUser, password);
        if (generated) {
            try {
                PrivateFiles.write(stateDir.resolve(INITIAL_FILE), "user: " + initialUser + "\npassword: " + password + "\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            LOG.warnf("Local login: created the user %s, its password is in %s until the first login", initialUser, stateDir.resolve(INITIAL_FILE));
        } else {
            LOG.infof("Local login: created the user %s with MOSQUITTO_UI_LOCAL_AUTH_INITIAL_PASSWORD", initialUser);
        }
    }

    private Map<String, StoredUser> load() {
        Map<String, StoredUser> loaded = users;
        if (loaded != null) {
            return loaded;
        }
        synchronized (this) {
            if (users == null) {
                Map<String, StoredUser> read = new TreeMap<>();
                Path file = stateDir.resolve(FILE);
                try {
                    if (Files.exists(file)) {
                        List<StoredUser> list = mapper.readValue(cipher.open(Files.readString(file)), new TypeReference<List<StoredUser>>() {
                        });
                        list.forEach(user -> read.put(key(user.username), user));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                users = Map.copyOf(read);
            }
            return users;
        }
    }

    private static String key(String username) {
        return username.toLowerCase(java.util.Locale.ROOT);
    }

    private synchronized void save(Map<String, StoredUser> changed) {
        try {
            List<StoredUser> list = new ArrayList<>(changed.values());
            list.sort(Comparator.comparing(user -> key(user.username)));
            PrivateFiles.write(stateDir.resolve(FILE), cipher.seal(mapper.writeValueAsString(list)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        users = Map.copyOf(changed);
    }

    private static User view(StoredUser user) {
        return new User(user.username, user.version, user.created, user.lastLogin);
    }

    /** For the backup (section ACCESS, encrypted): the users with their password hashes */
    public synchronized String export() {
        try {
            return load().isEmpty() ? null : mapper.writeValueAsString(new ArrayList<>(load().values()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Adds the users of a backup that are not here; those that are keep their password, so that whoever restores is not locked
     * out. An older backup without users changes nothing.
     */
    public synchronized void restore(String json) {
        if (json == null) {
            return;
        }
        List<StoredUser> stored;
        try {
            stored = mapper.readValue(json, new TypeReference<List<StoredUser>>() {
            });
        } catch (IOException e) {
            throw new IllegalArgumentException("The UI users in the backup are not readable", e);
        }
        Map<String, StoredUser> changed = new TreeMap<>(load());
        for (StoredUser user : stored) {
            if (user.username != null && user.hash != null && USERNAME.matcher(user.username).matches()) {
                changed.putIfAbsent(key(user.username), user);
            }
        }
        save(changed);
    }

    public List<User> all() {
        return load().values().stream().map(LocalUsers::view).sorted(Comparator.comparing(user -> key(user.username()))).toList();
    }

    /** The user with this name, as it is spelled where it was created */
    public Optional<User> find(String username) {
        StoredUser user = load().get(key(username));
        return Optional.ofNullable(user).map(LocalUsers::view);
    }

    /** @return the user if the password is right; the check takes the same time when there is no such user */
    public synchronized Optional<User> check(String username, String password) {
        StoredUser user = username == null ? null : load().get(key(username));
        if (!Passwords.verify(password == null ? "" : password, user == null ? null : user.hash)) {
            return Optional.empty();
        }
        Map<String, StoredUser> changed = new TreeMap<>(load());
        StoredUser logged = copy(user);
        logged.lastLogin = Instant.now();
        changed.put(key(user.username), logged);
        save(changed);
        if (key(user.username).equals(key(initialUser))) {
            forgetInitialPassword();
        }
        return Optional.of(view(logged));
    }

    public synchronized User add(String username, String password) {
        validate(username, password);
        if (load().containsKey(key(username))) {
            throw new LocalAuthException("already_exists", "User " + username + " already exists", "username");
        }
        StoredUser user = new StoredUser();
        user.username = username;
        user.hash = Passwords.hash(password);
        user.version = 1;
        user.created = Instant.now();
        Map<String, StoredUser> changed = new TreeMap<>(load());
        changed.put(key(username), user);
        save(changed);
        return view(user);
    }

    /** Also ends all sessions of the user */
    public synchronized User setPassword(String username, String password) {
        validate(username, password);
        StoredUser user = existing(username);
        StoredUser changedUser = copy(user);
        changedUser.hash = Passwords.hash(password);
        changedUser.version = user.version + 1;
        Map<String, StoredUser> changed = new TreeMap<>(load());
        changed.put(key(username), changedUser);
        save(changed);
        if (key(username).equals(key(initialUser))) {
            forgetInitialPassword();
        }
        return view(changedUser);
    }

    /** Ends all sessions of the user, without a new password */
    public synchronized void endSessions(String username) {
        StoredUser user = existing(username);
        StoredUser changedUser = copy(user);
        changedUser.version = user.version + 1;
        Map<String, StoredUser> changed = new TreeMap<>(load());
        changed.put(key(username), changedUser);
        save(changed);
    }

    public synchronized void delete(String username) {
        existing(username);
        if (load().size() == 1) {
            throw new LocalAuthException("last_user", "The last user cannot be deleted", null);
        }
        Map<String, StoredUser> changed = new TreeMap<>(load());
        changed.remove(key(username));
        save(changed);
    }

    private StoredUser existing(String username) {
        StoredUser user = username == null ? null : load().get(key(username));
        if (user == null) {
            throw new LocalAuthException("not_found", "User " + username + " not found", null);
        }
        return user;
    }

    private static StoredUser copy(StoredUser user) {
        StoredUser copy = new StoredUser();
        copy.username = user.username;
        copy.hash = user.hash;
        copy.version = user.version;
        copy.created = user.created;
        copy.lastLogin = user.lastLogin;
        return copy;
    }

    private static void validate(String username, String password) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new LocalAuthException("validation", "Up to 64 letters, digits and . _ @ -", "username");
        }
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw new LocalAuthException("password_length", "At least " + MIN_PASSWORD + " characters", "password");
        }
    }

    private void forgetInitialPassword() {
        try {
            if (Files.deleteIfExists(stateDir.resolve(INITIAL_FILE))) {
                LOG.infof("Local login: removed %s", stateDir.resolve(INITIAL_FILE));
            }
        } catch (IOException e) {
            LOG.warnf("%s not removed: %s", stateDir.resolve(INITIAL_FILE), e.getMessage());
        }
    }
}
