package com.dfc.mosquittoui.backup;

import com.dfc.mosquittoui.backup.BackupStore.State;
import com.dfc.mosquittoui.backup.crypto.Gpg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Backup jobs: what is backed up, where to, when and how it is encrypted. */
@ApplicationScoped
public class BackupJobService {

    /** problem: the key is expired or revoked, so that a run would fail. */
    public record GpgRecipient(String fingerprint, String name, @Schema(nullable = true) String problem) {
    }

    public record JobView(long id, String name, boolean enabled, List<BackupSection> sections, long targetId, String targetName, BackupTarget.Type targetType,
            BackupJob.Schedule schedule, @Schema(nullable = true) String time, @Schema(nullable = true) Integer weekday, @Schema(nullable = true) Integer monthDay,
            BackupJob.Encryption encryption, boolean passwordSet, List<GpgRecipient> gpgRecipients, boolean running,
            @Schema(nullable = true) BackupRunService.RunView lastRun) {
    }

    /**
     * password: null leaves it, empty removes it. gpgRecipients: null leaves them, otherwise the complete list of fingerprints of stored keys and
     * new armored public keys.
     */
    public record JobInput(String name, @Schema(nullable = true) Boolean enabled, List<BackupSection> sections, Long targetId, BackupJob.Schedule schedule,
            @Schema(nullable = true) String time, @Schema(nullable = true) Integer weekday, @Schema(nullable = true) Integer monthDay, BackupJob.Encryption encryption,
            @Schema(nullable = true) String password, @Schema(nullable = true) List<String> gpgRecipients) {
    }

    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9A-Fa-f]{40}");
    private static final int MAX_NAME = 100;
    private static final int COPY_BASE = 92;
    private static final int MIN_PASSWORD = 12;
    private static final int LAST_WEEKDAY = 7;
    private static final int LAST_MONTH_DAY = 28;

    @Inject
    BackupStore store;

    @Inject
    BackupRunService runService;

    public List<JobView> list() {
        return store.read(state -> state.jobs.stream().map(job -> view(job, state)).sorted(Comparator.comparing(JobView::name)).toList());
    }

    public JobView create(JobInput input) {
        List<String> keys = gpgKeys(input, List.of());
        return store.update(state -> {
            BackupJob job = new BackupJob();
            job.createdAt = Instant.now();
            apply(job, input, keys, state);
            job.id = state.newId();
            state.jobs.add(job);
            return view(job, state);
        });
    }

    public JobView update(long id, JobInput input) {
        List<String> keys = gpgKeys(input, store.read(state -> List.copyOf(find(state, id).gpgKeys)));
        return store.update(state -> {
            BackupJob job = find(state, id);
            apply(job, input, keys, state);
            return view(job, state);
        });
    }

    /** With password and keys, the schedule switched off. */
    public JobView copy(long id) {
        return store.update(state -> {
            BackupJob source = find(state, id);
            BackupJob job = new BackupJob();
            job.id = state.newId();
            job.name = (source.name.length() > COPY_BASE ? source.name.substring(0, COPY_BASE) : source.name) + " (copy)";
            job.enabled = false;
            job.sections = source.sections;
            job.targetId = source.targetId;
            job.schedule = source.schedule;
            job.time = source.time;
            job.weekday = source.weekday;
            job.monthDay = source.monthDay;
            job.encryption = source.encryption;
            job.password = source.password;
            job.gpgKeys = source.gpgKeys;
            job.createdAt = Instant.now();
            state.jobs.add(job);
            return view(job, state);
        });
    }

    /** Deletes the job and its runs; the backups on the target stay. */
    public void delete(long id) {
        if (runService.running(id)) {
            throw BackupException.conflict("job_running", "The job is running");
        }
        store.change(state -> {
            BackupJob job = find(state, id);
            state.runs.removeIf(run -> run.jobId == id);
            state.jobs.remove(job);
        });
    }

    static BackupJob find(State state, long id) {
        return state.jobs.stream().filter(job -> job.id == id).findFirst().orElseThrow(() -> BackupException.notFound("Backup job"));
    }

    /** Keys after the input: stored ones referenced by fingerprint, new ones checked. */
    private static List<String> gpgKeys(JobInput input, List<String> stored) {
        if (input == null || input.encryption() != BackupJob.Encryption.GPG) {
            return List.of();
        }
        Map<String, Gpg.Key> existing = new LinkedHashMap<>();
        stored.forEach(armored -> Gpg.parseAll(armored).forEach(key -> existing.put(key.fingerprint().toUpperCase(), key)));
        if (input.gpgRecipients() == null) {
            return existing.values().stream().map(Gpg.Key::armored).toList();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String entry : input.gpgRecipients()) {
            String value = entry == null ? "" : entry.strip();
            Gpg.Key key;
            if (FINGERPRINT.matcher(value).matches()) {
                key = existing.get(value.toUpperCase());
                if (key == null) {
                    throw BackupException.validation("gpgRecipients", "Unknown key " + value + ": paste or search it");
                }
            } else {
                try {
                    key = Gpg.parse(value);
                } catch (IllegalArgumentException e) {
                    throw BackupException.validation("gpgRecipients", "GPG key: " + e.getMessage());
                }
            }
            if (seen.add(key.fingerprint().toUpperCase())) {
                result.add(key.armored());
            }
        }
        return result;
    }

    private static void apply(BackupJob job, JobInput input, List<String> gpgKeys, State state) {
        if (BackupTargetService.blank(input.name()) == null || input.name().strip().length() > MAX_NAME) {
            throw BackupException.validation("name", "Enter a name (at most 100 characters)");
        }
        if (input.sections() == null || input.sections().isEmpty()) {
            throw BackupException.validation("sections", "Select at least one part to back up");
        }
        if (input.targetId() == null || state.targets.stream().noneMatch(target -> target.id == input.targetId())) {
            throw BackupException.validation("targetId", "Choose a target");
        }
        BackupJob.Schedule schedule = input.schedule() == null ? BackupJob.Schedule.MANUAL : input.schedule();
        if (schedule != BackupJob.Schedule.MANUAL && (input.time() == null || !TIME.matcher(input.time()).matches())) {
            throw BackupException.validation("time", "Enter the time as HH:mm");
        }
        if (schedule == BackupJob.Schedule.WEEKLY && (input.weekday() == null || input.weekday() < 1 || input.weekday() > LAST_WEEKDAY)) {
            throw BackupException.validation("weekday", "Choose a weekday");
        }
        if (schedule == BackupJob.Schedule.MONTHLY && (input.monthDay() == null || input.monthDay() < 1 || input.monthDay() > LAST_MONTH_DAY)) {
            throw BackupException.validation("monthDay", "Choose a day between 1 and 28");
        }
        BackupJob.Encryption encryption = input.encryption() == null ? BackupJob.Encryption.NONE : input.encryption();
        String password = job.password;
        if (input.password() != null) {
            password = input.password().isEmpty() ? null : input.password();
        }
        if (encryption == BackupJob.Encryption.PASSWORD) {
            if (password == null) {
                throw BackupException.validation("password", "Enter a password");
            }
            if (input.password() != null && !input.password().isEmpty() && input.password().length() < MIN_PASSWORD) {
                throw BackupException.validation("password", "The password must have at least 12 characters");
            }
        }
        if (encryption == BackupJob.Encryption.GPG && gpgKeys.isEmpty()) {
            throw BackupException.validation("gpgRecipients", "Add at least one GPG key");
        }
        if (encryption == BackupJob.Encryption.NONE && input.sections().stream().anyMatch(BackupSection::encryptedOnly)) {
            throw BackupException.validation("sections", "These parts hold secrets and are only backed up encrypted: choose a password or GPG");
        }
        job.name = input.name().strip();
        job.enabled = input.enabled() == null || input.enabled();
        job.sections = EnumSet.copyOf(input.sections());
        job.targetId = input.targetId();
        job.schedule = schedule;
        job.time = schedule == BackupJob.Schedule.MANUAL ? null : input.time();
        job.weekday = schedule == BackupJob.Schedule.WEEKLY ? input.weekday() : null;
        job.monthDay = schedule == BackupJob.Schedule.MONTHLY ? input.monthDay() : null;
        job.encryption = encryption;
        // Only the secret of the chosen encryption is kept
        job.password = encryption == BackupJob.Encryption.PASSWORD ? password : null;
        job.gpgKeys = encryption == BackupJob.Encryption.GPG ? new ArrayList<>(gpgKeys) : new ArrayList<>();
    }

    private JobView view(BackupJob job, State state) {
        List<GpgRecipient> recipients = new ArrayList<>();
        for (String armored : job.gpgKeys) {
            for (Gpg.Key key : Gpg.parseAll(armored)) {
                recipients.add(new GpgRecipient(key.fingerprint(), String.join(", ", key.names()), key.problem()));
            }
        }
        BackupTarget target = BackupTargetService.find(state, job.targetId);
        BackupRun last = state.runs.stream().filter(run -> run.jobId == job.id).max(Comparator.comparing((BackupRun run) -> run.startedAt)).orElse(null);
        return new JobView(job.id, job.name, job.enabled, List.copyOf(job.sections), job.targetId, target.name, target.type, job.schedule, job.time, job.weekday,
                job.monthDay, job.encryption, job.password != null, recipients, runService.running(job.id), last == null ? null : BackupRunService.view(last, false));
    }
}
