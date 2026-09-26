package dev.suven.jungey.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.suven.jungey.core.Config;
import dev.suven.jungey.net.Http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Text to speech.
 *
 * <p>Three engines, in descending order of sounding like a person:
 * <ul>
 *   <li><b>Piper</b> - a neural voice running locally. Natural, instant, offline, free.
 *       This is the one to install.</li>
 *   <li><b>Hugging Face</b> - a hosted neural voice. Better still on a good day, but it
 *       needs a token, needs the network, and adds a second of latency to every sentence.</li>
 *   <li><b>espeak-ng</b> - formant synthesis from the 1980s. Always available, always
 *       sounds like a robot. The floor, not the goal.</li>
 * </ul>
 *
 * <p>Speaking must never block or crash the UI, so everything runs on a single worker
 * thread, every failure falls back to the engine below it, and the last fallback is silence.
 */
public final class Speaker {

    private enum Engine {PIPER, HF, ESPEAK, NONE}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExecutorService voice = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jungey-voice");
        t.setDaemon(true);
        return t;
    });

    private final AudioOut audio = new AudioOut();

    private final Engine engine;
    private volatile boolean muted;
    private volatile Process current;

    /**
     * Lines queued but not yet finished. The ears go by this rather than by "a process is
     * running right now": the gap between queueing a reply and the engine opening its mouth
     * is still time when Jungey is about to talk, and a microphone left live across it hears
     * the reply and takes it for a command.
     */
    private final AtomicInteger pending = new AtomicInteger();

    /** Bumped by stop(), so lines queued before it are dropped rather than spoken late. */
    private final AtomicInteger generation = new AtomicInteger();

    /** Said once, the first time a hosted call fails - nobody needs it every sentence. */
    private volatile boolean hostedComplaint;

    public Speaker() {
        this.engine = detect();
        this.muted = !Config.get().bool("voice.enabled");
        System.out.println("[jungey] voice engine: " + engineDetail() + (muted ? " (muted)" : ""));
    }

    private static Engine detect() {
        String forced = Config.get().str("voice.engine", "auto").toLowerCase(Locale.ENGLISH);

        switch (forced) {
            case "none" -> {
                return Engine.NONE;
            }
            case "piper" -> {
                if (piperReady()) return Engine.PIPER;
            }
            case "hf", "huggingface" -> {
                if (HuggingFace.configured()) return Engine.HF;
            }
            case "espeak" -> {
                if (espeakReady()) return Engine.ESPEAK;
            }
            default -> {
                // auto: fall through to the preference order below
            }
        }

        // Automatic means local. A hosted voice sends every reply to someone else's server,
        // so it is only ever used when voice.engine asks for it by name.
        if (piperReady()) return Engine.PIPER;
        if (espeakReady()) return Engine.ESPEAK;
        return Engine.NONE;
    }

    private static boolean piperReady() {
        return onPath("piper") && Files.isRegularFile(piperModel());
    }

    private static boolean espeakReady() {
        return onPath("espeak-ng") || onPath("espeak");
    }

    static boolean onPath(String binary) {
        return resolve(binary) != null;
    }

    /**
     * How to invoke a helper binary, or null if it is not installed.
     *
     * <p>A desktop launcher does not always inherit the PATH a login shell has, and
     * ~/.local/bin in particular is only added if it already existed when you logged in -
     * which it will not have, the first time setup-voice.sh puts piper there. Looking in
     * the obvious places saves a puzzling "still using espeak" until the next reboot.
     */
    static String resolve(String binary) {
        try {
            Process p = new ProcessBuilder("which", binary)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (p.waitFor() == 0) return binary;
        } catch (IOException | InterruptedException e) {
            // Fall through to the well-known directories.
        }

        for (String dir : new String[]{System.getProperty("user.home") + "/.local/bin",
                "/usr/local/bin", "/usr/bin"}) {
            Path candidate = Path.of(dir, binary);
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    /** True if a speech engine was found - independent of whether the user has muted it. */
    public boolean available() {
        return engine != Engine.NONE;
    }

    public String engineName() {
        return engine.name().toLowerCase(Locale.ENGLISH);
    }

    /** The engine and the voice it is using, for the console and for "voice". */
    public String engineDetail() {
        return switch (engine) {
            case PIPER -> "piper (" + stripSuffix(piperModel().getFileName().toString()) + ")";
            case HF -> "hugging face (" + ttsModel() + ")";
            case ESPEAK -> "espeak-ng - install piper for a human voice";
            case NONE -> "none";
        };
    }

    public boolean muted() {
        return muted;
    }

    /** True while anything is queued or being spoken - the ears mute themselves during this. */
    public boolean speaking() {
        return pending.get() > 0;
    }

    /** Silence or restore speech for this session, and remember the choice for the next one. */
    public void setMuted(boolean value) {
        muted = value;
        if (value) stop();
        Config cfg = Config.get();
        cfg.set("voice.enabled", String.valueOf(!value));
        cfg.save();
    }

    /** What the status bar shows: the engine while speaking, "off" while muted or unavailable. */
    public String statusLabel() {
        return (engine == Engine.NONE || muted) ? "off" : engineName();
    }

    /** Queue a line to be spoken. Returns immediately. */
    public void say(String text) {
        if (engine == Engine.NONE || muted || text == null || text.isBlank()) return;

        String clean = Spoken.forSpeech(text, Config.get().intv("voice.maxChars", 400));
        if (clean.isBlank()) return;

        // The whole reply goes to one engine invocation. Splitting it into sentences and
        // synthesising them separately sounds like it should start sooner, but Piper loads
        // a 60 MB model per process: the silence that buys between sentences is longer than
        // the head start, and Piper streams its audio sentence by sentence regardless.
        int queuedIn = generation.get();
        pending.incrementAndGet();
        voice.submit(() -> {
            try {
                if (queuedIn != generation.get()) return;
                speakNow(clean);
            } catch (Exception e) {
                System.err.println("[jungey] speech failed: " + e.getMessage());
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private void speakNow(String text) throws Exception {
        switch (engine) {
            case PIPER -> speakPiper(text);
            case HF -> {
                if (!speakHosted(text) && espeakReady()) speakEspeak(text);
            }
            case ESPEAK -> speakEspeak(text);
            case NONE -> {
                // Nothing to do; say() already returned for this case.
            }
        }
    }

    // ---------------------------------------------------------------- piper

    /**
     * Piper streams raw PCM on stdout as it synthesises, so the first words are audible
     * before the last ones exist. The sample rate is whatever the model was trained at -
     * assuming 22.05 kHz is how a voice ends up sounding like a chipmunk.
     */
    private void speakPiper(String text) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(resolve("piper"),
                "--model", piperModel().toString(),
                rawFlag()));

        String lengthFlag = firstSupported("--length-scale", "--length_scale");
        if (lengthFlag != null) {
            cmd.add(lengthFlag);
            cmd.add(Config.get().str("voice.piper.speed", "1.0"));
        }
        String silenceFlag = firstSupported("--sentence-silence", "--sentence_silence");
        if (silenceFlag != null) {
            cmd.add(silenceFlag);
            cmd.add(Config.get().str("voice.piper.pause", "0.35"));
        }

        Process p = new ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        current = p;
        feed(p, text);

        try {
            audio.stream(p.getInputStream(), piperSampleRate());
        } finally {
            p.destroy();
            current = null;
        }
    }

    /** The sample rate declared in the voice's companion JSON, next to the .onnx. */
    private static float piperSampleRate() {
        try {
            Path json = Path.of(piperModel() + ".json");
            if (Files.isReadable(json)) {
                int rate = MAPPER.readTree(Files.readString(json))
                        .path("audio").path("sample_rate").asInt();
                if (rate > 0) return rate;
            }
        } catch (Exception e) {
            // Missing or broken JSON: 22.05 kHz is what every medium voice uses.
        }
        return 22_050f;
    }

    /** Piper renamed its flags between releases; ask the binary which spelling it knows. */
    private static volatile String piperHelp;

    private static String piperHelpText() {
        if (piperHelp == null) {
            try {
                Process p = new ProcessBuilder(resolve("piper"), "--help")
                        .redirectErrorStream(true)
                        .start();
                piperHelp = new String(AudioOut.drain(p.getInputStream()), StandardCharsets.UTF_8);
                p.waitFor();
            } catch (Exception e) {
                piperHelp = "";
            }
        }
        return piperHelp;
    }

    private static String firstSupported(String... flags) {
        String help = piperHelpText();
        for (String flag : flags) {
            if (help.contains(flag)) return flag;
        }
        return null;
    }

    private static String rawFlag() {
        String flag = firstSupported("--output-raw", "--output_raw");
        return flag == null ? "--output-raw" : flag;
    }

    // ---------------------------------------------------------- hugging face

    /** @return true if the hosted voice actually spoke; false means fall back to a local one. */
    private boolean speakHosted(String text) throws Exception {
        byte[] clip = cached(text);

        if (clip == null) {
            String body = MAPPER.writeValueAsString(Map.of("inputs", text));
            Http.Reply reply = HuggingFace.call(ttsModel(),
                    body.getBytes(StandardCharsets.UTF_8), "application/json", Duration.ofSeconds(45));

            if (!reply.ok() || reply.body().length == 0) {
                if (!hostedComplaint) {
                    hostedComplaint = true;
                    System.err.println("[jungey] hosted voice unavailable, using the local one. "
                            + HuggingFace.explain(reply, ttsModel()));
                }
                return false;
            }
            clip = reply.body();
            cache(text, clip);
        }

        audio.play(clip);
        return true;
    }

    private static String ttsModel() {
        return Config.get().str("voice.hf.ttsModel", "facebook/mms-tts-eng");
    }

    /**
     * Stock lines - greetings, "one moment", "yes?" - are said over and over, and paying a
     * network round trip for each of them is what makes a hosted voice feel sluggish.
     */
    private static Path cacheFile(String text) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            String key = ttsModel() + "|" + text;
            String name = HexFormat.of().formatHex(sha.digest(key.getBytes(StandardCharsets.UTF_8)));
            return Path.of(System.getProperty("user.home"), ".cache", "jungey", "tts", name);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] cached(String text) {
        if (!Config.get().bool("voice.cache")) return null;
        Path file = cacheFile(text);
        try {
            return (file != null && Files.isReadable(file)) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void cache(String text, byte[] clip) {
        // Only short, repeatable lines are worth keeping; a spoken essay is said once.
        if (!Config.get().bool("voice.cache") || text.length() > 120) return;
        Path file = cacheFile(text);
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, clip);
        } catch (IOException e) {
            // A cache that cannot be written is just a slower cache.
        }
    }

    // --------------------------------------------------------------- espeak

    /**
     * espeak-ng writes a WAV to stdout rather than opening the sound device itself: on a
     * PulseAudio desktop its own ALSA output is what made speech crackle and break up.
     * Received Pronunciation with a lower pitch and a little word gap is about as close
     * to human as formant synthesis gets.
     */
    private void speakEspeak(String text) throws Exception {
        Config cfg = Config.get();
        List<String> cmd = List.of(
                onPath("espeak-ng") ? resolve("espeak-ng") : resolve("espeak"),
                "--stdout",
                "-v", cfg.str("voice.espeak.voice", "en-gb-x-rp"),
                "-s", String.valueOf(cfg.intv("voice.rate", 165)),
                "-p", String.valueOf(cfg.intv("voice.espeak.pitch", 45)),
                "-g", String.valueOf(cfg.intv("voice.espeak.gap", 3)));

        Process p = new ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        current = p;
        feed(p, text);

        try {
            audio.play(AudioOut.drain(p.getInputStream()));
            p.waitFor();
        } finally {
            p.destroy();
            current = null;
        }
    }

    // --------------------------------------------------------------- shared

    /** Write the line to the engine's stdin on its own thread, so a full pipe cannot wedge us. */
    private static void feed(Process p, String text) {
        Thread writer = new Thread(() -> {
            try (OutputStream in = p.getOutputStream()) {
                in.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // The engine died early; the empty audio that follows is handled by the caller.
            }
        }, "jungey-voice-in");
        writer.setDaemon(true);
        writer.start();
    }

    /** Interrupt whatever is being said - used when a new command arrives mid-sentence. */
    public void stop() {
        generation.incrementAndGet();
        audio.stop();

        Process p = current;
        if (p != null && p.isAlive()) p.destroy();
    }

    static Path piperModel() {
        Config cfg = Config.get();
        String path = cfg.str("voice.piper.model", cfg.str("voice.piperModel",
                System.getProperty("user.home") + "/.local/share/piper/en_GB-alan-medium.onnx"));
        return Path.of(path.replaceFirst("^~", System.getProperty("user.home")));
    }

    private static String stripSuffix(String fileName) {
        return fileName.endsWith(".onnx") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    public void shutdown() {
        stop();
        voice.shutdownNow();
    }
}
