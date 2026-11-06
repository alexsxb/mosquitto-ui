package com.dfc.mosquittoui.logs;

import com.dfc.mosquittoui.broker.BrokerLogLine;
import com.dfc.mosquittoui.broker.DynsecClient;
import com.dfc.mosquittoui.security.PrivateFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.BackPressureStrategy;
import io.smallrye.mutiny.subscription.MultiEmitter;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * The latest lines of the log of the broker, for the page Logs. The broker publishes them with log_dest topic, the connection of
 * this application gets them (DynsecClient). They are kept in memory and in a file in the state directory, so that they are
 * still there after a restart of this application; the file is cut in two when it gets large, the older half is dropped.
 */
@ApplicationScoped
public class BrokerLogService {

    private static final Logger LOG = Logger.getLogger(BrokerLogService.class);
    static final String FILE = "broker-log.jsonl";
    static final String OLDER = "broker-log.1.jsonl";

    /** A line as it is kept and shown; id grows with each line within a run of this application. */
    public record LogLine(long id, Instant time, String level, String text) {
    }

    /**
     * The lines after the one asked for. run changes with each start of this application, as the ids begin anew: a page that
     * knows another one loads all again.
     */
    public record LogView(String run, List<LogLine> lines, long last, boolean connected,
            @Schema(description = "false: the broker user of the UI may not subscribe to $SYS/broker/log/#") boolean readable, int keep) {
    }

    /**
     * What the stream sends: first all lines kept with reset, then the new ones as they come, a few times a second at most, and
     * an empty batch now and then, so that the page sees the state of the connection and no proxy closes the stream.
     */
    public record LogBatch(boolean reset, List<LogLine> lines, boolean connected,
            @Schema(description = "false: the broker user of the UI may not subscribe to $SYS/broker/log/#") boolean readable, int keep) {
    }

    record Stored(Instant time, String level, String text) {
    }

    @Inject
    ObjectMapper mapper;

    @Inject
    DynsecClient dynsec;

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    @ConfigProperty(name = "mosquitto-ui.broker-log.keep", defaultValue = "10000")
    int keep;

    @ConfigProperty(name = "mosquitto-ui.broker-log.file-bytes", defaultValue = "4000000")
    long fileBytes;

    private static final long SEND_MS = 250;
    /** An empty batch after this many quiet sends: 15 seconds */
    private static final int HEARTBEAT_TICKS = 60;
    private static final int SNAPSHOT_CHUNK = 1000;

    private final String run = UUID.randomUUID().toString();
    private final Deque<LogLine> lines = new ArrayDeque<>();
    private final List<LogLine> unsaved = new ArrayList<>();
    /** Arrived since the last batch to the viewers */
    private final List<LogLine> unsent = new ArrayList<>();
    private final Set<MultiEmitter<? super LogBatch>> viewers = ConcurrentHashMap.newKeySet();
    private int quietTicks;
    private long last;

    void onStart(@Observes StartupEvent event) {
        List<Stored> stored = new ArrayList<>();
        read(stateDir.resolve(OLDER), stored);
        read(stateDir.resolve(FILE), stored);
        synchronized (this) {
            for (Stored line : stored.subList(Math.max(0, stored.size() - keep), stored.size())) {
                add(new LogLine(++last, line.time(), line.level(), line.text()));
            }
        }
        vertx.setPeriodic(SEND_MS, id -> send());
    }

    void onStop(@Observes ShutdownEvent event) {
        save();
    }

    private void read(Path file, List<Stored> into) {
        try {
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    try {
                        into.add(mapper.readValue(line, Stored.class));
                    } catch (IOException e) {
                        // a line cut off by a crash: the others stay
                        LOG.debugf("Unreadable line in %s", file);
                    }
                }
            }
        } catch (NoSuchFileException e) {
            // nothing kept yet
        } catch (IOException e) {
            LOG.warnf("Broker log in %s not read: %s", file, e.getMessage());
        }
    }

    /** From the connection to the broker, on its event loop: only kept, written later. */
    void onLine(@Observes BrokerLogLine line) {
        synchronized (this) {
            LogLine kept = new LogLine(++last, line.time(), line.level(), line.text());
            add(kept);
            unsaved.add(kept);
            if (!viewers.isEmpty()) {
                unsent.add(kept);
            }
        }
    }

    private void add(LogLine line) {
        lines.addLast(line);
        while (lines.size() > keep) {
            lines.removeFirst();
        }
    }

    /** All lines kept, then the new ones; emitted under the lock, so that no line comes twice or not at all. */
    public Multi<LogBatch> stream() {
        return Multi.createFrom().<LogBatch>emitter(emitter -> {
            synchronized (this) {
                List<LogLine> all = new ArrayList<>(lines);
                // one batch with reset even without lines: the page knows it has everything
                for (int i = 0; i == 0 || i < all.size(); i += SNAPSHOT_CHUNK) {
                    emitter.emit(batch(i == 0, all.subList(i, Math.min(i + SNAPSHOT_CHUNK, all.size()))));
                }
                viewers.add(emitter);
                emitter.onTermination(() -> viewers.remove(emitter));
            }
        }, BackPressureStrategy.BUFFER);
    }

    private LogBatch batch(boolean reset, List<LogLine> some) {
        return new LogBatch(reset, List.copyOf(some), dynsec.isConnected(), dynsec.logReadable(), keep);
    }

    private synchronized void send() {
        if (viewers.isEmpty()) {
            unsent.clear();
            return;
        }
        if (unsent.isEmpty() && ++quietTicks < HEARTBEAT_TICKS) {
            return;
        }
        quietTicks = 0;
        LogBatch batch = batch(false, unsent);
        unsent.clear();
        viewers.forEach(viewer -> viewer.emit(batch));
    }

    public synchronized LogView view(String knownRun, long after) {
        long from = run.equals(knownRun) ? after : 0;
        List<LogLine> newer = new ArrayList<>();
        // the newest are at the end; walking back stops at the first one known
        var iterator = lines.descendingIterator();
        while (iterator.hasNext()) {
            LogLine line = iterator.next();
            if (line.id() <= from) {
                break;
            }
            newer.add(line);
        }
        return new LogView(run, newer.reversed(), last, dynsec.isConnected(), dynsec.logReadable(), keep);
    }

    @Scheduled(every = "2s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void save() {
        List<LogLine> pending;
        synchronized (this) {
            if (unsaved.isEmpty()) {
                return;
            }
            pending = List.copyOf(unsaved);
            unsaved.clear();
        }
        try {
            Path file = stateDir.resolve(FILE);
            if (!Files.exists(file)) {
                PrivateFiles.write(file, "");
            } else if (Files.size(file) > fileBytes / 2) {
                // each half holds about the half; the older one goes when the next is full
                Files.move(file, stateDir.resolve(OLDER), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                PrivateFiles.write(file, "");
            }
            StringBuilder text = new StringBuilder();
            for (LogLine line : pending) {
                text.append(mapper.writeValueAsString(new Stored(line.time(), line.level(), line.text()))).append('\n');
            }
            Files.writeString(file, text, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warnf("Broker log not saved: %s", e.getMessage());
        }
    }
}
