package com.dfc.mosquittoui.sso;

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

/** The identity providers and whether the local login is on, in sso.json in the state directory, encrypted as a whole. */
@ApplicationScoped
public class SsoStore {

    static final String FILE = "sso.json";

    /** Somebody who may log in: by e-mail address ("*@example.org" for a domain) or user name; as a local user, if one is named */
    public static class Allowed {
        public String match;
        public String localUser;

        public Allowed() {
        }

        public Allowed(String match, String localUser) {
            this.match = match;
            this.localUser = localUser;
        }
    }

    /** Somebody who logged in at the provider but was not let in, so that they can be let in with one click */
    public static class Denied {
        public String username;
        public String email;
        public String subject;
        public List<String> roles = new ArrayList<>();
        public Instant at;
    }

    public static class Provider {
        public String id;
        public String name;
        public String template;
        public boolean enabled = true;
        public String issuer;
        public String clientId;
        public String clientSecret;
        /** Sign in with Apple: the private key (PEM), its id and the team */
        public String appleKeyId;
        public String appleTeamId;
        public List<String> scopes = new ArrayList<>();
        public List<String> roleClaims = new ArrayList<>();
        public SsoTemplate.RoleSource roleSource;
        public List<String> allowRoles = new ArrayList<>();
        public boolean allowAll;
        public List<Allowed> allowed = new ArrayList<>();
        public List<String> githubOrgs = new ArrayList<>();
        public List<Denied> denied = new ArrayList<>();
        /** For state and session cookies of the code flow */
        public String stateSecret;
        /** Grows with each change: sessions of an older one end, and Quarkus builds the tenant anew */
        public long version = 1;
        public boolean fromEnvironment;
        public Instant created;
        public Instant lastLogin;
    }

    public static class State {
        /** null: on while there is no provider */
        public Boolean localLogin;
        /** The OIDC settings of the environment were looked at once, they are not read again */
        public boolean environmentTaken;
        public List<Provider> providers = new ArrayList<>();
    }

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    @Inject
    SecretCipher cipher;

    @Inject
    ObjectMapper mapper;

    private volatile State state;

    private State load() {
        if (state == null) {
            Path file = stateDir.resolve(FILE);
            try {
                state = Files.exists(file) ? mapper.readValue(cipher.open(Files.readString(file)), State.class) : new State();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return state;
    }

    private void save() {
        try {
            PrivateFiles.write(stateDir.resolve(FILE), cipher.seal(mapper.writeValueAsString(state)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** For the backup (section ACCESS, encrypted): the providers with their secrets, without who was turned away */
    public synchronized String export() {
        State s = load();
        if (s.providers.isEmpty()) {
            return null;
        }
        try {
            List<Provider> providers = mapper.readValue(mapper.writeValueAsString(s.providers), new com.fasterxml.jackson.core.type.TypeReference<List<Provider>>() {
            });
            providers.forEach(p -> p.denied = new ArrayList<>());
            return mapper.writeValueAsString(providers);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The providers of a backup replace these; whether the local login is on stays as it is here, so that whoever restores is not
     * locked out (and it is on anyway while no provider is). An older backup without providers changes nothing.
     */
    public void restore(String json) {
        if (json == null) {
            return;
        }
        List<Provider> providers;
        try {
            providers = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Provider>>() {
            });
        } catch (IOException e) {
            throw new IllegalArgumentException("The identity providers in the backup are not readable", e);
        }
        change(state -> {
            for (Provider p : providers) {
                // new tenants and ended sessions, also when the same provider comes back
                p.version = p.version + 1;
                if (p.stateSecret == null) {
                    p.stateSecret = SsoService.secret();
                }
            }
            state.providers = new ArrayList<>(providers);
            state.environmentTaken = true;
        });
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
}
