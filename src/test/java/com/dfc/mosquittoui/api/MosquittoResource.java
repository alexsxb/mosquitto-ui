package com.dfc.mosquittoui.api;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/**
 * A real broker with the inspect, token login and dynamic security plugins, running in docker/mosquitto/agent.sh as in production: the
 * image of docker/mosquitto, built from its Dockerfile (Docker keeps its layers, so only a change there builds again).
 */
public class MosquittoResource implements QuarkusTestResourceLifecycleManager {

    static final String PASSWORD = "test-dynsec-password";

    static GenericContainer<?> broker;

    /** The broker image of this repository, built once for all tests that need it. */
    static final Future<String> IMAGE = new ImageFromDockerfile("mosquitto-ui-broker-test", false)
            .withDockerfile(Path.of("docker/mosquitto/Dockerfile"));

    /** The user running the tests: the owner of the directory they just made. */
    private static int owner(Path directory) {
        try {
            return (Integer) java.nio.file.Files.getAttribute(directory, "unix:uid");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, String> start() {
        Path shared = SharedDirectory.create("mosquitto-ui");
        broker = new GenericContainer<>(IMAGE)
                .withEnv("MOSQUITTO_DYNSEC_PASSWORD", PASSWORD)
                // the tests take the place of the UI, as the user running them
                .withEnv("UI_UID", String.valueOf(owner(shared)))
                .withNetwork(Network.SHARED)
                .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test.conf"), "/mosquitto/config/mosquitto.conf")
                // 1884: a listener behind a proxy, see ProxyProtocolTest
                .withExposedPorts(1883, 8883, 1884)
                .waitingFor(Wait.forLogMessage(".*mosquitto version .* running.*", 1));
        SharedDirectory.mount(broker, shared, "/mosquitto/ui", "sh", "/mosquitto/agent.sh");
        broker.start();
        return Map.of(
                "mosquitto.host", broker.getHost(),
                "mosquitto.port", String.valueOf(broker.getMappedPort(1883)),
                "mosquitto.username", "admin",
                "mosquitto.password", PASSWORD,
                "mosquitto-ui.shared-dir", shared.toString(),
                "mosquitto-ui.state-dir", shared.resolveSibling(shared.getFileName() + "-state").toString());
    }

    @Override
    public void stop() {
        if (broker != null) {
            broker.stop();
        }
    }
}
