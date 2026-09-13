package dev.suven.jungey.skills;

import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opens applications, folders and websites.
 *
 * <p>Installed apps are discovered by reading the freedesktop .desktop entries the
 * system already maintains, so anything in the Mint menu is launchable by name
 * without a hardcoded list.
 */
public class AppLauncherSkill implements Skill {

    private static final List<Path> DESKTOP_DIRS = List.of(
            Path.of("/usr/share/applications"),
            Path.of("/var/lib/flatpak/exports/share/applications"),
            Path.of("/var/lib/snapd/desktop/applications"),
            Path.of(System.getProperty("user.home"), ".local/share/applications")
    );

    /** name -> Exec command, populated lazily on first use. */
    private Map<String, String> apps;

    private static final Map<String, String> SHORTCUTS = Map.of(
            "youtube", "https://www.youtube.com",
            "github", "https://github.com",
            "gmail", "https://mail.google.com",
            "drive", "https://drive.google.com",
            "maps", "https://maps.google.com"
    );

    @Override
    public String name() {
        return "launcher";
    }

    @Override
    public String description() {
        return "Open applications, folders and websites.";
    }

    @Override
    public String[] examples() {
        return new String[]{"open firefox", "open vs code", "open downloads", "open youtube"};
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public boolean matches(String input) {
        return input.startsWith("open ") || input.startsWith("launch ")
                || input.startsWith("start ") || input.startsWith("run ");
    }

    @Override
    public SkillResult run(String input) throws IOException {
        String target = input.replaceFirst("(?i)^(open|launch|start|run)\\s+", "").trim();
        if (target.isEmpty()) {
            return SkillResult.error("Open what, exactly?");
        }
        String key = target.toLowerCase();

        // 1. A URL, or a site we have a shortcut for.
        if (key.startsWith("http://") || key.startsWith("https://") || key.matches("^[\\w.-]+\\.(com|org|net|io|dev|ai|co)(/.*)?$")) {
            String url = key.startsWith("http") ? target : "https://" + target;
            browse(url);
            return SkillResult.of("Opening " + target + ".");
        }
        if (SHORTCUTS.containsKey(key)) {
            browse(SHORTCUTS.get(key));
            return SkillResult.of(Personality.affirm() + " Opening " + target + ".");
        }

        // 2. A folder under home, e.g. "open downloads".
        Path home = Path.of(System.getProperty("user.home"));
        Path folder = home.resolve(capitalise(key));
        if (Files.isDirectory(folder)) {
            openFile(folder.toFile());
            return SkillResult.of("Opening your " + capitalise(key) + " folder.");
        }
        Path literal = Path.of(target.replaceFirst("^~", System.getProperty("user.home")));
        if (Files.exists(literal)) {
            openFile(literal.toFile());
            return SkillResult.of("Opening " + target + ".");
        }

        // 3. An installed application.
        if (apps == null) apps = scanApplications();
        String exec = findApp(key);
        if (exec != null) {
            new ProcessBuilder("sh", "-c", exec).start();
            return SkillResult.of(Personality.affirm() + " Launching " + target + ".");
        }

        return SkillResult.error("I could not find anything called \"" + target + "\".");
    }

    private String findApp(String key) {
        if (apps.containsKey(key)) return apps.get(key);
        // Fall back to a loose match so "vs code" finds "Visual Studio Code".
        String squashed = key.replace(" ", "");
        for (Map.Entry<String, String> e : apps.entrySet()) {
            String candidate = e.getKey().replace(" ", "");
            if (candidate.contains(squashed) || squashed.contains(candidate)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static Map<String, String> scanApplications() {
        Map<String, String> found = new LinkedHashMap<>();
        for (Path dir : DESKTOP_DIRS) {
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                for (Path entry : stream.filter(p -> p.toString().endsWith(".desktop")).toList()) {
                    parseDesktopEntry(entry, found);
                }
            } catch (IOException ignored) {
            }
        }
        return found;
    }

    private static void parseDesktopEntry(Path entry, Map<String, String> into) {
        try {
            String appName = null, exec = null;
            boolean noDisplay = false;
            for (String line : Files.readAllLines(entry)) {
                if (line.startsWith("[") && !line.equals("[Desktop Entry]") && appName != null) break;
                if (line.startsWith("Name=") && appName == null) appName = line.substring(5).trim();
                else if (line.startsWith("Exec=") && exec == null) exec = line.substring(5).trim();
                else if (line.startsWith("NoDisplay=true")) noDisplay = true;
            }
            if (appName != null && exec != null && !noDisplay) {
                // Strip the freedesktop field codes (%U, %f, ...) which are not valid shell.
                exec = exec.replaceAll("%[a-zA-Z]", "").trim();
                into.putIfAbsent(appName.toLowerCase(), exec);
            }
        } catch (IOException ignored) {
        }
    }

    private static void browse(String url) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            } catch (Exception ignored) {
            }
        }
        new ProcessBuilder("xdg-open", url).start();
    }

    private static void openFile(File file) throws IOException {
        new ProcessBuilder("xdg-open", file.getAbsolutePath()).start();
    }

    private static String capitalise(String s) {
        List<String> words = new ArrayList<>();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) continue;
            words.add(Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return String.join(" ", words);
    }
}
