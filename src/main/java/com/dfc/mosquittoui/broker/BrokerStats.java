package com.dfc.mosquittoui.broker;

import com.dfc.mosquittoui.api.Dtos.BrokerStatsView;
import com.dfc.mosquittoui.api.Dtos.StatsSample;
import com.dfc.mosquittoui.security.PrivateFiles;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * What the broker says about itself under $SYS, and a history of it. The broker only knows the present, so this writes it down
 * regularly: the time this application is down is missing from the history. The load is the average of the broker over the last
 * minutes, per minute; a history of it, unlike one of the counters, does not have to deal with a restart of the broker.
 */
@ApplicationScoped
public class BrokerStats {

    static final List<String> SYS_VALUES = List.of("version", "uptime", "clients/connected", "subscriptions/count", "retained messages/count", "heap/current",
            "load/publish/received/1min", "load/publish/sent/1min", "load/bytes/received/1min", "load/bytes/sent/1min",
            "load/publish/received/5min", "load/publish/sent/5min", "load/bytes/received/5min", "load/bytes/sent/5min");

    private static final Logger LOG = Logger.getLogger(BrokerStats.class);
    private static final String FILE = "broker-stats.json";
    private static final double SECONDS_PER_MINUTE = 60;
    private static final double HUNDREDTHS = 100;

    @Inject
    DynsecClient dynsec;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    @ConfigProperty(name = "mosquitto-ui.broker-stats.keep")
    Duration keep;

    private List<StatsSample> samples;

    public BrokerStatsView current() {
        return new BrokerStatsView(version(), firstWord("uptime"), firstWord("clients/connected"), firstWord("subscriptions/count"), firstWord("retained messages/count"),
                firstWord("heap/current"), rate("load/publish/received/1min"), rate("load/publish/sent/1min"), rate("load/bytes/received/1min"), rate("load/bytes/sent/1min"));
    }

    /** "mosquitto version 2.1.2" */
    private String version() {
        String value = dynsec.sysValue("version");
        return value == null ? null : value.substring(value.lastIndexOf(' ') + 1);
    }

    /** The number of a value such as "1234 seconds". */
    private Long firstWord(String name) {
        String value = dynsec.sysValue(name);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.split(" ", 2)[0]);
        } catch (NumberFormatException e) {
            LOG.debugf("Unreadable value of %s: %s", name, value);
            return null;
        }
    }

    /** Per second instead of per minute. */
    private Double rate(String name) {
        String value = dynsec.sysValue(name);
        if (value == null) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(value) / SECONDS_PER_MINUTE * HUNDREDTHS) / HUNDREDTHS;
        } catch (NumberFormatException e) {
            LOG.debugf("Unreadable value of %s: %s", name, value);
            return null;
        }
    }

    private List<StatsSample> samples() {
        if (samples == null) {
            samples = new ArrayList<>();
            Path file = stateDir.resolve(FILE);
            try {
                if (Files.exists(file)) {
                    samples.addAll(mapper.readValue(Files.readString(file), new TypeReference<List<StatsSample>>() {
                    }));
                }
            } catch (IOException e) {
                LOG.warnf("History of the broker not read: %s", e.getMessage());
            }
        }
        return samples;
    }

    /** The samples of this time up to now, the oldest first. */
    public synchronized List<StatsSample> history(Duration last) {
        Instant from = Instant.now().minus(last);
        return samples().stream().filter(sample -> sample.time().isAfter(from)).toList();
    }

    @Scheduled(every = "{mosquitto-ui.broker-stats.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sample() {
        Long connections = firstWord("clients/connected");
        Double messagesIn = rate("load/publish/received/5min");
        Double messagesOut = rate("load/publish/sent/5min");
        Double bytesIn = rate("load/bytes/received/5min");
        Double bytesOut = rate("load/bytes/sent/5min");
        if (connections != null && messagesIn != null && messagesOut != null && bytesIn != null && bytesOut != null) {
            add(new StatsSample(Instant.now(), connections, messagesIn, messagesOut, bytesIn, bytesOut, firstWord("heap/current")));
        }
    }

    private synchronized void add(StatsSample sample) {
        List<StatsSample> all = samples();
        Instant from = sample.time().minus(keep);
        all.removeIf(old -> old.time().isBefore(from));
        all.add(sample);
        try {
            PrivateFiles.write(stateDir.resolve(FILE), mapper.writeValueAsString(all));
        } catch (IOException e) {
            LOG.warnf("History of the broker not saved: %s", e.getMessage());
        }
    }
}
