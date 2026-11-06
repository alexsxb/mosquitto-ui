package com.dfc.mosquittoui.topics;

import com.dfc.mosquittoui.api.Dtos.TopicBatch;
import com.dfc.mosquittoui.api.Dtos.TopicPublish;
import com.dfc.mosquittoui.api.Dtos.TopicUpdate;
import com.dfc.mosquittoui.broker.BrokerUnavailableException;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.broker.DynsecException;
import com.dfc.mosquittoui.broker.DynsecService;
import com.dfc.mosquittoui.broker.MosquittoConfig;
import com.dfc.mosquittoui.importer.RetainedReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import io.smallrye.mutiny.subscription.MultiEmitter;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.messages.MqttPublishMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * What goes through the broker, for the page Topics. As long as someone looks, a connection of its own subscribes to everything
 * and publishes what the page sends, with a broker user that exists only for that time. The user of this application cannot do
 * that, it has no access to application topics. Only the last value of a topic is kept, and browsers get the changes in batches,
 * as a GX device alone sends hundreds of values per second.
 */
@ApplicationScoped
public class TopicMonitor {

    private static final Logger LOG = Logger.getLogger(TopicMonitor.class);
    private static final long BATCH_MS = 500;
    private static final int HEARTBEAT_TICKS = 30;
    private static final long RETRY_MS = 3000;
    private static final int SNAPSHOT_CHUNK = 1000;
    private static final int MAX_TOPICS = 100_000;
    private static final int MAX_VALUE_BYTES = 8192;
    private static final int MAX_BINARY_PREVIEW = 256;
    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    @Inject
    Vertx vertx;

    @Inject
    DynsecClient dynsec;

    @Inject
    MosquittoConfig config;

    @Inject
    RetainedReader reader;

    @ConfigProperty(name = "mosquitto-ui.topics.idle-stop")
    Duration idleStop;

    private final Map<String, TopicUpdate> topics = new ConcurrentHashMap<>();
    private final Map<String, TopicUpdate> changed = new ConcurrentHashMap<>();
    private final Set<MultiEmitter<? super TopicBatch>> viewers = ConcurrentHashMap.newKeySet();
    private volatile MqttClient client;
    /** All use of the client happens on this context. */
    private volatile Context clientContext;
    private volatile String password;
    private volatile boolean running;
    private volatile String error;
    private long batchTimer = -1;
    private long stopTimer = -1;
    private long ticks;

    void onStop(@Observes ShutdownEvent event) {
        running = false;
    }

    public Multi<TopicBatch> stream() {
        return Multi.createFrom().<TopicBatch>emitter(emitter -> {
            synchronized (this) {
                snapshot().forEach(emitter::emit);
                viewers.add(emitter);
                emitter.onTermination(() -> viewerGone(emitter));
                cancelStop();
                start();
            }
        }, BackPressureStrategy.BUFFER);
    }

    private boolean connected() {
        MqttClient current = client;
        return current != null && current.isConnected();
    }

    private TopicBatch batch(List<TopicUpdate> updates) {
        return new TopicBatch(updates, topics.size(), connected(), error);
    }

    private List<TopicBatch> snapshot() {
        List<TopicUpdate> all = new ArrayList<>(topics.values());
        List<TopicBatch> batches = new ArrayList<>();
        for (int i = 0; i < all.size(); i += SNAPSHOT_CHUNK) {
            batches.add(batch(new ArrayList<>(all.subList(i, Math.min(i + SNAPSHOT_CHUNK, all.size())))));
        }
        batches.add(batch(List.of()));
        return batches;
    }

    private synchronized void viewerGone(MultiEmitter<? super TopicBatch> emitter) {
        viewers.remove(emitter);
        if (viewers.isEmpty()) {
            // a reload of the page should not cost the values that are not retained
            stopTimer = vertx.setTimer(idleStop.toMillis(), id -> stopIfIdle());
        }
    }

    private void cancelStop() {
        if (stopTimer >= 0) {
            vertx.cancelTimer(stopTimer);
            stopTimer = -1;
        }
    }

    private synchronized void stopIfIdle() {
        stopTimer = -1;
        if (!viewers.isEmpty()) {
            return;
        }
        running = false;
        vertx.cancelTimer(batchTimer);
        batchTimer = -1;
        MqttClient current = client;
        client = null;
        if (current != null && current.isConnected()) {
            current.disconnect();
        }
        topics.clear();
        changed.clear();
        error = null;
        vertx.executeBlocking(() -> {
            removeUser();
            return null;
        }, false);
    }

    private void start() {
        if (running) {
            return;
        }
        running = true;
        batchTimer = vertx.setPeriodic(BATCH_MS, id -> sendBatch());
        connect();
    }

    private void sendBatch() {
        ticks++;
        if (changed.isEmpty() && ticks % HEARTBEAT_TICKS != 0) {
            return;
        }
        List<TopicUpdate> updates = new ArrayList<>(changed.size());
        for (String topic : List.copyOf(changed.keySet())) {
            TopicUpdate update = changed.remove(topic);
            if (update != null) {
                updates.add(update);
            }
        }
        TopicBatch batch = batch(updates);
        viewers.forEach(viewer -> viewer.emit(batch));
    }

    /** The user is made anew each time: nothing has to be kept for it, and neither a reset nor an import can leave it behind broken. */
    private void connect() {
        vertx.<String>executeBlocking(this::createUser, false).onSuccess(created -> {
            password = created;
            MqttClient mqtt = MqttClient.create(vertx, dynsec.options(DynsecService.MONITOR, created).setMaxMessageSize(MAX_MESSAGE_SIZE));
            client = mqtt;
            clientContext = vertx.getOrCreateContext();
            mqtt.publishHandler(this::onMessage);
            mqtt.closeHandler(v -> retry());
            mqtt.connect(config.port(), config.host()).onSuccess(ack -> {
                Map<String, Integer> filters = new LinkedHashMap<>();
                filters.put("#", MqttQoS.AT_MOST_ONCE.value());
                filters.put("$SYS/#", MqttQoS.AT_MOST_ONCE.value());
                mqtt.subscribe(filters);
                error = null;
            }).onFailure(failure -> {
                error = failure.getMessage();
                retry();
            });
        }).onFailure(failure -> {
            error = failure.getMessage();
            retry();
        });
    }

    private void retry() {
        if (running) {
            vertx.setTimer(RETRY_MS, id -> {
                if (running && !connected()) {
                    connect();
                }
            });
        }
    }

    private void removeUser() {
        ignoringMissing("deleteClient", "username", DynsecService.MONITOR);
        ignoringMissing("deleteRole", "rolename", DynsecService.MONITOR);
    }

    private void ignoringMissing(String command, String key, String value) {
        try {
            dynsec.call(dynsec.command(command).put(key, value));
        } catch (DynsecException e) {
            LOG.debugf("%s %s: %s", command, value, e.getMessage());
        } catch (BrokerUnavailableException e) {
            LOG.debugf("%s %s: the broker is away", command, value);
        }
    }

    private String createUser() {
        removeUser();
        ObjectNode role = dynsec.command("createRole").put("rolename", DynsecService.MONITOR).put("textname", "mosquitto-ui").put("textdescription", "For the page Topics")
                .put("allowwildcardsubs", true);
        ArrayNode acls = role.putArray("acls");
        // all of them: the default access may have been turned off
        for (String topic : List.of("#", "$SYS/#")) {
            for (String type : List.of("subscribePattern", "unsubscribePattern", "publishClientReceive")) {
                acls.addObject().put("acltype", type).put("topic", topic).put("priority", 0).put("allow", true);
            }
        }
        // the log of the broker would fill the tree with a topic per level and a value that changes all the time: page Logs
        acls.addObject().put("acltype", "publishClientReceive").put("topic", "$SYS/broker/log/#").put("priority", 1).put("allow", false);
        // "#" does not reach $-topics, so neither $CONTROL nor $SYS
        acls.addObject().put("acltype", "publishClientSend").put("topic", "#").put("priority", 0).put("allow", true);
        dynsec.call(role);
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String newPassword = Base64.getEncoder().encodeToString(random);
        ObjectNode user = dynsec.command("createClient").put("username", DynsecService.MONITOR).put("password", newPassword).put("textname", "mosquitto-ui")
                .put("textdescription", "For the page Topics, while it is open");
        user.putArray("roles").addObject().put("rolename", DynsecService.MONITOR);
        dynsec.call(user);
        return newPassword;
    }

    private void send(String topic, byte[] payload, int qos, boolean retain) {
        MqttClient current = client;
        if (!connected()) {
            throw new BrokerUnavailableException(error != null ? error : "Not connected");
        }
        CompletableFuture<Void> sent = new CompletableFuture<>();
        clientContext.runOnContext(v -> current.publish(topic, Buffer.buffer(payload), MqttQoS.valueOf(qos), false, retain)
                .onSuccess(id -> sent.complete(null)).onFailure(sent::completeExceptionally));
        try {
            sent.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new BrokerUnavailableException("No answer within " + config.timeout());
        } catch (ExecutionException e) {
            throw new BrokerUnavailableException(e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("Interrupted");
        }
    }

    public void publish(TopicPublish message) {
        send(message.topic(), message.value().getBytes(StandardCharsets.UTF_8), message.qos(), message.retain());
    }

    /**
     * The retained messages of a topic and all below it. Only the broker can tell: without MQTT 5 the flag only comes with a new
     * subscription, so what the connection of the page saw arrive live does not show it. It also takes the moment to correct that.
     */
    public List<String> retainedTopics(String branch) {
        return scanRetained(List.of(branch + "/#"));
    }

    /** All retained messages. Those of $SYS too, as the broker counts them: otherwise the number on the dashboard would not fit. */
    public List<String> allRetainedTopics() {
        return scanRetained(List.of("#", "$SYS/#"));
    }

    /** All of them or an exception: a deletion that seems done while some are left would be worse than none. */
    private List<String> scanRetained(List<String> filters) {
        if (!connected()) {
            throw new BrokerUnavailableException(error != null ? error : "Not connected");
        }
        Set<String> found = reader.read(DynsecService.MONITOR, password, filters).keySet();
        for (String topic : found) {
            TopicUpdate old = topics.get(topic);
            if (old != null && !old.retained()) {
                TopicUpdate update = new TopicUpdate(old.topic(), old.value(), old.binary(), old.size(), old.cut(), true, old.qos(), old.count(), false, old.time());
                topics.put(topic, update);
                changed.put(topic, update);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Deletes the retained messages of a topic and all below it, what is deleted disappears from the tree. Done here too and not
     * only by the echo: a broker drops QoS 0 messages for a subscriber that falls behind, and there can be thousands at once.
     *
     * @return how many
     */
    public int clearRetained(String branch) {
        List<String> found = retainedTopics(branch);
        for (String topic : found) {
            send(topic, new byte[0], MqttQoS.AT_MOST_ONCE.value(), true);
            TopicUpdate old = topics.remove(topic);
            changed.put(topic, new TopicUpdate(topic, null, false, 0, false, false, 0, old == null ? 0 : old.count(), true, System.currentTimeMillis()));
        }
        return found.size();
    }

    private static String text(byte[] payload) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private void onMessage(MqttPublishMessage message) {
        String topic = message.topicName();
        byte[] payload = message.payload().getBytes();
        TopicUpdate old = topics.get(topic);
        long now = System.currentTimeMillis();
        long count = old == null ? 1 : old.count() + 1;
        if (payload.length == 0) {
            // how a retained message is deleted; a topic that never had a value says nothing either
            topics.remove(topic);
            changed.put(topic, new TopicUpdate(topic, null, false, 0, false, false, 0, count, true, now));
            return;
        }
        if (old == null && topics.size() >= MAX_TOPICS) {
            error = "More than " + MAX_TOPICS + " topics, new ones are not shown";
            return;
        }
        boolean cut = payload.length > MAX_VALUE_BYTES;
        byte[] shown = cut ? java.util.Arrays.copyOf(payload, MAX_VALUE_BYTES) : payload;
        String value = text(shown);
        // a cut can end in the middle of a character
        boolean binary = value == null && !cut || value == null && text(java.util.Arrays.copyOf(shown, shown.length - 3)) == null;
        if (value == null) {
            value = binary ? HexFormat.of().formatHex(payload, 0, Math.min(payload.length, MAX_BINARY_PREVIEW)) : text(java.util.Arrays.copyOf(shown, shown.length - 3));
        }
        // the flag only comes with the first delivery, later values of a retained topic arrive without it
        boolean retained = message.isRetain() || old != null && old.retained();
        TopicUpdate update = new TopicUpdate(topic, value, binary, payload.length, cut || binary && payload.length > MAX_BINARY_PREVIEW, retained, message.qosLevel().value(), count, false, now);
        topics.put(topic, update);
        changed.put(topic, update);
    }
}
