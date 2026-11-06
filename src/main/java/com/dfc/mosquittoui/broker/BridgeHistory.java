package com.dfc.mosquittoui.broker;

import com.dfc.mosquittoui.security.PrivateFiles;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Since when bridges are in their state. The broker only tells the state, and this application restarts with every update, so the
 * time goes into a file. A retained message tells the state, only a live one that it just changed: a change while this application
 * was down gets the time it was noticed.
 */
@ApplicationScoped
public class BridgeHistory {

    /** since: null if it already was in this state when it was first seen. */
    record Change(boolean connected, Instant since) {
    }

    private static final Logger LOG = Logger.getLogger(BridgeHistory.class);
    private static final String FILE = "bridge-states.json";

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    private Map<String, Change> changes;

    private Map<String, Change> changes() {
        if (changes == null) {
            changes = new HashMap<>();
            Path file = stateDir.resolve(FILE);
            try {
                if (Files.exists(file)) {
                    changes.putAll(mapper.readValue(Files.readString(file), new TypeReference<Map<String, Change>>() {
                    }));
                }
            } catch (IOException e) {
                LOG.warnf("States of the bridges not read: %s", e.getMessage());
            }
        }
        return changes;
    }

    synchronized void record(String clientId, boolean connected, boolean retained, Instant now) {
        Change old = changes().get(clientId);
        if (old == null || old.connected() != connected) {
            changes().put(clientId, new Change(connected, old == null && retained ? null : now));
            try {
                PrivateFiles.write(stateDir.resolve(FILE), mapper.writeValueAsString(changes()));
            } catch (IOException e) {
                LOG.warnf("States of the bridges not saved: %s", e.getMessage());
            }
        }
    }

    synchronized Change get(String clientId) {
        return changes().get(clientId);
    }
}
