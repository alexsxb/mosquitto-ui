package com.dfc.mosquittoui.config;

import com.dfc.mosquittoui.api.Dtos.ConfigApplied;
import com.dfc.mosquittoui.api.Dtos.ConfigCheck;
import com.dfc.mosquittoui.api.Dtos.ConfigForm;
import com.dfc.mosquittoui.api.Dtos.ConfigView;
import com.dfc.mosquittoui.api.Dtos.ListenerForm;
import com.dfc.mosquittoui.broker.DynsecClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** The configuration file of the UI. The main file stays with the operator, it loads this one through include_dir. */
@ApplicationScoped
public class ConfigService {

    private static final int BACKUPS = 20;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    @Inject
    BrokerAgent agent;

    @Inject
    DynsecClient dynsec;

    /** The text of the file of the UI. */
    public String current() {
        return read(agent.managedFile());
    }

    /** The text of the file with the bridges, passwords included. */
    public String bridgesText() {
        return read(agent.bridgesFile());
    }

    String read(Path file) {
        try {
            return Files.readString(file);
        } catch (NoSuchFileException e) {
            return "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ConfigView view() {
        String text = current();
        return new ConfigView(agent.isAvailable(), text, ManagedConfig.parse(text), new TreeSet<>(agent.mainPorts()), agent.mainListeners());
    }

    public ConfigCheck check(String text) {
        Set<String> changed = ManagedConfig.changedOptions(current(), text);
        return new ConfigCheck(text, ManagedConfig.requiresRestart(changed), new ArrayList<>(changed));
    }

    public ConfigCheck checkForm(ConfigForm form) {
        if (!ManagedConfig.OPTIONS.containsAll(form.options().keySet())) {
            throw new AgentException("Unknown option in the form", false);
        }
        return check(ManagedConfig.render(current(), form));
    }

    /** Takes over a text. */
    public ConfigApplied apply(String text) {
        ConfigCheck check = check(text);
        if (check.changedOptions().isEmpty() && text.equals(current())) {
            return new ConfigApplied(false);
        }
        install(agent.managedFile(), text, check.restartRequired());
        return new ConfigApplied(check.restartRequired());
    }

    /**
     * Sets up the listener with this certificate; a port that already has it stays as it is.
     *
     * @param replacedPort a listener with this certificate that moves to the new port, or null
     */
    public void ensureTlsListener(int port, String certfile, String keyfile, Integer replacedPort) {
        if (agent.mainPorts().contains(port)) {
            throw new AgentException("mosquitto.conf has a listener on this port already", false);
        }
        String text = current();
        ConfigForm form = ManagedConfig.parse(text);
        List<ListenerForm> listeners = new ArrayList<>(form.listeners());
        ListenerForm old = listeners.stream().filter(listener -> listener.port() == port).findFirst().orElse(null);
        ListenerForm moved = replacedPort == null || replacedPort == port ? null : listeners.stream()
                .filter(listener -> listener.port() == replacedPort && certfile.equals(listener.certfile())).findFirst().orElse(null);
        if (old != null && certfile.equals(old.certfile()) && keyfile.equals(old.keyfile()) && moved == null) {
            return;
        }
        listeners.remove(moved);
        ListenerForm listener = new ListenerForm(port, old == null ? null : old.bindAddress(), old == null ? "mqtt" : old.protocol(), certfile, keyfile,
                old == null ? null : old.cafile(), old == null ? null : old.maxConnections(), old == null ? null : old.proxyProtocol(),
                old == null ? null : old.proxyRequireTls());
        listeners.removeIf(l -> l.port() == port);
        listeners.add(listener);
        // A reload does not turn a listener into a TLS listener, and a new one needs the restart anyway
        install(agent.managedFile(), ManagedConfig.render(text, new ConfigForm(form.logTypes(), form.options(), listeners)), true);
    }

    /** Empties the file of the UI; the old one stays among the backups. */
    public void reset() {
        if (!current().isBlank()) {
            install(agent.managedFile(), "", check("").restartRequired());
        }
    }

    /** Removes the managed listeners that use this certificate. */
    public void removeTlsListeners(String certfile) {
        String text = current();
        ConfigForm form = ManagedConfig.parse(text);
        List<ListenerForm> listeners = form.listeners().stream().filter(listener -> !certfile.equals(listener.certfile())).toList();
        if (listeners.size() != form.listeners().size()) {
            install(agent.managedFile(), ManagedConfig.render(text, new ConfigForm(form.logTypes(), form.options(), listeners)), true);
        }
    }

    /** The port of the managed listener that uses this certificate, null without one. */
    public Integer tlsPort(String certfile) {
        return ManagedConfig.parse(current()).listeners().stream().filter(listener -> certfile.equals(listener.certfile())).map(ListenerForm::port)
                .findFirst().orElse(null);
    }

    /** Takes over a file of the UI: the agent tests it, and goes back to the old file if the broker does not accept it. */
    void install(Path file, String text, boolean restart) {
        Directives.check(text, read(file), file.equals(agent.bridgesFile()));
        try {
            backup(file);
            Files.writeString(file.resolveSibling(file.getFileName() + ".new"), text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        agent.call("apply", restart ? "restart" : "reload", file.getFileName().toString());
        if (restart) {
            dynsec.awaitConnected();
        }
    }

    /** Only for this application: the bridges contain passwords. */
    private void backup(Path file) throws IOException {
        Path backups = agent.backups();
        Files.createDirectories(backups);
        String stem = file.getFileName().toString().replaceFirst("\\.conf$", "");
        Path backup = backups.resolve(stem + "-" + LocalDateTime.now().format(STAMP) + ".conf");
        Files.createFile(backup, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(backup, read(file));
        List<Path> old;
        try (Stream<Path> files = Files.list(backups)) {
            old = files.filter(path -> path.getFileName().toString().matches(java.util.regex.Pattern.quote(stem) + "-\\d.*"))
                    .sorted(Comparator.reverseOrder()).skip(BACKUPS).toList();
        }
        for (Path path : old) {
            Files.delete(path);
        }
    }
}
