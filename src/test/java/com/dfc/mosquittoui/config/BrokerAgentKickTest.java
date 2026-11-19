package com.dfc.mosquittoui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dfc.mosquittoui.api.Dtos.Kick;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** What the UI takes from control/dynsec-kick, which the agent writes (docker/mosquitto/agent.sh, dynsec_kick). */
class BrokerAgentKickTest {

    @TempDir
    Path shared;

    private BrokerAgent agent() {
        BrokerAgent agent = new BrokerAgent();
        agent.sharedDir = shared;
        return agent;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "''|MINIMAL",
        "plugin_opt_kick minimal|MINIMAL",
        "plugin_opt_kick always|ALWAYS",
        "  plugin_opt_kick   ALWAYS  |ALWAYS",
        // a plugin without kick-minimal.patch disconnects on every change, whatever the option says
        "unpatched|ALWAYS",
    })
    void theLineOfTheAgent(String line, Kick expected) throws IOException {
        Files.createDirectories(shared.resolve("control"));
        Files.writeString(shared.resolve("control/dynsec-kick"), line + "\n");
        assertEquals(expected, agent().dynsecKick());
    }

    /** An agent from before the patch writes no such file, and its broker disconnects on every change. */
    @Test
    void noFileFromAnOlderAgent() {
        assertEquals(Kick.ALWAYS, agent().dynsecKick());
    }
}
