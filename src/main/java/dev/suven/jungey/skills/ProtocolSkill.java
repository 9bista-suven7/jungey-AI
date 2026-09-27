package dev.suven.jungey.skills;

import dev.suven.jungey.core.Brain;
import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Protocols: several commands under one name. "Engage focus protocol" turns the volume
 * down and starts a 25-minute clock; "night protocol" dims the screen and locks it.
 *
 * <p>They live in ~/.config/jungey/protocols.conf, one per line, written with the same
 * words you would say to Jungey - so anything it understands can be a step, and adding a
 * protocol is a matter of editing a text file. The file is read each time a protocol is
 * asked for, so a change needs no restart.
 */
public class ProtocolSkill implements Skill {

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".config", "jungey", "protocols.conf");

    private static final String DEFAULTS = """
            # Jungey protocols. One per line:  name = command; command; command
            # Each command is anything you could say to Jungey. Then say
            # "engage <name> protocol" (or initiate, activate, run, start).
            # This file is read every time, so edits take effect at once.

            focus      = set volume to 25; remind me in 25 minutes to take a break
            break      = set volume to 50; remind me in 5 minutes to get back to work
            status     = status report; weather; what is on my todo
            night      = dimmer; dimmer; set volume to 20; remind me in 30 minutes to go to sleep
            lockdown   = mute audio; lock the screen
            house party = set volume to 80; open youtube
            """;

    private static final Pattern RUN = Pattern.compile(
            "^(?:engage|initiate|activate|run|start|execute|begin|launch|commence)\\s+(?:the\\s+)?(.+?)\\s+protocol$"
                    + "|^(.+?)\\s+protocol(?:,?\\s+(?:engage|go|now|please))?$");
    private static final Pattern LIST = Pattern.compile(
            "^(?:list|show|what are)?\\s*(?:my |the |your )?protocols$|^what protocols(?: do (?:you|i) have)?$");
    private static final Pattern EDIT = Pattern.compile("^(?:edit|change|open) (?:my |the )?protocols$");

    private final Brain brain;

    public ProtocolSkill(Brain brain) {
        this.brain = brain;
    }

    @Override
    public String name() {
        return "protocols";
    }

    @Override
    public String description() {
        return "Runs several commands under one name, from protocols.conf.";
    }

    @Override
    public String[] examples() {
        return new String[]{"engage focus protocol", "night protocol", "list protocols", "edit protocols"};
    }

    @Override
    public int priority() {
        return 16;   // ahead of the launcher, which would take "run …" and "start …"
    }

    @Override
    public boolean matches(String input) {
        String s = tidy(input);
        if (LIST.matcher(s).matches() || EDIT.matcher(s).matches()) return true;
        Matcher m = RUN.matcher(s);
        if (!m.matches()) return false;
        // "Engage X protocol" is plainly for us; a bare "X protocol" only when X is one of
        // ours - "what is the tcp protocol" is a question, not an order.
        return m.group(1) != null || known().contains(m.group(2).trim());
    }

    private java.util.Set<String> names = java.util.Set.of();
    private java.nio.file.attribute.FileTime namesAt;

    /** The protocol names, re-read only when the file changes, since matching runs on the UI thread. */
    private synchronized java.util.Set<String> known() {
        try {
            if (!Files.exists(FILE)) return java.util.Set.of("focus", "break", "status", "night", "lockdown", "house party");
            var modified = Files.getLastModifiedTime(FILE);
            if (!modified.equals(namesAt)) {
                names = load().keySet();
                namesAt = modified;
            }
        } catch (IOException e) {
            // Keep the names read last time.
        }
        return names;
    }

    @Override
    public SkillResult run(String input) throws Exception {
        String s = tidy(input);
        Map<String, List<String>> protocols = load();

        if (EDIT.matcher(s).matches()) {
            new ProcessBuilder("xdg-open", FILE.toString()).start();
            return SkillResult.of("Opening your protocols.", FILE.toString());
        }
        if (LIST.matcher(s).matches()) {
            StringBuilder detail = new StringBuilder();
            protocols.forEach((name, steps) -> detail.append(String.format("%-12s %s%n", name, String.join("; ", steps))));
            return SkillResult.of(protocols.size() + " protocols on file: "
                    + String.join(", ", protocols.keySet()) + ".", detail.toString().trim());
        }

        Matcher m = RUN.matcher(s);
        if (!m.matches()) return SkillResult.error("Which protocol?");
        String name = (m.group(1) != null ? m.group(1) : m.group(2)).trim();
        List<String> steps = protocols.get(name);
        if (steps == null) {
            return SkillResult.error("I have no protocol called " + name + ". They're kept in " + FILE
                    + " - say \"edit protocols\" to add one.");
        }
        return engage(name, steps);
    }

    private SkillResult engage(String name, List<String> steps) {
        List<String> said = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        int failed = 0;

        for (String step : steps) {
            Skill skill = brain.route(step);
            SkillResult r;
            if (skill == null || skill == this || skill.priority() >= 9000) {
                // A protocol inside a protocol could go round for ever, and a step only the
                // model can take has no business in a list of instructions.
                r = SkillResult.error("I don't know how to \"" + step + "\".");
            } else {
                r = brain.execute(skill, step);
            }
            if (!r.ok()) failed++;
            detail.append(r.ok() ? "✓ " : "✗ ").append(step);
            if (!r.ok() || r.speech().isBlank()) detail.append("  - ").append(r.speech());
            detail.append('\n');
            if (!r.streamed() && !r.speech().isBlank()) said.add(Personality.withoutAffirmation(r.speech()));
        }

        String sir = Config.get().honorific();
        String head = capitalise(name) + " protocol engaged" + (failed == 0 ? ", " + sir + "." : ", though "
                + failed + (failed == 1 ? " step" : " steps") + " failed.");
        return SkillResult.of(head + " " + String.join(" ", said), detail.toString().trim());
    }

    /** name -> steps, in file order. Writes the starter file the first time. */
    static Map<String, List<String>> load() throws IOException {
        if (!Files.exists(FILE)) {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, DEFAULTS, StandardCharsets.UTF_8);
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(FILE)) {
            String t = line.trim();
            int eq = t.indexOf('=');
            if (t.isEmpty() || t.startsWith("#") || eq <= 0) continue;
            String name = tidy(t.substring(0, eq)).replaceFirst("\\s+protocol$", "");
            List<String> steps = new ArrayList<>();
            for (String step : t.substring(eq + 1).split(";")) {
                if (!step.isBlank()) steps.add(step.trim());
            }
            if (!name.isEmpty() && !steps.isEmpty()) out.put(name, steps);
        }
        return out;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ENGLISH) + s.substring(1);
    }

    private static String tidy(String input) {
        return input.toLowerCase().replace('’', '\'').replaceAll("[^a-z0-9' ]", " ").trim().replaceAll("\\s+", " ");
    }
}
