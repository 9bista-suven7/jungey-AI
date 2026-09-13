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
        props.setProperty("voice.engine", "auto");   // auto | piper | espeak | none
        props.setProperty("voice.rate", "165");
        props.setProperty("voice.input.enabled", "true");
        // Must be a word the speech model knows; "Jungey" is not one, so it never matched.
        props.setProperty("voice.input.wakeWord", "purple");
        props.setProperty("voice.input.wakeVariants", "");
        props.setProperty("voice.input.model",
                System.getProperty("user.home") + "/.local/share/vosk/model");
        props.setProperty("weather.location", "");   // blank = auto-detect by IP
        props.setProperty("llm.backend", "ollama");  // ollama | none
        props.setProperty("llm.model", "llama3.2:3b");
        props.setProperty("llm.visionModel", "moondream");
        props.setProperty("llm.url", "http://localhost:11434");
        props.setProperty("ui.alwaysOnTop", "false");

        if (Files.exists(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("[jungey] could not read config: " + e.getMessage());
            }
        } else {
            save();
        }
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
