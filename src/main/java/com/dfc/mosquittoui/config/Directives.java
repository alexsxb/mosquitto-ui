package com.dfc.mosquittoui.config;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the files of the UI may contain. The broker runs as root until it has read its configuration, so some directives
 * reach beyond the broker: they load code (plugins, a TLS engine), write or serve files anywhere (pid_file, log_dest file,
 * http_dir), change the user or the authentication of the setup. They belong in mosquitto.conf, which stays with the
 * operator. The file of the bridges holds nothing but bridges. A line that the file already has stays allowed, so that a
 * setup from before these rules keeps working; it cannot come back once removed.
 */
public final class Directives {

    private static final Set<String> REFUSED = Set.of(
            "plugin", "plugin_load", "plugin_use", "global_plugin", "auth_plugin",
            "include_dir", "pid_file", "user",
            "persistence", "persistence_file", "persistence_location", "autosave_interval", "autosave_on_changes",
            "password_file", "acl_file", "psk_file", "per_listener_settings",
            "http_dir", "tls_engine", "tls_engine_kpass_sha1");
    private static final List<String> REFUSED_PREFIXES = List.of("plugin_opt_", "global_plugin_opt_", "auth_opt_");
    private static final String DISABLED = "#disabled ";

    private Directives() {
    }

    private static String key(String line) {
        return line.split("\\s+", 2)[0];
    }

    private static String value(String line) {
        String[] parts = line.split("\\s+", 2);
        return parts.length > 1 ? parts[1].strip() : "";
    }

    /** The option lines, disabled bridges included: enabling one turns its lines into options without another check. */
    private static List<String> options(String text) {
        return text.lines().map(String::strip)
                .map(line -> line.startsWith(DISABLED) ? line.substring(DISABLED.length()).strip() : line)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }

    /** Why a line of the managed file is not allowed, null if it is. */
    static String managed(String line) {
        String key = key(line);
        if (REFUSED.contains(key) || REFUSED_PREFIXES.stream().anyMatch(key::startsWith)) {
            return key + " belongs in mosquitto.conf";
        }
        if (key.equals("log_dest") && value(line).toLowerCase(Locale.ROOT).startsWith("file")) {
            return "log_dest file belongs in mosquitto.conf";
        }
        if (key.equals("connection")) {
            return "Bridges belong on the page Bridges";
        }
        return null;
    }

    /** Why a line of the file of the bridges is not allowed, null if it is. */
    static String bridge(String line) {
        String key = key(line);
        if (!key.equals("connection") && !BridgeFile.isBridgeOption(key)) {
            return key + " is no option of a bridge";
        }
        if (key.equals("topic")) {
            // topic pattern [direction [qos [local-prefix remote-prefix]]]: the local topic is the prefix and the pattern
            String[] parts = value(line).split("\\s+");
            String local = (parts.length > 3 && !parts[3].equals("\"\"") ? parts[3] : "") + parts[0];
            if (local.startsWith("$CONTROL")) {
                return "A bridge must not reach $CONTROL, the administration of the broker";
            }
        }
        return null;
    }

    /** Why a text for a file of the UI is not allowed, null if it is. */
    static String refusal(String text, boolean bridges) {
        return options(text).stream().map(line -> bridges ? bridge(line) : managed(line)).filter(reason -> reason != null).findFirst().orElse(null);
    }

    /**
     * Refuses a new text for a file of the UI with a line it must not contain and did not have before.
     *
     * @param bridges whether it is the file of the bridges
     */
    public static void check(String text, String before, boolean bridges) {
        Set<String> old = new HashSet<>(options(before));
        for (String line : options(text)) {
            String reason = bridges ? bridge(line) : managed(line);
            if (reason != null && !old.contains(line)) {
                // only the reason: the rest of the line could be a password
                throw new AgentException(reason, false);
            }
        }
    }
}
