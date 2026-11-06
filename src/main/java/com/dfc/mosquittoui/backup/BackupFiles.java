package com.dfc.mosquittoui.backup;

import com.dfc.mosquittoui.backup.crypto.PasswordEncryption;
import com.dfc.mosquittoui.backup.target.TargetStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Backups as files: those on a target, and those for download. A target can hold files of others, which restoring leaves alone. */
@ApplicationScoped
public class BackupFiles {

    /** A file to hand out. */
    public record Download(String name, byte[] content) {
    }

    private static final int MIN_PASSWORD = 12;

    @Inject
    BackupTargetService targets;

    @Inject
    BackupSettingsService settings;

    @Inject
    ArchiveService archives;

    @ConfigProperty(name = "mosquitto-ui.state-dir")
    Path stateDir;

    public String serverTime() {
        return ZonedDateTime.now(settings.timeZone()).withNano(0).toLocalDateTime().toString();
    }

    /** Whether a run of this application wrote a file of this name. */
    public static boolean own(String name) {
        return name != null && BackupRunService.OWN_FILE.matcher(name).matches();
    }

    private TargetStorage open(long targetId) throws Exception {
        BackupTarget target = targets.target(targetId);
        if (target.type == BackupTarget.Type.EMAIL) {
            throw BackupException.validation("target", "E-mail targets keep no files");
        }
        return targets.open(target);
    }

    private static String checked(String name) {
        try {
            return TargetStorage.checkName(name);
        } catch (IllegalArgumentException e) {
            throw BackupException.notFound("Backup file");
        }
    }

    /** All files on the target, newest backups first. */
    public List<TargetStorage.FileEntry> list(long targetId) throws Exception {
        try (TargetStorage storage = open(targetId)) {
            return storage.entries().stream().sorted(Comparator.comparing(TargetStorage.FileEntry::name, Comparator.reverseOrder())).toList();
        }
    }

    /** Any file of the target in a temporary file, which the caller removes. In the state volume: /tmp is small and held in memory. */
    public Path fetch(long targetId, String name) throws Exception {
        checked(name);
        Path temporary = Files.createTempFile(stateDir, "download", ".bin");
        try (TargetStorage storage = open(targetId)) {
            storage.download(name, temporary);
            return temporary;
        } catch (Exception e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    public void delete(long targetId, String name) throws Exception {
        checked(name);
        try (TargetStorage storage = open(targetId)) {
            storage.delete(name);
        }
    }

    public byte[] download(long targetId, String name) throws Exception {
        if (!own(name)) {
            throw BackupException.notFound("Backup file");
        }
        BackupTarget target = targets.target(targetId);
        Path temporary = Files.createTempFile("mosquitto-ui-restore", ".bin");
        try (TargetStorage storage = targets.open(target)) {
            storage.download(name, temporary);
            return Files.readAllBytes(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Download archive(List<BackupSection> sections, String password, String user) throws Exception {
        if (sections == null || sections.isEmpty()) {
            throw BackupException.validation("sections", "Select at least one part to back up");
        }
        boolean encrypt = password != null && !password.isEmpty();
        if (encrypt && password.length() < MIN_PASSWORD) {
            throw BackupException.validation("password", "The password must have at least 12 characters");
        }
        if (!encrypt && sections.stream().anyMatch(BackupSection::encryptedOnly)) {
            throw BackupException.validation("sections", "These parts hold secrets and are only backed up encrypted: enter a password");
        }
        byte[] content = archives.createZip(EnumSet.copyOf(sections), user);
        if (encrypt) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PasswordEncryption.encrypt(new ByteArrayInputStream(content), out, password);
            content = out.toByteArray();
        }
        String name = ZonedDateTime.now(settings.timeZone()).format(BackupRunService.STAMP) + "_mosquitto-ui.zip" + (encrypt ? ".enc" : "");
        return new Download(name, content);
    }
}
