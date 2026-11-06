package com.dfc.mosquittoui.config;

import com.dfc.mosquittoui.api.Dtos.BridgeForm;
import com.dfc.mosquittoui.api.Dtos.BridgeView;
import com.dfc.mosquittoui.api.Dtos.BridgesView;
import com.dfc.mosquittoui.broker.DynsecClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The bridges, in a file of their own in conf.d. A reload starts new bridges and stops removed ones, but leaves a bridge that
 * stays as it was started, whatever changed in its section; changing one takes a restart.
 */
@ApplicationScoped
public class BridgeService {

    @Inject
    BrokerAgent agent;

    @Inject
    ConfigService config;

    @Inject
    DynsecClient dynsec;

    @Inject
    VictronStore victron;

    public List<BridgeForm> list() {
        return BridgeFile.parse(config.read(agent.bridgesFile()));
    }

    private static BridgeForm withoutPassword(BridgeForm b) {
        return new BridgeForm(b.name(), b.address(), b.remoteClientId(), b.username(), null, b.tryPrivate(), b.cleanSession(), b.startType(), b.keepalive(),
                b.restartTimeout(), b.protocolVersion(), b.topics(), b.extraOptions(), b.disabled());
    }

    private static BridgeForm withDisabled(BridgeForm b, boolean disabled) {
        return new BridgeForm(b.name(), b.address(), b.remoteClientId(), b.username(), b.password(), b.tryPrivate(), b.cleanSession(), b.startType(), b.keepalive(),
                b.restartTimeout(), b.protocolVersion(), b.topics(), b.extraOptions(), disabled);
    }

    private BridgeView view(BridgeForm bridge) {
        // without its notification nothing can be said, which is not the same as being disconnected
        Boolean connected = bridge.disabled() || !BridgeFile.reportsState(bridge) ? null : dynsec.bridgeConnected(bridge.name(), bridge.remoteClientId());
        Instant stateSince = connected == null ? null : dynsec.bridgeStateSince(bridge.name(), bridge.remoteClientId());
        String portalId = victron.portals().get(bridge.name());
        String pinned = portalId == null ? null : VictronStore.fingerprint(victron.pinned(bridge));
        return new BridgeView(withoutPassword(bridge), bridge.password() != null, connected, stateSince, BridgeFile.warnings(bridge), portalId, pinned,
                pinned == null ? null : victron.changedCertificate(bridge.name()));
    }

    public BridgesView view() {
        return new BridgesView(agent.isAvailable(), list().stream().map(this::view).toList());
    }

    private static BridgeException notFound(String name) {
        return new BridgeException("not_found", "No bridge " + name);
    }

    /** As it will be read back, which leaves no difference between a form and the file other than a real change. */
    private static BridgeForm normal(BridgeForm bridge) {
        // what the form does not know has to be an option of a bridge, also while it is disabled; the file is checked again on writing
        for (String line : bridge.extraOptions()) {
            String key = line.strip().split("\\s+", 2)[0];
            if (!key.isEmpty() && !BridgeFile.isBridgeOption(key)) {
                throw new BridgeException("validation", key + " is no option of a bridge");
            }
        }
        List<BridgeForm> parsed = BridgeFile.parse(BridgeFile.render(List.of(bridge)));
        if (parsed.size() != 1) {
            throw new BridgeException("validation", "One bridge at a time, extra options cannot contain connection");
        }
        return parsed.getFirst();
    }

    private static void refuseLoop(List<BridgeForm> bridges) {
        String loop = BridgeFile.loop(bridges);
        if (loop != null) {
            throw new BridgeException("bridge_loop", loop + " send messages in an endless circle");
        }
    }

    private void write(List<BridgeForm> bridges, boolean restart) {
        refuseLoop(bridges);
        config.install(agent.bridgesFile(), BridgeFile.render(bridges), restart);
    }

    /** The bridges with this new one, if it can be added. */
    private List<BridgeForm> with(BridgeForm form) {
        List<BridgeForm> bridges = new ArrayList<>(list());
        if (bridges.stream().anyMatch(bridge -> bridge.name().equals(form.name()))) {
            throw new BridgeException("already_exists", "Bridge " + form.name() + " already exists");
        }
        bridges.add(normal(form));
        return bridges;
    }

    /** Whether it can be added, without doing so. */
    public void check(BridgeForm form) {
        refuseLoop(with(form));
    }

    public synchronized void create(BridgeForm form) {
        write(with(form), false);
    }

    /** Without a password in the request, the stored one stays. */
    public synchronized void update(String name, BridgeForm form, boolean confirmRestart) {
        List<BridgeForm> bridges = new ArrayList<>(list());
        BridgeForm old = bridges.stream().filter(bridge -> bridge.name().equals(name)).findFirst().orElseThrow(() -> notFound(name));
        BridgeForm changed = normal(withDisabled(form.password() == null || form.password().isBlank() ? copyPassword(form, old) : form, old.disabled()));
        if (changed.equals(old)) {
            return;
        }
        boolean renamed = !changed.name().equals(name);
        if (renamed && bridges.stream().anyMatch(bridge -> bridge.name().equals(changed.name()))) {
            throw new BridgeException("already_exists", "Bridge " + changed.name() + " already exists");
        }
        // under another name it is a new bridge for the broker, and a disabled one it does not know at all
        boolean restart = !renamed && !old.disabled();
        if (restart && !confirmRestart) {
            throw new BridgeException("restart_required", "Changing a bridge takes a restart of the broker");
        }
        bridges.set(bridges.indexOf(old), changed);
        write(bridges, restart);
        if (renamed) {
            victron.rename(name, changed.name());
        }
    }

    private static BridgeForm copyPassword(BridgeForm b, BridgeForm old) {
        return new BridgeForm(b.name(), b.address(), b.remoteClientId(), b.username(), old.password(), b.tryPrivate(), b.cleanSession(), b.startType(), b.keepalive(),
                b.restartTimeout(), b.protocolVersion(), b.topics(), b.extraOptions(), b.disabled());
    }

    /** For the broker this removes the bridge or adds a new one, which a reload does. */
    public synchronized void setEnabled(String name, boolean enabled) {
        List<BridgeForm> bridges = new ArrayList<>(list());
        BridgeForm old = bridges.stream().filter(bridge -> bridge.name().equals(name)).findFirst().orElseThrow(() -> notFound(name));
        if (old.disabled() == enabled) {
            bridges.set(bridges.indexOf(old), withDisabled(old, !enabled));
            write(bridges, false);
        }
    }

    public synchronized void delete(String name) {
        List<BridgeForm> bridges = new ArrayList<>(list());
        if (!bridges.removeIf(bridge -> bridge.name().equals(name))) {
            throw notFound(name);
        }
        write(bridges, false);
        victron.remove(name);
    }

    /** All of them, with what the UI keeps for GX devices. Tokens stay on the devices, they can only be removed there. */
    public synchronized void reset() {
        if (!list().isEmpty()) {
            write(List.of(), false);
        }
        victron.clear();
    }

    /** The file of a backup. The restart brings bridges that stay under their name to their settings, see the class. */
    public synchronized void restore(String text) {
        if (!text.equals(config.bridgesText())) {
            config.install(agent.bridgesFile(), text, true);
        }
    }

    /** New ones, in one reload. */
    public synchronized void add(List<BridgeForm> added) {
        if (!added.isEmpty()) {
            List<BridgeForm> bridges = new ArrayList<>(list());
            added.stream().map(BridgeService::normal).forEach(bridges::add);
            write(bridges, false);
        }
    }
}
