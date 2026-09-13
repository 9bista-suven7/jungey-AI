package dev.suven.jungey.skills;

import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Moves between open windows, via wmctrl. */
public class WindowSkill implements Skill {

    @Override
    public String name() {
        return "windows";
    }

    @Override
    public String description() {
        return "Switches between open windows.";
    }

    @Override
    public String[] examples() {
        return new String[]{"switch to firefox", "what is open"};
    }

    @Override
    public int priority() {
        return 28;
    }

    @Override
    public boolean matches(String input) {
        String s = input.trim();
        return s.matches("^(switch|go|focus) to .+")
                || s.matches(".*\\bwhat'?s?\\b.*\\bopen\\b.*")
                || s.equals("list windows") || s.equals("windows");
    }

    @Override
    public SkillResult run(String input) throws Exception {
        if (!CameraSkill.onPath("wmctrl")) {
            return SkillResult.error("Window control needs wmctrl. Try: sudo apt install wmctrl");
        }

        String s = input.trim().toLowerCase();
        List<String> windows = listWindows();

        if (!s.matches("^(switch|go|focus) to .+")) {
            if (windows.isEmpty()) return SkillResult.of("Nothing is open.");
            return SkillResult.of(windows.size() + " windows open.", String.join("\n", windows));
        }

        String target = s.replaceFirst("^(switch|go|focus) to ", "").trim();
        String match = windows.stream()
                .filter(w -> w.toLowerCase().contains(target))
                .findFirst()
                .orElse(null);

        if (match == null) {
            return SkillResult.error("Nothing open called \"" + target + "\".");
        }

        // -a raises and focuses whichever window's title contains this text.
        new ProcessBuilder("wmctrl", "-a", match).start().waitFor(5, TimeUnit.SECONDS);
        return SkillResult.of(Personality.affirm() + " Switching to " + match + ".");
    }

    /** Window titles only - wmctrl prints id, desktop and host in front of each. */
    private static List<String> listWindows() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("wmctrl", "-l").redirectErrorStream(true).start();
        List<String> titles = new ArrayList<>();

        try (var reader = p.inputReader()) {
            for (String line : reader.lines().toList()) {
                String[] parts = line.split("\\s+", 4);
                if (parts.length == 4 && !parts[3].isBlank()) titles.add(parts[3]);
            }
        }
        p.waitFor(5, TimeUnit.SECONDS);
        return titles;
    }
}
