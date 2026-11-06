package com.dfc.mosquittoui.notify;

import com.dfc.mosquittoui.backup.BackupSettingsService;
import com.dfc.mosquittoui.broker.BrokerLogLine;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Tells the recipients about lines in the log of the broker: each line of the level error, and many rejected logins in a short
 * time. At most one mail of each kind in {@code log-mail-every}; what came in between is in the next one. Only lines that come
 * while this application is connected count, as the broker publishes them (log_dest topic) and only of the levels in log_type.
 */
@ApplicationScoped
public class LogWatch {

    /** As mosquitto 2.1 writes it (src/loop.c); a wrong password is "not authorised" as well. Without an id it says so as well. */
    static final Pattern REJECTED = Pattern.compile("^Client (\\S+) (?:\\[([^\\]]*)\\] )?disconnected: (not authori[sz]ed|bad username or password|banned)");
    /** Lines kept for one mail, more are only counted */
    static final int MAX_LINES = 1000;
    /** Lines in a mail */
    static final int MAIL_LINES = 20;

    record Seen(Instant time, String text) {
    }

    record Rejection(Instant time, String client, String address, String reason) {
    }

    @Inject
    MailService mail;

    @Inject
    BackupSettingsService settings;

    @ConfigProperty(name = "mosquitto-ui.notifications.rejected-logins")
    int rejectedLogins;

    @ConfigProperty(name = "mosquitto-ui.notifications.rejected-window")
    Duration rejectedWindow;

    @ConfigProperty(name = "mosquitto-ui.notifications.log-mail-every")
    Duration mailEvery;

    private final List<Seen> errors = new ArrayList<>();
    private int moreErrors;
    private final Deque<Rejection> rejections = new ArrayDeque<>();
    private Instant errorsMailed;
    private Instant rejectionsMailed;

    /** On the event loop of the connection to the broker: only collects */
    void onLine(@Observes BrokerLogLine line) {
        synchronized (this) {
            if ("error".equals(line.level())) {
                if (errors.size() < MAX_LINES) {
                    errors.add(new Seen(line.time(), line.text()));
                } else {
                    moreErrors++;
                }
            }
            Matcher rejected = REJECTED.matcher(line.text());
            if (rejected.find()) {
                rejections.addLast(new Rejection(line.time(), rejected.group(1), rejected.group(2), rejected.group(3)));
                while (rejections.size() > MAX_LINES) {
                    rejections.removeFirst();
                }
            }
        }
    }

    @Scheduled(every = "{mosquitto-ui.notifications.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void look() {
        check(Instant.now());
    }

    /** Package private for tests, which have their own idea of the time. */
    void check(Instant now) {
        String errorText = null;
        int errorCount = 0;
        String rejectedText = null;
        int rejectedCount = 0;
        synchronized (this) {
            if (!errors.isEmpty() && due(errorsMailed, now)) {
                errorCount = errors.size() + moreErrors;
                errorText = errorText(errors, moreErrors);
                errors.clear();
                moreErrors = 0;
                errorsMailed = now;
            }
            while (!rejections.isEmpty() && !rejections.peekFirst().time().isAfter(now.minus(rejectedWindow))) {
                rejections.removeFirst();
            }
            if (rejections.size() >= rejectedLogins && due(rejectionsMailed, now)) {
                rejectedCount = rejections.size();
                rejectedText = rejectedText(List.copyOf(rejections));
                rejections.clear();
                rejectionsMailed = now;
            }
        }
        // the mail outside of the lock, the connection to the broker must not wait for it
        if (errorText != null) {
            mail.notifyFailure(Notice.LOG_ERROR, "mosquitto-ui: " + (errorCount == 1 ? "an error" : errorCount + " errors") + " in the log of the broker", errorText);
        }
        if (rejectedText != null) {
            mail.notifyFailure(Notice.LOGIN_REJECTED, "mosquitto-ui: " + rejectedCount + " rejected logins", rejectedText);
        }
    }

    private boolean due(Instant mailed, Instant now) {
        return mailed == null || !mailed.plus(mailEvery).isAfter(now);
    }

    private DateTimeFormatter time() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(settings.timeZone());
    }

    private String errorText(List<Seen> lines, int more) {
        DateTimeFormatter time = time();
        StringBuilder text = new StringBuilder("The broker logged ").append(lines.size() + more == 1 ? "this error" : (lines.size() + more) + " errors")
                .append(":\n\n");
        int from = Math.max(0, lines.size() - MAIL_LINES);
        if (from > 0 || more > 0) {
            text.append("(the last ").append(lines.size() - from).append(")\n");
        }
        for (Seen line : lines.subList(from, lines.size())) {
            text.append(time.format(line.time())).append("  ").append(line.text()).append('\n');
        }
        return text.append(footer()).toString();
    }

    private String rejectedText(List<Rejection> rejected) {
        DateTimeFormatter time = time();
        Map<String, Integer> byClient = new LinkedHashMap<>();
        Map<String, Integer> byAddress = new LinkedHashMap<>();
        for (Rejection r : rejected) {
            byClient.merge(r.client() + " (" + r.reason() + ")", 1, Integer::sum);
            if (r.address() != null) {
                byAddress.merge(r.address().replaceFirst(":\\d+$", ""), 1, Integer::sum);
            }
        }
        StringBuilder text = new StringBuilder("The broker rejected ").append(rejected.size()).append(" logins between ")
                .append(time.format(rejected.getFirst().time())).append(" and ").append(time.format(rejected.getLast().time())).append(".\n\nBy client:\n");
        top(text, byClient);
        if (!byAddress.isEmpty()) {
            text.append("\nBy address:\n");
            top(text, byAddress);
        }
        return text.append("\nA wrong password, a locked client or one this broker does not know. The page Logs has the lines.\n")
                .append(footer()).toString();
    }

    private static void top(StringBuilder text, Map<String, Integer> counts) {
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(MAIL_LINES)
                .forEach(entry -> text.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n'));
        if (counts.size() > MAIL_LINES) {
            text.append("- and ").append(counts.size() - MAIL_LINES).append(" more\n");
        }
    }

    private String footer() {
        return "\nAt most one mail of this kind every " + mailEvery.toMinutes() + " minutes. This message was sent automatically.";
    }
}
