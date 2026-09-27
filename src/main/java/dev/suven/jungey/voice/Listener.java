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
import java.util.Set;
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

    /**
     * How long a conversation waits for the next sentence. Silence this long means the
     * conversation is over, and the name is needed again.
     */
    private static final long CONVERSATION_WINDOW_MS = 45_000;

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

    /**
     * How long after Jungey stops talking the microphone still counts it as talking. The
     * sound server plays on for a moment after Java has handed the audio over, and the room
     * rings a little longer; heard as a command, that tail is Jungey answering its own last
     * word - "sir" - and then its own answer to that.
     */
    private static final long ECHO_TAIL_MS = 700;

    /**
     * Below this average confidence, what Vosk heard in a window Jungey opened itself is
     * taken for noise. A fan or a cough comes out as a word or two it is unsure of; a
     * sentence someone meant is heard with far more certainty than this.
     */
    private static final double MIN_CONFIDENCE = 0.55;

    /**
     * How many chunks of audio in a row (128 ms each) a partial result must hold the name
     * before it counts. Partials flicker: for a chunk or two almost anything is "purple" -
     * Jungey's own voice is, in two sentences out of five - while the name really said
     * stays put until it is final.
     */
    private static final int NAME_HELD_CHUNKS = 3;

    /** Words a recogniser makes out of noise - on their own they are never a command. */
    private static final Set<String> NOISE = Set.of(
            "the", "a", "an", "uh", "um", "huh", "hmm", "mm", "oh", "ah", "eh", "and", "but",
            "so", "i", "it", "sir", "her", "him", "he", "she", "you", "of", "to", "in", "is");

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

    /** In a conversation: every reply leaves the microphone open, and no name is needed. */
    private volatile boolean conversing;

    /** The open window came from the name cutting in over Jungey's own voice. */
    private volatile boolean bargedIn;

    /** Called when the name cuts in while Jungey is talking. */
    private volatile Runnable onBargeIn = () -> { };

    /** Chunks in a row the wake recogniser's partial result has held the name. */
    private int nameHeld;

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

    /**
     * Where the unpacked Vosk model lives. Without the large one, the small model kept for
     * the wake word hears commands too - less well, but with the microphone on rather than off.
     */
    public static Path modelPath() {
        Path main = Path.of(Config.get().str("voice.input.model",
                System.getProperty("user.home") + "/.local/share/vosk/model"));
        return !usable(main) && usable(wakeModelPath()) ? wakeModelPath() : main;
    }

    public static boolean modelInstalled() {
        return usable(modelPath());
    }

    /** Vosk needs the whole unpacked directory; "am" is always part of a usable model. */
    private static boolean usable(Path model) {
        return Files.isDirectory(model.resolve("am"));
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

    /** True from {@link #start} until {@link #stop}, the models still loading included. */
    public boolean on() {
        return running;
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

    /** True during a conversation - see {@link #converse}. */
    public boolean conversing() {
        return conversing;
    }

    /** What to do when the name is said over Jungey's own voice: stop talking, forget the old reply. */
    public void onBargeIn(Runnable action) {
        this.onBargeIn = action;
    }

    /**
     * Hold a conversation: keep the microphone open after each reply, so nothing needs the
     * name until it is ended - by "that's all", or by a long enough silence.
     *
     * @return false when there is nothing to listen with
     */
    public boolean converse(boolean on) {
        if (!on) {
            conversing = false;
            return true;
        }
        if (!running || state == State.OFF) return false;
        conversing = true;
        followUp();
        return true;
    }

    private long window() {
        return conversing ? CONVERSATION_WINDOW_MS : COMMAND_WINDOW_MS;
    }

    /**
     * Whether the name may cut Jungey off mid-sentence. Only with a grammar to wake on: the
     * microphone hears Jungey's own voice too, and with the whole dictionary open some
     * word of its reply would eventually sound like the name.
     */
    private boolean bargeIn() {
        return grammarWake && Config.get().bool("voice.input.bargeIn");
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
        // Turned off and straight back on: the last loop still holds the models it is about
        // to close, and a new one must not load its own until they are gone.
        if (thread != null) {
            try {
                thread.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (thread.isAlive()) return;
        }

        running = true;
        transcriber.warmUp();
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
        if (next != State.LISTENING) bargedIn = false;
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
            // Each word with how sure Vosk is of it, for telling speech from noise.
            command.setWords(true);
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
        if (supportsGrammar(wakeModelPath()) && !wakeModelPath().equals(modelPath())) {
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
        commandDeadline = System.currentTimeMillis() + window();
        wokenByName = false;
        setState(State.LISTENING);
    }

    private void listen(TargetDataLine line, Recognizer wake, Recognizer command) {
        byte[] buffer = new byte[4096];
        boolean wasSpeaking = false;
        long voiceAt = 0;

        while (running && !Thread.currentThread().isInterrupted()) {
            int read = line.read(buffer, 0, buffer.length);
            if (read <= 0) continue;

            long now = System.currentTimeMillis();
            if (speaker.speaking()) voiceAt = now;
            boolean speaking = now - voiceAt < ECHO_TAIL_MS;
            if (wasSpeaking && !speaking) {
                // What the wake recogniser heard over the voice is not the start of anything.
                wake.reset();
                nameHeld = 0;
            }
            wasSpeaking = speaking;

            // Never transcribe Jungey's own voice, or it answers itself - but do listen for
            // the name, so Jungey can be interrupted the way a person can.
            if (speaking) {
                if (state == State.LISTENING && bargedIn) {
                    // Just cut in: the voice is trailing off; what follows is the command.
                    takeCommand(line, command, buffer, read);
                    continue;
                }
                if (state == State.WAITING && bargeIn() && heardNameOverVoice(wake, buffer, read)) {
                    wake.reset();
                    command.reset();
                    forget();
                    commandDeadline = System.currentTimeMillis() + COMMAND_WINDOW_MS;
                    wokenByName = true;
                    bargedIn = true;
                    speaker.stop();
                    onBargeIn.run();
                    setState(State.LISTENING);
                    continue;
                }
                command.reset();
                forget();
                if (!bargeIn()) {
                    wake.reset();
                    line.flush();
                }
                // A follow-up window counts from when the voice stops, not from when the text
                // finished typing - otherwise a long spoken reply uses the whole window up.
                if (state == State.LISTENING) {
                    commandDeadline = System.currentTimeMillis() + window();
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
        if (!heardName(wake, buffer, read)) return;

        wake.reset();
        nameHeld = 0;
        openCommandWindow(command);
    }

    /**
     * The name, said over Jungey's own voice. There is no pause before it for the recogniser
     * to start a new utterance on, so it arrives after a run of unrecognised words; with a
     * grammar holding nothing but the name, any word it does recognise is the name.
     */
    private boolean heardNameOverVoice(Recognizer wake, byte[] buffer, int read) {
        // Over Jungey's own voice only a final result will do. Its sentences put the name in
        // the partial often enough to stop it mid-answer and hand it its own words as a
        // command; the final has not once mistaken them for the name.
        if (!wake.acceptWaveForm(buffer, read)) return false;
        return afterWakeWord(textOf(wake.getResult(), "text").replace("[unk]", " ").trim()) != null;
    }

    private boolean heardName(Recognizer wake, byte[] buffer, int read) {
        if (wake.acceptWaveForm(buffer, read)) {
            nameHeld = 0;
            return afterWakeWord(textOf(wake.getResult(), "text")) != null;
        }
        // Partial results let the wake word register before the speaker pauses, so a
        // command said in the same breath is not left waiting for silence first - once the
        // name has held long enough not to be a flicker.
        nameHeld = afterWakeWord(textOf(wake.getPartialResult(), "partial")) != null ? nameHeld + 1 : 0;
        return nameHeld >= NAME_HELD_CHUNKS;
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

        commandDeadline = System.currentTimeMillis() + window();
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
                // Nothing said for the whole window: a conversation has run its course.
                conversing = false;
                setState(State.WAITING);
            }
            return;
        }

        String result = spokeUp ? command.getResult() : command.getFinalResult();
        String heard = (carried + " " + textOf(result, "text")).trim();
        boolean mid = !carried.isEmpty();
        carried = "";

        byte[] clip = capture.toByteArray();
        command.reset();
        capture.reset();

        // Noise, not a command: keep listening out the window, but answer nothing. Words
        // carried over from the wake phrase are someone mid-sentence, never noise.
        if (!mid && doubtful(result, !wokenByName)) {
            clearPreroll();
            return;
        }

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
            commandDeadline = System.currentTimeMillis() + window();
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

    /**
     * Whether what Vosk made of a stretch of sound is too doubtful to act on: nothing, or
     * nothing but noise words - and, when strict, words it was on average unsure of.
     */
    private static boolean doubtful(String result, boolean strict) {
        try {
            JsonNode words = MAPPER.readTree(result).path("result");
            if (!words.isArray() || words.isEmpty()) return true;
            double sure = 0;
            int meant = 0;
            for (JsonNode w : words) {
                sure += w.path("conf").asDouble(1);
                if (!NOISE.contains(w.path("word").asText())) meant++;
            }
            return meant == 0 || (strict && sure / words.size() < MIN_CONFIDENCE);
        } catch (Exception e) {
            return false;
        }
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
