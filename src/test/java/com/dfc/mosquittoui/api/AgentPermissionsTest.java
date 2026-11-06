package com.dfc.mosquittoui.api;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/** The shared directory as the agent leaves it: the broker reads there and writes nothing, links lead the agent nowhere. */
@QuarkusTest
@QuarkusTestResource(MosquittoResource.class)
class AgentPermissionsTest {

    private static Path shared() {
        return ConfigProvider.getConfig().getValue("mosquitto-ui.shared-dir", Path.class);
    }

    private static int asBroker(String command) throws Exception {
        return MosquittoResource.broker.execInContainerWithUser("mosquitto", "sh", "-c", command).getExitCode();
    }

    @Test
    void theBrokerReadsItsConfigurationButCannotChangeIt() throws Exception {
        assertEquals(0, asBroker("cat /mosquitto/ui/conf.d/mosquitto-ui.conf /mosquitto/ui/conf.d/mosquitto-ui-bridges.conf"));
        assertNotEquals(0, asBroker("touch /mosquitto/ui/conf.d/plugin.conf"));
        assertNotEquals(0, asBroker("echo x >> /mosquitto/ui/conf.d/mosquitto-ui.conf"));
        assertNotEquals(0, asBroker("ls /mosquitto/ui/control"));
        assertNotEquals(0, asBroker("mkdir /mosquitto/ui/elsewhere"));
    }

    @Test
    void theAgentDoesNotWriteThroughALink() throws Exception {
        Path control = shared().resolve("control");
        // where the answer is about to be written, a link to a file of the broker
        Files.deleteIfExists(control.resolve("response.tmp"));
        Files.createSymbolicLink(control.resolve("response.tmp"), Path.of("/mosquitto/data/written-by-the-agent"));
        String id = "0123456789abcdef";
        Files.writeString(control.resolve("request.new"), id + " no-such-operation\n");
        Files.move(control.resolve("request.new"), control.resolve("request"));
        await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(control.resolve("response")) && Files.readString(control.resolve("response")).startsWith(id));
        assertNotEquals(0, MosquittoResource.broker.execInContainer("test", "-e", "/mosquitto/data/written-by-the-agent").getExitCode());
    }

    @Test
    void anIdThatIsNoIdGetsNoAnswer() throws Exception {
        Path control = shared().resolve("control");
        Files.writeString(control.resolve("request.new"), "$(touch${IFS}/mosquitto/data/injected) no-such-operation\n");
        Files.move(control.resolve("request.new"), control.resolve("request"));
        await().atMost(Duration.ofSeconds(10)).until(() -> !Files.exists(control.resolve("request")));
        assertNotEquals(0, MosquittoResource.broker.execInContainer("test", "-e", "/mosquitto/data/injected").getExitCode());
    }
}
