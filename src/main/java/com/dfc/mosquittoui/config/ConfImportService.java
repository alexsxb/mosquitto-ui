package com.dfc.mosquittoui.config;

import com.dfc.mosquittoui.api.Dtos.BridgeForm;
import com.dfc.mosquittoui.api.Dtos.ConfImportBridge;
import com.dfc.mosquittoui.api.Dtos.ConfImportPlan;
import com.dfc.mosquittoui.api.Dtos.ConfImportResult;
import com.dfc.mosquittoui.api.Dtos.ConfSkipped;
import com.dfc.mosquittoui.api.Dtos.ListenerForm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Takes over files of an existing configuration: bridges become bridges of the UI, what is left goes into the managed file.
 * Left out is what this setup does itself, and listeners on ports in use: the broker would accept them in --test-config and
 * then not start.
 */
@ApplicationScoped
public class ConfImportService {

    private static final Map<String, String> LEFT_OUT = Map.ofEntries(
            Map.entry("password_file", "auth"), Map.entry("acl_file", "auth"),
            Map.entry("persistence", "persistence"), Map.entry("persistence_file", "persistence"), Map.entry("persistence_location", "persistence"),
            Map.entry("autosave_interval", "persistence"), Map.entry("autosave_on_changes", "persistence"),
            Map.entry("plugin", "plugin"), Map.entry("global_plugin", "plugin"), Map.entry("auth_plugin", "plugin"),
            Map.entry("include_dir", "system"), Map.entry("pid_file", "system"), Map.entry("user", "system"), Map.entry("log_dest", "system"));
    private static final List<String> LEFT_OUT_PREFIXES = List.of("plugin_opt_", "global_plugin_opt_", "auth_opt_");
    /** The options that count for the listener before them, the others are global wherever they are (mosquitto.conf(5)). */
    private static final Set<String> LISTENER_OPTIONS = Set.of("accept_protocol_versions", "bind_interface", "cafile", "capath", "certfile", "ciphers", "ciphers_tls1.3",
            "crlfile", "dhparamfile", "disable_client_cert_date_checks", "enable_proxy_protocol", "http_dir", "keyfile", "max_connections", "max_qos", "max_topic_alias", "max_topic_alias_broker",
            "mount_point", "protocol", "proxy_protocol_v2_require_tls", "psk_hint", "require_certificate", "socket_domain", "tls_engine", "tls_engine_kpass_sha1", "tls_keyform", "tls_version",
            "use_identity_as_username", "use_subject_as_username", "use_username_as_clientid", "websockets_headers_size", "websockets_origin");
    /** Can appear several times with different values. */
    private static final Set<String> REPEATABLE = Set.of("log_type");

    @Inject
    ConfigService config;

    @Inject
    BridgeService bridges;

    @Inject
    BrokerAgent agent;

    private record Options(List<String> global, List<String> listeners, List<ConfSkipped> skipped) {
    }

    private record Plan(ConfImportPlan view, List<BridgeForm> newBridges, Options options, String text) {
    }

    private static String key(String line) {
        return line.split("\\s+", 2)[0];
    }

    private static String reason(String line) {
        String key = key(line);
        String reason = LEFT_OUT.get(key);
        if (reason == null && LEFT_OUT_PREFIXES.stream().anyMatch(key::startsWith)) {
            return "plugin";
        }
        // what the file of the UI must not contain at all (Directives), e.g. http_dir or tls_engine of a listener
        return reason == null && Directives.managed(line) != null ? "system" : reason;
    }

    private static Integer listenerPort(String line) {
        String[] parts = line.split("\\s+");
        try {
            return parts.length > 1 ? Integer.valueOf(parts[1]) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A listener that stays out takes its own options along, not the global ones that happen to follow it. */
    private Options options(List<String> lines, String managed) {
        Set<Integer> ports = new HashSet<>();
        ManagedConfig.parse(managed).listeners().stream().map(ListenerForm::port).forEach(ports::add);
        Set<Integer> inUse = agent.mainPorts();
        Set<String> global = new HashSet<>(ManagedConfig.globalOptions(managed));
        Options options = new Options(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        String listenerReason = null;
        for (String line : lines) {
            String key = key(line);
            boolean listener = key.equals("listener");
            if (listener) {
                Integer port = listenerPort(line);
                listenerReason = port != null && inUse.contains(port) ? "listener_in_use" : port != null && !ports.add(port) ? "listener_exists" : null;
            }
            String reason = reason(line);
            boolean ofListener = listener || LISTENER_OPTIONS.contains(key);
            if (ofListener && listenerReason != null) {
                options.skipped().add(new ConfSkipped(line, listenerReason));
            } else if (reason != null) {
                options.skipped().add(new ConfSkipped(line, reason));
            } else if (ofListener) {
                options.listeners().add(line);
            } else if (!REPEATABLE.contains(key) && !global.add(key) || options.global().contains(line) || managed.lines().anyMatch(l -> l.strip().equals(line))) {
                options.skipped().add(new ConfSkipped(line, "already_set"));
            } else {
                options.global().add(line);
            }
        }
        return options;
    }

    private static boolean complete(BridgeForm bridge) {
        return bridge.name().matches("[^/\\s]+") && bridge.address() != null && !bridge.topics().isEmpty()
                // a disabled bridge keeps every line of its section, also ones that are no options of a bridge
                && Directives.refusal(BridgeFile.render(List.of(bridge)), true) == null;
    }

    private Plan plan(String text) {
        BridgeFile.Parts parts = BridgeFile.split(text);
        List<BridgeForm> all = new ArrayList<>(bridges.list());
        Set<String> names = new HashSet<>();
        all.stream().map(BridgeForm::name).forEach(names::add);
        List<BridgeForm> added = new ArrayList<>();
        List<ConfImportBridge> views = new ArrayList<>();
        for (BridgeForm bridge : parts.bridges()) {
            String status;
            if (!complete(bridge)) {
                status = "INVALID";
            } else if (!names.add(bridge.name())) {
                status = "EXISTS";
            } else {
                all.add(bridge);
                if (BridgeFile.loop(all) != null) {
                    all.removeLast();
                    status = "LOOP";
                } else {
                    added.add(bridge);
                    status = "NEW";
                }
            }
            views.add(new ConfImportBridge(bridge.name(), bridge.address(), bridge.topics().size(), status, complete(bridge) ? BridgeFile.warnings(bridge) : List.of()));
        }
        String managed = config.read(agent.managedFile());
        Options options = options(parts.others(), managed);
        List<ConfSkipped> skipped = new ArrayList<>(options.skipped());
        // only the name: the value of a password line has no place in a preview
        parts.orphans().forEach(key -> skipped.add(new ConfSkipped(key, "orphan")));
        String newText = options.global().isEmpty() && options.listeners().isEmpty() ? managed : ManagedConfig.append(managed, options.global(), options.listeners());
        boolean restart = config.check(newText).restartRequired();
        List<String> lines = new ArrayList<>(options.global());
        lines.addAll(options.listeners());
        return new Plan(new ConfImportPlan(views, lines, skipped, restart), added, options, newText);
    }

    public ConfImportPlan preview(String text) {
        return plan(text).view();
    }

    /** Bridges first: they only need a reload, and do not depend on the options. */
    public ConfImportResult run(String text) {
        Plan plan = plan(text);
        bridges.add(plan.newBridges());
        boolean restarted = false;
        if (!plan.view().options().isEmpty()) {
            restarted = config.apply(plan.text()).restarted();
        }
        return new ConfImportResult(plan.newBridges().size(), plan.view().options().size(), restarted);
    }
}
