package com.dfc.mosquittoui.broker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.mqtt.messages.MqttPublishMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

/**
 * Commands to the dynamic security plugin over MQTT: a command is published to the control topic, the plugin answers on
 * the response topic with the same correlationData. Each message carries one command, as the plugin does not undo the
 * commands before a failed one in a message. Commands contain passwords, so they are never logged.
 */
@ApplicationScoped
public class DynsecClient {

    static final String TOPIC = "$CONTROL/dynamic-security/v1";
    static final String RESPONSE_TOPIC = TOPIC + "/response";
    /** Retained by the broker: 1 while the bridge with that client id is connected. */
    private static final String BRIDGE_STATE_PREFIX = "$SYS/broker/connection/";
    private static final String BRIDGE_STATE_SUFFIX = "/state";
    private static final String SYS_PREFIX = "$SYS/broker/";
    /** Under SYS_PREFIX, see sysValue */
    public static final String TOKEN_CLIENTS = "token-auth/clients";
    /** Under SYS_PREFIX: the tokens the plugin did not accept, see TokenRejectionsChanged */
    public static final String TOKEN_REJECTED = "token-auth/rejected";
    /** Retained by the inspect plugin when it starts (docker/mosquitto/inspect) */
    public static final String INSPECT_VERSION = "inspect/version";
    public static final String INSPECT_TOPIC = "$CONTROL/inspect/v1";
    static final String INSPECT_RESPONSE_TOPIC = INSPECT_TOPIC + "/response";
    static final String INSPECT_TRACE_TOPIC = INSPECT_TOPIC + "/trace";
    /** With log_dest topic in mosquitto.conf, each line of the log on LOG_PREFIX + level: E, W, N, I, M/subscribe, ... */
    public static final String LOG_PREFIX = "$SYS/broker/log/";
    /** A leading time stamp, as log_timestamp adds it in the format of log_timestamp_format; the time of arrival is kept instead */
    private static final java.util.regex.Pattern LOG_TIMESTAMP = java.util.regex.Pattern.compile("^\\d[\\dT:./ -]*?: ");
    /** The client ids of the connections of this application begin with it */
    public static final String CLIENT_ID_PREFIX = "mosquitto-ui-";

    private static final Logger LOG = Logger.getLogger(DynsecClient.class);
    private static final long RECONNECT_DELAY_MS = 1000;
    private static final long RECONNECT_CHECK_MS = 50;
    /** Lists of a large broker are one message. */
    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;
    private static final int SUBSCRIPTION_FAILED = 0x80;

    @Inject
    Vertx vertx;

    @Inject
    MosquittoConfig config;

    @Inject
    ObjectMapper mapper;

    @Inject
    BridgeHistory bridges;

    @Inject
    Event<TokenRejectionsChanged> tokenRejections;

    @Inject
    Event<TraceBatch> traceBatches;

    @Inject
    Event<BrokerLogLine> logLines;

    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, Boolean> bridgeStates = new ConcurrentHashMap<>();
    private final Map<String, String> sysValues = new ConcurrentHashMap<>();
    private volatile boolean bridgeStatesReadable;
    private volatile boolean inspectReadable;
    private volatile boolean logReadable;
    /** All use of the client happens on this context. */
    private Context context;
    private MqttClient client;
    private volatile boolean connected;
    private volatile boolean stopping;
    private volatile String lastError;

    void onStart(@Observes StartupEvent event) {
        context = vertx.getOrCreateContext();
        context.runOnContext(v -> connect());
    }

    void onStop(@Observes ShutdownEvent event) {
        stopping = true;
        context.runOnContext(v -> {
            if (client != null && client.isConnected()) {
                client.disconnect();
            }
        });
    }

    public boolean isConnected() {
        return connected;
    }

    /** After a restart of the broker: waits until this application is connected again, so that the next request works. */
    public void awaitConnected() {
        long end = System.nanoTime() + config.timeout().toNanos();
        while (!connected && System.nanoTime() < end) {
            try {
                Thread.sleep(RECONNECT_CHECK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Why the connection failed; null if there was no failure since the last connect. */
    public String lastError() {
        return lastError;
    }

    /**
     * Whether a bridge is connected; one that never reached the other side has no state yet. Without a client id the broker
     * takes "host name.bridge name".
     *
     * @return null if the broker does not tell, as the user of this application may not read $SYS
     */
    public Boolean bridgeConnected(String name, String clientId) {
        if (!bridgeStatesReadable) {
            return null;
        }
        String key = bridgeKey(name, clientId);
        return key != null && Boolean.TRUE.equals(bridgeStates.get(key));
    }

    /**
     * When a bridge reached its state; null if that is not known. A connected bridge is not older than the broker: its state comes
     * back retained after a restart, and an update of this application restarts both.
     */
    public Instant bridgeStateSince(String name, String clientId) {
        String key = bridgeKey(name, clientId);
        BridgeHistory.Change change = key == null ? null : bridges.get(key);
        if (change == null || change.since() == null) {
            return null;
        }
        String uptime = sysValue("uptime");
        if (change.connected() && uptime != null) {
            Instant started = Instant.now().minusSeconds(Long.parseLong(uptime.split(" ", 2)[0]));
            return change.since().isBefore(started) ? started : change.since();
        }
        return change.since();
    }

    private String bridgeKey(String name, String clientId) {
        return clientId != null ? clientId : bridgeStates.keySet().stream().filter(key -> key.endsWith("." + name)).findFirst().orElse(null);
    }

    private void onBridgeState(String clientId, boolean connected, boolean retained) {
        bridgeStates.put(clientId, connected);
        // writes a file, not on the event loop
        vertx.executeBlocking(() -> {
            bridges.record(clientId, connected, retained, Instant.now());
            return null;
        });
    }

    /**
     * The last value of one of {@link BrokerStats#SYS_VALUES}.
     *
     * @return null if the broker did not tell, as the user of this application may not read $SYS or sys_interval is 0
     */
    public String sysValue(String name) {
        return sysValues.get(name);
    }

    /** Whether the broker user may read the answers of the inspect plugin ($CONTROL/inspect/#). */
    public boolean inspectReadable() {
        return inspectReadable;
    }

    /** Whether the broker let this application subscribe to its log; whether it publishes one is another matter (log_dest topic). */
    public boolean logReadable() {
        return logReadable;
    }

    /** Options of a connection to the broker with these credentials. */
    public MqttClientOptions options(String username, String password) {
        MqttClientOptions options = new MqttClientOptions()
                // unique, a second connection with the same id would take over this one
                .setClientId(CLIENT_ID_PREFIX + UUID.randomUUID())
                .setUsername(username)
                .setPassword(password)
                .setCleanSession(true)
                .setKeepAliveInterval(30)
                .setMaxMessageSize(MAX_MESSAGE_SIZE);
        options.setSsl(config.tls());
        // Vert.x refuses TLS without a choice here
        options.setHostnameVerificationAlgorithm("HTTPS");
        return options;
    }

    private void connect() {
        MqttClient mqtt = MqttClient.create(vertx, options(config.username(), config.password()));
        client = mqtt;
        mqtt.publishHandler(this::onMessage);
        mqtt.closeHandler(v -> {
            connected = false;
            bridgeStates.clear();
            sysValues.clear();
            inspectReadable = false;
            logReadable = false;
            failPending();
            if (!stopping) {
                LOG.warn("Connection to the broker lost");
                scheduleReconnect();
            }
        });
        mqtt.subscribeCompletionHandler(ack -> {
            // Everything from $SYS is optional
            if (ack.grantedQoSLevels().getFirst() == SUBSCRIPTION_FAILED) {
                lastError = "No access to " + RESPONSE_TOPIC + ", the broker user needs the role dynsec-admin";
                LOG.error(lastError);
            } else {
                bridgeStatesReadable = ack.grantedQoSLevels().get(1) != SUBSCRIPTION_FAILED;
                // the last three: the log, and the answers and the recordings of the inspect plugin, which need $CONTROL/inspect/#
                List<Integer> granted = ack.grantedQoSLevels();
                logReadable = granted.get(granted.size() - 3) != SUBSCRIPTION_FAILED;
                inspectReadable = granted.get(granted.size() - 2) != SUBSCRIPTION_FAILED && granted.getLast() != SUBSCRIPTION_FAILED;
                connected = true;
                lastError = null;
                LOG.infof("Connected to the broker at %s:%d", config.host(), config.port());
            }
        });
        mqtt.connect(config.port(), config.host())
                .onSuccess(ack -> {
                    Map<String, Integer> topics = new LinkedHashMap<>();
                    topics.put(RESPONSE_TOPIC, MqttQoS.AT_LEAST_ONCE.value());
                    topics.put(BRIDGE_STATE_PREFIX + "+" + BRIDGE_STATE_SUFFIX, MqttQoS.AT_LEAST_ONCE.value());
                    // not $SYS/broker/#: the log is subscribed to on its own, below
                    BrokerStats.SYS_VALUES.forEach(name -> topics.put(SYS_PREFIX + name, MqttQoS.AT_MOST_ONCE.value()));
                    // the clients logged in with a token, which dynamic security does not know (docker/mosquitto/token-auth)
                    topics.put(SYS_PREFIX + TOKEN_CLIENTS, MqttQoS.AT_MOST_ONCE.value());
                    topics.put(SYS_PREFIX + TOKEN_REJECTED, MqttQoS.AT_MOST_ONCE.value());
                    topics.put(SYS_PREFIX + INSPECT_VERSION, MqttQoS.AT_MOST_ONCE.value());
                    // last, see the completion handler
                    topics.put(LOG_PREFIX + "#", MqttQoS.AT_MOST_ONCE.value());
                    topics.put(INSPECT_RESPONSE_TOPIC, MqttQoS.AT_LEAST_ONCE.value());
                    topics.put(INSPECT_TRACE_TOPIC, MqttQoS.AT_LEAST_ONCE.value());
                    mqtt.subscribe(topics);
                })
                .onFailure(error -> {
                    lastError = error.toString();
                    LOG.warnf("No connection to the broker at %s:%d: %s", config.host(), config.port(), error);
                    scheduleReconnect();
                });
    }

    private void scheduleReconnect() {
        vertx.setTimer(RECONNECT_DELAY_MS, id -> {
            if (!stopping) {
                context.runOnContext(v -> connect());
            }
        });
    }

    private void failPending() {
        pending.values().forEach(response -> response.completeExceptionally(new BrokerUnavailableException("Connection lost")));
    }

    private void onMessage(MqttPublishMessage message) {
        String topic = message.topicName();
        if (topic.startsWith(BRIDGE_STATE_PREFIX)) {
            String clientId = topic.substring(BRIDGE_STATE_PREFIX.length(), topic.length() - BRIDGE_STATE_SUFFIX.length());
            onBridgeState(clientId, message.payload().toString().equals("1"), message.isRetain());
            return;
        }
        if (topic.startsWith(LOG_PREFIX)) {
            String text = LOG_TIMESTAMP.matcher(message.payload().toString(java.nio.charset.StandardCharsets.UTF_8)).replaceFirst("");
            logLines.fire(new BrokerLogLine(java.time.Instant.now(), level(topic.substring(LOG_PREFIX.length())), text.stripTrailing()));
            return;
        }
        if (topic.startsWith(SYS_PREFIX)) {
            String name = topic.substring(SYS_PREFIX.length());
            String value = message.payload().toString();
            sysValues.put(name, value);
            if (TOKEN_REJECTED.equals(name)) {
                // a token with a key not known yet: the keys are fetched again at once, not on the event loop
                tokenRejections.fireAsync(new TokenRejectionsChanged(value));
            }
            return;
        }
        if (topic.equals(INSPECT_TRACE_TOPIC)) {
            // in order, and quick: the events are only appended
            try {
                traceBatches.fire(new TraceBatch(mapper.readTree(message.payload().getBytes())));
            } catch (IOException e) {
                LOG.warnf("Unreadable recording on %s: %s", topic, e.getMessage());
            } catch (RuntimeException e) {
                LOG.warnf(e, "Recording on %s not taken", topic);
            }
            return;
        }
        try {
            for (JsonNode response : mapper.readTree(message.payload().getBytes()).path("responses")) {
                CompletableFuture<JsonNode> waiting = pending.get(response.path("correlationData").asText());
                if (waiting != null) {
                    waiting.complete(response);
                }
            }
        } catch (IOException e) {
            LOG.warnf("Unreadable message on %s: %s", message.topicName(), e.getMessage());
        }
    }

    /** The level of a line of the log, from the end of its topic (mosquitto: src/logging.c) */
    static String level(String suffix) {
        return switch (suffix) {
            case "E" -> "error";
            case "W" -> "warning";
            case "N" -> "notice";
            case "I" -> "info";
            case "D" -> "debug";
            case "M/subscribe" -> "subscribe";
            case "M/unsubscribe" -> "unsubscribe";
            case "WS" -> "websockets";
            default -> suffix.toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** A new command, with its parameters to be added. */
    public ObjectNode command(String name) {
        return mapper.createObjectNode().put("command", name);
    }

    /**
     * Executes a command and waits for the answer.
     *
     * @return the data of the answer, a missing node if it has none
     * @throws DynsecException if the plugin rejects the command
     * @throws BrokerUnavailableException if there is no answer
     */
    public JsonNode call(ObjectNode command) {
        return call(TOPIC, command);
    }

    /** As call(command), to the plugin answering on this $CONTROL topic. */
    public JsonNode call(String topic, ObjectNode command) {
        if (!connected) {
            throw new BrokerUnavailableException(lastError != null ? lastError : "Not connected");
        }
        String correlationData = UUID.randomUUID().toString();
        command.put("correlationData", correlationData);
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        pending.put(correlationData, response);
        try {
            ObjectNode message = mapper.createObjectNode();
            message.putArray("commands").add(command);
            Buffer payload = Buffer.buffer(mapper.writeValueAsBytes(message));
            context.runOnContext(v -> client.publish(topic, payload, MqttQoS.AT_LEAST_ONCE, false, false)
                    .onFailure(response::completeExceptionally));
            JsonNode answer = response.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (answer.hasNonNull("error")) {
                throw new DynsecException(answer.get("error").asText());
            }
            return answer.path("data");
        } catch (TimeoutException e) {
            throw new BrokerUnavailableException("No answer within " + config.timeout());
        } catch (ExecutionException e) {
            throw new BrokerUnavailableException(e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("Interrupted");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } finally {
            pending.remove(correlationData);
        }
    }
}
