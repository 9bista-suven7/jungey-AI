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
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Always-on speech input: waits for the wake word, then treats the next thing said
 * as a command.
 *
 * <p>Two recognisers do the two jobs, because they are not the same job. Waiting for a
 * wake word is a yes-or-no question, so it runs against a grammar holding nothing but the
 * wake phrase and "anything else" - a search space small enough that it is hard to get
 * wrong, and cheap enough to leave running all day. Understanding the command that follows
 * needs the full vocabulary, and, if one is configured, a {@link Transcriber} that hears
 * better than Vosk does.
 *
 * <p>Audio never leaves the machine unless a hosted transcriber is explicitly turned on.
 */
public final class Listener {

    /** Vosk wants 16 kHz signed 16-bit mono, little-endian. */
    private static final AudioFormat FORMAT = new AudioFormat(16_000f, 16, 1, true, false);

    private static final int BYTES_PER_SECOND = 32_000;

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

    /**
     * Audio kept from just before the wake word fired. Waking happens on a partial result,
     * which arrives a beat after the sound that caused it, so without this the first word
     * or two of "purple, what time is it" would be cut off and never transcribed.
     */
    private static final int PREROLL_BYTES = BYTES_PER_SECOND * 2;

    /** Nobody issues a twenty-second command; past this the clip is closed and sent as is. */
    private static final int MAX_CAPTURE_BYTES = BYTES_PER_SECOND * 15;

    public enum State {OFF, WAITING, LISTENING}

    private final Speaker speaker;
    private final Consumer<String> onCommand;
    private final Consumer<State> onState;
    private final Transcriber transcriber = new Transcriber();

    private volatile State state = State.OFF;
    private volatile boolean running;
    private volatile long commandDeadline;
    private Thread thread;
    private Model model;
    private Model wakeModel;

    /** True when waking runs against a grammar rather than the whole dictionary. */
    private volatile boolean grammarWake;

    /** Whether the open window came from hearing the name, rather than from a follow-up. */
    private volatile boolean wokenByName;

    /** The last couple of seconds of microphone audio, oldest first. */
    private final Deque<byte[]> preroll = new ArrayDeque<>();
    private int prerollBytes;

    /** The command being spoken right now, kept so a better transcriber can re-hear it. */
    private final ByteArrayOutputStream capture = new ByteArrayOutputStream();

    /** Words decoded out of the pre-roll, waiting for the rest of the sentence to arrive. */
    private String carried = "";

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

    /**
     * True when the microphone opened because the name was heard, false when Jungey opened
     * it itself after answering.
     *
     * <p>The difference matters: being addressed mid-sentence means stop talking, while a
     * follow-up window is offered <em>during</em> the reply that prompted it, and treating
     * that the same way cuts Jungey off in the middle of its own answer.
     */
    public boolean wokenByName() {
        return wokenByName;
    }

    /** Which ears are in use, for the console: how it wakes, and what transcribes. */
    public String inputLabel() {
        return (grammarWake ? "grammar wake" : "open wake") + ", " + transcriber.label();
    }

    /** A small model kept only for spotting the wake word, if one has been installed. */
    public static Path wakeModelPath() {
        return Path.of(Config.get().str("voice.input.wakeModel",
                System.getProperty("user.home") + "/.local/share/vosk/wake-model"));
    }

    /**
     * Whether a model can be given a grammar at runtime.
     *
     * <p>Vosk's large models are compiled into one static HCLG graph and quietly ignore a
     * grammar - the warning goes to the log and the recogniser carries on with its whole
     * dictionary open, which looks exactly like the grammar working badly. The small models
     * ship the pieces separately, as Gr.fst, and those are the ones that can be constrained.
     */
    private static boolean supportsGrammar(Path model) {
        return Files.isRegularFile(model.resolve("graph").resolve("Gr.fst"));
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
        Recognizer wake = null;
        Recognizer command = null;
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(modelPath().toString());
            command = new Recognizer(model, 16_000f);
            wake = openWakeRecognizer();

            line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, FORMAT));
            line.open(FORMAT);
            line.start();

            setState(State.WAITING);
            listen(line, wake, command);

        } catch (Exception e) {
            System.err.println("[jungey] speech input stopped: " + e.getMessage());
            setState(State.OFF);
        } finally {
            running = false;
            if (wake != null) wake.close();
            if (command != null) command.close();
            if (wakeModel != null && wakeModel != model) wakeModel.close();
            if (model != null) model.close();
            if (line != null) {
                line.stop();
                line.close();
            }
        }
    }

    /**
     * The recogniser that listens for the name.
     *
     * <p>Waking is a yes-or-no question, and asking it of a recogniser with eighty thousand
     * words open is why a wake word gets missed: the name has to beat every other word in
     * the room. Against a grammar of just the wake phrase and "anything else" it rarely
     * loses. That needs a model that accepts a grammar, so a small one kept beside the main
     * model is used when it is there - it costs 40 MB and does nothing but listen for a name.
     */
    private Recognizer openWakeRecognizer() throws Exception {
        if (supportsGrammar(wakeModelPath())) {
            wakeModel = new Model(wakeModelPath().toString());
        } else if (supportsGrammar(modelPath())) {
            wakeModel = model;
        } else {
            wakeModel = model;
            grammarWake = false;
            System.out.println("[jungey] wake word is competing with the whole dictionary - "
                    + "run scripts/setup-ears.sh for a small model that only listens for the name.");
            return new Recognizer(model, 16_000f);
        }

        StringBuilder grammar = new StringBuilder("[");
        for (List<String> phrase : wakePhrases()) {
            grammar.append('"').append(String.join(" ", phrase)).append("\", ");
        }
        grammar.append("\"[unk]\"]");

        try {
            Recognizer recognizer = new Recognizer(wakeModel, 16_000f, grammar.toString());
            grammarWake = true;
            return recognizer;
        } catch (Exception e) {
            // A wake word outside that model's vocabulary is rejected here.
            System.err.println("[jungey] \"" + Config.get().str("voice.input.wakeWord", DEFAULT_WAKE)
                    + "\" is not in the wake model's vocabulary, so waking will be unreliable. "
                    + "Pick a word the model knows - see docs/VOICE.md.");
            if (wakeModel != model) {
                wakeModel.close();
                wakeModel = model;
            }
            grammarWake = false;
            return new Recognizer(model, 16_000f);
        }
    }

    /**
     * Stay open for another command without the wake word. Used straight after a reply,
     * so a conversation does not need the name said before every sentence.
     */
    public void followUp() {
        if (!running || state == State.OFF) return;
        commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS;
        wokenByName = false;
        setState(State.LISTENING);
    }

    private void listen(TargetDataLine line, Recognizer wake, Recognizer command) {
        byte[] buffer = new byte[4096];

        while (running && !Thread.currentThread().isInterrupted()) {
            int read = line.read(buffer, 0, buffer.length);
            if (read <= 0) continue;

            // Never transcribe Jungey's own voice, or it answers itself.
            if (speaker.speaking()) {
                wake.reset();
                command.reset();
                forget();
                line.flush();
                // A follow-up window counts from when the voice stops, not from when the text
                // finished typing - otherwise a long spoken reply uses the whole window up.
                if (state == State.LISTENING) {
                    commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS;
                }
                continue;
            }

            remember(buffer, read);

            if (state == State.LISTENING) {
                takeCommand(line, command, buffer, read);
            } else {
                waitForWake(command, wake, buffer, read);
            }
        }
    }

    /** WAITING: the only question is whether the name was said. */
    private void waitForWake(Recognizer command, Recognizer wake, byte[] buffer, int read) {
        boolean woken;
        if (wake.acceptWaveForm(buffer, read)) {
            woken = afterWakeWord(textOf(wake.getResult(), "text")) != null;
        } else {
            // Partial results let the wake word register before the speaker pauses, so a
            // command said in the same breath is not left waiting for silence first.
            woken = afterWakeWord(textOf(wake.getPartialResult(), "partial")) != null;
        }
        if (!woken) return;

        wake.reset();
        openCommandWindow(command);
    }

    /** Hand the recogniser and the clip everything heard just before the wake word landed. */
    private void openCommandWindow(Recognizer command) {
        command.reset();
        capture.reset();
        carried = "";

        // A short command can be over before the pre-roll is even replayed, in which case
        // the recogniser finishes here; those words are kept for the dispatch below.
        byte[] recent = recent();
        if (command.acceptWaveForm(recent, recent.length)) {
            carried = textOf(command.getResult(), "text");
        }
        capture.writeBytes(recent);

        // Spent: keeping it would seed the next command with the tail of this one.
        clearPreroll();

        commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS;
        wokenByName = true;
        setState(State.LISTENING);
    }

    /** LISTENING: collect the utterance, then decide what it was once the speaker stops. */
    private void takeCommand(TargetDataLine line, Recognizer command, byte[] buffer, int read) {
        if (capture.size() < MAX_CAPTURE_BYTES) capture.write(buffer, 0, read);

        boolean spokeUp = command.acceptWaveForm(buffer, read);
        boolean tooLong = capture.size() >= MAX_CAPTURE_BYTES;
        if (!spokeUp && !tooLong) {
            if (System.currentTimeMillis() > commandDeadline) {
                command.reset();
                capture.reset();
                clearPreroll();
                setState(State.WAITING);
            }
            return;
        }

        String heard = spokeUp
                ? textOf(command.getResult(), "text")
                : textOf(command.getFinalResult(), "text");
        heard = (carried + " " + heard).trim();
        carried = "";

        byte[] clip = capture.toByteArray();
        command.reset();
        capture.reset();

        // Whisper is slow enough that the microphone would overrun while it thinks, and
        // everything it says during that second is stale anyway.
        String better = transcriber.transcribe(clip);
        line.flush();

        clearPreroll();

        String text = better.isBlank() ? heard : better;
        if (text.isBlank()) {
            setState(State.WAITING);
            return;
        }

        // The wake word is never part of the command, whether it woke Jungey just now or
        // was said again during a follow-up window.
        String rest = afterWakeWord(text);
        String spoken = rest == null ? text : rest;

        if (spoken.isBlank()) {
            // Only the wake word - keep waiting for the command itself.
            commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS;
            return;
        }

        setState(State.WAITING);
        onCommand.accept(spoken);
    }

    // ------------------------------------------------------------- pre-roll

    private void remember(byte[] buffer, int read) {
        byte[] copy = new byte[read];
        System.arraycopy(buffer, 0, copy, 0, read);
        preroll.addLast(copy);
        prerollBytes += read;

        while (prerollBytes > PREROLL_BYTES && preroll.size() > 1) {
            prerollBytes -= preroll.removeFirst().length;
        }
    }

    private void clearPreroll() {
        preroll.clear();
        prerollBytes = 0;
    }

    private void forget() {
        clearPreroll();
        capture.reset();
        carried = "";
    }

    private byte[] recent() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(prerollBytes);
        preroll.forEach(out::writeBytes);
        return out.toByteArray();
    }

    // ----------------------------------------------------------- wake words

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

    /**
     * @return the words following the wake phrase, "" if the utterance was only the wake
     *         phrase, or null if it was not said at all
     */
    private static String afterWakeWord(String text) {
        if (text == null || text.isBlank()) return null;

        // Whisper writes like a person - capitals, commas - and Vosk does not.
        List<String> words = List.of(text.toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9' ]", " ")
                .trim()
                .split("\\s+"));

        for (List<String> phrase : wakePhrases()) {
            // The wake word has to lead, or "I like purple shirts" would be taken as an
            // order to do something about shirts. One word of slack allows "hey purple".
            int limit = Math.min(LEADING_SLACK, words.size() - phrase.size());

            for (int i = 0; i <= limit; i++) {
                if (matches(words.subList(i, i + phrase.size()), phrase)) {
                    return String.join(" ", words.subList(i + phrase.size(), words.size()));
                }
            }
        }
        return null;
    }

    private static boolean matches(List<String> heard, List<String> phrase) {
        for (int i = 0; i < phrase.size(); i++) {
            String said = heard.get(i);
            String want = phrase.get(i);
            if (said.equals(want)) continue;
            if (!Config.get().bool("voice.input.wakeFuzzy")) return false;
            // One wrong letter in a long enough word is a microphone, not a different word.
            if (want.length() < 5 || editDistance(said, want) > 1) return false;
        }
        return true;
    }

    /** Levenshtein, bounded to the two short words a wake phrase is made of. */
    private static int editDistance(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1) return 2;

        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
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
