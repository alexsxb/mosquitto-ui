package com.dfc.mosquittoui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * plugin_opt_kick always: the patched dynamic security of docker/mosquitto/dynsec disconnects on every change, as Mosquitto
 * itself does. A broker of its own, as the one of MosquittoResource runs with the default (minimal), see
 * AccessRulesTest.onlyWhatCanTakeASubscriptionAwayDisconnects.
 */
class DynsecKickAlwaysTest {

    private static final String ADMIN_PASSWORD = "always-admin-password";
    private static final String PASSWORD = "always-password";

    private static GenericContainer<?> broker;
    private static Vertx vertx;

    @BeforeAll
    static void start() {
        String conf = """
                listener 1883
                plugin /usr/lib/mosquitto_dynamic_security.so
                plugin_opt_config_file /mosquitto/data/dynamic-security.json
                plugin_opt_kick always
                """;
        broker = new GenericContainer<>(MosquittoResource.IMAGE)
                .withEnv("MOSQUITTO_DYNSEC_PASSWORD", ADMIN_PASSWORD)
                .withCopyToContainer(Transferable.of(conf), "/mosquitto/config/always.conf")
                .withCommand("mosquitto", "-c", "/mosquitto/config/always.conf")
                .withExposedPorts(1883)
                .waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*", 1));
        broker.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        if (vertx != null) {
            vertx.close();
        }
        if (broker != null) {
            broker.stop();
        }
    }

    private static void ctrl(String... args) throws Exception {
        String[] command = new String[args.length + 6];
        System.arraycopy(new String[] {"mosquitto_ctrl", "-u", "admin", "-P", ADMIN_PASSWORD, "dynsec"}, 0, command, 0, 6);
        System.arraycopy(args, 0, command, 6, args.length);
        var result = broker.execInContainer(command);
        assertEquals(0, result.getExitCode(), String.join(" ", args) + ": " + result.getStdout() + result.getStderr());
    }

    @Test
    void everyChangeDisconnects() throws Exception {
        ctrl("createRole", "always-role");
        ctrl("createClient", "always", "-p", PASSWORD);
        ctrl("addClientRole", "always", "always-role", "0");
        MqttClient client = MqttClient.create(vertx, new MqttClientOptions().setUsername("always").setPassword(PASSWORD).setClientId("always-id"));
        client.connect(broker.getMappedPort(1883), broker.getHost()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        CompletableFuture<Void> closed = new CompletableFuture<>();
        client.closeHandler(v -> closed.complete(null));

        // with minimal this keeps the connection
        ctrl("addRoleACL", "always-role", "publishClientSend", "always/x", "allow", "0");

        closed.get(10, TimeUnit.SECONDS);
    }
}
