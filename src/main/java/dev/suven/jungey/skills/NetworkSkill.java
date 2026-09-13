package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Whether you are online, and on what, via nmcli. */
public class NetworkSkill implements Skill {

    @Override
    public String name() {
        return "network";
    }

    @Override
    public String description() {
        return "Reports your network connection.";
    }

    @Override
    public String[] examples() {
        return new String[]{"am I online", "what wifi am I on"};
    }

    @Override
    public int priority() {
        return 21;
    }

    @Override
    public boolean matches(String input) {
        String s = input.trim();
        return s.matches(".*\\b(am i online|are we online|internet|wifi|wi-fi|network)\\b.*")
                || s.equals("connection");
    }

    @Override
    public SkillResult run(String input) throws Exception {
        if (!CameraSkill.onPath("nmcli")) {
            return SkillResult.error("Network status needs nmcli.");
        }

        String state = run("nmcli", "-t", "-f", "STATE", "general").trim();
        boolean online = state.contains("connected") && !state.contains("disconnected");

        if (!online) {
            return SkillResult.of("You are offline.");
        }

        String active = run("nmcli", "-t", "-f", "NAME,TYPE", "connection", "show", "--active").trim();
        String wifi = "";
        StringBuilder detail = new StringBuilder();

        for (String line : active.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split(":");
            if (parts.length < 2) continue;
            detail.append("• ").append(parts[0]).append("  (").append(parts[1]).append(")\n");
            if (parts[1].contains("wireless") && wifi.isEmpty()) wifi = parts[0];
        }

        String spoken = wifi.isEmpty()
                ? "You are online."
                : "You are online, on " + wifi + ".";
        return detail.isEmpty() ? SkillResult.of(spoken)
                : SkillResult.of(spoken, detail.toString().trim());
    }

    private static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(10, TimeUnit.SECONDS);
        return out;
    }
}
