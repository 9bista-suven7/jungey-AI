package dev.suven.jungeynotepad.notes;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * How the notepad was left: theme, text size, what was showing, which notes were open and
 * which are pinned. Kept in ~/.config/jungey-notepad/settings.properties.
 */
public final class Settings {

    private static final String SEP = "\u001f";

    private final Path file;
    private final Properties props = new Properties();

    private Settings(Path file) {
        this.file = file;
    }

    public static Settings load() {
        String config = System.getenv("XDG_CONFIG_HOME");
        Path base = config != null && !config.isBlank() ? Path.of(config)
                : Path.of(System.getProperty("user.home"), ".config");
        Settings s = new Settings(base.resolve("jungey-notepad").resolve("settings.properties"));
        if (Files.exists(s.file)) {
            try (Reader r = Files.newBufferedReader(s.file, StandardCharsets.UTF_8)) {
                s.props.load(r);
            } catch (IOException ignored) {
                // A broken settings file costs the preferences, not the notes.
            }
        }
        return s;
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(w, "Jungey Notepad");
            }
        } catch (IOException ignored) {
            // Next time starts with the defaults; nothing written is lost.
        }
    }

    public boolean dark() { return !"light".equals(props.getProperty("theme")); }
    public void dark(boolean v) { props.setProperty("theme", v ? "dark" : "light"); }

    public double fontSize() { return num("font.size", 15); }
    public void fontSize(double v) { props.setProperty("font.size", String.valueOf(v)); }

    public boolean wrap() { return flag("wrap", true); }
    public void wrap(boolean v) { props.setProperty("wrap", String.valueOf(v)); }

    public boolean lineNumbers() { return flag("line.numbers", false); }
    public void lineNumbers(boolean v) { props.setProperty("line.numbers", String.valueOf(v)); }

    public boolean mono() { return flag("mono", false); }
    public void mono(boolean v) { props.setProperty("mono", String.valueOf(v)); }

    public boolean sidebar() { return flag("sidebar", true); }
    public void sidebar(boolean v) { props.setProperty("sidebar", String.valueOf(v)); }

    public boolean preview() { return flag("preview", false); }
    public void preview(boolean v) { props.setProperty("preview", String.valueOf(v)); }

    public double width() { return num("window.width", 1180); }
    public double height() { return num("window.height", 760); }
    public void size(double w, double h) {
        props.setProperty("window.width", String.valueOf(w));
        props.setProperty("window.height", String.valueOf(h));
    }

    public List<String> openNotes() { return list("open"); }
    public void openNotes(List<String> v) { props.setProperty("open", String.join(SEP, v)); }

    public String active() { return props.getProperty("active", ""); }
    public void active(String v) { props.setProperty("active", v); }

    public Set<String> pinned() { return new LinkedHashSet<>(list("pinned")); }
    public void pinned(Set<String> v) { props.setProperty("pinned", String.join(SEP, v)); }

    private List<String> list(String key) {
        String v = props.getProperty(key, "");
        return v.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(v.split(SEP)));
    }

    private boolean flag(String key, boolean fallback) {
        String v = props.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    private double num(String key, double fallback) {
        try {
            return Double.parseDouble(props.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
