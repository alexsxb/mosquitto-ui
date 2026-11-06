package com.dfc.mosquittoui.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dfc.mosquittoui.backup.BackupSettingsService;
import com.dfc.mosquittoui.broker.BrokerLogLine;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Which lines of the log make a mail, and how often. */
class LogWatchTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    record Mail(Notice notice, String subject, String text) {
    }

    private final List<Mail> mails = new ArrayList<>();
    private LogWatch watch;

    @BeforeEach
    void start() {
        watch = new LogWatch();
        watch.mail = new MailService() {
            @Override
            public void notifyFailure(Notice notice, String subject, String text) {
                mails.add(new Mail(notice, subject, text));
            }
        };
        watch.settings = new BackupSettingsService() {
            @Override
            public ZoneId timeZone() {
                return ZoneId.of("UTC");
            }
        };
        watch.rejectedLogins = 3;
        watch.rejectedWindow = Duration.ofMinutes(10);
        watch.mailEvery = Duration.ofHours(1);
    }

    private void line(Instant time, String level, String text) {
        watch.onLine(new BrokerLogLine(time, level, text));
    }

    private void rejected(Instant time, String client) {
        line(time, "notice", "Client " + client + " [10.0.0.5:51234] disconnected: not authorised.");
    }

    @Test
    void errorsMakeOneMailAnHour() {
        line(NOW, "error", "Error: Unable to open log file");
        line(NOW, "warning", "Warning: something else");
        watch.check(NOW);
        assertEquals(1, mails.size());
        assertEquals(Notice.LOG_ERROR, mails.getFirst().notice());
        assertEquals("mosquitto-ui: an error in the log of the broker", mails.getFirst().subject());
        assertTrue(mails.getFirst().text().contains("2026-11-04 12:00:00 UTC  Error: Unable to open log file"));
        assertTrue(!mails.getFirst().text().contains("Warning"));

        // within the hour they wait, then all come in one mail
        line(NOW.plusSeconds(60), "error", "Error: one");
        line(NOW.plusSeconds(120), "error", "Error: two");
        watch.check(NOW.plusSeconds(180));
        assertEquals(1, mails.size());
        watch.check(NOW.plus(Duration.ofHours(1)));
        assertEquals(2, mails.size());
        assertEquals("mosquitto-ui: 2 errors in the log of the broker", mails.get(1).subject());
        assertTrue(mails.get(1).text().contains("Error: one") && mails.get(1).text().contains("Error: two"));

        watch.check(NOW.plus(Duration.ofHours(3)));
        assertEquals(2, mails.size());
    }

    @Test
    void manyRejectedLoginsInTheWindow() {
        rejected(NOW, "shelly1");
        rejected(NOW.plusSeconds(10), "shelly1");
        watch.check(NOW.plusSeconds(20));
        assertTrue(mails.isEmpty());

        line(NOW.plusSeconds(30), "notice", "Client plug7 [10.0.0.9:4000] disconnected: bad username or password.");
        watch.check(NOW.plusSeconds(40));
        assertEquals(1, mails.size());
        Mail mail = mails.getFirst();
        assertEquals(Notice.LOGIN_REJECTED, mail.notice());
        assertEquals("mosquitto-ui: 3 rejected logins", mail.subject());
        assertTrue(mail.text().contains("- shelly1 (not authorised): 2"), mail.text());
        assertTrue(mail.text().contains("- plug7 (bad username or password): 1"), mail.text());
        assertTrue(mail.text().contains("- 10.0.0.5: 2"), mail.text());

        // counted anew after a mail, and at most one an hour
        for (int i = 0; i < 3; i++) {
            rejected(NOW.plusSeconds(100 + i), "shelly1");
        }
        watch.check(NOW.plusSeconds(200));
        assertEquals(1, mails.size());
    }

    @Test
    void oldRejectionsLeaveTheWindow() {
        rejected(NOW, "a");
        rejected(NOW.plusSeconds(60), "b");
        rejected(NOW.plus(Duration.ofMinutes(11)), "c");
        watch.check(NOW.plus(Duration.ofMinutes(11)));
        assertTrue(mails.isEmpty());
    }

    @Test
    void otherDisconnectsAreNoRejection() {
        for (int i = 0; i < 5; i++) {
            line(NOW, "notice", "Client shelly1 [10.0.0.5:51234] disconnected: exceeded timeout.");
            line(NOW, "notice", "Client shelly1 [10.0.0.5:51234] disconnected: connection closed by client.");
        }
        watch.check(NOW);
        assertTrue(mails.isEmpty());
    }
}
