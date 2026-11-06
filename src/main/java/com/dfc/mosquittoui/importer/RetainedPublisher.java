package com.dfc.mosquittoui.importer;

import com.dfc.mosquittoui.broker.BrokerUnavailableException;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.broker.MosquittoConfig;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Publishes messages as retained over a connection of their own, one after the other and each with QoS 1: an acknowledged one is stored. */
@ApplicationScoped
public class RetainedPublisher {

    @Inject
    Vertx vertx;

    @Inject
    DynsecClient dynsec;

    @Inject
    MosquittoConfig config;

    public void publish(String username, String password, List<DbFile.Message> messages) {
        Context context = vertx.getOrCreateContext();
        MqttClient client = MqttClient.create(vertx, dynsec.options(username, password));
        AtomicReference<CompletableFuture<Void>> acknowledged = new AtomicReference<>();
        client.publishCompletionHandler(id -> acknowledged.get().complete(null));
        try {
            await(client.connect(config.port(), config.host()).toCompletionStage().toCompletableFuture());
            for (DbFile.Message message : messages) {
                CompletableFuture<Void> done = new CompletableFuture<>();
                acknowledged.set(done);
                context.runOnContext(v -> client.publish(message.topic(), Buffer.buffer(message.payload()), MqttQoS.AT_LEAST_ONCE, false, true)
                        .onFailure(done::completeExceptionally));
                await(done);
            }
        } finally {
            context.runOnContext(v -> {
                if (client.isConnected()) {
                    client.disconnect();
                }
            });
        }
    }

    private void await(CompletableFuture<?> future) {
        try {
            future.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new BrokerUnavailableException("No answer within " + config.timeout());
        } catch (ExecutionException e) {
            throw new BrokerUnavailableException(e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("Interrupted");
        }
    }
}
