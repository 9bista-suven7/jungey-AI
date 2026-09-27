package dev.suven.jungey.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * The scrolling transcript. Jungey's lines type themselves in; yours appear at once. Every
 * line can be selected and copied - drag across it, then Ctrl+C or right-click.
 */
public class ConsoleView extends ScrollPane {

    private final VBox lines = new VBox(6);

    public ConsoleView() {
        lines.setPadding(new Insets(14, 18, 14, 18));
        lines.setFillWidth(true);

        setContent(lines);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        getStyleClass().add("console");
        VBox.setVgrow(this, Priority.ALWAYS);

        // Follow the tail as content grows.
        lines.heightProperty().addListener((obs, old, now) -> setVvalue(1.0));
    }

    /** A line the user typed. */
    public void addUser(String text) {
        TextArea label = selectable(text, "line-user", true);

        Label caret = new Label("› ");
        caret.getStyleClass().add("caret-user");

        HBox row = new HBox(caret, label);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(label, Priority.ALWAYS);
        add(row);
    }

    /** A line from Jungey, typed out character by character. */
    public void addJungey(String text, Runnable onFinished) {
        TextArea label = selectable("", "line-jungey", true);

        Label caret = new Label("◆ ");
        caret.getStyleClass().add("caret-jungey");

        HBox row = new HBox(caret, label);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(label, Priority.ALWAYS);
        add(row);

        type(label, text, onFinished);
    }

    public void addJungey(String text) {
        addJungey(text, null);
    }

    /**
     * A line from Jungey that fills in as it is generated - the arriving text is its own
     * typewriter. Returns the appender; safe to call from any thread.
     */
    public Consumer<String> addJungeyLive() {
        TextArea label = selectable("", "line-jungey", true);

        Label caret = new Label("◆ ");
        caret.getStyleClass().add("caret-jungey");

        HBox row = new HBox(caret, label);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(label, Priority.ALWAYS);
        add(row);

        return chunk -> Platform.runLater(() -> label.appendText(chunk));
    }

    /** A failure, shown in red without the typing flourish. */
    public void addError(String text) {
        TextArea label = selectable(text, "line-error", true);

        Label caret = new Label("⚠ ");
        caret.getStyleClass().add("caret-error");

        HBox row = new HBox(caret, label);
        HBox.setHgrow(label, Priority.ALWAYS);
        add(row);
    }

    /** A monospace detail block - readouts, tables, article text. */
    public void addDetail(String text) {
        TextArea label = selectable(text, "detail", false);

        VBox box = new VBox(label);
        box.getStyleClass().add("detail-box");
        add(box);
    }

    /** A dim system line, used for the boot sequence. */
    public void addSystem(String text) {
        add(selectable(text, "line-system", true));
    }

    /** A captured picture, shown inline with a caption. */
    public void addImage(java.nio.file.Path file, String caption) {
        javafx.scene.image.ImageView picture = new javafx.scene.image.ImageView(
                new javafx.scene.image.Image(file.toUri().toString(), 440, 0, true, true));
        picture.setPreserveRatio(true);

        Label label = new Label(caption);
        label.getStyleClass().add("line-system");

        VBox box = new VBox(6, picture, label);
        box.getStyleClass().add("detail-box");
        add(box);
    }

    /** Put an arbitrary node in the transcript - used for the live camera feed. */
    public void addNode(javafx.scene.Node node) {
        add(node);
    }

    public void removeNode(javafx.scene.Node node) {
        Platform.runLater(() -> lines.getChildren().remove(node));
    }

    /**
     * Text that looks like a label but can be selected and copied: a read-only text area,
     * see-through, exactly as tall as its text, so it reads as one line of the transcript.
     */
    private TextArea selectable(String content, String style, boolean wrap) {
        TextArea area = new TextArea(content);
        area.getStyleClass().addAll("selectable", style);
        area.setEditable(false);
        area.setWrapText(wrap);
        area.setFocusTraversable(false);
        area.setPrefRowCount(1);
        area.setPrefColumnCount(1);
        area.setMinWidth(0);
        area.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(area, Priority.ALWAYS);

        // The height of the text inside, once the skin that draws it exists - and again
        // whenever it wraps differently or grows.
        area.skinProperty().addListener((obs, old, skin) -> {
            if (skin == null || !(area.lookup(".text") instanceof Text text)) return;
            Runnable fit = () -> {
                double h = Math.ceil(text.getLayoutBounds().getHeight()) + 2;
                area.setPrefHeight(h);
                area.setMinHeight(h);
                area.setMaxHeight(h);
            };
            text.layoutBoundsProperty().addListener((o, was, now) -> fit.run());
            fit.run();
        });

        // The wheel scrolls the transcript, never the line under the pointer.
        area.addEventFilter(ScrollEvent.SCROLL, e -> {
            double extra = lines.getHeight() - getViewportBounds().getHeight();
            if (extra > 0) setVvalue(Math.max(0, Math.min(1, getVvalue() - e.getDeltaY() / extra)));
            e.consume();
        });
        return area;
    }

    private void add(javafx.scene.Node node) {
        Platform.runLater(() -> lines.getChildren().add(node));
    }

    public void clear() {
        Platform.runLater(() -> lines.getChildren().clear());
    }

    /**
     * Typewriter effect. Deliberately fast - 14ms a character reads as "printing",
     * where anything slower starts to feel like the app is stalling.
     */
    private void type(TextArea target, String text, Runnable onFinished) {
        Platform.runLater(() -> {
            Timeline timeline = new Timeline();
            for (int i = 0; i <= text.length(); i++) {
                final int end = i;
                timeline.getKeyFrames().add(new KeyFrame(
                        Duration.millis(14.0 * i),
                        e -> target.setText(text.substring(0, end))));
            }
            timeline.setCycleCount(1);
            if (onFinished != null) {
                timeline.statusProperty().addListener((obs, old, now) -> {
                    if (now == Animation.Status.STOPPED) onFinished.run();
                });
            }
            timeline.play();
        });
    }
}
