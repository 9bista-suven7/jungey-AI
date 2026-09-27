package dev.suven.jungeynotepad.ui;

import dev.suven.jungeynotepad.notes.History;
import dev.suven.jungeynotepad.notes.Library;
import dev.suven.jungeynotepad.notes.Markdown;
import dev.suven.jungeynotepad.notes.SmartEdit;
import dev.suven.jungeynotepad.notes.TextStats;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One open note: the editor, its preview beside it, and a find bar under it.
 *
 * <p>Saving is not something to remember: the note is written a moment after typing stops,
 * when the tab is closed and when the window is left, and a version is kept in
 * {@link History} every few minutes, so undo reaches back past any mistake.
 */
final class EditorTab extends Tab {

    enum State { SAVED, EDITING, FAILED }

    private static final Pattern URL = Pattern.compile("^https?://\\S+$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH);

    private final History history;
    private final Consumer<EditorTab> onSaved;

    final CodeArea area = new CodeArea();
    private final Preview preview;
    private final SplitPane split = new SplitPane();
    private final FindBar find = new FindBar();
    private final PauseTransition saveSoon = new PauseTransition(Duration.millis(700));
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.SAVED);
    private final ReadOnlyObjectWrapper<TextStats> stats = new ReadOnlyObjectWrapper<>(TextStats.of(""));

    private Path path;
    private String savedText = "";
    private long savedTime;
    private boolean previewShown;

    EditorTab(Path path, History history, Consumer<String> onLink, Consumer<EditorTab> onSaved) throws IOException {
        this.path = path;
        this.history = history;
        this.onSaved = onSaved;
        this.preview = new Preview(this::toggleTaskAt, this::goToLine, onLink);

        area.getStyleClass().add("editor");
        // CodeArea brings a stylesheet of its own that makes everything monospace and, being
        // the node's own, outranks the window's: without it the theme picks the font.
        area.getStylesheets().removeIf(s -> s.endsWith("/code-area.css"));
        area.setWrapText(true);
        VirtualizedScrollPane<CodeArea> scroller = new VirtualizedScrollPane<>(area);
        scroller.getStyleClass().add("editor-scroll");
        StackPane editorBox = new StackPane(scroller);
        editorBox.getStyleClass().add("editor-box");
        // A comfortable column on a wide window, rather than lines the width of the screen.
        editorBox.widthProperty().addListener((o, a, w) -> {
            double side = Math.max(28, (w.doubleValue() - 820) / 2);
            area.setPadding(new Insets(22, side, 60, side));
        });
        split.getItems().add(editorBox);
        split.getStyleClass().add("editor-split");

        BorderPane content = new BorderPane(split);
        content.setBottom(find);
        setContent(content);

        load();
        watchEdits();
        keys();
        saveSoon.setOnFinished(e -> save());
        setOnClosed(e -> closed());
    }

    // ---------- loading and saving ----------

    private void load() throws IOException {
        String text = Files.exists(path) ? Library.read(path) : "";
        savedText = text;
        savedTime = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0;
        area.replaceText(text);
        area.getUndoManager().forgetHistory();
        area.getUndoManager().mark();
        area.moveTo(0);
        area.requestFollowCaret();
        refresh(text);
        updateTitle();
    }

    private void watchEdits() {
        area.textProperty().addListener((o, a, text) -> {
            state.set(text.equals(savedText) ? State.SAVED : State.EDITING);
            updateTitle();
            saveSoon.playFromStart();
        });
        // Colouring and counting a long note on every key would lag the typing: do it when
        // the typing pauses for a breath.
        area.multiPlainChanges().successionEnds(java.time.Duration.ofMillis(90))
                .subscribe(ignore -> refresh(area.getText()));
    }

    private void refresh(String text) {
        area.setStyleSpans(0, Highlighter.compute(text));
        stats.set(TextStats.of(text));
        if (previewShown) preview.show(text);
    }

    /** Writes the note if it changed. True when it is safely on disk. */
    boolean save() {
        saveSoon.stop();
        String text = area.getText();
        if (text.equals(savedText) && Files.exists(path)) {
            state.set(State.SAVED);
            return true;
        }
        try {
            Library.write(path, text);
            savedText = text;
            savedTime = Files.getLastModifiedTime(path).toMillis();
            history.keep(path, text, false);
            state.set(State.SAVED);
            updateTitle();
            onSaved.accept(this);
            return true;
        } catch (IOException e) {
            state.set(State.FAILED);
            return false;
        }
    }

    private void closed() {
        save();
        try {
            history.keep(path, area.getText(), true);
        } catch (IOException ignored) {
            // The note itself is saved; only this copy of it is missing.
        }
    }

    /**
     * The file changed on disk while open - another editor, a sync, Jungey adding a note.
     * Unchanged here, the new text is taken, keeping the place; changed here too, ours is
     * kept and theirs goes into the history, so neither is lost.
     *
     * @return a message for the person, or null when there was nothing to do
     */
    String checkDisk() {
        try {
            if (!Files.exists(path)) return null;
            long time = Files.getLastModifiedTime(path).toMillis();
            if (time == savedTime) return null;
            String theirs = Library.read(path);
            savedTime = time;
            if (theirs.equals(area.getText())) {
                savedText = theirs;
                return null;
            }
            if (area.getText().equals(savedText)) {
                int caret = Math.min(area.getCaretPosition(), theirs.length());
                savedText = theirs;
                area.replaceText(theirs);
                area.moveTo(caret);
                state.set(State.SAVED);
                return displayName() + " changed on disk - updated.";
            }
            history.keep(path, theirs, true);
            savedText = theirs;
            state.set(State.EDITING);
            saveSoon.playFromStart();
            return displayName() + " also changed on disk. Yours is kept; theirs is in its history.";
        } catch (IOException e) {
            return null;
        }
    }

    // ---------- what it shows ----------

    private void updateTitle() {
        String t = displayName();
        setText((state.get() == State.SAVED ? "" : "● ") + (t.length() > 28 ? t.substring(0, 27) + "…" : t));
        setTooltip(new Tooltip(path.toString()));
    }

    String displayName() {
        String title = Markdown.title(area.getText());
        if (!title.isBlank()) return title;
        String n = path.getFileName().toString();
        return n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
    }

    Path path() {
        return path;
    }

    void moved(Path to) {
        path = to;
        updateTitle();
    }

    ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    ReadOnlyObjectProperty<TextStats> statsProperty() {
        return stats.getReadOnlyProperty();
    }

    boolean previewShown() {
        return previewShown;
    }

    void showPreview(boolean show) {
        if (show == previewShown) return;
        previewShown = show;
        if (show) {
            split.getItems().add(preview);
            split.setDividerPositions(0.5);
            preview.show(area.getText());
        } else {
            split.getItems().remove(preview);
        }
    }

    void setLineNumbers(boolean on) {
        area.setParagraphGraphicFactory(on ? LineNumberFactory.get(area) : null);
    }

    void setWrap(boolean on) {
        area.setWrapText(on);
    }

    void setFontSize(double size) {
        area.setStyle("-fx-font-size: " + size + "px;");
    }

    /** Replaces the whole text in a way undo can take back - restoring an old version. */
    void replaceAll(String text) {
        area.replaceText(text);
        area.moveTo(0);
        area.requestFollowCaret();
    }

    void goToLine(int line) {
        int l = Math.max(0, Math.min(line, area.getParagraphs().size() - 1));
        area.moveTo(l, 0);
        area.showParagraphAtTop(Math.max(0, l - 2));
        area.requestFocus();
    }

    void openFind(boolean replace) {
        find.open(replace, area.getSelectedText());
    }

    /** Headings of this note with their lines, for "go to heading". */
    List<String[]> headings() {
        List<String[]> out = new ArrayList<>();
        boolean inCode = false;
        for (int i = 0; i < area.getParagraphs().size(); i++) {
            String line = area.getText(i);
            if (line.trim().startsWith("```")) inCode = !inCode;
            if (!inCode && line.matches("^#{1,6}\\s+.*")) {
                int level = line.indexOf(' ');
                out.add(new String[]{"  ".repeat(level - 1) + Markdown.plain(line.substring(level)), String.valueOf(i)});
            }
        }
        return out;
    }

    // ---------- writing helpers ----------

    private void keys() {
        area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShortcutDown() && !e.isShiftDown() && !e.isAltDown()) {
                if (area.getSelection().getLength() == 0 && enter()) e.consume();
            } else if (e.getCode() == KeyCode.TAB && !e.isShortcutDown()) {
                indent(!e.isShiftDown());
                e.consume();
            } else if (is(e, KeyCode.ENTER)) {
                editLines(SmartEdit::toggleTask);
                e.consume();
            } else if (is(e, KeyCode.B)) {
                wrapSelection("**");
                e.consume();
            } else if (is(e, KeyCode.I)) {
                wrapSelection("*");
                e.consume();
            } else if (e.isShortcutDown() && e.isShiftDown() && e.getCode() == KeyCode.X) {
                wrapSelection("~~");
                e.consume();
            } else if (e.isShortcutDown() && e.getCode() == KeyCode.BACK_QUOTE) {
                wrapSelection("`");
                e.consume();
            } else if (is(e, KeyCode.K)) {
                link();
                e.consume();
            } else if (e.isShortcutDown() && e.isShiftDown() && e.getCode() == KeyCode.H) {
                editLines(SmartEdit::cycleHeading);
                e.consume();
            } else if (is(e, KeyCode.D)) {
                duplicateLine();
                e.consume();
            } else if (e.isAltDown() && (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN)) {
                moveLines(e.getCode() == KeyCode.UP ? -1 : 1);
                e.consume();
            } else if (e.getCode() == KeyCode.F5) {
                area.replaceSelection(LocalDateTime.now().format(STAMP));
                e.consume();
            } else if (is(e, KeyCode.V) && pasteLink()) {
                e.consume();
            }
        });
    }

    private static boolean is(KeyEvent e, KeyCode code) {
        return e.isShortcutDown() && !e.isShiftDown() && !e.isAltDown() && e.getCode() == code;
    }

    /** Carries a list on to the next line, or ends it on an empty item. False: let Enter be Enter. */
    private boolean enter() {
        int para = area.getCurrentParagraph();
        int col = area.getCaretColumn();
        String line = area.getText(para);
        if (col < line.length()) {
            String before = line.substring(0, col);
            SmartEdit.Enter enter = SmartEdit.onEnter(before);
            if (enter.endList() || enter.prefix().isEmpty()) return false;
            area.replaceSelection("\n" + enter.prefix());
            return true;
        }
        SmartEdit.Enter enter = SmartEdit.onEnter(line);
        if (enter.endList()) {
            area.replaceText(para, 0, para, line.length(), "");
            return true;
        }
        if (enter.prefix().isEmpty()) return false;
        area.replaceSelection("\n" + enter.prefix());
        area.requestFollowCaret();
        return true;
    }

    private int[] selectedLines() {
        var sel = area.getSelection();
        int first = area.offsetToPosition(sel.getStart(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
        int last = area.offsetToPosition(sel.getEnd(), org.fxmisc.richtext.model.TwoDimensional.Bias.Backward).getMajor();
        if (sel.getLength() > 0 && area.offsetToPosition(sel.getEnd(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMinor() == 0
                && last > first) {
            last--;
        }
        return new int[]{first, Math.max(first, last)};
    }

    private void editLines(java.util.function.UnaryOperator<String> edit) {
        int[] lines = selectedLines();
        int caretPara = area.getCurrentParagraph();
        int caretCol = area.getCaretColumn();
        StringBuilder b = new StringBuilder();
        for (int i = lines[0]; i <= lines[1]; i++) {
            if (i > lines[0]) b.append('\n');
            b.append(edit.apply(area.getText(i)));
        }
        String before = area.getText(caretPara);
        area.replaceText(lines[0], 0, lines[1], area.getParagraphLength(lines[1]), b.toString());
        int shift = area.getText(caretPara).length() - before.length();
        area.moveTo(caretPara, Math.max(0, Math.min(caretCol + shift, area.getParagraphLength(caretPara))));
    }

    private void indent(boolean in) {
        if (in && area.getSelection().getLength() == 0) {
            String line = area.getText(area.getCurrentParagraph());
            // Tab in the middle of a sentence is a tab; at a list item it nests the item.
            if (!line.matches("^\\s*([-*+]|\\d+[.)])\\s.*") && !line.isBlank()) {
                area.replaceSelection("    ");
                return;
            }
        }
        editLines(l -> in ? "  " + l : l.startsWith("  ") ? l.substring(2) : l.startsWith("\t") ? l.substring(1) : l.stripLeading());
    }

    private void wrapSelection(String mark) {
        String sel = area.getSelectedText();
        if (sel.isEmpty()) {
            area.replaceSelection(mark + mark);
            area.moveTo(area.getCaretPosition() - mark.length());
            return;
        }
        int start = area.getSelection().getStart();
        String wrapped = SmartEdit.wrap(sel, mark);
        area.replaceSelection(wrapped);
        area.selectRange(start, start + wrapped.length());
    }

    private void link() {
        String sel = area.getSelectedText();
        String clip = Clipboard.getSystemClipboard().getString();
        String url = clip != null && URL.matcher(clip.trim()).matches() ? clip.trim() : "";
        int start = area.getSelection().getStart();
        area.replaceSelection("[" + sel + "](" + url + ")");
        if (sel.isEmpty()) area.moveTo(start + 1);
        else if (url.isEmpty()) area.moveTo(start + sel.length() + 3);
    }

    /** Pasting an address over selected words makes them a link to it. */
    private boolean pasteLink() {
        String sel = area.getSelectedText();
        String clip = Clipboard.getSystemClipboard().getString();
        if (sel.isEmpty() || sel.contains("\n") || clip == null || !URL.matcher(clip.trim()).matches()) return false;
        area.replaceSelection("[" + sel + "](" + clip.trim() + ")");
        return true;
    }

    private void duplicateLine() {
        int[] lines = selectedLines();
        String block = area.getText(lines[0], 0, lines[1], area.getParagraphLength(lines[1]));
        area.insertText(lines[1], area.getParagraphLength(lines[1]), "\n" + block);
        area.moveTo(lines[1] + 1 + (area.getCurrentParagraph() - lines[0]), area.getCaretColumn());
    }

    private void moveLines(int by) {
        int[] lines = selectedLines();
        int total = area.getParagraphs().size();
        if (lines[0] + by < 0 || lines[1] + by >= total) return;
        int col = area.getCaretColumn();
        int caretPara = area.getCurrentParagraph();
        List<String> all = new ArrayList<>();
        int from = Math.min(lines[0], lines[0] + by);
        int to = Math.max(lines[1], lines[1] + by);
        for (int i = from; i <= to; i++) all.add(area.getText(i));
        if (by < 0) all.add(all.removeFirst());
        else all.addFirst(all.removeLast());
        area.replaceText(from, 0, to, area.getParagraphLength(to), String.join("\n", all));
        int start = area.getAbsolutePosition(lines[0] + by, 0);
        int end = area.getAbsolutePosition(lines[1] + by, area.getParagraphLength(lines[1] + by));
        if (lines[0] == lines[1]) area.moveTo(caretPara + by, Math.min(col, area.getParagraphLength(caretPara + by)));
        else area.selectRange(start, end);
    }

    private void toggleTaskAt(int line) {
        if (line >= area.getParagraphs().size()) return;
        String text = area.getText(line);
        area.replaceText(line, 0, line, text.length(), SmartEdit.toggleTask(text));
    }

    // ---------- find and replace ----------

    /** Ctrl+F: find as you type; Ctrl+H adds replace. Enter for the next, Shift+Enter the last. */
    final class FindBar extends HBox {

        private final TextField query = new TextField();
        private final TextField replacement = new TextField();
        private final ToggleButton matchCase = new ToggleButton("Aa");
        private final ToggleButton regex = new ToggleButton(".*");
        private final ToggleButton words = new ToggleButton("W");
        private final Label count = new Label();
        private final HBox replaceRow;

        FindBar() {
            super(8);
            getStyleClass().add("find-bar");
            setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(8, 12, 8, 12));
            query.setPromptText("Find");
            replacement.setPromptText("Replace with");
            query.getStyleClass().add("find-field");
            replacement.getStyleClass().add("find-field");
            HBox.setHgrow(query, Priority.ALWAYS);
            HBox.setHgrow(replacement, Priority.ALWAYS);
            matchCase.setTooltip(new Tooltip("Match case"));
            regex.setTooltip(new Tooltip("Regular expression"));
            words.setTooltip(new Tooltip("Whole words"));
            for (ToggleButton t : List.of(matchCase, regex, words)) {
                t.getStyleClass().add("find-toggle");
                t.selectedProperty().addListener((o, a, b) -> search(false, true));
            }
            count.getStyleClass().add("find-count");
            count.setMinWidth(70);

            Button prev = small("↑", "Previous (Shift+Enter)", () -> step(-1));
            Button next = small("↓", "Next (Enter)", () -> step(1));
            Button close = small("✕", "Close (Esc)", this::close);
            Button one = small("Replace", "Replace this one", this::replaceOne);
            Button all = small("All", "Replace every match", this::replaceAll);
            replaceRow = new HBox(8, replacement, one, all);
            replaceRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(replaceRow, Priority.ALWAYS);

            getChildren().addAll(query, matchCase, words, regex, count, prev, next, replaceRow, close);

            query.textProperty().addListener((o, a, b) -> search(false, true));
            query.setOnKeyPressed(this::keys);
            replacement.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER) {
                    replaceOne();
                    e.consume();
                } else {
                    keys(e);
                }
            });
            setVisible(false);
            setManaged(false);
        }

        private Button small(String text, String tip, Runnable action) {
            Button b = new Button(text);
            b.getStyleClass().add("find-button");
            b.setTooltip(new Tooltip(tip));
            b.setOnAction(e -> action.run());
            b.setFocusTraversable(false);
            return b;
        }

        private void keys(KeyEvent e) {
            if (e.getCode() == KeyCode.ESCAPE) {
                close();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                step(e.isShiftDown() ? -1 : 1);
                e.consume();
            }
        }

        void open(boolean withReplace, String selected) {
            setVisible(true);
            setManaged(true);
            replaceRow.setVisible(withReplace);
            replaceRow.setManaged(withReplace);
            if (!selected.isEmpty() && !selected.contains("\n")) query.setText(selected);
            query.requestFocus();
            query.selectAll();
            search(false, true);
        }

        void close() {
            setVisible(false);
            setManaged(false);
            area.requestFocus();
        }

        private Pattern pattern() {
            String q = query.getText();
            if (q.isEmpty()) return null;
            String p = regex.isSelected() ? q : Pattern.quote(q);
            if (words.isSelected()) p = "\\b" + p + "\\b";
            try {
                return Pattern.compile(p, matchCase.isSelected() ? Pattern.MULTILINE
                        : Pattern.MULTILINE | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } catch (PatternSyntaxException e) {
                return null;
            }
        }

        private List<int[]> matches() {
            Pattern p = pattern();
            List<int[]> out = new ArrayList<>();
            if (p == null) return out;
            Matcher m = p.matcher(area.getText());
            while (m.find() && out.size() < 100_000) {
                if (m.end() == m.start()) continue;
                out.add(new int[]{m.start(), m.end()});
            }
            return out;
        }

        /** Selects the nearest match from the caret; {@code fromStart} of the selection so typing refines in place. */
        private void search(boolean after, boolean fromStart) {
            List<int[]> all = matches();
            query.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("none"),
                    !query.getText().isEmpty() && all.isEmpty());
            if (all.isEmpty()) {
                count.setText(query.getText().isEmpty() ? "" : pattern() == null ? "Bad pattern" : "No results");
                return;
            }
            int from = fromStart ? area.getSelection().getStart() : area.getSelection().getEnd();
            int index = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i)[0] >= from) {
                    index = i;
                    break;
                }
                if (i == all.size() - 1) index = 0;
            }
            select(all, index);
        }

        private void step(int by) {
            List<int[]> all = matches();
            if (all.isEmpty()) {
                search(false, true);
                return;
            }
            int start = area.getSelection().getStart();
            int index = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i)[0] == start) index = i;
            }
            if (index < 0) {
                search(false, by > 0);
                return;
            }
            select(all, Math.floorMod(index + by, all.size()));
        }

        private void select(List<int[]> all, int index) {
            int[] m = all.get(index);
            area.selectRange(m[0], m[1]);
            area.requestFollowCaret();
            count.setText((index + 1) + " of " + all.size());
        }

        private String replacementFor(String matched) {
            if (!regex.isSelected()) return replacement.getText();
            Pattern p = pattern();
            return p == null ? replacement.getText() : p.matcher(matched).replaceFirst(replacement.getText());
        }

        private void replaceOne() {
            Pattern p = pattern();
            String sel = area.getSelectedText();
            if (p != null && !sel.isEmpty() && p.matcher(sel).matches()) {
                int start = area.getSelection().getStart();
                String with = replacementFor(sel);
                area.replaceSelection(with);
                area.moveTo(start + with.length());
            }
            step(1);
            if (matches().isEmpty()) count.setText("No results");
        }

        private void replaceAll() {
            Pattern p = pattern();
            if (p == null) return;
            Matcher m = p.matcher(area.getText());
            int n = 0;
            StringBuilder b = new StringBuilder();
            while (m.find()) {
                n++;
                m.appendReplacement(b, regex.isSelected() ? replacement.getText() : Matcher.quoteReplacement(replacement.getText()));
            }
            if (n == 0) return;
            m.appendTail(b);
            int caret = area.getCaretPosition();
            // One change, so one undo takes it all back.
            area.replaceText(b.toString());
            area.moveTo(Math.min(caret, area.getLength()));
            count.setText("Replaced " + n);
        }
    }
}
