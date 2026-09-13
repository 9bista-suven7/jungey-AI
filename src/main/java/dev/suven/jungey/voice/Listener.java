package dev.suven.jungey.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.suven.jungey.core.Config;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Always-on speech input: waits for the wake word, then treats the next thing said
 * as a command.
 *
 * <p>Recognition is offline via Vosk, so audio never leaves the machine. The model is
 * large enough that it is downloaded separately rather than bundled - when it is absent
 * the listener simply reports itself unavailable and Jungey stays keyboard-only.
 */
public final class Listener {

    /** Vosk wants 16 kHz signed 16-bit mono, little-endian. */
    private static final AudioFormat FORMAT = new AudioFormat(16_000f, 16, 1, true, false);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The wake word has to be a word the model knows. A recogniser can only emit things
     * in its vocabulary, so a name it has never seen comes out as whatever sounds nearest
     * - differently every time - and no list of guessed spellings fixes that.
     */
    private static final String DEFAULT_WAKE = "purple";

    /** How long to keep listening for a command after the wake word before giving up. */
    private static final long COMMAND_WINDOW_MS = 8_000;

    /** How many words may precede the wake phrase - enough for a "hey" or an "ok". */
    private static final int LEADING_SLACK = 1;

    public enum State {OFF, WAITING, LISTENING}

    private final Speaker speaker;
    private final Consumer<String> onCommand;
    private final Consumer<State> onState;

    private volatile State state = State.OFF;
    private volatile boolean running;
    private volatile long commandDeadline;
    private Thread thread;
    private Model model;

    public Listener(Speaker speaker, Consumer<String> onCommand, Consumer<State> onState) {
        this.speaker = speaker;
        this.onCommand = onCommand;
        this.onState = onState;
    }

    /** Where the unpacked Vosk model lives. */
    public static Path modelPath() {
        return Path.of(Config.get().str("voice.input.model",
                System.getProperty("user.home") + "/.local/share/vosk/model"));
    }

    public static boolean modelInstalled() {
        // Vosk needs the whole unpacked directory; "am" is always part of a usable model.
        return Files.isDirectory(modelPath()) && Files.isDirectory(modelPath().resolve("am"));
    }

    public static boolean micPresent() {
        return AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMAT));
    }

    public boolean available() {
        return Config.get().bool("voice.input.enabled") && modelInstalled() && micPresent();
    }

    public State state() {
        return state;
    }

    /** Begin listening for the wake word. Safe to call when unavailable - it just does nothing. */
    public void start() {
        if (running || !available()) return;

        running = true;
        thread = new Thread(this::loop, "jungey-ears");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        setState(State.OFF);
        if (thread != null) thread.interrupt();
    }

    private void setState(State next) {
        if (state != next) {
            state = next;
            onState.accept(next);
        }
    }

    private void loop() {
        TargetDataLine line = null;
        Recognizer recognizer = null;
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(modelPath().toString());
            recognizer = new Recognizer(model, 16_000f);

            line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, FORMAT));
            line.open(FORMAT);
            line.start();

            setState(State.WAITING);
            listen(line, recognizer);

        } catch (Exception e) {
            System.err.println("[jungey] speech input stopped: " + e.getMessage());
            setState(State.OFF);
        } finally {
            running = false;
            if (recognizer != null) recognizer.close();
            if (model != null) model.close();
            if (line != null) {
                line.stop();
                line.close();
            }
        }
    }

    /**
     * Stay open for another command without the wake word. Used straight after a reply,
     * so a conversation does not need the name said before every sentence.
     */
    public void followUp() {
        if (!running || state == State.OFF) return;
        commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS;
        setState(State.LISTENING);
    }

    private void listen(TargetDataLine line, Recognizer recognizer) {
        byte[] buffer = new byte[4096];

        while (running && !Thread.currentThread().isInterrupted()) {
            int read = line.read(buffer, 0, buffer.length);
            if (read <= 0) continue;

            // Never transcribe Jungey's own voice, or it answers itself.
            if (speaker.speaking()) {
                recognizer.reset();
                line.flush();
                // A follow-up window counts from when the voice stops, not from when the text
                // finished typing - otherwise a long spoken reply uses the whole window up.
                if (state == State.LISTENING) {
                    commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS;
                }
                continue;
            }

            if (state == State.LISTENING && System.currentTimeMillis() > commandDeadline) {
                recognizer.reset();
                setState(State.WAITING);
            }

            if (recognizer.acceptWaveForm(buffer, read)) {
                String text = textOf(recognizer.getResult(), "text");
                if (text.isBlank()) continue;

                // The wake word is never part of the command, whether it woke Jungey just now
                // or was said again during a follow-up window.
                String rest = afterWakeWord(text);

                if (state == State.LISTENING) {
                    String command = rest == null ? text : rest;
                    if (command.isBlank()) {
                        // Only the wake word again - keep waiting for the command itself.
                        commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS;
                        continue;
                    }
                    setState(State.WAITING);
                    onCommand.accept(command);
                } else {
                    if (rest == null) continue;

                    if (rest.isBlank()) {
                        // Woken with nothing after it - wait for the command.
                        setState(State.LISTENING);
                        commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS;
                    } else {
                        // "purple what time is it" - wake word and command in one breath.
                        onCommand.accept(rest);
                    }
                }
            } else if (state == State.WAITING) {
                // Partial results let the wake word register before the speaker pauses. The
                // recogniser is deliberately not reset: in "purple what time is it" the command
                // is already being decoded, and resetting here would drop its first words.
                String partial = textOf(recognizer.getPartialResult(), "partial");
                if (afterWakeWord(partial) != null && partial.split("\\s+").length <= wakeLength() + 1) {
                    setState(State.LISTENING);
                    commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS;
                }
            }
        }
    }

    /** The wake phrase, plus any near-misses listed in the config, each as its own words. */
    private static List<List<String>> wakePhrases() {
        Config cfg = Config.get();
        List<List<String>> phrases = new ArrayList<>();

        phrases.add(List.of(cfg.str("voice.input.wakeWord", DEFAULT_WAKE)
                .toLowerCase(Locale.ENGLISH).trim().split("\\s+")));

        String variants = cfg.str("voice.input.wakeVariants", "");
        for (String variant : variants.split(",")) {
            if (!variant.isBlank()) {
                phrases.add(List.of(variant.toLowerCase(Locale.ENGLISH).trim().split("\\s+")));
            }
        }
        return phrases;
    }

    /** Longest wake phrase, so a partial result can be judged against it. */
    private static int wakeLength() {
        return wakePhrases().stream().mapToInt(List::size).max().orElse(1);
    }

    /**
     * @return the words following the wake phrase, "" if the utterance was only the wake
     *         phrase, or null if it was not said at all
     */
    private static String afterWakeWord(String text) {
        if (text == null || text.isBlank()) return null;

        List<String> words = List.of(text.toLowerCase(Locale.ENGLISH).trim().split("\\s+"));

        for (List<String> phrase : wakePhrases()) {
            // The wake word has to lead, or "I like purple shirts" would be taken as an
            // order to do something about shirts. One word of slack allows "hey purple".
            int limit = Math.min(LEADING_SLACK, words.size() - phrase.size());

            for (int i = 0; i <= limit; i++) {
                if (words.subList(i, i + phrase.size()).equals(phrase)) {
                    return String.join(" ", words.subList(i + phrase.size(), words.size()));
                }
            }
        }
        return null;
    }

    private static String textOf(String json, String field) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return node.path(field).asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
