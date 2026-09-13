package dev.suven.jungey.voice;

import dev.suven.jungey.core.Config;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Text to speech, delegated to whatever is installed on the machine.
 *
 * <p>Tries in order: Piper (neural, natural), then espeak-ng, then falls silent.
 * Speaking must never block or crash the UI, so everything runs on a single
 * worker thread and every failure degrades to silence.
 */
public final class Speaker {

    private enum Engine {PIPER, ESPEAK, NONE}

    private final ExecutorService voice = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jungey-voice");
        t.setDaemon(true);
        return t;
    });

    private final Engine engine;
    private volatile boolean muted;
    private volatile boolean speaking;
    private Process current;

    public Speaker() {
        this.engine = detect();
        this.muted = !Config.get().bool("voice.enabled");
        System.out.println("[jungey] voice engine: " + engine + (muted ? " (muted)" : ""));
    }

    private static Engine detect() {
        Config cfg = Config.get();
        String forced = cfg.str("voice.engine", "auto");
        if (forced.equals("none")) return Engine.NONE;
        if (forced.equals("piper") && onPath("piper")) return Engine.PIPER;
        if (forced.equals("espeak")) {
            if (onPath("espeak-ng")) return Engine.ESPEAK;
            if (onPath("espeak")) return Engine.ESPEAK;
        }

        if (onPath("piper")) return Engine.PIPER;
        if (onPath("espeak-ng") || onPath("espeak")) return Engine.ESPEAK;
        return Engine.NONE;
    }

    private static boolean onPath(String binary) {
        try {
            Process p = new ProcessBuilder("which", binary)
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** True if a speech engine was found - independent of whether the user has muted it. */
    public boolean available() {
        return engine != Engine.NONE;
    }

    public String engineName() {
        return engine.name().toLowerCase();
    }

    public boolean muted() {
        return muted;
    }

    /** True while a line is actually being spoken - the ears mute themselves during this. */
    public boolean speaking() {
        return speaking;
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

        // Strip anything that reads badly aloud.
        String clean = text.replaceAll("[\\[\\]{}#*_`|]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.isEmpty()) return;

        voice.submit(() -> {
            speaking = true;
            try {
                speakNow(clean);
            } catch (Exception e) {
                System.err.println("[jungey] speech failed: " + e.getMessage());
            } finally {
                speaking = false;
            }
        });
    }

    private void speakNow(String text) throws IOException, InterruptedException {
        int rate = Config.get().intv("voice.rate", 165);

        List<String> cmd = switch (engine) {
            // Piper writes raw audio to stdout; aplay plays it. 22.05kHz mono is Piper's default.
            case PIPER -> List.of("sh", "-c",
                    "echo " + shellQuote(text) + " | piper --model "
                            + shellQuote(piperModel()) + " --output-raw | aplay -r 22050 -f S16_LE -t raw -q -");
            case ESPEAK -> List.of(espeakBinary(), "-s", String.valueOf(rate), "-v", "en-gb", text);
            case NONE -> List.of();
        };

        if (cmd.isEmpty()) return;

        current = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        current.waitFor();
    }

    /** Interrupt whatever is being said - used when a new command arrives mid-sentence. */
    public void stop() {
        Process p = current;
        if (p != null && p.isAlive()) {
            p.destroy();
        }
    }

    private static String espeakBinary() {
        return onPath("espeak-ng") ? "espeak-ng" : "espeak";
    }

    private static String piperModel() {
        return Config.get().str("voice.piperModel",
                System.getProperty("user.home") + "/.local/share/piper/en_GB-alan-medium.onnx");
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public void shutdown() {
        stop();
        voice.shutdownNow();
    }
}
