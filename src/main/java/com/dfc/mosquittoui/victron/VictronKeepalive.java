package com.dfc.mosquittoui.victron;

import com.dfc.mosquittoui.api.Dtos.BridgeForm;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.config.BridgeService;
import com.dfc.mosquittoui.config.VictronStore;
import io.quarkus.scheduler.Scheduled;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.mqtt.MqttClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * A GX device only publishes while something asks for it, at least once a minute, and it retains nothing but a few topics. A
 * bridge does not ask, so this application does, on a connection of its own to the device: the same token on another client id.
 * An empty keepalive makes it send all values, which is needed when a bridge comes up and now and then for values that never
 * change; the others only keep it going. The connection trusts only the certificate the bridge trusts; when it fails, a look at
 * the certificate the device presents tells whether that changed, which the page Bridges shows.
 */
@ApplicationScoped
public class VictronKeepalive {

    private static final Logger LOG = Logger.getLogger(VictronKeepalive.class);
    private static final Buffer KEEP_GOING = Buffer.buffer("{\"keepalive-options\":[\"suppress-republish\"]}");
    private static final int FULL_EVERY_TICKS = 20;
    private static final int DEFAULT_TLS_PORT = 8883;
    /** A failed connection is tried again on every tick, the certificate is looked at less often. */
    private static final Duration PROBE_EVERY = Duration.ofMinutes(1);

    @Inject
    VictronStore store;

    @Inject
    BridgeService bridges;

    @Inject
    DynsecClient dynsec;

    @Inject
    Vertx vertx;

    @Inject
    VictronService victron;

    private final Map<String, Instant> probed = new ConcurrentHashMap<>();

    private final Map<String, MqttClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Boolean> bridgeUp = new ConcurrentHashMap<>();
    private int ticks;

    private static String topic(String portalId) {
        return "R/" + portalId + "/keepalive";
    }

    private static boolean tls(BridgeForm bridge, int port) {
        return port == DEFAULT_TLS_PORT || bridge.extraOptions().stream().anyMatch(line -> line.startsWith("bridge_cafile"));
    }

    private void disconnect(String name) {
        MqttClient client = clients.remove(name);
        bridgeUp.remove(name);
        probed.remove(name);
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
    }

    private static void publish(MqttClient client, String portalId, boolean full) {
        client.publish(topic(portalId), full ? Buffer.buffer() : KEEP_GOING, MqttQoS.AT_MOST_ONCE, false, false);
    }

    private void send(BridgeForm bridge, String portalId, boolean full) {
        MqttClient client = clients.get(bridge.name());
        if (client != null && client.isConnected()) {
            publish(client, portalId, full);
            return;
        }
        String[] address = bridge.address().split("\\s+")[0].split(":");
        int port = Integer.parseInt(address[1]);
        String pinned = tls(bridge, port) ? store.pinned(bridge) : null;
        if (tls(bridge, port) && pinned == null) {
            // without the certificate from pairing there is nothing to trust, and the token would go to whoever answers
            LOG.warnf("No keepalive for bridge %s: the certificate of the device is missing, pair it again", bridge.name());
            return;
        }
        MqttClient created = MqttClient.create(vertx, VictronService.options(port, pinned, bridge.username(), bridge.password(),
                "mosquitto-ui-keepalive-" + UUID.randomUUID()));
        clients.put(bridge.name(), created);
        created.connect(port, address[0])
                .onSuccess(ack -> {
                    store.certificateConfirmed(bridge.name());
                    publish(created, portalId, true);
                })
                .onFailure(error -> {
                    LOG.warnf("No keepalive connection for bridge %s: %s", bridge.name(), error.getMessage());
                    if (pinned != null) {
                        probe(bridge.name(), address[0], port, pinned);
                    }
                });
    }

    /** Whether the device presents another certificate than the trusted one; asks for nothing but the handshake. */
    private void probe(String name, String host, int port, String pinned) {
        Instant last = probed.get(name);
        if (last != null && last.isAfter(Instant.now().minus(PROBE_EVERY))) {
            return;
        }
        probed.put(name, Instant.now());
        vertx.executeBlocking(() -> VictronStore.fingerprint(victron.certificates(host, port)), false).onSuccess(presented -> {
            if (presented != null && !presented.equals(VictronStore.fingerprint(pinned))) {
                LOG.warnf("The GX device of bridge %s presents another certificate (%s), pair it again", name, presented);
                store.certificateChanged(name, presented);
            }
        }).onFailure(error -> {
            // not reachable, e.g. while it starts again after a reset: that tells nothing, the next tick looks again
            probed.remove(name);
        });
    }

    /** After pairing again: a new connection with the new token and certificate. */
    void reconnect(String name) {
        disconnect(name);
        tick();
    }

    @Scheduled(every = "{mosquitto-ui.victron.keepalive-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    synchronized void tick() {
        Map<String, String> portals = store.portals();
        List<String> gone = clients.keySet().stream().filter(name -> !portals.containsKey(name)).collect(Collectors.toList());
        gone.forEach(this::disconnect);
        if (portals.isEmpty()) {
            return;
        }
        boolean periodic = ++ticks % FULL_EVERY_TICKS == 0;
        for (BridgeForm bridge : bridges.list()) {
            String portalId = portals.get(bridge.name());
            if (bridge.disabled()) {
                disconnect(bridge.name());
            } else if (portalId != null) {
                boolean up = Boolean.TRUE.equals(dynsec.bridgeConnected(bridge.name(), bridge.remoteClientId()));
                Boolean wasUp = bridgeUp.put(bridge.name(), up);
                send(bridge, portalId, periodic || up && !Boolean.TRUE.equals(wasUp));
            }
        }
    }
}
