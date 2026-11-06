package com.dfc.mosquittoui.importer;

import com.dfc.mosquittoui.broker.BrokerUnavailableException;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.broker.MosquittoConfig;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Reads retained messages over a connection of its own, all that are there or an exception. With QoS 0 on purpose: measured with Mosquitto 2.1.2 and 30000 messages,
 * a subscription with QoS 1 or 2 got 1040 of them, as the rest does not fit max_queued_messages and is dropped without a word.
 * The stored QoS and the properties of MQTT 5 do not come along.
 */
@ApplicationScoped
public class RetainedReader {

    /** The broker sends them right after the subscription; a pause means there are no more. */
    private static final long QUIET_MS = 2000;
    private static final long MAX_MS = 600_000;
    private static final long POLL_MS = 50;

    @Inject
    Vertx vertx;

    @Inject
    DynsecClient dynsec;

    @Inject
    MosquittoConfig config;

    public Map<String, byte[]> read(String username, String password, List<String> filters) {
        Map<String, byte[]> found = new ConcurrentHashMap<>();
        AtomicLong last = new AtomicLong(System.currentTimeMillis());
        CompletableFuture<Void> subscribed = new CompletableFuture<>();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        MqttClient reader = MqttClient.create(vertx, dynsec.options(username, password));
        reader.publishHandler(message -> {
            if (message.isRetain() && message.payload().length() > 0) {
                found.put(message.topicName(), message.payload().getBytes());
                last.set(System.currentTimeMillis());
            }
        });
        reader.subscribeCompletionHandler(ack -> {
            last.set(System.currentTimeMillis());
            subscribed.complete(null);
        });
        reader.closeHandler(v -> closed.complete(null));
        reader.connect(config.port(), config.host())
                .compose(ack -> reader.subscribe(filters.stream().collect(Collectors.toMap(filter -> filter, filter -> MqttQoS.AT_MOST_ONCE.value()))))
                .onFailure(subscribed::completeExceptionally);
        try {
            subscribed.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
            long end = System.currentTimeMillis() + MAX_MS;
            while (System.currentTimeMillis() - last.get() < QUIET_MS) {
                // half a backup that looks like a whole one would be worse than none
                if (closed.isDone()) {
                    throw new BrokerUnavailableException("The connection ended while reading the retained messages");
                }
                if (System.currentTimeMillis() > end) {
                    throw new BrokerUnavailableException("The retained messages did not arrive in time");
                }
                Thread.sleep(POLL_MS);
            }
        } catch (TimeoutException e) {
            throw new BrokerUnavailableException("No answer within " + config.timeout());
        } catch (ExecutionException e) {
            throw new BrokerUnavailableException(e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("Interrupted");
        } finally {
            if (reader.isConnected()) {
                reader.disconnect();
            }
        }
        return found;
    }
}
