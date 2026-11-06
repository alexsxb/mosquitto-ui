package com.dfc.mosquittoui.backup;

import com.dfc.mosquittoui.notify.MailService;
import com.dfc.mosquittoui.notify.Notice;
import com.dfc.mosquittoui.backup.BackupStore.State;
import com.dfc.mosquittoui.backup.crypto.Gpg;
import com.dfc.mosquittoui.backup.crypto.PasswordEncryption;
import com.dfc.mosquittoui.backup.target.TargetStorage;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jboss.logging.Logger;

/**
 * Runs of backup jobs: started by hand or by the schedule, checked every minute so that changes take effect at once, one at a time per job. A run
 * writes the archive of the job's parts, encrypts it, uploads it to the target, deletes the target's own backups older than its retention and keeps
 * a log. Failures are mailed to the notification recipients.
 */
@ApplicationScoped
public class BackupRunService {

    /** log: only for a single run. */
    public record RunView(long id, long jobId, String jobName, Instant startedAt, @Schema(nullable = true) Instant finishedAt, @Schema(nullable = true) Boolean success,
            @Schema(nullable = true) String message, @Schema(nullable = true) Long bytes, @Schema(nullable = true) String fileName, String triggeredBy,
            @Schema(nullable = true) String log) {
    }

    /** failed: the last finished run failed. overdue: no successful run for longer than the schedule allows. */
    public record JobHealth(String job, boolean failed, @Schema(nullable = true) String message, @Schema(nullable = true) Instant lastSuccess, boolean overdue,
            @Schema(nullable = true) Instant nextRun) {
    }

    public record RunPage(List<RunView> items, long total) {
    }

    private static final Logger LOG = Logger.getLogger(BackupRunService.class);
    /** A monthly job runs on a day up to the 28th, which every month has. */
    private static final int MAX_DAYS_TO_NEXT_RUN = 31;
    /** File names of own backups: yyyyMMdd_HHmm_mosquitto-ui.zip[.enc|.gpg]. */
    static final Pattern OWN_FILE = Pattern.compile("^(\\d{8})_\\d{4}_mosquitto-ui\\.zip(\\.enc|\\.gpg)?$");
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");
    private static final String SCHEDULE = "schedule";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_MESSAGE = 500;
    private static final int MAX_PAGE = 200;
    private static final int DAILY_HOURS = 26;
    private static final int WEEKLY_HOURS = 170;
    private static final int MONTHLY_DAYS = 32;
    private static final double KB = 1024.0;

    /** State of a running job, for cancelling. */
    private static final class Running {
        final long runId;
        volatile boolean cancelled;
        volatile String cancelledBy;
        volatile AutoCloseable connection;

        Running(long runId) {
            this.runId = runId;
        }
    }

    private record Plan(String name, Set<BackupSection> sections, BackupTarget target, BackupJob.Encryption encryption, String password, List<String> keys) {
    }

    @Inject
    BackupStore store;

    @Inject
    BackupTargetService targets;

    @Inject
    BackupSettingsService settings;

    @Inject
    MailService mail;

    @Inject
    ArchiveService archives;

    private final Map<Long, Running> running = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** Runs still marked as running were cut off by a restart. */
    void onStart(@Observes StartupEvent event) {
        boolean open = store.read(state -> state.runs.stream().anyMatch(run -> run.success == null));
        if (open) {
            store.change(state -> state.runs.stream().filter(run -> run.success == null).forEach(run -> {
                run.success = false;
                run.finishedAt = Instant.now();
                run.message = "aborted: the server was restarted";
            }));
        }
    }

    public boolean running(long jobId) {
        return running.containsKey(jobId);
    }

    /** Whether any job runs, so that its data must not change underneath it. */
    public boolean anyRunning() {
        return !running.isEmpty();
    }

    /** Starts a run in the background and returns its id. */
    public long start(long jobId, String triggeredBy) {
        BackupRun run = store.update(state -> {
            BackupJob job = BackupJobService.find(state, jobId);
            BackupRun r = new BackupRun();
            r.id = state.newId();
            r.jobId = job.id;
            r.jobName = job.name;
            r.startedAt = Instant.now();
            r.triggeredBy = triggeredBy;
            state.runs.add(r);
            return r;
        });
        Running current = new Running(run.id);
        if (running.putIfAbsent(jobId, current) != null) {
            store.change(state -> state.runs.remove(run));
            throw BackupException.conflict("job_running", "The job is already running");
        }
        executor.submit(() -> {
            try {
                execute(jobId, current);
            } finally {
                running.remove(jobId);
                pruneRuns();
            }
        });
        return run.id;
    }

    public void cancel(long jobId, String user) {
        Running current = running.get(jobId);
        if (current == null) {
            throw BackupException.conflict("job_not_running", "The job is not running");
        }
        current.cancelledBy = user;
        current.cancelled = true;
        AutoCloseable connection = current.connection;
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                LOG.debug("Closing the connection of a cancelled run", e);
            }
        }
    }

    /** Every minute: starts the enabled jobs whose time has come, in the time zone of the settings. */
    @Scheduled(cron = "0 * * * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        ZonedDateTime now = ZonedDateTime.now(settings.timeZone());
        List<Long> due = store.read(state -> state.jobs.stream().filter(job -> job.enabled && due(job, now)).map(job -> job.id).toList());
        for (long id : due) {
            if (!running(id)) {
                try {
                    start(id, SCHEDULE);
                } catch (RuntimeException e) {
                    LOG.warnf("Scheduled backup job %d not started: %s", id, e.getMessage());
                }
            }
        }
    }

    static boolean due(BackupJob job, ZonedDateTime now) {
        if (job.time == null || !job.time.equals(now.format(CLOCK))) {
            return false;
        }
        return switch (job.schedule) {
            case DAILY -> true;
            case WEEKLY -> job.weekday != null && now.getDayOfWeek() == DayOfWeek.of(job.weekday);
            case MONTHLY -> job.monthDay != null && now.getDayOfMonth() == job.monthDay;
            case MANUAL -> false;
        };
    }

    /** When the job starts next, null if never, by the same rule as the check of every minute. */
    static ZonedDateTime nextRun(BackupJob job, ZonedDateTime now) {
        if (job.time == null) {
            return null;
        }
        LocalTime time = LocalTime.parse(job.time, CLOCK);
        for (int days = 0; days <= MAX_DAYS_TO_NEXT_RUN; days++) {
            ZonedDateTime candidate = now.toLocalDate().plusDays(days).atTime(time).atZone(now.getZone());
            if (candidate.isAfter(now) && due(job, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Plan plan(long jobId) {
        return store.read(state -> {
            BackupJob job = BackupJobService.find(state, jobId);
            return new Plan(job.name, EnumSet.copyOf(job.sections), BackupTargetService.find(state, job.targetId), job.encryption, job.password, List.copyOf(job.gpgKeys));
        });
    }

    private void execute(long jobId, Running current) {
        Path work = null;
        String targetName = "?";
        try {
            Plan plan = plan(jobId);
            targetName = plan.target().name;
            ZoneId zone = settings.timeZone();
            log(current, "Backup of " + plan.sections() + " to " + plan.target().name);

            byte[] zip = archives.createZip(plan.sections(), "backup job " + plan.name());
            checkCancelled(current);
            archives.check(zip);
            log(current, "Archive written and read back: " + size(zip.length));

            work = Files.createTempDirectory("mosquitto-ui-backup");
            String name = ZonedDateTime.now(zone).format(STAMP) + "_mosquitto-ui.zip" + switch (plan.encryption()) {
                case NONE -> "";
                case PASSWORD -> ".enc";
                case GPG -> ".gpg";
            };
            Path file = work.resolve(name);
            try (OutputStream out = Files.newOutputStream(file)) {
                switch (plan.encryption()) {
                    case NONE -> out.write(zip);
                    case PASSWORD -> PasswordEncryption.encrypt(new ByteArrayInputStream(zip), out, plan.password());
                    case GPG -> Gpg.encrypt(new ByteArrayInputStream(zip), out, plan.keys());
                }
            }
            long bytes = Files.size(file);
            if (plan.encryption() != BackupJob.Encryption.NONE) {
                log(current, "Encrypted " + (plan.encryption() == BackupJob.Encryption.GPG ? "for " + plan.keys().size() + " GPG recipient(s)" : "with the password") + ": "
                        + size(bytes));
            }
            checkCancelled(current);
            int deleted = transfer(plan, file, name, current, zone);
            String message = size(bytes) + ", transferred to " + plan.target().name + (deleted > 0 ? ", " + deleted + " old backup(s) deleted" : "");
            finish(current, true, "Done: " + message, message, bytes, name);
        } catch (Exception e) {
            String message = current.cancelled ? "aborted by " + current.cancelledBy : e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.warnf("Backup job %d failed: %s", jobId, message);
            finish(current, false, current.cancelled ? message : "ERROR: " + message, message, null, null);
            if (!current.cancelled) {
                String job = store.read(state -> state.jobs.stream().filter(j -> j.id == jobId).map(j -> j.name).findFirst().orElse("?"));
                mail.notifyFailure(Notice.BACKUP, "mosquitto-ui: backup " + job + " failed", "The backup job " + job + " (target " + targetName + ") failed:\n\n" + message);
            }
        } finally {
            remove(work);
        }
    }

    private int transfer(Plan plan, Path file, String name, Running current, ZoneId zone) throws Exception {
        BackupTarget target = plan.target();
        if (target.type == BackupTarget.Type.EMAIL) {
            List<String> recipients = BackupTargetService.recipients(target.recipients);
            mail.send(recipients, "Backup " + plan.name() + ": " + name, "Attached: backup " + name + " of mosquitto-ui (job " + plan.name() + ").\n\nThis message was sent automatically.",
                    List.of(file), BackupTargetService.maxMb(target));
            log(current, "Sent by e-mail to " + recipients.size() + " recipient(s)");
            return 0;
        }
        int deleted = 0;
        try (TargetStorage storage = targets.open(target)) {
            current.connection = storage;
            targets.learnHostKey(target, storage.hostKey());
            log(current, "Uploading " + name + " to " + BackupTargetService.describe(target));
            storage.upload(file, name);
            checkCancelled(current);
            log(current, "Uploaded");
            current.connection = null;
            if (target.retentionDays > 0) {
                LocalDate limit = LocalDate.now(zone).minusDays(target.retentionDays);
                for (String existing : storage.files()) {
                    if (outdated(existing, limit)) {
                        storage.delete(existing);
                        deleted++;
                    }
                }
                if (deleted > 0) {
                    log(current, deleted + " file(s) older than " + target.retentionDays + " days deleted on the target");
                }
            }
        }
        return deleted;
    }

    private static void remove(Path work) {
        if (work != null) {
            try (var files = Files.walk(work)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException e) {
                LOG.warnf("Temporary backup files not removed: %s", e.getMessage());
            }
        }
    }

    static boolean outdated(String file, LocalDate limit) {
        Matcher matcher = OWN_FILE.matcher(file);
        if (!matcher.matches()) {
            return false;
        }
        try {
            return LocalDate.parse(matcher.group(1), DateTimeFormatter.BASIC_ISO_DATE).isBefore(limit);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void checkCancelled(Running current) throws IOException {
        if (current.cancelled) {
            throw new IOException("cancelled");
        }
    }

    private static BackupRun find(State state, long id) {
        return state.runs.stream().filter(run -> run.id == id).findFirst().orElseThrow(() -> BackupException.notFound("Backup run"));
    }

    private void log(Running current, String line) {
        String entry = ZonedDateTime.now(settings.timeZone()).format(LOG_TIME) + "  " + line + "\n";
        store.change(state -> find(state, current.runId).log += entry);
    }

    private void finish(Running current, boolean success, String logLine, String message, Long bytes, String fileName) {
        log(current, logLine);
        store.change(state -> {
            BackupRun run = find(state, current.runId);
            run.success = success;
            run.finishedAt = Instant.now();
            run.message = message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message;
            run.bytes = bytes;
            run.fileName = fileName;
        });
    }

    private static String size(long bytes) {
        return bytes < KB * KB ? String.format(Locale.ROOT, "%.1f kB", bytes / KB) : String.format(Locale.ROOT, "%.1f MB", bytes / KB / KB);
    }

    private static Duration allowed(BackupJob.Schedule schedule) {
        return switch (schedule) {
            case DAILY -> Duration.ofHours(DAILY_HOURS);
            case WEEKLY -> Duration.ofHours(WEEKLY_HOURS);
            default -> Duration.ofDays(MONTHLY_DAYS);
        };
    }

    /** Enabled jobs with a schedule: whether their last run failed and whether a successful run is overdue. */
    public List<JobHealth> health() {
        Instant now = Instant.now();
        ZonedDateTime zoned = now.atZone(settings.timeZone());
        return store.read(state -> {
            List<JobHealth> health = new ArrayList<>();
            for (BackupJob job : state.jobs) {
                if (job.enabled && job.schedule != BackupJob.Schedule.MANUAL) {
                    List<BackupRun> finished = state.runs.stream().filter(run -> run.jobId == job.id && run.success != null)
                            .sorted(Comparator.comparing((BackupRun run) -> run.startedAt).reversed()).toList();
                    BackupRun last = finished.isEmpty() ? null : finished.getFirst();
                    BackupRun success = finished.stream().filter(run -> run.success).findFirst().orElse(null);
                    Instant since = success != null ? success.startedAt : job.createdAt;
                    ZonedDateTime next = nextRun(job, zoned);
                    health.add(new JobHealth(job.name, last != null && !last.success, last == null ? null : last.message, success == null ? null : success.startedAt,
                            since != null && since.plus(allowed(job.schedule)).isBefore(now), next == null ? null : next.toInstant()));
                }
            }
            return health;
        });
    }

    public RunPage list(int limit, int offset) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        return store.read(state -> {
            List<BackupRun> sorted = state.runs.stream().sorted(Comparator.comparing((BackupRun run) -> run.startedAt).thenComparing(run -> run.id).reversed()).toList();
            return new RunPage(sorted.stream().skip(Math.max(0, offset)).limit(size).map(run -> view(run, false)).toList(), sorted.size());
        });
    }

    public RunView get(long id) {
        return store.read(state -> view(find(state, id), true));
    }

    /** Removes finished runs older than the retention; the newest run of each job stays. Returns how many. */
    public int pruneRuns() {
        try {
            return store.update(state -> {
                int days = state.settings.runRetentionDays;
                if (days == 0) {
                    return 0;
                }
                Instant limit = Instant.now().minus(Duration.ofDays(days));
                List<BackupRun> old = state.runs.stream().filter(run -> run.success != null && run.startedAt.isBefore(limit)).filter(run -> state.runs.stream()
                        .filter(other -> other.jobId == run.jobId).anyMatch(other -> other.startedAt.isAfter(run.startedAt))).toList();
                state.runs.removeAll(old);
                return old.size();
            });
        } catch (RuntimeException e) {
            LOG.warnf("Pruning the backup runs failed: %s", e.getMessage());
            return 0;
        }
    }

    static RunView view(BackupRun r, boolean withLog) {
        return new RunView(r.id, r.jobId, r.jobName, r.startedAt, r.finishedAt, r.success, r.message, r.bytes, r.fileName, r.triggeredBy, withLog ? r.log : null);
    }
}
