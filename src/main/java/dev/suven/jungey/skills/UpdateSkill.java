package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reports pending package updates.
 *
 * <p>Reads only. Installing them needs root, and an assistant that can silently elevate
 * privileges is a worse idea than one that tells you the command to run.
 */
public class UpdateSkill implements Skill {

    private static final int LIST_LIMIT = 15;

    @Override
    public String name() {
        return "updates";
    }

    @Override
    public String description() {
        return "Checks for pending package updates.";
    }

    @Override
    public String[] examples() {
        return new String[]{"any updates", "are there updates"};
    }

    @Override
    public int priority() {
        return 23;
    }

    @Override
    public boolean matches(String input) {
        String s = input.trim();
        return s.matches(".*\\b(any |are there |check for )?updates?\\b.*")
                && !s.matches(".*\\b(update my|update the)\\b.*");
    }

    @Override
    public SkillResult run(String input) throws Exception {
        if (!CameraSkill.onPath("apt")) {
            return SkillResult.error("I only know how to check apt.");
        }

        List<String> upgradable = new ArrayList<>();
        Process p = new ProcessBuilder("apt", "list", "--upgradable")
                .redirectErrorStream(true)
                .start();

        try (var reader = p.inputReader()) {
            for (String line : reader.lines().toList()) {
                // Lines look like "package/suite 1.2.3 amd64 [upgradable from: 1.2.2]".
                if (line.contains("[upgradable")) {
                    upgradable.add(line.substring(0, line.indexOf('/')));
                }
            }
        }
        p.waitFor(60, TimeUnit.SECONDS);

        if (upgradable.isEmpty()) {
            return SkillResult.of("Everything is up to date.");
        }

        List<String> shown = upgradable.size() > LIST_LIMIT
                ? upgradable.subList(0, LIST_LIMIT) : upgradable;

        String detail = String.join("\n", shown)
                + (upgradable.size() > LIST_LIMIT ? "\n… and " + (upgradable.size() - LIST_LIMIT) + " more" : "")
                + "\n\nInstall with: sudo apt upgrade";

        return SkillResult.of(upgradable.size() + " package"
                + (upgradable.size() == 1 ? "" : "s") + " can be upgraded.", detail);
    }
}
