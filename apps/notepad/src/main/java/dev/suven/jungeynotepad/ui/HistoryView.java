package dev.suven.jungeynotepad.ui;

import dev.suven.jungeynotepad.notes.History;
import dev.suven.jungeynotepad.notes.Library;
import dev.suven.jungeynotepad.notes.TextStats;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A note's earlier versions: pick a time, read what it said then, and bring it back.
 * Restoring is an ordinary edit - Ctrl+Z undoes it, and the text it replaced becomes a
 * version of its own.
 */
final class HistoryView {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    private HistoryView() {
    }

    static void show(Window owner, List<String> stylesheets, String rootStyle, String title, String current,
                     List<History.Version> versions, Consumer<String> restore) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("History - " + title);

        ListView<History.Version> list = new ListView<>();
        list.getItems().setAll(versions);
        list.getStyleClass().add("history-list");
        list.setPrefWidth(250);
        TextArea text = new TextArea();
        text.setEditable(false);
        text.setWrapText(true);
        text.getStyleClass().add("history-text");
        Label change = new Label();
        change.getStyleClass().add("history-change");

        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(History.Version item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                var when = item.when().atZone(ZoneId.systemDefault());
                LocalDate day = when.toLocalDate();
                String d = day.equals(LocalDate.now()) ? "Today" : day.equals(LocalDate.now().minusDays(1)) ? "Yesterday" : when.format(DAY);
                setText(d + ", " + when.format(TIME) + "    " + size(item.size()));
            }
        });

        int nowWords = TextStats.of(current).words();
        list.getSelectionModel().selectedItemProperty().addListener((o, a, v) -> {
            if (v == null) return;
            try {
                String s = Library.read(v.file());
                text.setText(s);
                int diff = nowWords - TextStats.of(s).words();
                change.setText(diff == 0 ? "Same length as now" : Math.abs(diff) + " words " + (diff > 0 ? "fewer" : "more") + " than now");
            } catch (IOException e) {
                text.setText("This version could not be read.");
            }
        });

        Button restoreButton = new Button("Restore this version");
        restoreButton.getStyleClass().add("accent-button");
        restoreButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        restoreButton.setOnAction(e -> {
            restore.accept(text.getText());
            stage.close();
        });
        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox buttons = new HBox(10, change, gap, close, restoreButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(12, 14, 12, 14));

        Label empty = new Label("No earlier versions yet. One is kept every few minutes while you write.");
        empty.getStyleClass().add("muted");
        SplitPane split = new SplitPane(list, text);
        split.setDividerPositions(0.3);
        BorderPane root = new BorderPane(versions.isEmpty() ? new VBox(empty) : split);
        root.setBottom(buttons);
        root.getStyleClass().addAll("history-root", rootStyle);
        if (versions.isEmpty()) ((VBox) root.getCenter()).setPadding(new Insets(24));

        Scene scene = new Scene(root, 860, 560);
        scene.getStylesheets().addAll(stylesheets);
        stage.setScene(scene);
        list.getSelectionModel().selectFirst();
        stage.show();
    }

    private static String size(long bytes) {
        return bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }
}
