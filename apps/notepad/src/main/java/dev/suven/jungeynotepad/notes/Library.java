package dev.suven.jungeynotepad.notes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The notes: plain Markdown files in one folder, ~/Documents/Jungey by default - the same
 * place Jungey keeps what it is told to note down, so its notes.md and todo.md are here too.
 *
 * <p>Nothing is kept anywhere else. Any editor, grep or sync tool works on the same files,
 * and a note deleted here goes to a .trash folder beside them rather than away.
 */
public final class Library {

    /** What the sidebar shows of a note, read from the file and kept while its time stays the same. */
    public record Note(Path path, String title, String snippet, Set<String> tags, Instant modified, long size,
                       int tasks, int tasksDone) {

        public String name() {
            String n = path.getFileName().toString();
            int dot = n.lastIndexOf('.');
            return dot > 0 ? n.substring(0, dot) : n;
        }
    }

    /** A note and the line where it matched. */
    public record Hit(Note note, int line, String text) {
    }

    private static final Set<String> EXTENSIONS = Set.of("md", "markdown", "txt");
    private static final Pattern TAG = Pattern.compile("(?<![\\w#&/])#([\\p{L}][\\p{L}\\p{N}_/-]*)");
    private static final DateTimeFormatter DAY_TITLE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

    private final Path root;
    private final Map<Path, Note> cache = new ConcurrentHashMap<>();

    public Library(Path root) {
        this.root = root;
    }

    /** $JUNGEY_NOTES_DIR if set, else ~/Documents/Jungey. */
    public static Library standard() {
        String dir = System.getenv("JUNGEY_NOTES_DIR");
        return new Library(dir != null && !dir.isBlank() ? Path.of(dir)
                : Path.of(System.getProperty("user.home"), "Documents", "Jungey"));
    }

    public Path root() {
        return root;
    }

    public static boolean isNote(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Every note, newest first. */
    public List<Note> notes() {
        List<Note> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try {
            Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), 4, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root) && dir.getFileName().toString().startsWith(".")
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && isNote(file) && !file.getFileName().toString().startsWith(".")) {
                        Note n = describe(file, attrs);
                        if (n != null) out.add(n);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        cache.keySet().retainAll(out.stream().map(Note::path).toList());
        out.sort(Comparator.comparing(Note::modified).reversed());
        return out;
    }

    private Note describe(Path file, BasicFileAttributes attrs) {
        Instant modified = attrs.lastModifiedTime().toInstant();
        Note known = cache.get(file);
        if (known != null && known.modified().equals(modified) && known.size() == attrs.size()) return known;
        try {
            // A huge file is somebody's log, not a note: describe it by name only.
            String text = attrs.size() > 2_000_000 ? "" : read(file);
            String title = Markdown.title(text);
            TextStats stats = TextStats.of(text);
            Note n = new Note(file, title.isBlank() ? nameOf(file) : title, snippet(text), tags(text), modified,
                    attrs.size(), stats.tasks(), stats.tasksDone());
            cache.put(file, n);
            return n;
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }

    private static String nameOf(Path file) {
        String n = file.getFileName().toString();
        return n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
    }

    /** The first words after the title, for the sidebar's second line. */
    static String snippet(String text) {
        StringBuilder b = new StringBuilder();
        boolean skippedTitle = false;
        for (String line : text.split("\n", 30)) {
            if (line.isBlank() || line.trim().startsWith("```")) continue;
            if (!skippedTitle) {
                skippedTitle = true;
                continue;
            }
            if (!b.isEmpty()) b.append("  ");
            b.append(Markdown.plain(line.replaceFirst("^\\s*(#{1,6}|[-*+>]|\\d+[.)])\\s*(\\[[ xX]\\]\\s*)?", "")));
            if (b.length() > 140) break;
        }
        return b.length() > 140 ? b.substring(0, 139) + "…" : b.toString();
    }

    public static Set<String> tags(String text) {
        Set<String> tags = new LinkedHashSet<>();
        boolean inCode = false;
        for (String line : text.split("\n")) {
            if (line.trim().startsWith("```")) inCode = !inCode;
            if (inCode) continue;
            Matcher m = TAG.matcher(line.replaceAll("`[^`]*`", "").replaceAll("https?://\\S+", ""));
            while (m.find()) tags.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return tags;
    }

    // ---------- reading and writing ----------

    public static String read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (s.startsWith("\uFEFF")) s = s.substring(1);
        return s.replace("\r\n", "\n");
    }

    /**
     * Writes all of {@code text} or nothing: into a file beside the note, then moved over it,
     * so a crash or a full disk mid-save never leaves half a note.
     */
    public static void write(Path file, String text) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".saving");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** A new note named after {@code title}, never over an existing one. */
    public Path create(String title, String text) throws IOException {
        String base = fileName(title);
        Path p = root.resolve(base + ".md");
        for (int i = 2; Files.exists(p); i++) p = root.resolve(base + " " + i + ".md");
        write(p, text);
        return p;
    }

    /** A title made safe to be a file name on any disk a note might be synced to. */
    public static String fileName(String title) {
        String s = title.replaceAll("[\\\\/:*?\"<>|#\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        s = s.replaceAll("^\\.+", "");
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s.isEmpty() ? "Untitled" : s;
    }

    /** Today's page in the journal, made with its date as the heading the first time. */
    public Path daily(LocalDate day) throws IOException {
        Path p = root.resolve("Journal").resolve(day + ".md");
        if (!Files.exists(p)) write(p, "# " + day.format(DAY_TITLE) + "\n\n");
        return p;
    }

    public Path rename(Path file, String newName) throws IOException {
        String ext = file.getFileName().toString().contains(".")
                ? file.getFileName().toString().substring(file.getFileName().toString().lastIndexOf('.')) : ".md";
        Path target = file.resolveSibling(fileName(newName) + ext);
        if (target.equals(file)) return file;
        if (Files.exists(target)) throw new IOException("There is already a note called " + target.getFileName());
        return Files.move(file, target);
    }

    /** Moves a note into .trash, stamped so two of the same name can both be kept. */
    public Path trash(Path file) throws IOException {
        Path trash = root.resolve(".trash");
        Files.createDirectories(trash);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path target = trash.resolve(dot > 0 ? name.substring(0, dot) + " (" + stamp + ")" + name.substring(dot)
                : name + " (" + stamp + ")");
        return Files.move(file, target);
    }

    /** Notes with every word of {@code query} in them, title matches first. */
    public List<Hit> search(String query) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return List.of();
        List<Hit> hits = new ArrayList<>();
        for (Note n : notes()) {
            String text;
            try {
                text = n.size() > 2_000_000 ? "" : read(n.path());
            } catch (IOException e) {
                continue;
            }
            String lower = (n.title() + "\n" + text).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) all &= lower.contains(w);
            if (!all) continue;
            String[] lines = text.split("\n");
            int best = -1;
            for (int i = 0; i < lines.length && best < 0; i++) {
                if (lines[i].toLowerCase(Locale.ROOT).contains(words[0])) best = i;
            }
            hits.add(new Hit(n, Math.max(best, 0), best < 0 ? n.snippet() : lines[best].trim()));
        }
        hits.sort(Comparator.comparing((Hit h) -> !h.note().title().toLowerCase(Locale.ROOT).contains(words[0])));
        return hits;
    }

    /** The note a person most likely means by {@code name}: its title or its file name. */
    public Note find(String name) {
        Note best = null;
        int bestScore = 0;
        for (Note n : notes()) {
            if (n.title().equalsIgnoreCase(name) || n.name().equalsIgnoreCase(name)) return n;
            int s = Math.max(Fuzzy.score(name, n.title()), Fuzzy.score(name, n.name()));
            if (s > bestScore) {
                bestScore = s;
                best = n;
            }
        }
        return best;
    }
}
