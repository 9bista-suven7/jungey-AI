package dev.suven.jungeynotepad;

import dev.suven.jungeynotepad.notes.History;
import dev.suven.jungeynotepad.notes.Library;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    @TempDir
    Path dir;

    private Library library;
    private History history;
    private int exit;

    @BeforeEach
    void setUp() {
        library = new Library(dir.resolve("notes"));
        history = new History(dir.resolve("history"));
    }

    private String run(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        exit = Cli.run(args, new PrintStream(bytes, true, StandardCharsets.UTF_8), library, history);
        return bytes.toString(StandardCharsets.UTF_8).strip();
    }

    /** What Jungey would say: the message of the --json answer. */
    private String say(String... args) {
        String[] withJson = new String[args.length + 1];
        withJson[0] = "--json";
        System.arraycopy(args, 0, withJson, 1, args.length);
        return run(withJson).replaceAll("^\\{\"ok\":(true|false),\"message\":\"((?:[^\"\\\\]|\\\\.)*)\".*$", "$2");
    }

    private Path note(String name, String text, long age) throws IOException {
        Path p = library.root().resolve(name + ".md");
        Library.write(p, text);
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - age * 60_000));
        return p;
    }

    @Test
    void commandsAreToldFromFiles() {
        assertTrue(Cli.isCommand(new String[]{"list"}));
        assertTrue(Cli.isCommand(new String[]{"--json", "add", "x", "y"}));
        assertTrue(Cli.isCommand(new String[]{"--help"}));
        assertFalse(Cli.isCommand(new String[]{}));
        assertFalse(Cli.isCommand(new String[]{"--window"}));
        assertFalse(Cli.isCommand(new String[]{"/home/me/todo.md"}));
        assertFalse(Cli.isCommand(new String[]{"--window", "notes.txt"}));
    }

    @Test
    void theAnswerIsJsonWithASpokenMessage() throws IOException {
        note("Shopping", "# Shopping\n\n- eggs\n- milk\n", 5);
        String out = run("--json", "read", "shopping");
        assertEquals(0, exit);
        assertTrue(out.startsWith("{\"ok\":true,\"message\":\"Shopping: eggs and milk.\",\"data\":{\"title\":\"Shopping\""), out);
        assertTrue(out.contains("\"text\":\"# Shopping\\n\\n- eggs\\n- milk\\n\""), out);

        out = run("--json", "read", "nothing like it");
        assertEquals(1, exit);
        assertEquals("{\"ok\":false,\"message\":\"There is no note called nothing like it.\",\"problem\":\"not_found\"}", out);

        out = run("--json", "fly");
        assertTrue(out.contains("\"problem\":\"usage\""), out);
    }

    @Test
    void aTerminalGetsTheNoteItself() throws IOException {
        note("Shopping", "# Shopping\n\n- eggs\n", 5);
        assertEquals("# Shopping\n\n- eggs", run("read", "Shopping"));
    }

    @Test
    void listingSaysTheNewestFirst() throws IOException {
        assertEquals("There are no notes yet.", run("list"));
        note("Old", "# Old thing\n", 60);
        note("New", "# New thing\n", 1);
        note("Middle", "# Middle thing\n", 30);
        assertEquals("You have 3 notes. The latest are New thing and Middle thing.", say("list", "2"));
    }

    @Test
    void addingGoesOnTheListTheNoteEndsWith() throws IOException {
        Path p = note("Shopping list", "# Shopping list\n\n- [ ] eggs\n- [x] milk\n\n", 5);
        assertEquals("Added bread to Shopping list.", run("add", "my shopping list", "bread"));
        assertEquals("# Shopping list\n\n- [ ] eggs\n- [x] milk\n- [ ] bread\n", Library.read(p));
        assertEquals("# Shopping list\n\n- [ ] eggs\n- [x] milk\n\n", Library.read(history.versions(p).getFirst().file()));

        // Part of the title is enough: "shopping" is the Shopping list.
        run("add", "shopping", "jam");
        assertTrue(Library.read(p).endsWith("- [ ] bread\n- [ ] jam\n"));
    }

    @Test
    void addingToANoteThatIsNotThereStartsIt() throws IOException {
        note("Shopping list", "# Shopping list\n", 5);
        assertEquals("Started a note called Gift ideas with a scarf for mum in it.",
                run("add", "the gift ideas note", "a scarf for mum"));
        assertEquals("# Gift ideas\n\n- a scarf for mum\n", Library.read(library.root().resolve("Gift ideas.md")));
    }

    @Test
    void textIsAddedAsTheNextItemOrAsANewList() {
        assertEquals("- one\n", Cli.append("", "one"));
        assertEquals("# T\n\n- one\n", Cli.append("# T\n", "one"));
        assertEquals("Words here.\n\n- one\n", Cli.append("Words here.", "one"));
        assertEquals("1. a\n2. one\n", Cli.append("1. a\n", "one"));
        assertEquals("  - a\n  - one\n", Cli.append("  - a", "one"));
        assertEquals("> said\n\n- one\n", Cli.append("> said\n", "one"));
    }

    @Test
    void newNotesAreCapitalisedWhenSaidInLowerCase() throws IOException {
        assertEquals("Started a note called Book club.", run("new", "book club", "first", "meeting", "friday"));
        assertEquals("# Book club\n\nfirst meeting friday\n", Library.read(library.root().resolve("Book club.md")));
        run("new", "iPhone ideas");
        assertTrue(Files.exists(library.root().resolve("iPhone ideas.md")));
    }

    @Test
    void todaysPageTakesTimedLines() throws IOException {
        assertEquals("Nothing on today's page yet.", say("today"));
        assertEquals("Added to today's page.", run("today", "called", "the", "bank"));
        String page = Library.read(library.daily(LocalDate.now()));
        assertTrue(page.matches("(?s)# .+\n\n- \\d\\d:\\d\\d called the bank\n"), page);
        assertTrue(say("today").matches("Today's page: \\d\\d:\\d\\d called the bank\\."));
    }

    @Test
    void tasksLeftAreGatheredFromEveryNote() throws IOException {
        note("Home", "# Home\n\n- [ ] fix the tap\n- [x] paint\n", 5);
        note("Work", "# Work\n\n- [ ] send the report\n```\n- [ ] not a task\n```\n", 10);
        note("Done", "# Done\n\n- [x] all of it\n", 1);
        assertEquals("2 things left to do: fix the tap and send the report.", say("tasks"));
        assertEquals("One thing left to do in Work: send the report.", say("tasks", "work"));
        assertEquals("Nothing left to do in Done.", run("tasks", "done"));
    }

    @Test
    void searchSaysWhichNotesMentionIt() throws IOException {
        note("Recipes", "# Recipes\n\nPancakes: milk, eggs\n", 5);
        note("Shopping", "# Shopping\n\n- milk\n", 1);
        assertEquals("2 notes mention milk: Shopping and Recipes.", say("search", "milk"));
        assertEquals("No note mentions caviar.", run("search", "caviar"));
    }

    @Test
    void notesAreFoundByTitleThenFileNameThenWords() throws IOException {
        note("2026-09-01", "# Trip to Rome\n", 1);
        note("Rome", "# Roman history\n", 5);
        Cli cli = new Cli(library, history);
        assertEquals("Trip to Rome", cli.resolve("trip to rome", false).title());
        assertEquals("Roman history", cli.resolve("rome", false).title());
        assertEquals("Trip to Rome", cli.resolve("2026-09-01", false).title());
        assertEquals("Trip to Rome", cli.resolve("trip", false).title());
        assertNull(cli.resolve("paris", false));
        assertTrue(Cli.hasWords("Shopping list", "shop list"));
        assertFalse(Cli.hasWords("Shopping list", "list of shops"));
        assertEquals("shopping list", Cli.bare("My shopping list note"));
    }

    @Test
    void notesAreReadAloudWithoutTheirMarks() {
        assertEquals("Call Sam first. Then: a, b (done) and c. After that, rest.",
                Cli.spoken("# Plan\n\nCall **Sam** first\n\nThen:\n- a\n- [x] b\n1. c\n\n```\ncode\n```\nAfter that, rest.", "Plan"));
        assertEquals("", Cli.spoken("# Only a title\n", "Only a title"));
        assertEquals("Near the Pantheon. Packing: passport (done) and plug. Notes. Words.",
                Cli.spoken("# Trip\n\nNear the Pantheon. #travel\n\n## Packing\n\n- [x] passport\n- [ ] plug #shop\n\n"
                        + "## Notes\n\nWords", "Trip"));
        String long_ = Cli.spoken("# T\n\n" + "word ".repeat(400), "T");
        assertTrue(long_.length() < 720 && long_.endsWith("… and more."), long_);
    }

    @Test
    void jsonIsEscaped() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", "say \"hi\"\n\ttab \\ \u0001");
        map.put("b", Arrays.asList(1, true, null));
        assertEquals("{\"a\":\"say \\\"hi\\\"\\n\\ttab \\\\ \\u0001\",\"b\":[1,true,null]}", Json.write(map));
    }

    @Test
    void handOffPathsAreAbsoluteLines() {
        assertEquals(List.of(Path.of("/a/b.md"), Path.of("/c d.md")), Instance.paths("/a/b.md\n/c d.md\n\nrelative.md\n"));
    }
}
