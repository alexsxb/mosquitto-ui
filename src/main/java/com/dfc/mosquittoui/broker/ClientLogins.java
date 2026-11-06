package com.dfc.mosquittoui.broker;

import com.dfc.mosquittoui.api.Dtos.ClientLogin;
import com.dfc.mosquittoui.api.Dtos.ClientView;
import com.dfc.mosquittoui.api.Dtos.MissingClient;
import com.dfc.mosquittoui.security.PrivateFiles;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * When clients were last connected. The broker only tells who is connected now, so this looks at it regularly and keeps the
 * rest: a connection between two looks goes unnoticed, and so does anything while this application is down.
 */
@ApplicationScoped
public class ClientLogins {

    private static final Logger LOG = Logger.getLogger(ClientLogins.class);
    private static final String FILE = "client-logins.json";
    /** Every look changes the time of those connected, the file does not need to follow each one. */
    private static final Duration WRITE_EVERY = Duration.ofMinutes(1);
    /** A client seen this shortly before a start is taken for still being on the same connection. */
    private static final Duration SAME_CONNECTION = Duration.ofMinutes(10);

    @Inject
    DynsecService service;

    @Inject
    DynsecClient dynsec;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    @ConfigProperty(name = "mosquitto-ui.client-logins.steady-after")
    Duration steadyAfter;

    @ConfigProperty(name = "mosquitto-ui.client-logins.missing-after")
    Duration missingAfter;

    @ConfigProperty(name = "mosquitto-ui.client-logins.missing-for")
    Duration missingFor;

    private Map<String, ClientLogin> logins;
    private final Set<String> connected = new HashSet<>();
    private boolean started;
    private Instant written = Instant.EPOCH;

    private Map<String, ClientLogin> logins() {
        if (logins == null) {
            logins = new HashMap<>();
            Path file = stateDir.resolve(FILE);
            try {
                if (Files.exists(file)) {
                    logins.putAll(mapper.readValue(Files.readString(file), new TypeReference<Map<String, ClientLogin>>() {
                    }));
                }
            } catch (IOException e) {
                LOG.warnf("Last connections of the clients not read: %s", e.getMessage());
            }
        }
        return logins;
    }

    public synchronized ClientLogin get(String username) {
        return logins().get(username);
    }

    /**
     * Clients that are usually there and have been away for a while, the latest first. "Usually there" means that the last
     * connection lasted: this keeps out devices that connect to send a value and leave. Without a limit in time, one that was taken
     * out of service would stay here until someone deletes it.
     */
    public List<MissingClient> missing(List<ClientView> clients, Instant now) {
        return clients.stream()
                .filter(client -> client.connections().isEmpty() && !client.disabled() && !client.locked() && client.lastLogin() != null)
                .filter(client -> isMissing(client.lastLogin(), now))
                .map(client -> new MissingClient(client.username(), client.textname(), client.lastLogin().lastSeen()))
                .sorted(Comparator.comparing(MissingClient::lastSeen).reversed())
                .toList();
    }

    /** Whether a client last seen with this connection is missed now: it usually stays, and it has been gone for a while. */
    public boolean isMissing(ClientLogin login, Instant now) {
        return login.lastSeen().plus(missingAfter).isBefore(now)
                && login.lastSeen().plus(missingFor).isAfter(now)
                && !login.connectedAt().plus(steadyAfter).isAfter(login.lastSeen());
    }

    @Scheduled(every = "{mosquitto-ui.client-logins.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void look() {
        if (!dynsec.isConnected()) {
            return;
        }
        try {
            record(service.listClients(), Instant.now());
        } catch (RuntimeException e) {
            // the broker restarts now and then, the next look will do
            LOG.debugf("Clients not read: %s", e.getMessage());
        }
    }

    synchronized void record(List<ClientView> clients, Instant now) {
        Set<String> online = clients.stream().filter(client -> !client.connections().isEmpty()).map(ClientView::username).collect(Collectors.toSet());
        Map<String, ClientLogin> known = logins();
        boolean changed = known.keySet().retainAll(clients.stream().map(ClientView::username).collect(Collectors.toSet()));
        for (ClientView client : clients) {
            if (online.contains(client.username())) {
                ClientLogin old = known.get(client.username());
                boolean same = old != null && (connected.contains(client.username()) || !started && old.lastSeen().isAfter(now.minus(SAME_CONNECTION)));
                changed |= !same;
                known.put(client.username(), new ClientLogin(same ? old.connectedAt() : now, now, client.connections().getFirst()));
            }
        }
        changed |= !connected.equals(online);
        connected.clear();
        connected.addAll(online);
        started = true;
        if (changed || written.plus(WRITE_EVERY).isBefore(now)) {
            write(known, now);
        }
    }

    private void write(Map<String, ClientLogin> known, Instant now) {
        try {
            PrivateFiles.write(stateDir.resolve(FILE), mapper.writeValueAsString(known));
            written = now;
        } catch (IOException e) {
            LOG.warnf("Last connections of the clients not saved: %s", e.getMessage());
        }
    }
}
