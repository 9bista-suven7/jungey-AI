package dev.suven.jungeynotepad.notes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Earlier versions of each note, so nothing typed over is ever gone: a copy is kept when a
 * note is saved and has changed, at most every few minutes while writing, and always when
 * it is closed.
 *
 * <p>Kept in ~/.local/share/jungey-notepad/history, one folder per note (named by a hash of
 * its path), outside the notes folder so sync tools and grep do not see them.
 */
public final class History {

    public record Version(Path file, Instant when, long size) {
    }

    private static final int KEEP = 80;
    private static final Duration EVERY = Duration.ofMinutes(4);

    private final Path dir;

    public History(Path dir) {
        this.dir = dir;
    }

    public static History standard() {
        String data = System.getenv("XDG_DATA_HOME");
        Path base = data != null && !data.isBlank() ? Path.of(data)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return new History(base.resolve("jungey-notepad").resolve("history"));
    }

    private Path folder(Path note) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(note.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return dir.resolve(HexFormat.of().formatHex(hash, 0, 10));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Keeps {@code text} as a version of {@code note} if it differs from the last one kept,
     * and either {@code force} is set or the last one is a few minutes old.
     *
     * @return true when a version was kept
     */
    public boolean keep(Path note, String text, boolean force) throws IOException {
        if (text.isBlank()) return false;
        List<Version> versions = versions(note);
        if (!versions.isEmpty()) {
            Version last = versions.getFirst();
            if (!force && Duration.between(last.when(), Instant.now()).compareTo(EVERY) < 0) return false;
            if (Library.read(last.file()).equals(text)) return false;
        }
        Path folder = folder(note);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("path"), note.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
        long now = System.currentTimeMillis();
        Path file = folder.resolve(now + ".md");
        while (Files.exists(file)) file = folder.resolve(++now + ".md");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        for (Version old : versions.subList(Math.min(versions.size(), KEEP - 1), versions.size())) {
            Files.deleteIfExists(old.file());
        }
        return true;
    }

    /** Newest first. */
    public List<Version> versions(Path note) {
        Path folder = folder(note);
        List<Version> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(f -> f.getFileName().toString().matches("\\d+\\.md")).forEach(f -> {
                String n = f.getFileName().toString();
                try {
                    out.add(new Version(f, Instant.ofEpochMilli(Long.parseLong(n.substring(0, n.length() - 3))), Files.size(f)));
                } catch (IOException ignored) {
                    // Gone between listing and looking: nothing to show.
                }
            });
        } catch (IOException e) {
            return out;
        }
        out.sort(Comparator.comparing(Version::when).reversed());
        return out;
    }

    /** A note renamed keeps its past: the folder moves with it. */
    public void moved(Path from, Path to) throws IOException {
        Path a = folder(from);
        Path b = folder(to);
        if (!Files.isDirectory(a) || Files.exists(b)) return;
        Files.createDirectories(b.getParent());
        Files.move(a, b);
        Files.writeString(b.resolve("path"), to.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
    }
}
