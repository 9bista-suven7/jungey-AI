package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Answers "why is this thing slow" and "am I out of space" with the processes and
 * filesystems actually responsible, rather than a percentage.
 */
public class DiagnosticsSkill implements Skill {

    private static final int TOP_N = 6;

    @Override
    public String name() {
        return "diagnostics";
    }

    @Override
    public String description() {
        return "Finds what is using the CPU, memory or disk.";
    }

    @Override
    public String[] examples() {
        return new String[]{"why is my laptop slow", "what is using my cpu", "how much disk space"};
    }

    @Override
    public int priority() {
        return 19;
    }

    @Override
    public boolean matches(String input) {
        return topic(input.trim()) != null;
    }

    private static String topic(String s) {
        if (s.matches(".*\\b(disk|storage|space|drive)\\b.*")) return "disk";
        if (s.matches(".*\\b(slow|sluggish|laggy|hogging)\\b.*")) return "slow";
        if (s.matches(".*\\b(using|eating|taking)\\b.*\\b(cpu|memory|ram)\\b.*")) return "slow";
        if (s.matches(".*\\bwhat'?s? running\\b.*") || s.equals("processes")) return "slow";
        return null;
    }

    @Override
    public SkillResult run(String input) throws Exception {
        return topic(input.trim().toLowerCase()).equals("disk") ? disk() : busiest();
    }

    private SkillResult busiest() throws IOException, InterruptedException {
        List<String> rows = run("ps", "-eo", "comm,pcpu,pmem", "--sort=-pcpu", "--no-headers");
        if (rows.isEmpty()) return SkillResult.error("I could not read the process list.");

        StringBuilder detail = new StringBuilder(String.format("%-22s %6s %6s%n", "process", "cpu", "mem"));
        String worst = null;
        double worstCpu = 0;

        for (String row : rows.stream().limit(TOP_N).toList()) {
            String[] p = row.trim().split("\\s+");
            if (p.length < 3) continue;
            detail.append(String.format("%-22s %5s%% %5s%%%n", p[0], p[1], p[2]));

            double cpu = Double.parseDouble(p[1]);
            if (cpu > worstCpu) {
                worstCpu = cpu;
                worst = p[0];
            }
        }

        // ps sums across cores, so a busy process on an 8-core machine reports 800%.
        // Spoken aloud that is nonsense, so it is scaled to the machine as a whole.
        int share = (int) Math.round(worstCpu / Runtime.getRuntime().availableProcessors());

        String spoken = worst == null || share < 5
                ? "Nothing is working very hard."
                : worst + " is using the most, about " + share + " percent of your processor.";
        return SkillResult.of(spoken, detail.toString().trim());
    }

    private SkillResult disk() throws IOException, InterruptedException {
        List<String> rows = run("df", "-h", "--output=target,size,used,avail,pcent", "-x", "tmpfs", "-x", "devtmpfs");
        if (rows.size() < 2) return SkillResult.error("I could not read the disks.");

        StringBuilder detail = new StringBuilder();
        String spoken = "";

        for (String row : rows) {
            if (row.isBlank()) continue;
            detail.append(row.trim()).append('\n');

            String[] p = row.trim().split("\\s+");
            if (p.length >= 5 && p[0].equals("/")) {
                spoken = p[3] + " free on your main drive, " + p[4] + " used.";
            }
        }
        return SkillResult.of(spoken.isEmpty() ? "Here are your disks." : spoken, detail.toString().trim());
    }

    private static List<String> run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (var reader = p.inputReader()) {
            lines.addAll(reader.lines().toList());
        }
        p.waitFor(15, TimeUnit.SECONDS);
        return lines;
    }
}
