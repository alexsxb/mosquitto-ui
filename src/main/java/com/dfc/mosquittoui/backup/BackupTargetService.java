package com.dfc.mosquittoui.backup;

import com.dfc.mosquittoui.notify.MailService;
import com.dfc.mosquittoui.backup.target.TargetStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.nio.file.Path;
import java.nio.file.Files;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Targets of backups. Passwords are never returned. */
@ApplicationScoped
public class BackupTargetService {

    /** passwordSet: a password or secret key is stored. */
    public record TargetView(long id, String name, BackupTarget.Type type, @Schema(nullable = true) String host, int port, @Schema(nullable = true) String username,
            boolean passwordSet, @Schema(nullable = true) String path, boolean tls, @Schema(nullable = true) String hostKey, @Schema(nullable = true) String region,
            @Schema(nullable = true) String bucket, @Schema(nullable = true) String recipients, @Schema(nullable = true) Integer maxMb, int retentionDays, long jobCount) {
    }

    /** password: null leaves it, empty removes it. */
    public record TargetInput(String name, BackupTarget.Type type, @Schema(nullable = true) String host, @Schema(nullable = true) Integer port,
            @Schema(nullable = true) String username, @Schema(nullable = true) String password, @Schema(nullable = true) String path, @Schema(nullable = true) Boolean tls,
            @Schema(nullable = true) String region, @Schema(nullable = true) String bucket, @Schema(nullable = true) String recipients,
            @Schema(nullable = true) Integer maxMb, @Schema(nullable = true) Integer retentionDays) {
    }

    public record TargetTestResult(boolean success, String message) {
    }

    public static final Pattern EMAIL = Pattern.compile("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+");
    private static final Pattern BUCKET = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    private static final int MAX_NAME = 100;
    private static final int MAX_DAYS = 3650;
    private static final int MAX_PORT = 65535;
    private static final int MAX_ADDRESS = 255;
    private static final int MAX_MAIL_MB = 50;
    private static final int DEFAULT_MAIL_MB = 20;

    @Inject
    BackupStore store;

    /** Local targets lie below it: the server keeps its secret key, the state and the files of the broker elsewhere. */
    @ConfigProperty(name = "mosquitto-ui.backup.local-root")
    Path localRoot;

    @Inject
    MailService mail;

    public List<TargetView> list() {
        return store.read(state -> state.targets.stream().map(t -> view(t, state)).sorted(java.util.Comparator.comparing(TargetView::name)).toList());
    }

    public TargetView create(TargetInput input) {
        validate(input);
        return store.update(state -> {
            BackupTarget target = new BackupTarget();
            target.id = state.newId();
            target.createdAt = Instant.now();
            apply(target, input);
            state.targets.add(target);
            return view(target, state);
        });
    }

    public TargetView update(long id, TargetInput input) {
        validate(input);
        return store.update(state -> {
            BackupTarget target = find(state, id);
            // A different server needs its key learned again
            if (!Objects.equals(target.host, blank(input.host())) || target.port != port(input) || target.type != input.type()
                    || target.tls != Boolean.TRUE.equals(input.tls())) {
                target.hostKey = null;
            }
            apply(target, input);
            return view(target, state);
        });
    }

    public void delete(long id) {
        store.change(state -> {
            BackupTarget target = find(state, id);
            if (state.jobs.stream().anyMatch(job -> job.targetId == id)) {
                throw BackupException.conflict("target_in_use", "The target is still used by a backup job");
            }
            state.targets.remove(target);
        });
    }

    public void forgetHostKey(long id) {
        store.change(state -> find(state, id).hostKey = null);
    }

    /** Connects, logs in, opens or creates the directory and lists it; e-mail targets are only checked. */
    public TargetTestResult test(long id) {
        BackupTarget target = target(id);
        if (target.type == BackupTarget.Type.EMAIL) {
            try {
                return new TargetTestResult(true, "Sender " + mail.check() + ", " + recipients(target.recipients).size() + " recipient(s), up to " + maxMb(target)
                        + " MB per message; no e-mail was sent");
            } catch (IOException e) {
                return new TargetTestResult(false, e.getMessage());
            }
        }
        try (TargetStorage storage = open(target, true)) {
            int files = storage.files().size();
            String key = storage.hostKey();
            learnHostKey(target, key);
            return new TargetTestResult(true, "Connection fine, " + files + " file(s) in the directory"
                    + (key == null ? "" : (target.type == BackupTarget.Type.FTP ? ", certificate " : ", server key ") + key));
        } catch (Exception e) {
            return new TargetTestResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** SFTP, FTP, LOCAL and S3; e-mail goes through the mail service. */
    TargetStorage open(BackupTarget target) throws Exception {
        return open(target, false);
    }

    private TargetStorage open(BackupTarget target, boolean learnCertificate) throws Exception {
        String path = target.type == BackupTarget.Type.LOCAL ? localDirectory(target.path).toString() : target.path;
        return TargetStorage.open(new TargetStorage.Settings(TargetStorage.Type.valueOf(target.type.name()), target.host, target.port, target.username, target.password,
                path, target.tls, target.hostKey, target.region, target.bucket), learnCertificate);
    }

    /** Whether a path stays below the root of local targets, as written. */
    private boolean belowLocalRoot(String path) {
        Path normal = Path.of(path.strip()).normalize();
        return normal.isAbsolute() && normal.startsWith(localRoot.toAbsolutePath().normalize());
    }

    /** The directory of a local target, created if missing; also checked after following links, which could lead out of the root. */
    Path localDirectory(String path) throws IOException {
        if (path == null || !belowLocalRoot(path)) {
            throw new IOException("A local target has to be below " + localRoot);
        }
        Path directory = Files.createDirectories(Path.of(path.strip()).normalize()).toRealPath();
        if (!directory.startsWith(Files.createDirectories(localRoot).toRealPath())) {
            throw new IOException("A local target has to be below " + localRoot);
        }
        return directory;
    }

    /** Stores the SFTP key seen on the first contact, or the FTPS certificate trusted by its fingerprint. */
    void learnHostKey(BackupTarget target, String key) {
        if (key != null && target.hostKey == null) {
            store.change(state -> find(state, target.id).hostKey = key);
        }
    }

    BackupTarget target(long id) {
        return store.read(state -> find(state, id));
    }

    static BackupTarget find(BackupStore.State state, long id) {
        return state.targets.stream().filter(t -> t.id == id).findFirst().orElseThrow(() -> BackupException.notFound("Backup target"));
    }

    static List<String> recipients(String value) {
        return value == null ? List.of() : Arrays.stream(value.split("[,;\\s]+")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    static int maxMb(BackupTarget target) {
        return target.maxMb == null ? DEFAULT_MAIL_MB : target.maxMb;
    }

    private void validate(TargetInput input) {
        if (input == null || blank(input.name()) == null || input.name().strip().length() > MAX_NAME) {
            throw BackupException.validation("name", "Enter a name (at most 100 characters)");
        }
        if (input.type() == null) {
            throw BackupException.validation("type", "Choose a type");
        }
        if (input.port() != null && (input.port() < 0 || input.port() > MAX_PORT)) {
            throw BackupException.validation("port", "Invalid port");
        }
        if (input.retentionDays() != null && input.retentionDays() > MAX_DAYS) {
            throw BackupException.validation("retentionDays", "At most 3650 days");
        }
        switch (input.type()) {
            case SFTP, FTP -> {
                if (blank(input.host()) == null || blank(input.username()) == null) {
                    throw BackupException.validation("host", "Server and user are missing");
                }
            }
            case LOCAL -> {
                if (blank(input.path()) == null || !belowLocalRoot(input.path())) {
                    throw BackupException.validation("path", "A local target has to be below " + localRoot);
                }
            }
            case S3 -> {
                if (blank(input.host()) == null || blank(input.bucket()) == null || blank(input.username()) == null) {
                    throw BackupException.validation("host", "Endpoint, bucket and access key are missing");
                }
                if (!BUCKET.matcher(input.bucket().strip()).matches()) {
                    throw BackupException.validation("bucket", "Invalid bucket name");
                }
            }
            case EMAIL -> {
                List<String> recipients = recipients(input.recipients());
                if (recipients.isEmpty() || recipients.stream().anyMatch(r -> r.length() > MAX_ADDRESS || !EMAIL.matcher(r).matches())) {
                    throw BackupException.validation("recipients", "Enter at least one valid e-mail address");
                }
                if (input.maxMb() != null && (input.maxMb() < 1 || input.maxMb() > MAX_MAIL_MB)) {
                    throw BackupException.validation("maxMb", "Between 1 and 50 MB");
                }
            }
        }
    }

    private static void apply(BackupTarget target, TargetInput input) {
        target.name = input.name().strip();
        target.type = input.type();
        target.host = input.type() == BackupTarget.Type.S3 ? stripScheme(blank(input.host())) : blank(input.host());
        target.port = port(input);
        target.username = blank(input.username());
        if (input.password() != null) {
            target.password = input.password().isEmpty() ? null : input.password();
        }
        target.path = blank(input.path());
        target.tls = Boolean.TRUE.equals(input.tls());
        target.region = blank(input.region());
        target.bucket = blank(input.bucket());
        boolean email = input.type() == BackupTarget.Type.EMAIL;
        target.recipients = email ? String.join(", ", recipients(input.recipients())) : null;
        target.maxMb = email ? input.maxMb() : null;
        target.retentionDays = email || input.retentionDays() == null ? 0 : Math.max(0, input.retentionDays());
    }

    private static int port(TargetInput input) {
        return input.port() == null ? 0 : input.port();
    }

    private static String stripScheme(String host) {
        if (host == null) {
            return null;
        }
        String stripped = host.replaceFirst("(?i)^https?://", "");
        return stripped.endsWith("/") ? stripped.substring(0, stripped.length() - 1) : stripped;
    }

    public static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static TargetView view(BackupTarget t, BackupStore.State state) {
        return new TargetView(t.id, t.name, t.type, t.host, t.port, t.username, t.password != null, t.path, t.tls, t.hostKey, t.region, t.bucket, t.recipients, t.maxMb,
                t.retentionDays, state.jobs.stream().filter(job -> job.targetId == t.id).count());
    }

    static String describe(BackupTarget t) {
        return switch (t.type) {
            case EMAIL -> t.recipients;
            case LOCAL -> t.path;
            case S3 -> t.host + " → " + t.bucket + (t.path == null ? "" : "/" + t.path);
            default -> t.username + "@" + t.host + (t.port == 0 ? "" : ":" + t.port) + (t.path == null ? "" : " → " + t.path);
        };
    }
}
