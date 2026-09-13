package dev.suven.jungey;

import dev.suven.jungey.core.Brain;
import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.ui.ConsoleView;
import dev.suven.jungey.ui.ReactorView;
import dev.suven.jungey.ui.StatusBar;
import dev.suven.jungey.voice.Speaker;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * Jungey's window and event loop.
 *
 * <p>The window is undecorated so the HUD reads as an overlay rather than a normal
 * app; it is dragged by its header and closed from the header button or by typing
 * "exit".
 */
public class JungeyApp extends Application {

    private final Brain brain = new Brain();
    private final Speaker speaker = new Speaker();

    private ConsoleView console;
    private ReactorView reactor;
    private StatusBar statusBar;
    private TextField input;

    private double dragOffsetX, dragOffsetY;

    @Override
    public void start(Stage stage) {
        Config cfg = Config.get();

        reactor = new ReactorView(132);
        console = new ConsoleView();
        statusBar = new StatusBar(speaker.available() ? speaker.engineName() : "off");

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setTop(buildHeader(stage));
        root.setCenter(buildBody());
        root.setBottom(new VBox(buildInput(), statusBar));

        Scene scene = new Scene(root, 880, 560);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        scene.getStylesheets().add(
                getClass().getResource("/styles/jungey.css").toExternalForm());

        // Esc from anywhere returns focus to the prompt.
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) input.requestFocus();
        });

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("Jungey");
        stage.setScene(scene);
        stage.setMinWidth(620);
        stage.setMinHeight(420);
        stage.setAlwaysOnTop(cfg.bool("ui.alwaysOnTop"));
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();

        reactor.start();
        statusBar.start();
        input.requestFocus();

        boot();
    }

    private HBox buildHeader(Stage stage) {
        Label title = new Label("JUNGEY");
        title.getStyleClass().add("title");

        Label subtitle = new Label("local assistant · v0.1.0");
        subtitle.getStyleClass().add("subtitle");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label close = new Label("✕");
        close.getStyleClass().add("close-button");
        close.setOnMouseClicked(e -> shutdown());

        HBox header = new HBox(12, title, subtitle, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 18, 10, 20));
        header.getStyleClass().add("header");

        // Undecorated windows have no title bar, so the header is the drag handle.
        header.setOnMousePressed(e -> {
            dragOffsetX = e.getSceneX();
            dragOffsetY = e.getSceneY();
        });
        header.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });

        return header;
    }

    private HBox buildBody() {
        VBox reactorColumn = new VBox(reactor);
        reactorColumn.setAlignment(Pos.CENTER);
        reactorColumn.setPadding(new Insets(0, 8, 0, 14));
        reactorColumn.setMinWidth(168);

        HBox body = new HBox(reactorColumn, console);
        HBox.setHgrow(console, Priority.ALWAYS);
        return body;
    }

    private HBox buildInput() {
        Label prompt = new Label("›");
        prompt.getStyleClass().add("prompt-caret");

        input = new TextField();
        input.setPromptText("Ask me something…  (try \"help\")");
        input.getStyleClass().add("prompt-field");
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(e -> submit(input.getText()));

        HBox box = new HBox(10, prompt, input);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(10, 18, 10, 20));
        box.getStyleClass().add("prompt-bar");
        return box;
    }

    /** Prints the boot lines on a timer, then greets. */
    private void boot() {
        var lines = Personality.bootSequence();
        Timeline timeline = new Timeline();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            timeline.getKeyFrames().add(new KeyFrame(
                    Duration.millis(130.0 * (i + 1)),
                    e -> console.addSystem(line)));
        }

        timeline.getKeyFrames().add(new KeyFrame(
                Duration.millis(130.0 * lines.size() + 320),
                e -> {
                    String greeting = Personality.greeting();
                    console.addJungey(greeting);
                    speaker.say(greeting);
                }));

        timeline.play();
    }

    private void submit(String text) {
        if (text == null || text.isBlank()) return;

        input.clear();
        console.addUser(text);
        speaker.stop();   // cut off whatever is being said - a new command takes priority

        if (text.trim().equalsIgnoreCase("exit") || text.trim().equalsIgnoreCase("quit")) {
            String bye = Personality.farewell();
            console.addJungey(bye);
            speaker.say(bye);
            // Let the line finish typing and the voice start before the window goes.
            Timeline delay = new Timeline(new KeyFrame(Duration.seconds(2.2), e -> shutdown()));
            delay.play();
            return;
        }

        if (text.trim().equalsIgnoreCase("clear")) {
            console.clear();
            return;
        }

        boolean slow = brain.isSlow(text);
        reactor.setState(ReactorView.State.THINKING);

        if (slow) {
            String wait = Personality.thinking();
            console.addSystem(wait);
        }

        brain.handle(text).thenAccept(result -> Platform.runLater(() -> render(result)));
    }

    private void render(SkillResult result) {
        if (!result.ok()) {
            reactor.setState(ReactorView.State.ERROR);
            console.addError(result.speech());
            // Hold the red long enough to register, then settle back.
            Timeline back = new Timeline(new KeyFrame(Duration.seconds(1.6),
                    e -> reactor.setState(ReactorView.State.IDLE)));
            back.play();
            return;
        }

        reactor.setState(ReactorView.State.SPEAKING);
        speaker.say(result.speech());

        console.addJungey(result.speech(), () -> {
            if (result.hasDetail()) {
                console.addDetail(result.detail());
            }
            reactor.setState(ReactorView.State.IDLE);
        });
    }

    private void shutdown() {
        reactor.stop();
        statusBar.stop();
        speaker.shutdown();
        brain.shutdown();
        Platform.exit();
        // JavaFX will not always tear down AWT's Desktop helper threads on its own.
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
