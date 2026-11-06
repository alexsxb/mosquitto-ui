package com.dfc.mosquittoui.config;

import com.dfc.mosquittoui.api.Dtos.ListenerForm;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Requests to docker/mosquitto/agent.sh, see there. The exchange is one file, so there is one request at a time. */
@ApplicationScoped
public class BrokerAgent {

    private static final Duration ALIVE = Duration.ofSeconds(10);
    private static final long POLL_MS = 100;

    @ConfigProperty(name = "mosquitto-ui.shared-dir")
    Path sharedDir;

    @ConfigProperty(name = "mosquitto-ui.agent-timeout")
    Duration timeout;

    private Path control() {
        return sharedDir.resolve("control");
    }

    public Path managedFile() {
        return sharedDir.resolve("conf.d/mosquitto-ui.conf");
    }

    /** The bridges, apart from the text of the managed file, as they contain passwords. */
    public Path bridgesFile() {
        return sharedDir.resolve("conf.d/mosquitto-ui-bridges.conf");
    }

    /** The copy of the dynamic security file between dynsec-begin and dynsec-commit. */
    public Path dynsecCopy() {
        return control().resolve("dynamic-security.json");
    }

    /** For files that the agent takes over with the next call. */
    public Path controlFile(String name) {
        return control().resolve(name);
    }

    public Path backups() {
        return sharedDir.resolve("backups");
    }

    /** The listeners in the main file of the broker. */
    public List<ListenerForm> mainListeners() {
        try {
            return ManagedConfig.parseMainListeners(Files.readString(control().resolve("main-listeners")));
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The ports of the listeners in the main file of the broker. */
    public Set<Integer> mainPorts() {
        return mainListeners().stream().map(ListenerForm::port).collect(Collectors.toSet());
    }

    /** The agent marks each of its rounds. */
    public boolean isAvailable() {
        try {
            Instant alive = Files.getLastModifiedTime(control().resolve("alive")).toInstant();
            return alive.isAfter(Instant.now().minus(ALIVE));
        } catch (IOException e) {
            return false;
        }
    }

    public synchronized void call(String operation, String... arguments) {
        if (!isAvailable()) {
            throw new AgentException("The agent in the broker container does not answer", true);
        }
        String id = UUID.randomUUID().toString();
        try {
            Path request = control().resolve("request.tmp");
            Files.writeString(request, id + " " + operation + " " + String.join(" ", arguments) + "\n");
            Files.move(request, control().resolve("request"), StandardCopyOption.ATOMIC_MOVE);
            String answer = awaitResponse(id);
            int lineEnd = answer.indexOf('\n');
            if (!answer.substring(0, lineEnd).endsWith(" 0")) {
                throw new AgentException(reason(answer.substring(lineEnd + 1)), false);
            }
        } catch (IOException e) {
            throw new AgentException(e.getMessage(), true);
        }
    }

    /** The lines of the broker's output that tell what is wrong, without their timestamps. */
    private static String reason(String output) {
        String errors = output.lines().map(line -> line.replaceFirst("^\\d+: ", "")).filter(line -> line.startsWith("Error"))
                .collect(Collectors.joining("\n"));
        return errors.isEmpty() ? output.strip() : errors;
    }

    private String awaitResponse(String id) throws IOException {
        Instant end = Instant.now().plus(timeout);
        Path response = control().resolve("response");
        while (Instant.now().isBefore(end)) {
            try {
                String answer = Files.readString(response);
                if (answer.startsWith(id + " ")) {
                    Files.delete(response);
                    return answer;
                }
            } catch (NoSuchFileException e) {
                // not yet
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AgentException("No answer of the agent within " + timeout, true);
    }
}
