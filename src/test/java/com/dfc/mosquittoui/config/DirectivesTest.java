package com.dfc.mosquittoui.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DirectivesTest {

    @Test
    void whatReachesBeyondTheBrokerStaysOutOfTheManagedFile() {
        for (String line : new String[] {"plugin /mosquitto/ui/x.so", "plugin_load x /x.so", "plugin_opt_config_file /x", "global_plugin /x.so", "auth_opt_x y",
                "include_dir /tmp", "pid_file /mosquitto/agent.sh", "user root", "persistence_location /", "password_file /etc/shadow", "acl_file /x",
                "per_listener_settings true", "http_dir /mosquitto/data", "tls_engine pkcs11", "log_dest file /mosquitto/agent.sh", "log_dest  File /x",
                "connection evil"}) {
            assertNotNull(Directives.managed(line), line);
        }
        for (String line : new String[] {"sys_interval 5", "log_dest stdout", "log_type all", "listener 8883", "certfile /x/fullchain.pem", "allow_anonymous false"}) {
            assertNull(Directives.managed(line), line);
        }
    }

    @Test
    void theFileOfTheBridgesHoldsOnlyBridges() {
        assertNull(Directives.bridge("connection north"));
        assertNull(Directives.bridge("remote_password secret"));
        assertNull(Directives.bridge("topic sensors/# out 1 local/ remote/"));
        assertNotNull(Directives.bridge("log_dest file /x"));
        assertNotNull(Directives.bridge("sys_interval 1"));
        assertNotNull(Directives.bridge("topic dynamic-security/# in 0 $CONTROL/ \"\""));
        assertNotNull(Directives.bridge("topic $CONTROL/dynamic-security/v1 both 0"));
    }

    @Test
    void aDisabledBridgeIsCheckedAsWell() {
        String text = "connection x\naddress a:1883\n#disabled connection y\n#disabled plugin /x.so\n";
        assertEquals("plugin is no option of a bridge", assertThrows(AgentException.class, () -> Directives.check(text, "", true)).getMessage());
    }

    @Test
    void aLineFromBeforeTheseRulesStays() {
        String before = "log_dest file /mosquitto/log/mosquitto.log\nsys_interval 5\n";
        assertDoesNotThrow(() -> Directives.check(before + "sys_interval 10\n", before, false));
        assertThrows(AgentException.class, () -> Directives.check(before + "pid_file /x\n", before, false));
    }

    @Test
    void theMessageLeavesOutTheValue() {
        AgentException refused = assertThrows(AgentException.class, () -> Directives.check("auth_opt_password hunter2\n", "", false));
        assertEquals("auth_opt_password belongs in mosquitto.conf", refused.getMessage());
    }
}
