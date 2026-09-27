package dev.suven.jungeynotepad.notes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesTest {

    // ---------- Markdown ----------

    @Test
    void blocksOfEveryKindWithTheirLines() {
        List<Markdown.Block> b = Markdown.parse("""
                # Plans
                Some words
                that carry on.

                - [ ] call the bank
                  - [x] find the number
                1. first
                > quoted
                ---
                ```
                code # not a heading
                ```""");
        assertEquals(Markdown.Kind.HEADING, b.get(0).kind());
        assertEquals(1, b.get(0).level());
        assertEquals(Markdown.Kind.PARAGRAPH, b.get(1).kind());
        assertEquals("Some words\nthat carry on.", b.get(1).raw());
        assertEquals(Markdown.Kind.BLANK, b.get(2).kind());
        assertEquals(Markdown.Kind.TASK, b.get(3).kind());
        assertEquals(4, b.get(3).line());
        assertFalse(b.get(3).done());
        assertTrue(b.get(4).done());
        assertEquals(1, b.get(4).level());
        assertEquals(Markdown.Kind.NUMBERED, b.get(5).kind());
        assertEquals("1", b.get(5).marker());
        assertEquals(Markdown.Kind.QUOTE, b.get(6).kind());
        assertEquals(Markdown.Kind.RULE, b.get(7).kind());
        assertEquals(Markdown.Kind.CODE, b.get(8).kind());
        assertEquals("code # not a heading", b.get(8).raw());
    }

    @Test
    void marksInsideALine() {
        List<Markdown.Span> s = Markdown.inline("**bold** and *it* with `code`, [a link](https://x.org) #tag");
        assertTrue(s.get(0).bold());
        assertEquals("bold", s.get(0).text());
        assertTrue(s.get(2).italic());
        assertTrue(s.get(4).code());
        assertEquals("https://x.org", s.get(6).link());
        assertEquals("a link", s.get(6).text());
        assertTrue(s.get(8).tag());
        assertEquals("bold and it", Markdown.plain("**bold** and *it*"));
    }

    @Test
    void aNoteIsCalledByItsFirstHeadingOrElseItsFirstWords() {
        assertEquals("Shopping", Markdown.title("\n# Shopping\n- eggs"));
        assertEquals("eggs", Markdown.title("- [ ] eggs\n- milk"));
        assertEquals("", Markdown.title("\n\n"));
    }

    @Test
    void htmlIsEscapedAndListsClosed() {
        String html = Markdown.toHtml("A <b>", "- one\n- [x] two\n\npara <script>");
        assertTrue(html.contains("<title>A &lt;b&gt;</title>"));
        assertTrue(html.contains("<ul>\n<li>one</li>"));
        assertTrue(html.contains("checked"));
        assertTrue(html.contains("</ul>\n<p>para &lt;script&gt;</p>"));
    }

    // ---------- SmartEdit ----------

    @Test
    void enterCarriesListsOnAndEndsThemWhenEmpty() {
        assertEquals("- ", SmartEdit.onEnter("- eggs").prefix());
        assertEquals("  - [ ] ", SmartEdit.onEnter("  - [x] done thing").prefix());
        assertEquals("10. ", SmartEdit.onEnter("9. nine").prefix());
        assertEquals("> ", SmartEdit.onEnter("> said").prefix());
        assertEquals("    ", SmartEdit.onEnter("    code").prefix());
        assertTrue(SmartEdit.onEnter("- ").endList());
        assertTrue(SmartEdit.onEnter("- [ ] ").endList());
    }

    @Test
    void tasksTickAndLinesBecomeTasks() {
        assertEquals("- [x] milk", SmartEdit.toggleTask("- [ ] milk"));
        assertEquals("- [ ] milk", SmartEdit.toggleTask("- [X] milk"));
        assertEquals("  - [ ] milk", SmartEdit.toggleTask("  * milk"));
        assertEquals("- [ ] milk", SmartEdit.toggleTask("3. milk"));
        assertEquals("- [ ] milk", SmartEdit.toggleTask("milk"));
    }

    @Test
    void headingsCycleAndMarksWrapAndUnwrap() {
        assertEquals("# Title", SmartEdit.cycleHeading("Title"));
        assertEquals("## Title", SmartEdit.cycleHeading("# Title"));
        assertEquals("Title", SmartEdit.cycleHeading("###### Title"));
        assertEquals("**word**", SmartEdit.wrap("word", "**"));
        assertEquals("word", SmartEdit.wrap("**word**", "**"));
    }

    // ---------- Fuzzy and counts ----------

    @Test
    void aFewLettersFindTheName() {
        assertTrue(Fuzzy.score("mtg", "Meeting notes") > 0);
        assertEquals(0, Fuzzy.score("xyz", "Meeting notes"));
        assertTrue(Fuzzy.score("meet", "Meeting notes") > Fuzzy.score("meet", "Some team meeting summary"));
    }

    @Test
    void wordsLinesAndTasksAreCounted() {
        TextStats s = TextStats.of("# Hi there\n- [ ] one\n- [x] two\ndon't stop");
        assertEquals(7, s.words());
        assertEquals(4, s.lines());
        assertEquals(2, s.tasks());
        assertEquals(1, s.tasksDone());
        assertEquals(1, s.readingMinutes());
    }

    // ---------- Library ----------

    @Test
    void notesAreDescribedNewestFirstAndTheTrashIsLeftOut(@TempDir Path dir) throws IOException {
        Library lib = new Library(dir);
        Path a = lib.create("Shopping", "# Shopping\n\n- eggs #food\n- [ ] milk\n");
        Path b = lib.create("Ideas", "# Ideas\n\nA #plan to `#notatag` do things\n");
        Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        lib.trash(lib.create("Old", "# Old\n"));
        Files.writeString(dir.resolve("picture.png"), "not a note");

        List<Library.Note> notes = lib.notes();
        assertEquals(List.of(b, a), notes.stream().map(Library.Note::path).toList());
        Library.Note shopping = notes.get(1);
        assertEquals("Shopping", shopping.title());
        assertEquals("eggs #food  milk", shopping.snippet());
        assertEquals(Set.of("food"), shopping.tags());
        assertEquals(1, shopping.tasks());
        assertEquals(Set.of("plan"), notes.get(0).tags());
    }

    @Test
    void namesAreSafeAndNeverOverwritten(@TempDir Path dir) throws IOException {
        Library lib = new Library(dir);
        assertEquals("a b c", Library.fileName("a/b:c"));
        assertEquals("Untitled", Library.fileName("..."));
        Path one = lib.create("Plans", "one");
        Path two = lib.create("Plans", "two");
        assertEquals("Plans.md", one.getFileName().toString());
        assertEquals("Plans 2.md", two.getFileName().toString());
        assertEquals("one", Library.read(one));
        assertThrows(IOException.class, () -> lib.rename(two, "Plans"));
        assertEquals("Later.md", lib.rename(two, "Later").getFileName().toString());
    }

    @Test
    void readingDropsTheByteOrderMarkAndWindowsLineEnds(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("win.txt");
        Files.writeString(f, "\uFEFFone\r\ntwo\r\n");
        assertEquals("one\ntwo\n", Library.read(f));
    }

    @Test
    void searchNeedsEveryWordAndPutsTitlesFirst(@TempDir Path dir) throws IOException {
        Library lib = new Library(dir);
        lib.create("Recipes", "# Recipes\n\nPancakes need milk and eggs\n");
        lib.create("Milk", "# Milk\n\nbuy eggs too\n");
        lib.create("Other", "# Other\n\nmilk only\n");
        List<Library.Hit> hits = lib.search("milk eggs");
        assertEquals(List.of("Milk", "Recipes"), hits.stream().map(h -> h.note().title()).toList());
        assertEquals(2, hits.get(1).line());
    }

    @Test
    void todaysPageIsMadeOnceWithItsDate(@TempDir Path dir) throws IOException {
        Library lib = new Library(dir);
        Path p = lib.daily(LocalDate.of(2026, 9, 27));
        assertEquals(dir.resolve("Journal").resolve("2026-09-27.md"), p);
        assertEquals("# Sunday, 27 September 2026\n\n", Library.read(p));
        Library.write(p, "changed");
        lib.daily(LocalDate.of(2026, 9, 27));
        assertEquals("changed", Library.read(p));
    }

    // ---------- History ----------

    @Test
    void versionsAreKeptOnlyWhenChangedAndFollowARename(@TempDir Path dir) throws IOException {
        History history = new History(dir.resolve("history"));
        Path note = dir.resolve("a.md");
        assertTrue(history.keep(note, "first", true));
        assertFalse(history.keep(note, "first", true));
        assertFalse(history.keep(note, "second", false));
        assertTrue(history.keep(note, "second", true));
        assertFalse(history.keep(note, "   ", true));
        assertEquals(2, history.versions(note).size());
        assertEquals("second", Library.read(history.versions(note).getFirst().file()));

        Path moved = dir.resolve("b.md");
        history.moved(note, moved);
        assertEquals(0, history.versions(note).size());
        assertEquals(2, history.versions(moved).size());
    }
}
