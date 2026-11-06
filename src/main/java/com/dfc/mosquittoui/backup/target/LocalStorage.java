package com.dfc.mosquittoui.backup.target;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.dfc.mosquittoui.backup.target.TargetStorage.checkName;

/**
 * A directory in the server's file system; created if missing. Links in it are not followed: the directory lies below the
 * root of local targets, a link could lead out of it.
 */
final class LocalStorage implements TargetStorage {

    private final Path dir;

    LocalStorage(Settings settings) throws IOException {
        if (settings.path() == null || settings.path().isBlank() || !Path.of(settings.path()).isAbsolute()) {
            throw new IOException("A local target needs an absolute path");
        }
        dir = Path.of(settings.path());
        Files.createDirectories(dir);
    }

    @Override
    public void upload(Path file, String name) throws IOException {
        Path part = dir.resolve(checkName(name) + TEMPORARY_SUFFIX);
        Files.copy(file, part, StandardCopyOption.REPLACE_EXISTING);
        Files.move(part, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public List<FileEntry> entries() throws IOException {
        List<FileEntry> result = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path path : s.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                result.add(new FileEntry(path.getFileName().toString(), Files.size(path), Files.getLastModifiedTime(path).toInstant()));
            }
        }
        return result;
    }

    @Override
    public void download(String name, Path destination) throws IOException {
        Path file = dir.resolve(checkName(name));
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(name);
        }
        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void delete(String name) throws IOException {
        Files.deleteIfExists(dir.resolve(checkName(name)));
    }

    @Override
    public void close() {
    }
}
