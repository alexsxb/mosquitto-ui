package com.dfc.mosquittoui.importer;

import com.dfc.mosquittoui.api.Dtos.ImportFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/** The files of an upload, ZIP files unpacked in memory, and what each of them is by its content. */
final class UploadedFiles {

    enum Kind {
        PASSWORD, ACL, DB, CONF, UNKNOWN
    }

    record Entry(String name, byte[] content, Kind kind) {
        String text() {
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    /** A small ZIP file can unpack to anything. */
    private static final int MAX_ENTRIES = 500;
    private static final long MAX_UNPACKED_BYTES = 256L * 1024 * 1024;
    private static final int BUFFER = 8192;

    private static final Set<String> ACL_KEYS = Set.of("user", "topic", "pattern");
    private static final Pattern PASSWORD_LINE = Pattern.compile("[^\\s:]+:\\S+");
    private static final Set<String> CONF_KEYS = Set.of("allow_anonymous", "autosave_interval", "bind_address", "cafile", "certfile", "connection", "include_dir",
            "keyfile", "listener", "log_dest", "log_type", "max_connections", "max_inflight_messages", "max_keepalive", "max_packet_size", "max_queued_messages",
            "message_size_limit", "per_listener_settings", "persistence", "persistence_file", "persistence_location", "pid_file", "plugin", "port", "protocol",
            "retain_available", "sys_interval", "acl_file", "password_file");

    private UploadedFiles() {
    }

    static List<Entry> read(List<ImportFile> files) {
        List<Entry> entries = new ArrayList<>();
        for (ImportFile file : files) {
            if (isZip(file.content())) {
                unzip(file, entries);
            } else {
                entries.add(entry(baseName(file.name()), file.content()));
            }
        }
        return entries;
    }

    private static boolean isZip(byte[] data) {
        return data.length >= ZIP_MAGIC.length && java.util.Arrays.equals(java.util.Arrays.copyOf(data, ZIP_MAGIC.length), ZIP_MAGIC);
    }

    private static String baseName(String path) {
        return path.substring(path.replace('\\', '/').lastIndexOf('/', path.length() - 1) + 1);
    }

    private static boolean ignored(ZipEntry entry) {
        String name = baseName(entry.getName());
        return entry.isDirectory() || entry.getName().startsWith("__MACOSX/") || name.startsWith(".");
    }

    private static void unzip(ImportFile file, List<Entry> entries) {
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file.content()))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER];
            while ((entry = zip.getNextEntry()) != null) {
                if (ignored(entry)) {
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw new InvalidFileException(file.name() + " has more than " + MAX_ENTRIES + " files");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_UNPACKED_BYTES) {
                        throw new InvalidFileException(file.name() + " unpacks to more than " + MAX_UNPACKED_BYTES / (1024 * 1024) + " MB");
                    }
                    out.write(buffer, 0, read);
                }
                entries.add(entry(baseName(entry.getName()), out.toByteArray()));
            }
        } catch (ZipException e) {
            throw new InvalidFileException(file.name() + " cannot be unpacked: " + e.getMessage());
        } catch (IOException e) {
            throw new InvalidFileException(file.name() + " cannot be read: " + e.getMessage());
        }
    }

    private static Entry entry(String name, byte[] content) {
        return new Entry(name, content, kind(name, content));
    }

    private static boolean binary(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    static Kind kind(String name, byte[] content) {
        if (DbFile.isDbFile(content)) {
            return Kind.DB;
        }
        if (binary(content)) {
            return Kind.UNKNOWN;
        }
        List<String> lines = new String(content, StandardCharsets.UTF_8).lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        if (lines.isEmpty()) {
            return Kind.UNKNOWN;
        }
        List<String> keys = lines.stream().map(line -> line.split("\\s+", 2)[0]).toList();
        if (keys.contains("connection") || keys.contains("listener")) {
            return Kind.CONF;
        }
        if (ACL_KEYS.containsAll(keys)) {
            return Kind.ACL;
        }
        if (lines.stream().allMatch(line -> PASSWORD_LINE.matcher(line).matches())) {
            return Kind.PASSWORD;
        }
        return name.endsWith(".conf") || keys.stream().anyMatch(CONF_KEYS::contains) ? Kind.CONF : Kind.UNKNOWN;
    }
}
