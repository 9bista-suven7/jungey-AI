package dev.suven.jungeynotepad.ui;

import dev.suven.jungeynotepad.notes.Fuzzy;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Ctrl+P: type a few letters of anything - a note, a command, a heading - and press Enter.
 * The keyboard's way to everywhere, so nothing needs hunting for in a menu.
 */
final class Palette extends StackPane {

    /** One choice. {@code kind} is the small label on the right: Note, Command, Heading. */
    record Item(String title, String detail, String kind, Runnable action) {
    }

    private final TextField field = new TextField();
    private final ListView<Item> list = new ListView<>();
    private final Label hint = new Label();
    private Supplier<List<Item>> source = List::of;
    private List<Item> items = List.of();
    private Runnable onClose = () -> { };

    Palette() {
        getStyleClass().add("palette-shade");
        setVisible(false);

        field.getStyleClass().add("palette-field");
        list.getStyleClass().add("palette-list");
        list.setFixedCellSize(46);
        list.setPrefHeight(46 * 8 + 4);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label title = new Label(item.title());
                title.getStyleClass().add("palette-title");
                Label detail = new Label(item.detail());
                detail.getStyleClass().add("palette-detail");
                VBox text = new VBox(1, title, detail);
                if (item.detail().isEmpty()) text.getChildren().remove(detail);
                text.setAlignment(Pos.CENTER_LEFT);
                Label kind = new Label(item.kind());
                kind.getStyleClass().add("palette-kind");
                Region gap = new Region();
                HBox.setHgrow(gap, Priority.ALWAYS);
                HBox row = new HBox(10, text, gap, kind);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 1) run();
        });
        hint.getStyleClass().add("palette-hint");
        hint.setText("↑↓ choose   Enter open   Esc close   > commands   # headings");

        VBox box = new VBox(8, field, list, hint);
        box.getStyleClass().add("palette");
        box.setMaxWidth(620);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        box.setPadding(new Insets(12));
        StackPane.setAlignment(box, Pos.TOP_CENTER);
        StackPane.setMargin(box, new Insets(70, 20, 20, 20));
        getChildren().add(box);

        setOnMouseClicked(e -> {
            if (e.getTarget() == this) close();
        });
        field.textProperty().addListener((o, a, q) -> filter(q));
        field.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> close();
                case ENTER -> run();
                case DOWN -> move(1);
                case UP -> move(-1);
                case PAGE_DOWN -> move(7);
                case PAGE_UP -> move(-7);
                default -> {
                    return;
                }
            }
            e.consume();
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) run();
            if (e.getCode() == KeyCode.ESCAPE) close();
        });
    }

    void open(String start, Supplier<List<Item>> source, Runnable onClose) {
        this.source = source;
        this.onClose = onClose;
        items = source.get();
        setVisible(true);
        field.setText(start);
        filter(start);
        field.requestFocus();
        field.end();
    }

    boolean isOpen() {
        return isVisible();
    }

    void close() {
        setVisible(false);
        onClose.run();
    }

    private void filter(String q) {
        if (!isVisible()) return;
        String query = q;
        String kind = null;
        if (q.startsWith(">")) {
            kind = "Command";
            query = q.substring(1);
        } else if (q.startsWith("#")) {
            kind = "Heading";
            query = q.substring(1);
        }
        record Scored(Item item, int score, int order) {
        }
        List<Scored> scored = new ArrayList<>();
        int order = 0;
        String qq = query.trim();
        for (Item it : items) {
            order++;
            if (kind != null ? !it.kind().equals(kind) : it.kind().equals("Heading")) continue;
            int s = qq.isEmpty() ? 1 : Math.max(Fuzzy.score(qq, it.title()), Fuzzy.score(qq, it.detail()) / 3);
            if (s > 0) scored.add(new Scored(it, s, order));
        }
        // Empty, keep the given order (recent notes first); typed, the best fit first.
        scored.sort(qq.isEmpty() ? Comparator.comparingInt(Scored::order)
                : Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::order));
        list.getItems().setAll(scored.stream().limit(200).map(Scored::item).toList());
        list.getSelectionModel().selectFirst();
        list.scrollTo(0);
    }

    private void move(int by) {
        int n = list.getItems().size();
        if (n == 0) return;
        int i = Math.max(0, Math.min(n - 1, list.getSelectionModel().getSelectedIndex() + by));
        list.getSelectionModel().select(i);
        list.scrollTo(Math.max(0, i - 3));
    }

    private void run() {
        Item it = list.getSelectionModel().getSelectedItem();
        if (it == null) return;
        close();
        it.action().run();
    }

    /** Re-reads the source, for when what it lists has changed while open. */
    void reload() {
        if (!isVisible()) return;
        items = source.get();
        filter(field.getText());
    }
}
