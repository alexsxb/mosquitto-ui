package com.dfc.mosquittoui.config;

import com.dfc.mosquittoui.api.Dtos.ConfigForm;
import com.dfc.mosquittoui.api.Dtos.ListenerForm;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;

/**
 * The configuration file of the UI as text: the form shows and changes the options it knows, everything else in the file,
 * comments included, stays as it is. A listener is its line and the options up to the next one.
 */
public final class ManagedConfig {

    /**
     * Measured with Mosquitto 2.1.2, not taken from mosquitto.conf(5): it also lists max_packet_size and max_queued_messages, which
     * stay as they were after a reload. Whatever could not be seen to work counts as needing a restart.
     */
    private static final Set<String> RELOADABLE = Set.of("allow_anonymous", "certfile", "connection_messages", "keyfile", "log_timestamp", "log_timestamp_format", "log_type",
            "sys_interval", "upgrade_outgoing_qos");

    /** What the form sets. */
    public static final Set<String> OPTIONS = Set.of("allow_anonymous", "allow_zero_length_clientid", "connection_messages", "log_timestamp", "log_timestamp_format",
            "max_inflight_bytes", "max_inflight_messages", "max_keepalive", "max_packet_size", "max_queued_bytes", "max_queued_messages", "memory_limit",
            "persistent_client_expiration", "queue_qos0_messages", "retain_available", "set_tcp_nodelay", "sys_interval", "upgrade_outgoing_qos");

    private static final List<String> LISTENER_OPTIONS = List.of("protocol", "certfile", "keyfile", "cafile", "max_connections", "enable_proxy_protocol",
            "proxy_protocol_v2_require_tls");

    private ManagedConfig() {
    }

    private static boolean isOption(String line) {
        String trimmed = line.strip();
        return !trimmed.isEmpty() && !trimmed.startsWith("#");
    }

    private static String key(String line) {
        return line.strip().split("\\s+", 2)[0];
    }

    private static String value(String line) {
        String[] parts = line.strip().split("\\s+", 2);
        return parts.length > 1 ? parts[1] : "";
    }

    private static List<String> lines(String text) {
        return new ArrayList<>(text.lines().toList());
    }

    private static int firstListener(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (isOption(lines.get(i)) && key(lines.get(i)).equals("listener")) {
                return i;
            }
        }
        return lines.size();
    }

    /** The blocks of the listeners, each starting with its listener line. */
    private static List<List<String>> blocks(List<String> lines) {
        List<List<String>> blocks = new ArrayList<>();
        for (String line : lines.subList(firstListener(lines), lines.size())) {
            if (isOption(line) && key(line).equals("listener")) {
                blocks.add(new ArrayList<>());
            }
            blocks.getLast().add(line);
        }
        return blocks;
    }

    private static String get(List<String> lines, String key) {
        return lines.stream().filter(ManagedConfig::isOption).filter(line -> key(line).equals(key)).map(ManagedConfig::value).findFirst().orElse(null);
    }

    private static Integer integer(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static ConfigForm parse(String text) {
        List<String> lines = lines(text);
        List<String> global = lines.subList(0, firstListener(lines));
        List<ListenerForm> listeners = new ArrayList<>();
        for (List<String> block : blocks(lines)) {
            String[] listener = value(block.getFirst()).split("\\s+");
            Integer port = integer(listener[0]);
            if (port != null) {
                String protocol = get(block, "protocol");
                listeners.add(new ListenerForm(port, listener.length > 1 ? listener[1] : null, protocol != null ? protocol : "mqtt",
                        get(block, "certfile"), get(block, "keyfile"), get(block, "cafile"), integer(get(block, "max_connections")),
                        integer(get(block, "enable_proxy_protocol")), "true".equals(get(block, "proxy_protocol_v2_require_tls")) ? Boolean.TRUE : null));
            }
        }
        List<String> logTypes = global.stream().filter(ManagedConfig::isOption).filter(line -> key(line).equals("log_type")).map(ManagedConfig::value).toList();
        Map<String, String> options = new TreeMap<>();
        for (String option : OPTIONS) {
            String value = get(global, option);
            if (value != null) {
                options.put(option, value);
            }
        }
        return new ConfigForm(logTypes, options, listeners);
    }

    /** The listeners of what the agent reports of the main file, where the old "port" still works as well. */
    public static List<ListenerForm> parseMainListeners(String text) {
        return parse(text.replaceAll("(?m)^\\s*port\\s", "listener ")).listeners();
    }

    /** The text with the values of the form. */
    public static String render(String text, ConfigForm form) {
        List<String> lines = lines(text);
        List<String> global = new ArrayList<>(lines.subList(0, firstListener(lines)));
        global.removeIf(line -> isOption(line) && key(line).equals("log_type"));
        form.logTypes().forEach(type -> global.add("log_type " + type));
        for (String option : new TreeSet<>(OPTIONS)) {
            set(global, option, form.options().get(option));
        }

        List<List<String>> blocks = blocks(lines);
        List<String> result = new ArrayList<>(global);
        for (ListenerForm listener : form.listeners()) {
            List<String> block = blocks.stream().filter(b -> value(b.getFirst()).split("\\s+")[0].equals(String.valueOf(listener.port()))).findFirst().orElseGet(ArrayList::new);
            String line = "listener " + listener.port() + (blank(listener.bindAddress()) ? "" : " " + listener.bindAddress());
            if (block.isEmpty()) {
                block.add(line);
            } else {
                block.set(0, line);
            }
            set(block, LISTENER_OPTIONS.get(0), "mqtt".equals(listener.protocol()) ? null : listener.protocol());
            set(block, LISTENER_OPTIONS.get(1), listener.certfile());
            set(block, LISTENER_OPTIONS.get(2), listener.keyfile());
            set(block, LISTENER_OPTIONS.get(3), listener.cafile());
            set(block, LISTENER_OPTIONS.get(4), listener.maxConnections());
            set(block, LISTENER_OPTIONS.get(5), listener.proxyProtocol());
            // only with version 2, and the broker's default is false
            set(block, LISTENER_OPTIONS.get(6), Integer.valueOf(2).equals(listener.proxyProtocol()) && Boolean.TRUE.equals(listener.proxyRequireTls()) ? "true" : null);
            result.addAll(block);
        }
        return String.join("\n", result) + "\n";
    }

    /** The text with more options: global ones in front of the first listener, as they would count for it otherwise. */
    public static String append(String text, List<String> global, List<String> listeners) {
        List<String> lines = lines(text);
        lines.addAll(firstListener(lines), global);
        lines.addAll(listeners);
        return String.join("\n", lines) + "\n";
    }

    /** The option names outside of listener blocks. */
    public static Set<String> globalOptions(String text) {
        List<String> lines = lines(text);
        Set<String> names = new HashSet<>();
        lines.subList(0, firstListener(lines)).stream().filter(ManagedConfig::isOption).map(ManagedConfig::key).forEach(names::add);
        return names;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void set(List<String> lines, String key, Object value) {
        boolean remove = value == null || blank(value.toString());
        for (int i = 0; i < lines.size(); i++) {
            if (isOption(lines.get(i)) && key(lines.get(i)).equals(key)) {
                if (remove) {
                    lines.remove(i);
                } else {
                    lines.set(i, key + " " + value);
                }
                return;
            }
        }
        if (!remove) {
            lines.add(key + " " + value);
        }
    }

    private static Set<String> options(String text) {
        Set<String> options = new HashSet<>();
        text.lines().filter(ManagedConfig::isOption).map(String::strip).forEach(options::add);
        return options;
    }

    /** The names of the options that differ between two texts. */
    public static Set<String> changedOptions(String before, String after) {
        Set<String> old = options(before);
        Set<String> changed = new LinkedHashSet<>(options(after));
        changed.removeAll(old);
        old.removeAll(options(after));
        changed.addAll(old);
        Set<String> names = new TreeSet<>();
        changed.forEach(line -> names.add(key(line)));
        return names;
    }

    public static boolean requiresRestart(Set<String> changedOptions) {
        return !RELOADABLE.containsAll(changedOptions);
    }
}
