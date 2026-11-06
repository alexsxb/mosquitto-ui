package com.dfc.mosquittoui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.api.Dtos.ConfigForm;
import com.dfc.mosquittoui.api.Dtos.ListenerForm;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ManagedConfigTest {

    @Test
    void listenersOfTheMainFile() {
        List<ListenerForm> listeners = ManagedConfig.parseMainListeners("""
                port 1883
                max_connections 500
                listener 8883 10.0.0.1
                certfile /certs/fullchain.pem
                keyfile /certs/privkey.pem
                listener 9001
                protocol websockets
                """);
        assertEquals(List.of(new ListenerForm(1883, null, "mqtt", null, null, null, 500),
                new ListenerForm(8883, "10.0.0.1", "mqtt", "/certs/fullchain.pem", "/certs/privkey.pem", null, null),
                new ListenerForm(9001, null, "websockets", null, null, null, null)), listeners);
    }

    private static final String TEXT = """
            # my comment
            log_type error
            log_type warning
            sys_interval 10
            some_other_option 5
            listener 8883 0.0.0.0
            certfile /c.pem
            keyfile /k.pem
            max_connections 10
            listener 9001
            protocol websockets
            """;

    @Test
    void parse() {
        ConfigForm form = ManagedConfig.parse(TEXT);
        assertEquals(List.of("error", "warning"), form.logTypes());
        assertEquals("10", form.options().get("sys_interval"));
        assertNull(form.options().get("max_packet_size"));
        assertFalse(form.options().containsKey("some_other_option"));
        assertEquals(new ListenerForm(8883, "0.0.0.0", "mqtt", "/c.pem", "/k.pem", null, 10), form.listeners().get(0));
        assertEquals(new ListenerForm(9001, null, "websockets", null, null, null, null), form.listeners().get(1));
    }

    @Test
    void renderKeepsWhatTheFormDoesNotKnow() {
        ConfigForm form = ManagedConfig.parse(TEXT);
        ConfigForm changed = new ConfigForm(List.of("all"), java.util.Map.of("persistent_client_expiration", "1d", "max_packet_size", "1000", "retain_available", "false"),
                List.of(new ListenerForm(8883, null, "mqtt", "/new.pem", "/k.pem", null, 10), new ListenerForm(1884, null, "mqtt", null, null, null, null)));
        String text = ManagedConfig.render(TEXT, changed);

        assertTrue(text.contains("# my comment"));
        assertTrue(text.contains("some_other_option 5"));
        assertTrue(text.contains("max_connections 10"), "options of a listener stay with it");
        assertTrue(text.contains("log_type all"));
        assertFalse(text.contains("log_type error"));
        assertFalse(text.contains("sys_interval"));
        assertFalse(text.contains("9001"), "removed listener");
        assertTrue(text.indexOf("max_connections 10") < text.indexOf("listener 1884"));
        assertEquals(changed.listeners(), ManagedConfig.parse(text).listeners());
        assertEquals(java.util.Set.of(), ManagedConfig.changedOptions(TEXT, ManagedConfig.render(TEXT, form)), "no change without a change");
    }

    @Test
    void restart() {
        assertEquals(Set.of("sys_interval"), ManagedConfig.changedOptions("sys_interval 1\n", "# c\nsys_interval 2\n"));
        assertFalse(ManagedConfig.requiresRestart(ManagedConfig.changedOptions("sys_interval 1\n", "sys_interval 2\n")));
        assertFalse(ManagedConfig.requiresRestart(ManagedConfig.changedOptions("certfile /a\n", "certfile /b\n")));
        assertTrue(ManagedConfig.requiresRestart(ManagedConfig.changedOptions("", "listener 1884\n")));
        assertTrue(ManagedConfig.requiresRestart(ManagedConfig.changedOptions("", "unknown_option 1\n")));
    }

    @Test
    void aListenerBehindAProxy() {
        String text = "listener 1884\nenable_proxy_protocol 2\nproxy_protocol_v2_require_tls true\n";
        ListenerForm listener = ManagedConfig.parse(text).listeners().getFirst();
        assertEquals(new ListenerForm(1884, null, "mqtt", null, null, null, null, 2, true), listener);
        assertEquals(text, ManagedConfig.render(text, ManagedConfig.parse(text)));
        // requiring TLS only means something with version 2
        String one = ManagedConfig.render(text, new ConfigForm(List.of(), java.util.Map.of(), List.of(new ListenerForm(1884, null, "mqtt", null, null, null, null, 1, true))));
        assertEquals("listener 1884\nenable_proxy_protocol 1\n", one);
        String off = ManagedConfig.render(text, new ConfigForm(List.of(), java.util.Map.of(), List.of(new ListenerForm(1884, null, "mqtt", null, null, null, null))));
        assertEquals("listener 1884\n", off);
    }
}
