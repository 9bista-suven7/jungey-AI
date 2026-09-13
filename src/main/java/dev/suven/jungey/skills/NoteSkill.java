package dev.suven.jungey.skills;

import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Notes and a todo list, kept as plain markdown.
 *
 * <p>Writing to a file rather than driving a text editor is the whole point: typing into
 * someone else's window depends on focus and timing and fails silently, where a file is
 * greppable, syncable and survives Jungey not running.
 */
public class NoteSkill implements Skill {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public String name() {
        return "notes";
    }

    @Override
    public String description() {
        return "Keeps notes and a todo list.";
    }

    @Override
    public String[] examples() {
        return new String[]{"note: buy milk", "add to my todo call the bank", "what is on my todo"};
    }

    @Override
    public int priority() {
        return 18;
    }

    @Override
    public boolean matches(String input) {
        return action(input.trim()) != null;
    }

    private static String action(String s) {
        if (s.matches("^(add (a )?)?(to my )?todo:?\\s+.+") || s.matches("^add .+ to my todo$")) return "todo-add";
        if (s.matches(".*\\b(what'?s?|what is|show|read|list)\\b.*\\btodos?\\b.*") || s.equals("todo")) return "todo-list";
        if (s.matches("^(take a )?note:?\\s+.+") || s.matches("^(make|write) a note:?\\s+.+")) return "note-add";
        if (s.matches(".*\\b(what'?s?|what is|show|read|list)\\b.*\\bnotes?\\b.*") || s.equals("notes")) return "note-list";
        if (s.matches(".*\\bopen\\b.*\\b(my )?(notes|todo)\\b.*")) return "open";
        return null;
    }

    @Override
    public SkillResult run(String input) throws Exception {
        String s = input.trim();
        String action = action(s.toLowerCase());
        if (action == null) return SkillResult.error("I did not catch that.");

        return switch (action) {
            case "todo-add" -> add(file("todo.md"), body(s), "todo");
            case "note-add" -> add(file("notes.md"), body(s), "note");
            case "todo-list" -> list(file("todo.md"), "on your todo list");
            case "note-list" -> list(file("notes.md"), "in your notes");
            case "open" -> open(s.toLowerCase().contains("todo") ? file("todo.md") : file("notes.md"));
            default -> SkillResult.error("I did not catch that.");
        };
    }

    /** Strip the command wrapper, leaving just the thing to remember. */
    private static String body(String s) {
        String text = s.replaceFirst("(?i)^(take a |make a |write a )?(note|todo)s?:?\\s*", "")
                .replaceFirst("(?i)^add (a )?(to my )?(note|todo)s?:?\\s*", "")
                .replaceFirst("(?i)^(to my )?(note|todo)s?:?\\s*", "")
                .replaceFirst("(?i)\\s+to my todo$", "")
                .trim();
        return text;
    }

    private SkillResult add(Path file, String text, String kind) throws IOException {
        if (text.isBlank()) return SkillResult.error("What should I write down?");

        String line = kind.equals("todo")
                ? "- [ ] " + text + "  _(" + LocalDateTime.now().format(WHEN) + ")_\n"
                : "- " + text + "  _(" + LocalDateTime.now().format(WHEN) + ")_\n";

        Files.createDirectories(file.getParent());
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        return SkillResult.of(Personality.affirm() + " Added to your " + kind + ".");
    }

    private SkillResult list(Path file, String where) throws IOException {
        if (!Files.exists(file)) {
            return SkillResult.of("Nothing " + where + " yet.");
        }

        List<String> lines = Files.readAllLines(file).stream()
                .filter(l -> !l.isBlank())
                .toList();
        if (lines.isEmpty()) {
            return SkillResult.of("Nothing " + where + " yet.");
        }

        // Only the tail is worth reading aloud; the file keeps everything.
        List<String> recent = lines.size() > 12 ? lines.subList(lines.size() - 12, lines.size()) : lines;
        String detail = String.join("\n", recent).replaceAll("\\s+_\\(.*?\\)_", "");

        return SkillResult.of(lines.size() + " item" + (lines.size() == 1 ? "" : "s") + " " + where + ".",
                detail);
    }

    private SkillResult open(Path file) throws IOException {
        if (!Files.exists(file)) return SkillResult.error("There is nothing written down yet.");
        new ProcessBuilder("xdg-open", file.toString()).start();
        return SkillResult.of("Opening it.");
    }

    private static Path file(String name) {
        return Path.of(System.getProperty("user.home"), "Documents", "Jungey", name);
    }
}
