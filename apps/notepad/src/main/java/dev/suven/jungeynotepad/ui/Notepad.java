package dev.suven.jungeynotepad.ui;

import dev.suven.jungeynotepad.Instance;
import dev.suven.jungeynotepad.notes.History;
import dev.suven.jungeynotepad.notes.Library;
import dev.suven.jungeynotepad.notes.Library.Note;
import dev.suven.jungeynotepad.notes.Markdown;
import dev.suven.jungeynotepad.notes.Settings;
import dev.suven.jungeynotepad.notes.TextStats;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Jungey Notepad's window: the notes down the side, open ones in tabs, and each one an
 * editor that keeps its own Markdown tidy, with a preview beside it on request.
 *
 * <p>Everything is a key away - Ctrl+P finds any note, heading or command - and nothing
 * needs saving: see {@link EditorTab}. The notes folder is read again whenever the window
 * comes back into focus and every few seconds, so a note Jungey adds by voice appears.
 */
public final class Notepad extends Application {

    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);

    private final Settings settings = Settings.load();
    private final Library library = Library.standard();
    private final History history = History.standard();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> daemon(r, "jungey-notepad-read"));
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "jungey-notepad-tick"));

    private Stage stage;
    private final StackPane root = new StackPane();
    private final BorderPane frame = new BorderPane();
    private final TabPane tabs = new TabPane();
    private final Palette palette = new Palette();
    private final Label toast = new Label();

    private final VBox sidebar = new VBox(10);
    private final TextField filter = new TextField();
    private final FlowPane tagBar = new FlowPane(6, 6);
    private final ListView<Note> noteList = new ListView<>();
    private final Label libraryCount = new Label();
    private HBox topBar;
    private HBox statusBar;
    private final VBox welcome = new VBox(18);
    private final VBox recentBox = new VBox(4);

    private final Label saveState = new Label();
    private final Label where = new Label();
    private final Label caret = new Label();
    private final Label counts = new Label();
    private final Label tasksLabel = new Label();

    private List<Note> notes = List.of();
    private final Set<String> pinned = settings.pinned();
    private final Set<Path> madeEmpty = new HashSet<>();
    private String activeTag;
    private boolean focusMode;
    private int searchGeneration;

    private EditorTab watched;
    private final ChangeListener<EditorTab.State> stateListener = (o, a, s) -> showState(s);
    private final ChangeListener<TextStats> statsListener = (o, a, s) -> showStats(s);
    private final ChangeListener<Integer> caretListener = (o, a, c) -> showCaret();

    public static void main(String[] args) {
        launch(args);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        topBar = topBar();
        buildSidebar();
        statusBar = statusBar();
        buildWelcome();

        tabs.getStyleClass().add("note-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, old, now) -> tabChanged(old, now));
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) c -> {
            boolean none = tabs.getTabs().isEmpty();
            welcome.setVisible(none);
            tabs.setVisible(!none);
            if (none) {
                showState(null);
                refreshWelcome();
            }
        });
        StackPane center = new StackPane(welcome, tabs);
        center.getStyleClass().add("center");

        frame.setTop(topBar);
        frame.setLeft(settings.sidebar() ? sidebar : null);
        frame.setCenter(center);
        frame.setBottom(statusBar);

        toast.getStyleClass().add("toast");
        toast.setOpacity(0);
        toast.setMouseTransparent(true);
        root.getChildren().addAll(frame, palette, toast);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 48, 0));
        root.getStyleClass().add("backdrop");
        applyLook();

        Scene scene = new Scene(root, settings.width(), settings.height());
        scene.getStylesheets().add(getClass().getResource("/styles/notepad.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::shortcuts);
        dragAndDrop(scene);
        stage.setScene(scene);
        stage.setTitle("Jungey Notepad");
        for (int size : new int[]{16, 32, 48, 128, 256}) {
            var icon = getClass().getResource("/icons/jungey-notepad-" + size + ".png");
            if (icon != null) stage.getIcons().add(new Image(icon.toExternalForm()));
        }
        stage.setMinWidth(560);
        stage.setMinHeight(380);
        // Leaving the window is a natural moment to be safe; coming back, to catch up.
        stage.focusedProperty().addListener((o, a, focused) -> {
            if (focused) {
                rescan();
                checkDisk();
            } else {
                saveAll();
            }
        });
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        restoreSession();
        rescan();
        ticker.scheduleWithFixedDelay(() -> Platform.runLater(() -> {
            rescan();
            checkDisk();
        }), 6, 6, TimeUnit.SECONDS);
        Instance.listen(files -> Platform.runLater(() -> arrived(files)));
    }

    /** Files from a later launch - the file manager, or Jungey asked to open a note. */
    private void arrived(List<Path> files) {
        for (Path f : files) open(f, -1);
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
        rescan();
        checkDisk();
    }

    // ---------- layout ----------

    private HBox topBar() {
        Label brand = new Label("Jungey");
        brand.getStyleClass().add("brand");
        Label sub = new Label("NOTEPAD");
        sub.getStyleClass().add("brand-sub");
        HBox name = new HBox(6, brand, sub);
        name.setAlignment(Pos.BASELINE_LEFT);

        Button sideToggle = iconButton("☰", "Show or hide the notes (Ctrl+\\)", this::toggleSidebar);
        Button newNote = pill("+  New note", "Ctrl+N", this::newNote);
        newNote.getStyleClass().add("accent-button");
        Button today = pill("Today", "Today's journal page (Ctrl+Shift+D)", this::daily);

        Button jump = new Button("Search notes, headings and commands…      Ctrl+P");
        jump.getStyleClass().add("jump");
        jump.setOnAction(e -> openPalette(""));
        jump.setMaxWidth(440);
        HBox.setHgrow(jump, Priority.SOMETIMES);

        Button preview = iconButton("◧", "Preview beside the text (Ctrl+E)", this::togglePreview);
        Button theme = iconButton("◐", "Light or dark", this::toggleTheme);
        Button focus = iconButton("⬚", "Focus mode (F11)", this::toggleFocus);

        MenuButton more = new MenuButton("⋯");
        more.getStyleClass().add("icon-button");
        more.setTooltip(new Tooltip("More"));
        more.getItems().addAll(
                item("Open a file…", "Ctrl+O", this::openFile),
                item("Rename this note…", "F2", this::renameCurrent),
                item("History of this note…", "Ctrl+Shift+Y", this::showHistory),
                item("Export as a web page…", "", this::exportHtml),
                item("Move this note to the trash", "", this::trashCurrent),
                new SeparatorMenuItem(),
                item("Line numbers", "", this::toggleLineNumbers),
                item("Wrap long lines", "", this::toggleWrap),
                item("Typewriter font", "", this::toggleMono),
                item("Bigger text", "Ctrl+=", () -> zoom(1)),
                item("Smaller text", "Ctrl+-", () -> zoom(-1)),
                new SeparatorMenuItem(),
                item("Open the notes folder", "", () -> getHostServices().showDocument(library.root().toUri().toString())),
                item("Keyboard shortcuts", "F1", this::showShortcuts));

        HBox bar = new HBox(10, sideToggle, name, gap(18), newNote, today, spacer(), jump, spacer(), preview, theme, focus, more);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 14, 10, 14));
        bar.getStyleClass().add("top-bar");
        return bar;
    }

    private void buildSidebar() {
        filter.setPromptText("Search all notes   Ctrl+Shift+F");
        filter.getStyleClass().add("filter");
        filter.textProperty().addListener((o, a, q) -> showNotes());
        filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                filter.clear();
                focusEditor();
            } else if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.ENTER) {
                noteList.requestFocus();
                noteList.getSelectionModel().selectFirst();
                if (e.getCode() == KeyCode.ENTER && !noteList.getItems().isEmpty()) open(noteList.getItems().getFirst().path(), -1);
            }
        });

        tagBar.getStyleClass().add("tag-bar");

        noteList.getStyleClass().add("note-list");
        noteList.setCellFactory(v -> new NoteCell());
        noteList.setOnMouseClicked(e -> {
            Note n = noteList.getSelectionModel().getSelectedItem();
            if (n != null) open(n.path(), hitLine(n));
        });
        noteList.setOnKeyPressed(e -> {
            Note n = noteList.getSelectionModel().getSelectedItem();
            if (n == null) return;
            if (e.getCode() == KeyCode.ENTER) open(n.path(), hitLine(n));
            if (e.getCode() == KeyCode.DELETE) trash(n.path());
            if (e.getCode() == KeyCode.F2) rename(n.path());
        });
        VBox.setVgrow(noteList, Priority.ALWAYS);

        libraryCount.getStyleClass().add("muted");
        Label heading = new Label("NOTES");
        heading.getStyleClass().add("section");
        HBox head = new HBox(8, heading, spacer(), libraryCount);
        head.setAlignment(Pos.CENTER_LEFT);

        sidebar.getChildren().setAll(filter, tagBar, head, noteList);
        sidebar.setPadding(new Insets(12, 10, 10, 12));
        sidebar.setPrefWidth(290);
        sidebar.setMinWidth(230);
        sidebar.getStyleClass().add("sidebar");
    }

    private HBox statusBar() {
        saveState.getStyleClass().add("save-state");
        where.getStyleClass().add("status");
        caret.getStyleClass().add("status");
        counts.getStyleClass().add("status");
        tasksLabel.getStyleClass().add("status");
        HBox bar = new HBox(16, saveState, where, spacer(), tasksLabel, counts, caret);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(5, 14, 6, 14));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void buildWelcome() {
        Label title = new Label("Write something down.");
        title.getStyleClass().add("welcome-title");
        Label sub = new Label("Plain Markdown files in " + tilde(library.root()) + " - saved as you type, with every version kept.");
        sub.getStyleClass().add("muted");
        sub.setWrapText(true);
        Button n = pill("+  New note", "Ctrl+N", this::newNote);
        n.getStyleClass().add("accent-button");
        Button d = pill("Today's page", "Ctrl+Shift+D", this::daily);
        Button p = pill("Find a note", "Ctrl+P", () -> openPalette(""));
        HBox actions = new HBox(10, n, d, p);
        Label recent = new Label("RECENT");
        recent.getStyleClass().add("section");
        Label keys = new Label("Ctrl+P  jump anywhere     Ctrl+Enter  tick a task     Ctrl+E  preview     F1  all shortcuts");
        keys.getStyleClass().add("muted");
        welcome.getChildren().setAll(title, sub, actions, recent, recentBox, keys);
        welcome.setMaxWidth(620);
        welcome.setPadding(new Insets(60, 30, 30, 30));
        welcome.getStyleClass().add("welcome");
        StackPane.setAlignment(welcome, Pos.TOP_CENTER);
    }

    private void refreshWelcome() {
        recentBox.getChildren().clear();
        for (Note n : notes.stream().limit(6).toList()) {
            Button b = new Button(n.title());
            b.getStyleClass().add("recent");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> open(n.path(), -1));
            Label when = new Label(ago(n.modified()));
            when.getStyleClass().add("muted");
            HBox row = new HBox(10, b, spacer(), when);
            HBox.setHgrow(b, Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
            recentBox.getChildren().add(row);
        }
        if (notes.isEmpty()) {
            Label none = new Label("Nothing yet. Your first note is one click away.");
            none.getStyleClass().add("muted");
            recentBox.getChildren().add(none);
        }
    }

    // ---------- the note list ----------

    private final class NoteCell extends ListCell<Note> {
        @Override
        protected void updateItem(Note n, boolean empty) {
            super.updateItem(n, empty);
            // Never wider than the list: long snippets are cut short, not scrolled sideways.
            setPrefWidth(0);
            if (empty || n == null) {
                setGraphic(null);
                setContextMenu(null);
                // Cells are reused: one left empty must not still look like the open note.
                pseudoClassStateChanged(ACTIVE, false);
                return;
            }
            Label title = new Label((pinned.contains(n.path().toString()) ? "★ " : "") + n.title());
            title.getStyleClass().add("note-title");
            String hit = hits.get(n.path());
            Label snippet = new Label(hit != null ? hit : n.snippet().isEmpty() ? "No more text" : n.snippet());
            snippet.getStyleClass().add("note-snippet");
            snippet.setWrapText(true);
            snippet.setMaxHeight(34);
            Label when = new Label(ago(n.modified()));
            when.getStyleClass().add("note-meta");
            HBox meta = new HBox(8, when);
            Path folder = library.root().relativize(n.path()).getParent();
            if (folder != null) meta.getChildren().add(metaLabel(folder.toString()));
            if (n.tasks() > 0) meta.getChildren().add(metaLabel("☑ " + n.tasksDone() + "/" + n.tasks()));
            for (String t : n.tags().stream().limit(3).toList()) {
                Label tag = metaLabel("#" + t);
                tag.getStyleClass().add("note-tag");
                meta.getChildren().add(tag);
            }
            VBox box = new VBox(3, title, snippet, meta);
            box.setPadding(new Insets(7, 8, 7, 8));
            setGraphic(box);
            boolean open = tabFor(n.path()) != null;
            pseudoClassStateChanged(ACTIVE, open && tabs.getSelectionModel().getSelectedItem() == tabFor(n.path()));

            MenuItem pin = new MenuItem(pinned.contains(n.path().toString()) ? "Unpin" : "Pin to the top");
            pin.setOnAction(e -> togglePin(n.path()));
            MenuItem rename = new MenuItem("Rename…");
            rename.setOnAction(e -> rename(n.path()));
            MenuItem hist = new MenuItem("History…");
            hist.setOnAction(e -> {
                open(n.path(), -1);
                showHistory();
            });
            MenuItem reveal = new MenuItem("Show in folder");
            reveal.setOnAction(e -> getHostServices().showDocument(n.path().getParent().toUri().toString()));
            MenuItem trash = new MenuItem("Move to trash");
            trash.setOnAction(e -> trash(n.path()));
            setContextMenu(new ContextMenu(pin, rename, hist, reveal, new SeparatorMenuItem(), trash));
        }
    }

    private static Label metaLabel(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("note-meta");
        return l;
    }

    /** What matched a content search, by note, shown in place of the snippet. */
    private final Map<Path, String> hits = new HashMap<>();
    private final Map<Path, Integer> hitLines = new HashMap<>();

    private int hitLine(Note n) {
        return hitLines.getOrDefault(n.path(), -1);
    }

    private void rescan() {
        reader.submit(() -> {
            List<Note> found = library.notes();
            Platform.runLater(() -> {
                boolean changed = !found.equals(notes);
                notes = found;
                if (changed) {
                    showNotes();
                    showTags();
                    palette.reload();
                    if (tabs.getTabs().isEmpty()) refreshWelcome();
                }
            });
        });
    }

    private void showNotes() {
        String q = filter.getText().trim();
        libraryCount.setText(notes.size() + (notes.size() == 1 ? " note" : " notes"));
        if (q.isEmpty()) {
            hits.clear();
            hitLines.clear();
            setList(notes.stream().filter(this::tagged).toList());
            return;
        }
        // Searching reads every note: off the UI thread, and only the latest search counts.
        int generation = ++searchGeneration;
        reader.submit(() -> {
            List<Library.Hit> found = library.search(q);
            Platform.runLater(() -> {
                if (generation != searchGeneration) return;
                hits.clear();
                hitLines.clear();
                for (Library.Hit h : found) {
                    hits.put(h.note().path(), h.text());
                    hitLines.put(h.note().path(), h.line());
                }
                setList(found.stream().map(Library.Hit::note).filter(this::tagged).toList());
            });
        });
    }

    private void setList(List<Note> list) {
        Note selected = noteList.getSelectionModel().getSelectedItem();
        List<Note> sorted = new ArrayList<>(list);
        // Pinned first; otherwise the order given (newest first, or best match first).
        sorted.sort(Comparator.comparing((Note n) -> !pinned.contains(n.path().toString())));
        noteList.getItems().setAll(sorted);
        if (selected != null) {
            for (Note n : sorted) {
                if (n.path().equals(selected.path())) noteList.getSelectionModel().select(n);
            }
        }
    }

    private boolean tagged(Note n) {
        return activeTag == null || n.tags().contains(activeTag);
    }

    private void showTags() {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Note n : notes) for (String t : n.tags()) count.merge(t, 1, Integer::sum);
        if (activeTag != null && !count.containsKey(activeTag)) activeTag = null;
        tagBar.getChildren().clear();
        if (count.isEmpty()) {
            tagBar.setManaged(false);
            tagBar.setVisible(false);
            return;
        }
        tagBar.setManaged(true);
        tagBar.setVisible(true);
        tagBar.getChildren().add(tagChip("All", null));
        count.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(14)
                .forEach(e -> tagBar.getChildren().add(tagChip("#" + e.getKey(), e.getKey())));
    }

    private Node tagChip(String text, String tag) {
        Button b = new Button(text);
        b.getStyleClass().add("tag-chip");
        b.pseudoClassStateChanged(ACTIVE, java.util.Objects.equals(tag, activeTag));
        b.setOnAction(e -> {
            activeTag = java.util.Objects.equals(tag, activeTag) ? null : tag;
            showTags();
            showNotes();
        });
        return b;
    }

    // ---------- opening and closing notes ----------

    private EditorTab tabFor(Path p) {
        for (Tab t : tabs.getTabs()) {
            if (t instanceof EditorTab e && e.path().equals(p)) return e;
        }
        return null;
    }

    private EditorTab current() {
        return tabs.getSelectionModel().getSelectedItem() instanceof EditorTab e ? e : null;
    }

    void open(Path path, int line) {
        Path p = path.toAbsolutePath().normalize();
        EditorTab tab = tabFor(p);
        if (tab == null) {
            try {
                tab = new EditorTab(p, history, url -> getHostServices().showDocument(url), t -> rescan());
            } catch (IOException e) {
                say("Could not open " + p.getFileName() + ": " + e.getMessage());
                return;
            }
            EditorTab made = tab;
            tab.setOnCloseRequest(e -> beforeClose(made));
            tab.setLineNumbers(settings.lineNumbers());
            tab.setWrap(settings.wrap());
            tab.setFontSize(settings.fontSize());
            tab.showPreview(settings.preview());
            tabs.getTabs().add(tab);
        }
        tabs.getSelectionModel().select(tab);
        EditorTab t = tab;
        Platform.runLater(() -> {
            if (line >= 0) t.goToLine(line);
            t.area.requestFocus();
        });
    }

    private void beforeClose(EditorTab tab) {
        tab.save();
        Platform.runLater(() -> tidy(tab));
    }

    /**
     * After a note is closed: an untouched new note is removed rather than left as an empty
     * file, and one still called "Untitled" takes its title as its name.
     */
    private void tidy(EditorTab tab) {
        Path p = tab.path();
        try {
            if (!Files.exists(p)) return;
            String text = Library.read(p);
            if (madeEmpty.contains(p) && Markdown.plain(text.replace("#", "")).isBlank()) {
                Files.deleteIfExists(p);
                madeEmpty.remove(p);
                rescan();
                return;
            }
            String name = p.getFileName().toString();
            String title = Markdown.title(text);
            if (name.matches("Untitled( \\d+)?\\.md") && !title.isBlank() && !title.startsWith("Untitled")) {
                Path to = library.rename(p, title);
                history.moved(p, to);
                if (tabFor(p) != null) tabFor(p).moved(to);
                replacePinned(p, to);
                madeEmpty.remove(p);
                rescan();
            }
        } catch (IOException ignored) {
            // A name clash or a vanished file: the note stays as it was.
        }
    }

    private void newNote() {
        try {
            Path p = library.create("Untitled", "# ");
            madeEmpty.add(p);
            open(p, -1);
            EditorTab t = tabFor(p.toAbsolutePath().normalize());
            if (t != null) Platform.runLater(() -> t.area.moveTo(t.area.getLength()));
            rescan();
        } catch (IOException e) {
            say("Could not make a note: " + e.getMessage());
        }
    }

    private void daily() {
        try {
            Path p = library.daily(LocalDate.now());
            open(p, -1);
            EditorTab t = tabFor(p.toAbsolutePath().normalize());
            if (t != null) Platform.runLater(() -> {
                t.area.moveTo(t.area.getLength());
                t.area.requestFollowCaret();
            });
            rescan();
        } catch (IOException e) {
            say("Could not open today's page: " + e.getMessage());
        }
    }

    private void openFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open a file");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Notes and text", "*.md", "*.markdown", "*.txt"),
                new FileChooser.ExtensionFilter("Any file", "*"));
        File f = chooser.showOpenDialog(stage);
        if (f != null) open(f.toPath(), -1);
    }

    private void dragAndDrop(Scene scene) {
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            boolean any = false;
            for (File f : e.getDragboard().getFiles()) {
                if (f.isFile()) {
                    open(f.toPath(), -1);
                    any = true;
                }
            }
            e.setDropCompleted(any);
            e.consume();
        });
    }

    private void renameCurrent() {
        EditorTab t = current();
        if (t != null) rename(t.path());
    }

    private void rename(Path p) {
        TextInputDialog d = new TextInputDialog(stripExt(p.getFileName().toString()));
        d.setTitle("Rename");
        d.setHeaderText("A new name for this note");
        d.initOwner(stage);
        d.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
            EditorTab t = tabFor(p);
            if (t != null) t.save();
            try {
                Path to = library.rename(p, name);
                history.moved(p, to);
                if (t != null) t.moved(to);
                replacePinned(p, to);
                madeEmpty.remove(p);
                rescan();
            } catch (IOException e) {
                say(e.getMessage());
            }
        });
    }

    private void trashCurrent() {
        EditorTab t = current();
        if (t != null) trash(t.path());
    }

    private void trash(Path p) {
        if (!p.startsWith(library.root())) {
            say("Only notes in the notes folder can be moved to its trash.");
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "It goes to the .trash folder inside your notes, where you can get it back.",
                ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText("Move \"" + stripExt(p.getFileName().toString()) + "\" to the trash?");
        a.initOwner(stage);
        if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        EditorTab t = tabFor(p);
        if (t != null) {
            t.save();
            t.setOnClosed(null);
            tabs.getTabs().remove(t);
        }
        try {
            library.trash(p);
            pinned.remove(p.toString());
            say("Moved to the trash.");
        } catch (IOException e) {
            say("Could not move it: " + e.getMessage());
        }
        rescan();
    }

    private void togglePin(Path p) {
        if (!pinned.remove(p.toString())) pinned.add(p.toString());
        settings.pinned(pinned);
        showNotes();
    }

    private void replacePinned(Path from, Path to) {
        if (pinned.remove(from.toString())) {
            pinned.add(to.toString());
            settings.pinned(pinned);
        }
    }

    private void showHistory() {
        EditorTab t = current();
        if (t == null) return;
        t.save();
        HistoryView.show(stage, stage.getScene().getStylesheets(), settings.dark() ? "dark" : "light",
                t.displayName(), t.area.getText(), history.versions(t.path()), text -> {
                    try {
                        history.keep(t.path(), t.area.getText(), true);
                    } catch (IOException ignored) {
                        // Undo still has it.
                    }
                    t.replaceAll(text);
                    say("Restored. Ctrl+Z to undo.");
                });
    }

    private void exportHtml() {
        EditorTab t = current();
        if (t == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export as a web page");
        chooser.setInitialFileName(Library.fileName(t.displayName()) + ".html");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Web page", "*.html"));
        File f = chooser.showSaveDialog(stage);
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), Markdown.toHtml(t.displayName(), t.area.getText()), StandardCharsets.UTF_8);
            say("Exported " + f.getName());
        } catch (IOException e) {
            say("Could not export: " + e.getMessage());
        }
    }

    // ---------- the palette ----------

    private void openPalette(String start) {
        palette.open(start, this::paletteItems, this::focusEditor);
    }

    private List<Palette.Item> paletteItems() {
        List<Palette.Item> items = new ArrayList<>();
        EditorTab t = current();
        if (t != null) {
            for (String[] h : t.headings()) {
                int line = Integer.parseInt(h[1]);
                items.add(new Palette.Item(h[0], "", "Heading", () -> t.goToLine(line)));
            }
        }
        for (Note n : notes) {
            Path rel = library.root().relativize(n.path());
            items.add(new Palette.Item(n.title(), rel.toString() + "  ·  " + ago(n.modified()), "Note", () -> open(n.path(), -1)));
        }
        record C(String name, String keys, Runnable run) {
        }
        List<C> commands = List.of(
                new C("New note", "Ctrl+N", this::newNote),
                new C("Today's journal page", "Ctrl+Shift+D", this::daily),
                new C("Find in this note", "Ctrl+F", () -> find(false)),
                new C("Find and replace", "Ctrl+H", () -> find(true)),
                new C("Search all notes", "Ctrl+Shift+F", this::focusSearch),
                new C("Toggle preview", "Ctrl+E", this::togglePreview),
                new C("Toggle the notes list", "Ctrl+\\", this::toggleSidebar),
                new C("Focus mode", "F11", this::toggleFocus),
                new C("Light or dark theme", "", this::toggleTheme),
                new C("Typewriter font", "", this::toggleMono),
                new C("Line numbers", "", this::toggleLineNumbers),
                new C("Wrap long lines", "", this::toggleWrap),
                new C("Bigger text", "Ctrl+=", () -> zoom(1)),
                new C("Smaller text", "Ctrl+-", () -> zoom(-1)),
                new C("Rename this note", "F2", this::renameCurrent),
                new C("History of this note", "Ctrl+Shift+Y", this::showHistory),
                new C("Pin or unpin this note", "", () -> {
                    if (current() != null) togglePin(current().path());
                }),
                new C("Export as a web page", "", this::exportHtml),
                new C("Open a file", "Ctrl+O", this::openFile),
                new C("Move this note to the trash", "", this::trashCurrent),
                new C("Open the notes folder", "", () -> getHostServices().showDocument(library.root().toUri().toString())),
                new C("Keyboard shortcuts", "F1", this::showShortcuts));
        for (C c : commands) items.add(new Palette.Item(c.name(), c.keys(), "Command", c.run()));
        return items;
    }

    // ---------- keys ----------

    private static KeyCombination key(KeyCode code, KeyCombination.Modifier... mods) {
        return new KeyCodeCombination(code, mods);
    }

    private static final KeyCombination.Modifier CTRL = KeyCombination.SHORTCUT_DOWN;
    private static final KeyCombination.Modifier SHIFT = KeyCombination.SHIFT_DOWN;

    private void shortcuts(KeyEvent e) {
        if (palette.isOpen()) return;
        Runnable action = null;
        if (key(KeyCode.N, CTRL).match(e)) action = this::newNote;
        else if (key(KeyCode.D, CTRL, SHIFT).match(e)) action = this::daily;
        else if (key(KeyCode.P, CTRL).match(e)) action = () -> openPalette("");
        else if (key(KeyCode.P, CTRL, SHIFT).match(e)) action = () -> openPalette(">");
        else if (key(KeyCode.O, CTRL, SHIFT).match(e) || key(KeyCode.R, CTRL).match(e)) action = () -> openPalette("#");
        else if (key(KeyCode.O, CTRL).match(e)) action = this::openFile;
        else if (key(KeyCode.S, CTRL).match(e)) action = () -> {
            if (current() != null && current().save()) say("Saved");
        };
        else if (key(KeyCode.W, CTRL).match(e)) action = this::closeCurrent;
        else if (key(KeyCode.F, CTRL).match(e)) action = () -> find(false);
        else if (key(KeyCode.H, CTRL).match(e)) action = () -> find(true);
        else if (key(KeyCode.F, CTRL, SHIFT).match(e)) action = this::focusSearch;
        else if (key(KeyCode.E, CTRL).match(e)) action = this::togglePreview;
        else if (key(KeyCode.BACK_SLASH, CTRL).match(e)) action = this::toggleSidebar;
        else if (key(KeyCode.Y, CTRL, SHIFT).match(e)) action = this::showHistory;
        else if (key(KeyCode.EQUALS, CTRL).match(e) || key(KeyCode.PLUS, CTRL).match(e) || key(KeyCode.ADD, CTRL).match(e)) action = () -> zoom(1);
        else if (key(KeyCode.MINUS, CTRL).match(e) || key(KeyCode.SUBTRACT, CTRL).match(e)) action = () -> zoom(-1);
        else if (key(KeyCode.DIGIT0, CTRL).match(e)) action = () -> zoom(0);
        else if (e.getCode() == KeyCode.F11) action = this::toggleFocus;
        else if (e.getCode() == KeyCode.F1) action = this::showShortcuts;
        else if (e.getCode() == KeyCode.F2 && e.getTarget() != noteList) action = this::renameCurrent;
        else if (e.getCode() == KeyCode.ESCAPE && focusMode && !e.isConsumed()) action = this::toggleFocus;
        if (action != null) {
            e.consume();
            action.run();
        }
    }

    private void find(boolean replace) {
        if (current() != null) current().openFind(replace);
    }

    private void focusSearch() {
        if (!settings.sidebar()) toggleSidebar();
        filter.requestFocus();
        filter.selectAll();
    }

    private void focusEditor() {
        if (current() != null) current().area.requestFocus();
    }

    private void closeCurrent() {
        EditorTab t = current();
        if (t == null) return;
        beforeClose(t);
        tabs.getTabs().remove(t);
        // Removing a tab by hand does not fire its closed event; the tab's own tidy-up does.
        if (t.getOnClosed() != null) t.getOnClosed().handle(null);
    }

    private void showShortcuts() {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.initOwner(stage);
        a.setTitle("Keyboard shortcuts");
        a.setHeaderText("Jungey Notepad shortcuts");
        a.setContentText("""
                Ctrl+P            jump to a note, heading or command
                Ctrl+R            go to a heading in this note
                Ctrl+N            new note
                Ctrl+Shift+D      today's journal page
                Ctrl+Shift+F      search every note
                Ctrl+F / Ctrl+H   find / replace in this note
                Ctrl+E            preview beside the text
                Ctrl+W            close the note
                F2                rename   ·   Ctrl+Shift+Y  history

                Ctrl+Enter        tick a task (or make the line one)
                Ctrl+B / Ctrl+I   bold / italic   ·   Ctrl+`  code
                Ctrl+K            link (uses a copied address)
                Ctrl+Shift+H      heading level
                Ctrl+D            duplicate line   ·   Alt+↑/↓  move lines
                Tab / Shift+Tab   indent / outdent list items
                F5                insert the date and time
                Enter on a list   carries the list on; twice ends it

                Ctrl+= / Ctrl+-   text size   ·   F11  focus mode
                Paste an address over words to link them.""");
        a.getDialogPane().setMinWidth(560);
        a.getDialogPane().lookup(".content").setStyle("-fx-font-family: monospace;");
        a.show();
    }

    // ---------- look ----------

    private void applyLook() {
        root.getStyleClass().removeAll("dark", "light", "mono");
        root.getStyleClass().add(settings.dark() ? "dark" : "light");
        if (settings.mono()) root.getStyleClass().add("mono");
    }

    private void toggleTheme() {
        settings.dark(!settings.dark());
        applyLook();
    }

    private void toggleMono() {
        settings.mono(!settings.mono());
        applyLook();
    }

    private void toggleSidebar() {
        settings.sidebar(!settings.sidebar());
        frame.setLeft(settings.sidebar() && !focusMode ? sidebar : null);
    }

    private void togglePreview() {
        boolean show = current() == null ? !settings.preview() : !current().previewShown();
        settings.preview(show);
        for (Tab t : tabs.getTabs()) if (t instanceof EditorTab e) e.showPreview(show);
    }

    private void toggleLineNumbers() {
        settings.lineNumbers(!settings.lineNumbers());
        for (Tab t : tabs.getTabs()) if (t instanceof EditorTab e) e.setLineNumbers(settings.lineNumbers());
    }

    private void toggleWrap() {
        settings.wrap(!settings.wrap());
        for (Tab t : tabs.getTabs()) if (t instanceof EditorTab e) e.setWrap(settings.wrap());
    }

    private void zoom(int by) {
        double size = by == 0 ? 15 : Math.max(10, Math.min(32, settings.fontSize() + by));
        settings.fontSize(size);
        for (Tab t : tabs.getTabs()) if (t instanceof EditorTab e) e.setFontSize(size);
        say("Text size " + (int) size);
    }

    private void toggleFocus() {
        focusMode = !focusMode;
        frame.setTop(focusMode ? null : topBar);
        frame.setBottom(focusMode ? null : statusBar);
        frame.setLeft(!focusMode && settings.sidebar() ? sidebar : null);
        tabs.getStyleClass().remove("focus");
        if (focusMode) tabs.getStyleClass().add("focus");
        stage.setFullScreenExitHint("");
        stage.setFullScreen(focusMode);
        if (focusMode) say("Focus mode - F11 or Esc to leave");
        focusEditor();
    }

    // ---------- status ----------

    private void tabChanged(Tab old, Tab now) {
        if (watched != null) {
            watched.stateProperty().removeListener(stateListener);
            watched.statsProperty().removeListener(statsListener);
            watched.area.caretPositionProperty().removeListener(caretListener);
        }
        if (old instanceof EditorTab o) {
            o.save();
            Platform.runLater(() -> {
                if (tabs.getTabs().contains(o)) tidyName(o);
            });
        }
        watched = now instanceof EditorTab e ? e : null;
        if (watched != null) {
            watched.stateProperty().addListener(stateListener);
            watched.statsProperty().addListener(statsListener);
            watched.area.caretPositionProperty().addListener(caretListener);
            showState(watched.stateProperty().get());
            showStats(watched.statsProperty().get());
            showCaret();
            stage.setTitle(watched.displayName() + " - Jungey Notepad");
        } else {
            stage.setTitle("Jungey Notepad");
        }
        noteList.refresh();
    }

    /** An "Untitled" note still open, left for another: named after its title now. */
    private void tidyName(EditorTab t) {
        Path p = t.path();
        String title = Markdown.title(t.area.getText());
        if (!p.getFileName().toString().matches("Untitled( \\d+)?\\.md") || title.isBlank() || title.startsWith("Untitled")) return;
        try {
            t.save();
            Path to = library.rename(p, title);
            history.moved(p, to);
            t.moved(to);
            replacePinned(p, to);
            madeEmpty.remove(p);
            rescan();
        } catch (IOException ignored) {
            // Keeps its name; tried again next time.
        }
    }

    private void showState(EditorTab.State s) {
        saveState.getStyleClass().removeAll("saved", "editing", "failed");
        if (s == null) {
            saveState.setText("");
            where.setText(tilde(library.root()));
            caret.setText("");
            counts.setText("");
            tasksLabel.setText("");
            return;
        }
        switch (s) {
            case SAVED -> {
                saveState.setText("● Saved");
                saveState.getStyleClass().add("saved");
            }
            case EDITING -> {
                saveState.setText("● Editing");
                saveState.getStyleClass().add("editing");
            }
            case FAILED -> {
                saveState.setText("● Could not save");
                saveState.getStyleClass().add("failed");
            }
        }
        if (watched != null) {
            Path p = watched.path();
            where.setText(p.startsWith(library.root()) ? library.root().relativize(p).toString() : tilde(p));
            stage.setTitle(watched.displayName() + " - Jungey Notepad");
        }
    }

    private void showStats(TextStats s) {
        counts.setText(String.format(Locale.ROOT, "%,d words  ·  %,d characters  ·  %d min read", s.words(), s.characters(), s.readingMinutes()));
        tasksLabel.setText(s.tasks() == 0 ? "" : "☑ " + s.tasksDone() + " of " + s.tasks() + " done");
    }

    private void showCaret() {
        if (watched == null) return;
        caret.setText("Ln " + (watched.area.getCurrentParagraph() + 1) + ", Col " + (watched.area.getCaretColumn() + 1));
    }

    private void say(String message) {
        toast.setText(message);
        FadeTransition in = new FadeTransition(Duration.millis(140), toast);
        in.setToValue(1);
        FadeTransition out = new FadeTransition(Duration.millis(400), toast);
        out.setToValue(0);
        new SequentialTransition(in, new PauseTransition(Duration.seconds(2.2)), out).play();
    }

    // ---------- the session ----------

    private void restoreSession() {
        List<String> paths = new ArrayList<>(settings.openNotes());
        for (String arg : getParameters().getRaw()) {
            if (!arg.startsWith("--")) paths.add(Path.of(arg).toAbsolutePath().normalize().toString());
        }
        for (String p : paths) {
            if (Files.isRegularFile(Path.of(p))) open(Path.of(p), -1);
        }
        String active = getParameters().getRaw().isEmpty() ? settings.active()
                : Path.of(getParameters().getRaw().getLast()).toAbsolutePath().normalize().toString();
        EditorTab a = tabFor(Path.of(active.isEmpty() ? "/" : active));
        if (a != null) tabs.getSelectionModel().select(a);
        if (tabs.getTabs().isEmpty()) refreshWelcome();
    }

    private void saveAll() {
        for (Tab t : tabs.getTabs()) if (t instanceof EditorTab e) e.save();
    }

    private void checkDisk() {
        for (Tab t : tabs.getTabs()) {
            if (t instanceof EditorTab e) {
                String message = e.checkDisk();
                if (message != null) say(message);
            }
        }
    }

    private void shutdown() {
        List<String> open = new ArrayList<>();
        for (Tab t : new ArrayList<>(tabs.getTabs())) {
            if (t instanceof EditorTab e) {
                e.save();
                try {
                    history.keep(e.path(), e.area.getText(), true);
                } catch (IOException ignored) {
                    // Saved regardless.
                }
                tidyName(e);
                if (madeEmpty.contains(e.path()) && Markdown.plain(e.area.getText().replace("#", "")).isBlank()) {
                    try {
                        Files.deleteIfExists(e.path());
                    } catch (IOException ignored) {
                        // An empty file left behind is harmless.
                    }
                    continue;
                }
                open.add(e.path().toString());
            }
        }
        settings.openNotes(open);
        settings.active(current() != null ? current().path().toString() : "");
        settings.pinned(pinned);
        // The scene's size, which is what the next start asks for: the stage's includes the
        // title bar, and would grow the window by its height every time.
        if (!stage.isFullScreen()) settings.size(stage.getScene().getWidth(), stage.getScene().getHeight());
        settings.save();
        ticker.shutdownNow();
        reader.shutdownNow();
    }

    // ---------- small parts ----------

    private static Button pill(String text, String tip, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("pill");
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private static Button iconButton(String glyph, String tip, Runnable action) {
        Button b = new Button(glyph);
        b.getStyleClass().add("icon-button");
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        b.setFocusTraversable(false);
        return b;
    }

    private static MenuItem item(String text, String keys, Runnable action) {
        MenuItem m = new MenuItem(keys.isEmpty() ? text : text + "    " + keys);
        m.setOnAction(e -> action.run());
        return m;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Region gap(double w) {
        Region r = new Region();
        r.setMinWidth(w);
        return r;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String tilde(Path p) {
        String home = System.getProperty("user.home");
        String s = p.toString();
        return s.startsWith(home) ? "~" + s.substring(home.length()) : s;
    }

    static String ago(Instant when) {
        long minutes = ChronoUnit.MINUTES.between(when, Instant.now());
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        var local = when.atZone(ZoneId.systemDefault());
        LocalDate day = local.toLocalDate();
        LocalDate today = LocalDate.now();
        if (day.equals(today)) return local.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (day.equals(today.minusDays(1))) return "Yesterday";
        if (day.isAfter(today.minusDays(7))) return local.format(WEEKDAY);
        return local.format(day.getYear() == today.getYear() ? SHORT_DAY : DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
    }
}
