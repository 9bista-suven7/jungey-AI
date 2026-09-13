package dev.suven.jungey.ui;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** The scrolling transcript. Jungey's lines type themselves in; yours appear at once. */
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
        Label label = new Label(text);
        label.getStyleClass().add("line-user");
        label.setWrapText(true);

        Label caret = new Label("› ");
        caret.getStyleClass().add("caret-user");

        HBox row = new HBox(caret, label);
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(label, Priority.ALWAYS);
        add(row);
    }

    /** A line from Jungey, typed out character by character. */
    public void addJungey(String text, Runnable onFinished) {
        Label label = new Label();
        label.getStyleClass().add("line-jungey");
        label.setWrapText(true);

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

    /** A failure, shown in red without the typing flourish. */
    public void addError(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("line-error");
        label.setWrapText(true);

        Label caret = new Label("⚠ ");
        caret.getStyleClass().add("caret-error");

        HBox row = new HBox(caret, label);
        HBox.setHgrow(label, Priority.ALWAYS);
        add(row);
    }

    /** A monospace detail block - readouts, tables, article text. */
    public void addDetail(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("detail");
        label.setWrapText(false);

        VBox box = new VBox(label);
        box.getStyleClass().add("detail-box");
        add(box);
    }

    /** A dim system line, used for the boot sequence. */
    public void addSystem(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("line-system");
        label.setWrapText(true);
        add(label);
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
    private void type(Label target, String text, Runnable onFinished) {
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
