package dev.suven.jungey.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * User settings, stored as a plain properties file at ~/.config/jungey/jungey.properties.
 * Created with sensible defaults on first run so the app never needs a setup wizard.
 */
public final class Config {

    private static final Path DIR = Path.of(System.getProperty("user.home"), ".config", "jungey");
    private static final Path FILE = DIR.resolve("jungey.properties");

    private final Properties props = new Properties();

    private Config() {
        load();
    }

    private static final Config INSTANCE = new Config();

    public static Config get() {
        return INSTANCE;
    }

    private void load() {
        props.setProperty("user.name", System.getProperty("user.name", "sir"));
        props.setProperty("user.honorific", "sir");
        props.setProperty("voice.enabled", "true");
        // auto keeps speech on this machine: piper if installed, espeak-ng if not.
        props.setProperty("voice.engine", "auto");   // auto | piper | hf | espeak | none
        props.setProperty("voice.rate", "175");      // espeak words a minute; people talk at 150-180
        props.setProperty("voice.maxChars", "400");  // longer replies are left on screen
        props.setProperty("voice.cache", "true");    // keep hosted audio for repeated lines
        props.setProperty("voice.piper.model",
                System.getProperty("user.home") + "/.local/share/piper/en_GB-alan-medium.onnx");
        // Piper voices read at an announcer's pace at 1.0; 0.85 is how people actually talk.
        props.setProperty("voice.piper.speed", "0.85");   // above 1 is slower, below is quicker
        props.setProperty("voice.piper.pause", "0.15");   // seconds of silence between sentences
        props.setProperty("voice.espeak.voice", "en-gb-x-rp");
        props.setProperty("voice.espeak.pitch", "45");
        props.setProperty("voice.espeak.gap", "3");
        props.setProperty("voice.hf.ttsModel", "facebook/mms-tts-eng");

        props.setProperty("voice.input.enabled", "true");
        // auto stays local: whisper.cpp if it is installed, Vosk alone if it is not.
        props.setProperty("voice.input.engine", "auto");  // auto | vosk | whispercpp | hf
        props.setProperty("voice.input.hf.model", "openai/whisper-large-v3");
        props.setProperty("voice.input.whisperModel",
                System.getProperty("user.home") + "/.local/share/whisper/ggml-base.en.bin");
        // Must be a word the speech model knows; "Jungey" is not one, so it never matched.
        props.setProperty("voice.input.wakeWord", "purple");
        props.setProperty("voice.input.wakeVariants", "");
        props.setProperty("voice.input.wakeFuzzy", "true");
        props.setProperty("voice.input.model",
                System.getProperty("user.home") + "/.local/share/vosk/model");
        // A small model used only for spotting the wake word; scripts/setup-ears.sh
        // installs it. Only the small models accept a grammar, which is what makes
        // waking reliable - see docs/VOICE.md.
        props.setProperty("voice.input.wakeModel",
                System.getProperty("user.home") + "/.local/share/vosk/wake-model");
        // Saying the name while Jungey is talking cuts it off, as it would a person.
        props.setProperty("voice.input.bargeIn", "true");

        // Speaking up unprompted: battery, heat, memory, disk and network - see Sentinel.
        props.setProperty("sentinel.enabled", "true");
        props.setProperty("sentinel.hotCelsius", "90");
        // The first start of each day opens with a briefing; later ones only greet.
        props.setProperty("briefing.onBoot", "true");

        // Blank means look at HF_TOKEN, then at the token huggingface-cli writes.
        props.setProperty("hf.token", "");
        props.setProperty("hf.url", "https://router.huggingface.co/hf-inference/models");
        props.setProperty("weather.location", "");   // blank = auto-detect by IP
        props.setProperty("llm.backend", "ollama");  // ollama | none
        props.setProperty("llm.model", "llama3.2:3b");
        props.setProperty("llm.visionModel", "moondream");
        props.setProperty("llm.url", "http://localhost:11434");
        // How long Ollama holds a model in memory. Reloading it from a hard drive takes most
        // of a minute, and a 3B model needs about 2.5 GB, so it simply stays loaded.
        props.setProperty("llm.keepAlive", "24h");
        props.setProperty("llm.visionKeepAlive", "60m");
        props.setProperty("ui.alwaysOnTop", "false");
        // auto shows the talking face when scripts/setup-face.sh has installed one.
        props.setProperty("ui.avatar", "auto");   // auto | face | reactor
        props.setProperty("ui.face",
                System.getProperty("user.home") + "/.local/share/jungey/face/face.json");

        if (Files.exists(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("[jungey] could not read config: " + e.getMessage());
            }
            if (upgradeDefaults()) save();
        } else {
            save();
        }
    }

    /**
     * The file holds every default as it was when it was first written, so a default that
     * later changes would never reach anyone who had already run Jungey. A value still equal
     * to an old default was never chosen by hand, and moves on to the new one.
     */
    private boolean upgradeDefaults() {
        boolean changed = false;
        String[][] retired = {
                // key, old default, new default
                {"llm.keepAlive", "60m", "24h"},
                // The voice used to read at an announcer's pace, with long gaps between sentences.
                {"voice.piper.speed", "1.0", "0.85"},
                {"voice.piper.pause", "0.35", "0.15"},
                {"voice.rate", "165", "175"},
        };
        for (String[] r : retired) {
            if (r[1].equals(props.getProperty(r[0]))) {
                props.setProperty(r[0], r[2]);
                changed = true;
            }
        }
        return changed;
    }

    public void save() {
        try {
            Files.createDirectories(DIR);
            try (OutputStream out = Files.newOutputStream(FILE)) {
                props.store(out, "Jungey settings - edit freely, restart to apply");
            }
        } catch (IOException e) {
            System.err.println("[jungey] could not write config: " + e.getMessage());
        }
    }

    public String str(String key) {
        return props.getProperty(key, "");
    }

    public String str(String key, String fallback) {
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    public boolean bool(String key) {
        return Boolean.parseBoolean(props.getProperty(key, "false"));
    }

    public int intv(String key, int fallback) {
        try {
            return Integer.parseInt(props.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void set(String key, String value) {
        props.setProperty(key, value);
    }

    public Path file() {
        return FILE;
    }

    /** Name to address the user by, e.g. "Suven". */
    public String userName() {
        return str("user.name", "sir");
    }

    /** Formal address used for flourish, e.g. "sir". */
    public String honorific() {
        return str("user.honorific", "sir");
    }
}
