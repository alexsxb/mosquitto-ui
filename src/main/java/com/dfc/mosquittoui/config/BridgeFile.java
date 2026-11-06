package com.dfc.mosquittoui.config;

import com.dfc.mosquittoui.api.Dtos.BridgeForm;
import com.dfc.mosquittoui.api.Dtos.BridgeTopic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bridges in the syntax of mosquitto.conf. The file of the bridges is written from the forms, comments do not survive; lines
 * the form does not know do, as extra options. Topics are compared as seen by the broker: a bridge with "#" and "N" gets a
 * message on N twice, and two bridges to the same broker pass a message back and forth without end, try_private or not. A
 * disabled bridge stays in the file as lines the broker takes for comments, so that it is backed up and restored with the others.
 */
public final class BridgeFile {

    private static final Set<String> BRIDGE_OPTIONS = Set.of("address", "addresses", "bridge_alpn", "bridge_attempt_unsubscribe", "bridge_bind_address",
            "bridge_cafile", "bridge_capath", "bridge_certfile", "bridge_identity", "bridge_insecure", "bridge_keyfile", "bridge_max_packet_size",
            "bridge_outgoing_retain", "bridge_protocol_version", "bridge_psk", "bridge_receive_maximum", "bridge_reload_type", "bridge_require_ocsp",
            "bridge_session_expiry_interval", "bridge_tcp_keepalive", "bridge_tcp_user_timeout", "bridge_tls_version", "cleansession", "clientid",
            "idle_timeout", "keepalive_interval", "local_cleansession", "local_clientid", "local_password", "local_username", "notification_topic",
            "notifications", "notifications_local_only", "password", "remote_clientid", "remote_password", "remote_username", "restart_timeout",
            "round_robin", "start_type", "threshold", "topic", "try_private", "username");
    private static final int DEFAULT_PORT = 1883;
    private static final String EMPTY = "\"\"";
    private static final String DISABLED = "#disabled ";

    /** The bridges of a text, and its other lines without the comments. */
    public record Parts(List<BridgeForm> bridges, List<String> others, List<String> orphans) {
    }

    private static final class Builder {
        final String name;
        final boolean disabled;
        String address;
        String clientId;
        String username;
        String password;
        Boolean tryPrivate;
        Boolean cleanSession;
        String startType;
        Integer keepalive;
        String restartTimeout;
        String protocol;
        final List<BridgeTopic> topics = new ArrayList<>();
        final List<String> extra = new ArrayList<>();

        Builder(String name, boolean disabled) {
            this.name = name;
            this.disabled = disabled;
        }

        void add(String key, String value, String line) {
            switch (key) {
                case "address", "addresses" -> address = value;
                case "remote_clientid", "clientid" -> clientId = value;
                case "remote_username", "username" -> username = value;
                case "remote_password", "password" -> password = value;
                case "try_private" -> tryPrivate = Boolean.valueOf(value);
                case "cleansession" -> cleanSession = Boolean.valueOf(value);
                case "start_type" -> startType = value;
                case "keepalive_interval" -> keepalive = integer(value);
                case "restart_timeout" -> restartTimeout = value;
                case "bridge_protocol_version" -> protocol = value;
                case "topic" -> topics.add(topic(value));
                default -> extra.add(line);
            }
        }

        BridgeForm build() {
            return new BridgeForm(name, address, clientId, username, password, tryPrivate, cleanSession, startType, keepalive, restartTimeout, protocol, topics, extra, disabled);
        }
    }

    private BridgeFile() {
    }

    private static Integer integer(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String prefix(String[] parts, int index) {
        return parts.length <= index || parts[index].equals(EMPTY) ? null : parts[index];
    }

    /** topic pattern [[[ out | in | both ] qos-level] local-prefix remote-prefix] */
    private static BridgeTopic topic(String value) {
        String[] parts = value.split("\\s+");
        Integer qos = parts.length > 2 ? integer(parts[2]) : null;
        return new BridgeTopic(parts[0], parts.length > 1 ? parts[1] : "out", qos == null ? 0 : qos, prefix(parts, 3), prefix(parts, 4));
    }

    public static Parts split(String text) {
        List<BridgeForm> bridges = new ArrayList<>();
        List<String> others = new ArrayList<>();
        List<String> orphans = new ArrayList<>();
        Builder current = null;
        for (String raw : text.split("\\R")) {
            boolean disabled = raw.strip().startsWith(DISABLED);
            String line = raw.strip().substring(disabled ? DISABLED.length() : 0).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            String value = parts.length > 1 ? parts[1] : "";
            if (parts[0].equals("connection")) {
                if (current != null) {
                    bridges.add(current.build());
                }
                current = new Builder(value, disabled);
            } else if (current != null && (disabled || BRIDGE_OPTIONS.contains(parts[0]))) {
                current.add(parts[0], value, line);
            } else if (!BRIDGE_OPTIONS.contains(parts[0])) {
                others.add(line);
            } else {
                orphans.add(parts[0]);
            }
        }
        if (current != null) {
            bridges.add(current.build());
        }
        return new Parts(bridges, others, orphans);
    }

    /** Whether this can be a line in the section of a bridge, connection aside. */
    static boolean isBridgeOption(String key) {
        return BRIDGE_OPTIONS.contains(key);
    }

    public static List<BridgeForm> parse(String text) {
        return split(text).bridges();
    }

    private static void option(List<String> lines, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            lines.add(key + " " + value);
        }
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? EMPTY : value;
    }

    public static String render(List<BridgeForm> bridges) {
        List<String> lines = new ArrayList<>();
        lines.add("# Written by mosquitto-ui, change the bridges there");
        for (BridgeForm bridge : bridges) {
            lines.add("");
            lines.addAll(render(bridge).stream().map(line -> bridge.disabled() ? DISABLED + line : line).toList());
        }
        return String.join("\n", lines) + "\n";
    }

    private static List<String> render(BridgeForm bridge) {
        List<String> lines = new ArrayList<>();
        lines.add("connection " + bridge.name());
        option(lines, "address", bridge.address());
        option(lines, "remote_clientid", bridge.remoteClientId());
        option(lines, "remote_username", bridge.username());
        option(lines, "remote_password", bridge.password());
        option(lines, "try_private", bridge.tryPrivate());
        option(lines, "cleansession", bridge.cleanSession());
        option(lines, "start_type", bridge.startType());
        option(lines, "keepalive_interval", bridge.keepalive());
        option(lines, "restart_timeout", bridge.restartTimeout());
        option(lines, "bridge_protocol_version", bridge.protocolVersion());
        bridge.extraOptions().stream().filter(line -> !line.isBlank()).map(String::strip).forEach(lines::add);
        for (BridgeTopic topic : bridge.topics()) {
            boolean prefixed = !blankToEmpty(topic.localPrefix()).equals(EMPTY) || !blankToEmpty(topic.remotePrefix()).equals(EMPTY);
            lines.add("topic " + topic.pattern() + " " + topic.direction() + " " + topic.qos()
                    + (prefixed ? " " + blankToEmpty(topic.localPrefix()) + " " + blankToEmpty(topic.remotePrefix()) : ""));
        }
        return lines;
    }

    /** Whether a topic can match both filters. */
    static boolean overlap(String first, String second) {
        String[] a = first.split("/", -1);
        String[] b = second.split("/", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            if (i < a.length && a[i].equals("#") || i < b.length && b[i].equals("#")) {
                return true;
            }
            if (i >= a.length || i >= b.length) {
                return false;
            }
            if (!a[i].equals("+") && !b[i].equals("+") && !a[i].equals(b[i])) {
                return false;
            }
        }
        return true;
    }

    private static String prefixed(String prefix, String pattern) {
        return (prefix == null ? "" : prefix) + pattern;
    }

    private static boolean sameTopics(BridgeTopic a, BridgeTopic b) {
        return overlap(prefixed(a.localPrefix(), a.pattern()), prefixed(b.localPrefix(), b.pattern()))
                && overlap(prefixed(a.remotePrefix(), a.pattern()), prefixed(b.remotePrefix(), b.pattern()));
    }

    /** overlap: a message goes over the bridge several times; echo: it comes back to the local clients once. */
    public static List<String> warnings(BridgeForm bridge) {
        List<String> warnings = new ArrayList<>();
        List<BridgeTopic> topics = bridge.topics();
        for (int i = 0; i < topics.size() && warnings.isEmpty(); i++) {
            for (int j = i + 1; j < topics.size(); j++) {
                if (sameTopics(topics.get(i), topics.get(j))) {
                    warnings.add("overlap");
                    break;
                }
            }
        }
        boolean both = topics.stream().anyMatch(topic -> topic.direction().equals("both"));
        if (both && Boolean.FALSE.equals(bridge.tryPrivate()) && !"mqttv50".equals(bridge.protocolVersion())) {
            warnings.add("echo");
        }
        if (!reportsState(bridge)) {
            warnings.add("noState");
        }
        return warnings;
    }

    /** The state is the notification of the broker on its default topic under $SYS, as measured with 2.1.2: both options take it away. */
    public static boolean reportsState(BridgeForm bridge) {
        return bridge.extraOptions().stream().map(String::strip).noneMatch(line -> line.matches("notifications\\s+false") || line.matches("notification_topic\\s.*"));
    }

    private static Set<String> hosts(BridgeForm bridge) {
        Set<String> hosts = new HashSet<>();
        for (String address : bridge.address().toLowerCase(Locale.ROOT).split("\\s+")) {
            hosts.add(address.contains(":") ? address : address + ":" + DEFAULT_PORT);
        }
        return hosts;
    }

    private static boolean leaves(BridgeTopic topic) {
        return !topic.direction().equals("in");
    }

    private static boolean arrives(BridgeTopic topic) {
        return !topic.direction().equals("out");
    }

    private static boolean circles(BridgeForm a, BridgeForm b) {
        for (BridgeTopic first : a.topics()) {
            for (BridgeTopic second : b.topics()) {
                if (sameTopics(first, second) && (leaves(first) && arrives(second) || arrives(first) && leaves(second))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The name of a bridge that sends messages in a circle with another one to the same broker, null without one. */
    public static String loop(List<BridgeForm> bridges) {
        for (int i = 0; i < bridges.size(); i++) {
            for (int j = i + 1; j < bridges.size(); j++) {
                BridgeForm a = bridges.get(i);
                BridgeForm b = bridges.get(j);
                Set<String> shared = hosts(a);
                shared.retainAll(hosts(b));
                if (!a.disabled() && !b.disabled() && !shared.isEmpty() && circles(a, b)) {
                    return b.name() + " and " + a.name();
                }
            }
        }
        return null;
    }
}
