package dev.suven.jungeynotepad;

import dev.suven.jungeynotepad.notes.History;
import dev.suven.jungeynotepad.notes.Library;
import dev.suven.jungeynotepad.notes.Library.Note;
import dev.suven.jungeynotepad.notes.Markdown;
import dev.suven.jungeynotepad.notes.Markdown.Block;
import dev.suven.jungeynotepad.notes.Markdown.Span;
import dev.suven.jungeynotepad.notes.SmartEdit;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * jungey-notepad as a command: do one thing with the notes and say how it went. This is how
 * Jungey reads and writes them - with --json it gets {"ok":..., "message":...} back, the
 * message worded to be spoken. Without it, a person at a terminal gets the notes themselves.
 */
public final class Cli {

    static final String HELP = """
            Jungey Notepad - plain Markdown notes, kept in ~/Documents/Jungey.

              jungey-notepad                      open the window
              jungey-notepad FILE...              open these files in it
              jungey-notepad list [N]             the latest notes, 10 if not given
              jungey-notepad read NOTE            what a note says
              jungey-notepad search WORDS         the notes with all these words in them
              jungey-notepad new TITLE [TEXT]     start a note
              jungey-notepad add NOTE TEXT        add a line to a note; started if there is none
              jungey-notepad today [TEXT]         today's journal page; with TEXT, add it there
              jungey-notepad tasks [NOTE]         what is not ticked yet, in every note or one
              jungey-notepad open [NOTE]          show a note in the window
              jungey-notepad folder               where the notes are

            NOTE is a note's title or file name, or enough of it to tell which.
            Options: --json for a JSON reply, its message worded to be spoken.
            """;

    private static final Set<String> COMMANDS = Set.of("help", "list", "read", "show", "search", "find", "new",
            "add", "today", "journal", "tasks", "open", "folder");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final int SPOKEN_NAMES = 5;
    private static final int SPOKEN_TASKS = 8;
    private static final int SPOKEN_LENGTH = 700;

    /**
     * A finished command: whether it worked, what to say, what went wrong, anything worth
     * listing, and - for a terminal - the fuller text to print in place of the message.
     */
    record Outcome(boolean ok, String message, String problem, Object data, String text) {
        static Outcome ok(String message) {
            return new Outcome(true, message, null, null, null);
        }

        static Outcome ok(String message, Object data, String text) {
            return new Outcome(true, message, null, data, text);
        }

        static Outcome fail(String problem, String message) {
            return new Outcome(false, message, problem, null, null);
        }
    }

    private final Library library;
    private final History history;

    Cli(Library library, History history) {
        this.library = library;
        this.history = history;
    }

    /** True when the arguments are a command rather than files for the window. */
    public static boolean isCommand(String[] args) {
        for (String a : args) {
            if (a.equals("--json") || a.equals("--help") || a.equals("-h")) return true;
        }
        for (String a : args) {
            if (!a.startsWith("--")) return COMMANDS.contains(a.toLowerCase(Locale.ROOT));
        }
        return false;
    }

    public static int run(String[] argv, PrintStream out) {
        return run(argv, out, Library.standard(), History.standard());
    }

    static int run(String[] argv, PrintStream out, Library library, History history) {
        List<String> args = new ArrayList<>(Arrays.asList(argv));
        boolean json = args.remove("--json");
        Outcome outcome;
        try {
            outcome = new Cli(library, history).dispatch(args);
        } catch (IllegalArgumentException e) {
            outcome = Outcome.fail("usage", e.getMessage());
        } catch (IOException | UncheckedIOException e) {
            outcome = Outcome.fail("io", "I could not do that to the notes: " + e.getMessage());
        }

        if (json) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("ok", outcome.ok());
            node.put("message", outcome.message());
            if (outcome.problem() != null) node.put("problem", outcome.problem());
            if (outcome.data() != null) node.put("data", outcome.data());
            out.println(Json.write(node));
        } else {
            out.println(outcome.text() != null ? outcome.text() : outcome.message());
        }
        return outcome.ok() ? 0 : 1;
    }

    Outcome dispatch(List<String> args) throws IOException {
        if (args.isEmpty() || args.getFirst().equals("help") || args.getFirst().equals("--help")
                || args.getFirst().equals("-h")) {
            return Outcome.ok(HELP.strip());
        }
        String command = args.getFirst().toLowerCase(Locale.ROOT);
        List<String> rest = args.subList(1, args.size());
        String words = String.join(" ", rest).trim();

        return switch (command) {
            case "list" -> list(count(rest));
            case "read", "show" -> read(need(words, "Read which note?"));
            case "search", "find" -> search(need(words, "Search for what?"));
            case "new" -> create(rest);
            case "add" -> add(rest);
            case "today", "journal" -> today(words);
            case "tasks" -> tasks(words.isEmpty() ? null : words);
            case "open" -> open(words.isEmpty() ? null : words);
            case "folder" -> Outcome.ok("Your notes are in " + tilde(library.root()) + ".",
                    map("path", library.root().toString()), library.root().toString());
            default -> throw new IllegalArgumentException("I don't know \"" + command + "\". Try jungey-notepad help.");
        };
    }

    // ---------- reading ----------

    private Outcome list(int n) {
        List<Note> notes = library.notes();
        if (notes.isEmpty()) return Outcome.ok("There are no notes yet.", List.of(), null);
        List<Note> shown = notes.subList(0, Math.min(n, notes.size()));
        List<Object> data = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Note note : shown) {
            data.add(describe(note));
            text.append(note.modified().atZone(ZoneId.systemDefault()).format(WHEN)).append("   ")
                    .append(note.title()).append("   ").append(library.root().relativize(note.path())).append('\n');
        }
        String names = and(shown.stream().limit(SPOKEN_NAMES).map(Note::title).toList());
        String message = notes.size() == 1 ? "You have one note, " + names + "."
                : "You have " + notes.size() + " notes. The latest " + (shown.size() == 1 ? "is " : "are ") + names + ".";
        return Outcome.ok(message, data, text.toString().stripTrailing());
    }

    private Outcome read(String name) throws IOException {
        Note note = resolve(name, true);
        if (note == null) return notFound(name);
        String text = Library.read(note.path());
        String spoken = spoken(text, note.title());
        Map<String, Object> data = describe(note);
        data.put("text", text);
        return Outcome.ok(spoken.isEmpty() ? note.title() + " has nothing in it yet." : note.title() + ": " + spoken,
                data, text.stripTrailing());
    }

    private Outcome search(String words) {
        List<Library.Hit> hits = library.search(words);
        if (hits.isEmpty()) return Outcome.ok("No note mentions " + words + ".", List.of(), null);
        List<Object> data = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Library.Hit h : hits) {
            Map<String, Object> d = describe(h.note());
            d.put("line", h.line() + 1);
            d.put("match", h.text());
            data.add(d);
            text.append(h.note().title()).append("   ").append(h.line() + 1).append(": ").append(h.text()).append('\n');
        }
        String names = and(hits.stream().limit(SPOKEN_NAMES).map(h -> h.note().title()).toList());
        String message = hits.size() == 1 ? names + " mentions " + words + "."
                : hits.size() + " notes mention " + words + ": " + names + (hits.size() > SPOKEN_NAMES ? ", and more." : ".");
        return Outcome.ok(message, data, text.toString().stripTrailing());
    }

    private Outcome tasks(String name) throws IOException {
        List<Note> scope;
        if (name == null) {
            scope = library.notes();
        } else {
            Note note = resolve(name, true);
            if (note == null) return notFound(name);
            scope = List.of(note);
        }
        List<Object> data = new ArrayList<>();
        List<String> spoken = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Note note : scope) {
            if (note.tasks() == note.tasksDone() || note.size() > 2_000_000) continue;
            boolean headed = false;
            for (Block b : Markdown.parse(Library.read(note.path()))) {
                if (b.kind() != Markdown.Kind.TASK || b.done()) continue;
                String task = plain(b.spans());
                if (!headed) {
                    text.append(note.title()).append('\n');
                    headed = true;
                }
                text.append("  - [ ] ").append(task).append('\n');
                String said = said(b.spans());
                spoken.add(said.isEmpty() ? task : said);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("note", note.title());
                d.put("path", note.path().toString());
                d.put("line", b.line() + 1);
                d.put("task", task);
                data.add(d);
            }
        }
        String where = name == null ? "" : " in " + scope.getFirst().title();
        if (spoken.isEmpty()) return Outcome.ok("Nothing left to do" + where + ".", data, null);
        String list = spoken.size() > SPOKEN_TASKS
                ? String.join(", ", spoken.subList(0, SPOKEN_TASKS)) + ", and " + (spoken.size() - SPOKEN_TASKS) + " more"
                : and(spoken);
        String message = (spoken.size() == 1 ? "One thing left to do" : spoken.size() + " things left to do")
                + where + ": " + list + ".";
        return Outcome.ok(message, data, text.toString().stripTrailing());
    }

    // ---------- writing ----------

    private Outcome create(List<String> rest) throws IOException {
        String title = rest.isEmpty() ? "" : capitalised(rest.getFirst().trim());
        if (title.isEmpty()) throw new IllegalArgumentException("Start a note called what?");
        String body = String.join(" ", rest.subList(1, rest.size())).trim();
        Path p = library.create(title, "# " + title + "\n\n" + (body.isEmpty() ? "" : body + "\n"));
        return Outcome.ok("Started a note called " + title + ".", map("title", title, "path", p.toString()), null);
    }

    private Outcome add(List<String> rest) throws IOException {
        if (rest.size() < 2) throw new IllegalArgumentException("Add what, and to which note?");
        String name = rest.getFirst().trim();
        String item = String.join(" ", rest.subList(1, rest.size())).trim();
        if (name.isEmpty() || item.isEmpty()) throw new IllegalArgumentException("Add what, and to which note?");

        // Writing to the wrong note is worse than starting a new one, so no guessing here.
        Note note = resolve(name, false);
        if (note == null) {
            String title = capitalised(bare(name));
            Path p = library.create(title, "# " + title + "\n\n- " + item + "\n");
            return Outcome.ok("Started a note called " + title + " with " + item + " in it.",
                    map("title", title, "path", p.toString(), "created", true), null);
        }
        appendTo(note.path(), item);
        return Outcome.ok("Added " + item + " to " + note.title() + ".",
                map("title", note.title(), "path", note.path().toString(), "created", false), null);
    }

    private Outcome today(String text) throws IOException {
        Path page = library.daily(LocalDate.now());
        if (text.isEmpty()) {
            String now = Library.read(page);
            String title = Markdown.title(now);
            String spoken = spoken(now, title);
            return Outcome.ok(spoken.isEmpty() ? "Nothing on today's page yet." : "Today's page: " + spoken,
                    map("path", page.toString(), "text", now), now.stripTrailing());
        }
        appendTo(page, LocalTime.now().format(CLOCK) + " " + text);
        return Outcome.ok("Added to today's page.", map("path", page.toString()), null);
    }

    /** Adds {@code item} to the end of a note, keeping what it said before in its history. */
    private void appendTo(Path file, String item) throws IOException {
        String before = Library.read(file);
        try {
            history.keep(file, before, true);
        } catch (IOException ignored) {
            // The note matters more than its copy; the new line goes in regardless.
        }
        Library.write(file, append(before, item));
    }

    /**
     * {@code text} with {@code item} added as the next item of the list it ends with - the
     * same bullet, an empty box after a task, the next number - or a new list if it does not
     * end with one.
     */
    static String append(String text, String item) {
        String body = text.stripTrailing();
        if (body.isEmpty()) return "- " + item + "\n";
        String last = body.substring(body.lastIndexOf('\n') + 1);
        SmartEdit.Enter next = SmartEdit.onEnter(last);
        String prefix = next.prefix();
        boolean list = !prefix.isBlank() && !prefix.trim().startsWith(">");
        return list ? body + "\n" + prefix + item + "\n" : body + "\n\n- " + item + "\n";
    }

    // ---------- the window ----------

    private Outcome open(String name) throws IOException {
        Path path = null;
        String title = null;
        if (name != null && name.toLowerCase(Locale.ROOT).matches("today|today'?s page|(my |the )?journal")) {
            path = library.daily(LocalDate.now());
            title = "today's page";
        } else if (name != null) {
            Note note = resolve(name, true);
            if (note == null) return notFound(name);
            path = note.path();
            title = note.title();
        }
        List<Path> files = path == null ? List.of() : List.of(path);
        if (!Instance.handOff(files)) startWindow(files);
        return Outcome.ok(title == null ? "Opening Jungey Notepad." : "Opening " + title + ".",
                path == null ? null : map("path", path.toString()), null);
    }

    /**
     * A window of its own, which lives on after this command has answered: the same Java
     * and class path as this one, in a session of its own so closing the terminal that
     * asked does not close the window.
     */
    private static void startWindow(List<Path> files) throws IOException {
        List<String> cmd = new ArrayList<>();
        if (Files.isExecutable(Path.of("/usr/bin/setsid"))) cmd.add("/usr/bin/setsid");
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), Launcher.class.getName(), "--window"));
        for (Path f : files) cmd.add(f.toString());
        new ProcessBuilder(cmd)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    // ---------- finding a note by name ----------

    /**
     * The note meant by {@code name}: its title or file name exactly, else the newest whose
     * title has all its words, else - only when {@code loose}, for reading - the closest.
     */
    Note resolve(String name, boolean loose) {
        List<Note> notes = library.notes();
        List<String> wants = new ArrayList<>(List.of(name.trim()));
        String bare = bare(name);
        if (!bare.equalsIgnoreCase(name.trim()) && !bare.isEmpty()) wants.add(bare);

        for (String want : wants) {
            for (Note n : notes) {
                if (n.title().equalsIgnoreCase(want) || n.name().equalsIgnoreCase(want)) return n;
            }
        }
        for (String want : wants) {
            for (Note n : notes) {
                if (hasWords(n.title(), want) || hasWords(n.name(), want)) return n;
            }
        }
        return loose ? library.find(wants.getLast()) : null;
    }

    /** Every word of {@code words} starts a word of {@code title}: "shop" is in "Shopping list". */
    static boolean hasWords(String title, String words) {
        List<String> have = Arrays.asList(title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"));
        for (String w : words.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.isEmpty()) continue;
            if (have.stream().noneMatch(h -> h.startsWith(w))) return false;
        }
        return !words.isBlank();
    }

    /** "my shopping list note" as the name it means: "shopping list". */
    static String bare(String name) {
        return name.trim().replaceFirst("(?i)^(my|the|a|an)\\s+", "").replaceFirst("(?i)\\s+note$", "").trim();
    }

    private static Outcome notFound(String name) {
        return Outcome.fail("not_found", "There is no note called " + name + ".");
    }

    // ---------- saying it ----------

    /**
     * A note as it would be read aloud: its sentences, its lists as "a, b and c" - after
     * their heading, "Packing: a, b and c." - and no marks, tags, code or title, the title
     * being said before it.
     */
    static String spoken(String text, String title) {
        List<String> parts = new ArrayList<>();
        List<String> items = new ArrayList<>();
        String heading = null;
        boolean first = true;
        for (Block b : Markdown.parse(text)) {
            if (b.kind() == Markdown.Kind.BLANK) continue;
            String words = said(b.spans());
            if (first) {
                first = false;
                if (words.equalsIgnoreCase(title) || plain(b.spans()).equalsIgnoreCase(title)) continue;
            }
            switch (b.kind()) {
                case BULLET, NUMBERED -> items.add(words.replaceAll("[.;]$", ""));
                case TASK -> items.add(words.replaceAll("[.;]$", "") + (b.done() ? " (done)" : ""));
                case CODE, RULE -> heading = flush(heading, items, parts);
                case HEADING -> {
                    heading = flush(heading, items, parts);
                    heading = words.isEmpty() ? null : words.replaceAll("[.:]$", "");
                }
                default -> {
                    heading = flush(heading, items, parts);
                    if (!words.isEmpty()) parts.add(sentence(words));
                }
            }
        }
        flush(heading, items, parts);
        String all = String.join(" ", parts);
        if (all.length() <= SPOKEN_LENGTH) return all;
        int cut = all.lastIndexOf(' ', SPOKEN_LENGTH);
        return all.substring(0, cut > 0 ? cut : SPOKEN_LENGTH).replaceAll("[,;:]$", "") + "… and more.";
    }

    /** Says what is waiting - a heading, the list after it, or both as one - and forgets the heading. */
    private static String flush(String heading, List<String> items, List<String> parts) {
        items.removeIf(String::isBlank);
        if (!items.isEmpty()) parts.add(sentence((heading == null ? "" : heading + ": ") + and(items)));
        else if (heading != null) parts.add(sentence(heading));
        items.clear();
        return null;
    }

    private static String sentence(String s) {
        return s.matches(".*[.!?:…]$") ? s : s + ".";
    }

    private static String plain(List<Span> spans) {
        StringBuilder b = new StringBuilder();
        for (Span s : spans) b.append(s.text());
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    /** A line's words as said: without its #tags, which are for finding a note, not reading it. */
    private static String said(List<Span> spans) {
        StringBuilder b = new StringBuilder();
        for (Span s : spans) if (!s.tag()) b.append(s.text());
        return b.toString().replaceAll("\\s+", " ").replaceAll(" ([.,;:!?])", "$1").trim();
    }

    /** "a", "a and b", "a, b and c". */
    static String and(List<String> words) {
        if (words.isEmpty()) return "";
        if (words.size() == 1) return words.getFirst();
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
    }

    // ---------- small parts ----------

    private static Map<String, Object> describe(Note n) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("title", n.title());
        d.put("path", n.path().toString());
        d.put("modified", n.modified().toString());
        d.put("tags", List.copyOf(n.tags()));
        d.put("tasks", n.tasks());
        d.put("done", n.tasksDone());
        return d;
    }

    /** A map that keeps its keys in the order given, so answers read the same every time. */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    /** A title said all in lower case, as speech arrives, with a capital to start it. */
    static String capitalised(String title) {
        if (title.isEmpty() || !title.equals(title.toLowerCase(Locale.ROOT))) return title;
        return title.substring(0, 1).toUpperCase(Locale.ROOT) + title.substring(1);
    }

    private static String need(String words, String question) {
        if (words.isEmpty()) throw new IllegalArgumentException(question);
        return words;
    }

    private static int count(List<String> rest) {
        if (rest.isEmpty()) return 10;
        try {
            return Math.max(1, Math.min(500, Integer.parseInt(rest.getFirst())));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + rest.getFirst() + "\" is not a number.");
        }
    }

    private static String tilde(Path p) {
        String home = System.getProperty("user.home");
        String s = p.toString();
        return s.startsWith(home) ? "~" + s.substring(home.length()) : s;
    }
}
