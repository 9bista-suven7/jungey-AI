package dev.suven.jungey;

import dev.suven.jungey.core.Brain;
import dev.suven.jungey.core.Build;
import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.SingleInstance;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.Turn;
import dev.suven.jungey.skills.BriefingSkill;
import dev.suven.jungey.ui.Avatar;
import dev.suven.jungey.ui.CameraView;
import dev.suven.jungey.ui.ConsoleView;
import dev.suven.jungey.ui.FaceView;
import dev.suven.jungey.ui.ReactorView;
import dev.suven.jungey.ui.StatusBar;
import dev.suven.jungey.voice.Listener;
import dev.suven.jungey.voice.Speaker;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
public class JungeyApp extends Application implements dev.suven.jungey.core.Viewport {

    /** This process's claim to be the one running Jungey - see {@link SingleInstance}. */
    private static SingleInstance instance;

    private final Speaker speaker = new Speaker();
    private final Brain brain = new Brain(speaker, this);
    private final CameraView camera = new CameraView();

    private Stage stage;

    private ConsoleView console;
    private Avatar avatar;
    private StatusBar statusBar;
    private Listener listener;
    private TextField input;

    private double dragOffsetX, dragOffsetY;
    private boolean earsAnnounced;
    private boolean spokenTo;

    /** Requests handed to the brain and not yet answered. */
    private int inFlight;

    /** The name was said over Jungey's own voice; the next command changes the subject. */
    private boolean cutIn;

    private final java.util.List<String> history = new java.util.ArrayList<>();
    private int historyIndex;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Config cfg = Config.get();

        // A face that talks when one is installed (scripts/setup-face.sh), the reactor if not.
        avatar = FaceView.load(speaker.meter()).<Avatar>map(face -> face).orElseGet(() -> new ReactorView(132));
        console = new ConsoleView();
        listener = new Listener(speaker,
                heard -> Platform.runLater(() -> {
                    spokenTo = true;
                    submit(heard);
                }),
                state -> Platform.runLater(() -> onEars(state)));
        // Named mid-sentence: whatever was being answered is dropped, not just silenced.
        listener.onBargeIn(() -> {
            brain.interrupt();
            Platform.runLater(() -> cutIn = true);
        });
        statusBar = new StatusBar(speaker::statusLabel, this::earsLabel);

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
        // Without these the taskbar button is blank - the .desktop icon does not reach the window.
        for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
            var icon = getClass().getResource("/icons/jungey-" + size + ".png");
            if (icon != null) stage.getIcons().add(new javafx.scene.image.Image(icon.toExternalForm()));
        }
        stage.setScene(scene);
        stage.setMinWidth(620);
        stage.setMinHeight(420);
        stage.setAlwaysOnTop(cfg.bool("ui.alwaysOnTop"));
        stage.setOnCloseRequest(e -> shutdown());
        // Nobody watches a minimised face; stop drawing it until the window is back.
        stage.iconifiedProperty().addListener((obs, was, minimised) -> {
            if (minimised) avatar.stop();
            else avatar.start();
        });
        stage.show();

        avatar.start();
        statusBar.start();
        input.requestFocus();

        if (instance != null) {
            instance.listen(new SingleInstance.Handler() {
                @Override
                public void show() {
                    Platform.runLater(JungeyApp.this::summon);
                }

                @Override
                public void handOver() {
                    Platform.runLater(() -> {
                        console.addSystem("A newer Jungey is starting - handing over.");
                        shutdown();
                    });
                }
            });
        }

        boot();
        startEars();
    }

    /**
     * Jungey was opened again - Super+J, the menu, the desktop icon - while already running.
     * Come to the front and listen straight away, so the shortcut doubles as push-to-talk.
     */
    private void summon() {
        stage.setIconified(false);
        stage.show();
        stage.toFront();
        // Window managers are wary of windows asking for focus; a moment on top gets it seen.
        stage.setAlwaysOnTop(true);
        stage.setAlwaysOnTop(Config.get().bool("ui.alwaysOnTop"));
        stage.requestFocus();
        input.requestFocus();

        if (speaker.speaking() || inFlight > 0) return;   // busy answering; being in front is enough
        String line = Personality.summoned();
        console.addJungey(line);
        speaker.say(line);
        listener.followUp();
    }

    /**
     * Speech input is optional: without the model Jungey stays keyboard-only and says so
     * once, rather than failing at every utterance.
     */
    private void startEars() {
        if (!Config.get().bool("voice.input.enabled")) return;

        if (!Listener.modelInstalled()) {
            console.addSystem("Speech input idle - no voice model at " + Listener.modelPath() + ".");
            return;
        }
        if (!Listener.micPresent()) {
            console.addSystem("Speech input idle - no microphone found.");
            return;
        }

        console.addSystem("Loading voice model…");
        listener.start();
    }

    private String earsLabel() {
        return switch (listener.state()) {
            case OFF -> "off";
            case WAITING -> "wake";
            case LISTENING -> listener.conversing() ? "talk" : "live";
        };
    }

    private void onEars(Listener.State state) {
        if (state == Listener.State.LISTENING) {
            // Addressed mid-sentence: stop talking. But a follow-up window opens while the
            // reply that offered it is still being spoken, and cutting that off is how a
            // spoken answer ends up truncated.
            if (listener.wokenByName()) {
                if (speaker.speaking() || inFlight > 0) {
                    // Addressed while still answering: drop the old answer, not just its sound.
                    brain.interrupt();
                    cutIn = true;
                }
                speaker.stop();
                console.addSystem("Yes?");
                avatar.setState(Avatar.State.THINKING);
            }
        } else if (state == Listener.State.WAITING) {
            // The model takes a while to load, so the first WAITING is when the ears truly open.
            if (!earsAnnounced) {
                earsAnnounced = true;
                console.addSystem("Listening for \""
                        + Config.get().str("voice.input.wakeWord", "purple")
                        + "\" - ears: " + listener.inputLabel() + ".");
            }
            avatar.setState(Avatar.State.IDLE);
        }
    }

    private HBox buildHeader(Stage stage) {
        Label title = new Label("JUNGEY");
        title.getStyleClass().add("title");

        Label subtitle = new Label("local assistant · v" + Build.version());
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
        Node face = avatar.node();
        VBox avatarColumn = new VBox(face);
        avatarColumn.setAlignment(Pos.CENTER);
        avatarColumn.setPadding(new Insets(0, 8, 0, 14));
        avatarColumn.setMinWidth(Math.max(168, face.prefWidth(-1) + 22));

        HBox body = new HBox(avatarColumn, console);
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
        input.setOnAction(e -> {
            spokenTo = false;
            submit(input.getText());
        });

        // Up and down walk back through what you have typed, as a shell would.
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.UP) {
                recall(-1);
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN) {
                recall(1);
                e.consume();
            }
        });

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
                    briefIfFirstToday();
                }));

        timeline.play();
    }

    /**
     * The first start of the day follows the greeting with a briefing; later starts leave it
     * at hello. Either way the sentinel starts watching once the talking is done.
     */
    private void briefIfFirstToday() {
        Runnable settle = () -> {
            brain.startSentinel();
            // Fillers ready as audio before they are wanted - after the greeting, not ahead of it.
            speaker.prepare(Personality.stockLines());
        };

        if (!Config.get().bool("briefing.onBoot") || !BriefingSkill.firstStartToday()) {
            settle.run();
            return;
        }
        inFlight++;
        long turn = Turn.latest();
        brain.bootBriefing(false).thenAccept(result -> Platform.runLater(() -> {
            inFlight--;
            // Spoken to before the briefing was ready: they have moved on, so it waits on screen.
            if (Turn.superseded(turn)) {
                if (result.ok()) console.addDetail(result.hasDetail() ? result.detail() : result.speech());
            } else {
                render(result, turn);
            }
            settle.run();
        }));
    }

    /** Step through past commands. index == history.size() is the empty prompt. */
    private void recall(int direction) {
        if (history.isEmpty()) return;

        historyIndex = Math.max(0, Math.min(history.size(), historyIndex + direction));
        input.setText(historyIndex == history.size() ? "" : history.get(historyIndex));
        input.positionCaret(input.getText().length());
    }

    private void submit(String text) {
        if (text == null || text.isBlank()) return;

        if (history.isEmpty() || !history.get(history.size() - 1).equals(text)) {
            history.add(text);
        }
        historyIndex = history.size();

        input.clear();
        console.addUser(text);
        transcribe("you", text);

        // Cutting across an answer still being given - or being worked out. The old one is
        // let go of gently, and the new one opens with a word, as a person changes subject.
        boolean changingSubject = speaker.speaking() || inFlight > 0 || cutIn;
        cutIn = false;
        // Old reply first, voice second: a model mid-answer could otherwise slip one more
        // sentence into the queue between the two.
        if (changingSubject) brain.interrupt();
        speaker.stop();

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
        avatar.setState(Avatar.State.THINKING);

        String filler = null;
        if (changingSubject && wantsFiller(brain.route(text))) {
            filler = Personality.transition();
        } else if (slow && spokenTo) {
            // Asked aloud, silence while the answer is fetched sounds like not being heard.
            filler = Personality.thinking();
        }
        if (filler != null) speaker.say(filler);
        if (filler != null || slow) console.addSystem(filler != null ? filler : Personality.thinking());

        inFlight++;
        var answer = brain.handle(text);
        long turn = Turn.latest();   // handle() has just begun it
        answer.thenAccept(result -> Platform.runLater(() -> {
            inFlight--;
            render(result, turn);
        }));
    }

    /**
     * Whether a change of subject should open with a filler. Not for "stop" or "that's all",
     * where the silence is the point, nor for "thanks", which is already the small word.
     */
    private static boolean wantsFiller(Skill skill) {
        return skill == null || !java.util.Set.of("voice", "chatter", "training").contains(skill.name());
    }

    private void render(SkillResult result, long turn) {
        transcribe("jungey", result.speech());

        // Asked something else since: the answer is kept on screen but not said, so two
        // replies never talk over each other.
        if (Turn.superseded(turn)) {
            if (!result.streamed() && result.ok() && !result.speech().isBlank()) {
                console.addSystem("(earlier) " + result.speech());
            }
            return;
        }

        // Already shown and spoken while it arrived - nothing left but to settle.
        if (result.streamed()) {
            avatar.setState(Avatar.State.IDLE);
            if (spokenTo) listener.followUp();
            return;
        }

        if (!result.ok()) {
            avatar.setState(Avatar.State.ERROR);
            console.addError(result.speech());
            // Hold the red long enough to register, then settle back.
            Timeline back = new Timeline(new KeyFrame(Duration.seconds(1.6),
                    e -> avatar.setState(Avatar.State.IDLE)));
            back.play();
            return;
        }

        // A blank reply means the skill has already done the talking - or been told not to.
        if (result.speech().isBlank()) {
            avatar.setState(Avatar.State.IDLE);
            if (spokenTo) listener.followUp();
            return;
        }

        avatar.setState(Avatar.State.SPEAKING);
        speaker.say(result.speech());

        console.addJungey(result.speech(), () -> {
            if (result.hasDetail()) {
                console.addDetail(result.detail());
            }
            avatar.setState(Avatar.State.IDLE);

            // Having just been spoken to, stay open briefly so a follow-up needs no wake word.
            if (spokenTo) listener.followUp();
        });
    }

    @Override
    public void showCamera(String device) {
        Platform.runLater(() -> {
            try {
                camera.start(device);
                console.addNode(camera);
            } catch (java.io.IOException e) {
                console.addError("The camera would not start: " + e.getMessage());
            }
        });
    }

    @Override
    public void hideCamera() {
        Platform.runLater(() -> {
            camera.stop();
            console.removeNode(camera);
        });
    }

    @Override
    public boolean cameraVisible() {
        return camera.isRunning();
    }

    @Override
    public byte[] currentFrame() {
        return camera.latestFrame();
    }

    @Override
    public void showImage(java.nio.file.Path file, String caption) {
        console.addImage(file, caption);
    }

    /** Skills run off the UI thread, but the clipboard may only be read on it. */
    @Override
    public String clipboardText() {
        if (Platform.isFxApplicationThread()) return readClipboard();

        java.util.concurrent.CompletableFuture<String> answer = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> answer.complete(readClipboard()));
        try {
            return answer.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Append one line of the conversation to disk, so it outlives the window. Failure is
     * silent on purpose - losing a log line must never interrupt the exchange itself.
     */
    private static void transcribe(String who, String text) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(
                    System.getProperty("user.home"), ".local", "share", "jungey");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("transcript.log"),
                    java.time.LocalDateTime.now().withNano(0) + "  " + who + ": "
                            + text.replace("\n", " ") + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException ignored) {
            // Not worth telling anyone about.
        }
    }

    @Override
    public void announce(String text) {
        Platform.runLater(() -> {
            console.addJungey(text);
            speaker.say(text);
            notifyDesktop(text);
        });
    }

    @Override
    public ReplyStream beginReply() {
        // The turn this reply belongs to, fixed now: if another request starts while it is
        // still arriving, the rest of it is dropped rather than said over the new answer.
        long turn = Turn.mine();
        boolean stale = Turn.superseded(turn);
        java.util.function.Consumer<String> line = stale ? chunk -> { } : console.addJungeyLive();
        if (!stale) Platform.runLater(() -> avatar.setState(Avatar.State.SPEAKING));

        return new ReplyStream() {
            @Override
            public void text(String chunk) {
                if (!cancelled()) line.accept(chunk);
            }

            @Override
            public void sentence(String sentence) {
                if (!cancelled()) speaker.say(sentence);
            }

            @Override
            public boolean cancelled() {
                return Turn.superseded(turn);
            }
        };
    }

    @Override
    public boolean converse(boolean on) {
        // Ending one: the reply to "that's all" must not reopen the microphone. This runs
        // while the skill does, so it reaches the UI thread before the reply is rendered.
        if (!on) Platform.runLater(() -> spokenTo = false);
        return listener.converse(on);
    }

    /** The window is often not what is being looked at, so it goes to the desktop too. */
    private static void notifyDesktop(String text) {
        try {
            new ProcessBuilder("notify-send", "-a", "Jungey", "Jungey", text).start();
        } catch (java.io.IOException e) {
            // No notification daemon; the transcript and the voice already carried it.
        }
    }

    private static String readClipboard() {
        javafx.scene.input.Clipboard board = javafx.scene.input.Clipboard.getSystemClipboard();
        return board.hasString() ? board.getString() : "";
    }

    private void shutdown() {
        camera.stop();
        avatar.stop();
        statusBar.stop();
        listener.stop();
        speaker.shutdown();
        brain.shutdown();
        // Last, so a Jungey waiting to take over starts once the microphone and speaker are free.
        if (instance != null) instance.close();
        Platform.exit();
        // JavaFX will not always tear down AWT's Desktop helper threads on its own.
        System.exit(0);
    }

    public static void main(String[] args) {
        instance = SingleInstance.claim(Build.stamp());
        if (instance == null) {
            System.out.println("[jungey] Jungey is already running - brought it to the front.");
            return;
        }
        launch(args);
    }
}
