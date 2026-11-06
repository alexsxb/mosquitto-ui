package com.dfc.mosquittoui.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class NextRunTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** A Sunday. */
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 1, 12, 0, 0, 0, BERLIN);

    private static BackupJob job(BackupJob.Schedule schedule, String time, Integer weekday, Integer monthDay) {
        BackupJob job = new BackupJob();
        job.schedule = schedule;
        job.time = time;
        job.weekday = weekday;
        job.monthDay = monthDay;
        return job;
    }

    private static ZonedDateTime at(int month, int day, int hour) {
        return ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, BERLIN);
    }

    @Test
    void daily() {
        assertEquals(at(11, 1, 18), BackupRunService.nextRun(job(BackupJob.Schedule.DAILY, "18:00", null, null), NOW));
        assertEquals(at(11, 2, 3), BackupRunService.nextRun(job(BackupJob.Schedule.DAILY, "03:00", null, null), NOW));
    }

    @Test
    void weeklyAndMonthly() {
        assertEquals(at(11, 4, 3), BackupRunService.nextRun(job(BackupJob.Schedule.WEEKLY, "03:00", 3, null), NOW));
        assertEquals(at(12, 1, 3), BackupRunService.nextRun(job(BackupJob.Schedule.MONTHLY, "03:00", null, 1), NOW));
    }

    @Test
    void onlyByHand() {
        assertNull(BackupRunService.nextRun(job(BackupJob.Schedule.MANUAL, null, null, null), NOW));
    }
}
