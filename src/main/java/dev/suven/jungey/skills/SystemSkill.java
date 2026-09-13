package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.SysInfo;

/** Machine vitals: CPU, memory, disk, battery, uptime. The "status report" command. */
public class SystemSkill implements Skill {

    @Override
    public String name() {
        return "system";
    }

    @Override
    public String description() {
        return "Machine vitals - CPU, memory, disk, battery, uptime.";
    }

    @Override
    public String[] examples() {
        return new String[]{"status report", "system status", "how much ram", "battery"};
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean matches(String input) {
        return input.matches(".*\\b(status|system|diagnostics|vitals|cpu|ram|memory|disk|storage|battery|uptime|specs)\\b.*");
    }

    @Override
    public SkillResult run(String input) {
        String lower = input.toLowerCase();

        // Narrow questions get a one-line answer rather than the full readout.
        if (lower.contains("battery") && !lower.contains("status report")) {
            int pct = SysInfo.batteryPercent();
            if (pct < 0) return SkillResult.of("No battery detected. You are on mains power.");
            String state = SysInfo.onAcPower() ? "charging" : "on battery";
            return SkillResult.of("Battery is at " + pct + " percent, " + state + ".");
        }

        if ((lower.contains("ram") || lower.contains("memory")) && !lower.contains("status")) {
            long[] mem = SysInfo.memory();
            double usedGb = mem[0] / 1048576.0;
            double totalGb = mem[1] / 1048576.0;
            return SkillResult.of(String.format(
                    "Memory is at %.1f of %.1f gigabytes, roughly %.0f percent.",
                    usedGb, totalGb, 100.0 * mem[0] / Math.max(1, mem[1])));
        }

        // Full diagnostic readout.
        double cpu = SysInfo.cpuPercent();
        long[] mem = SysInfo.memory();
        long[] disk = SysInfo.disk();
        int battery = SysInfo.batteryPercent();

        StringBuilder sb = new StringBuilder();
        sb.append("HOST      ").append(SysInfo.hostName()).append('\n');
        sb.append("OS        ").append(SysInfo.distro()).append('\n');
        sb.append("CPU       ").append(SysInfo.cpuModel()).append("  (").append(SysInfo.cores()).append(" cores)\n");
        sb.append("LOAD      ").append(bar(cpu)).append(String.format("  %.0f%%", cpu)).append('\n');
        sb.append("MEMORY    ").append(bar(100.0 * mem[0] / Math.max(1, mem[1])))
                .append(String.format("  %.1f / %.1f GB", mem[0] / 1048576.0, mem[1] / 1048576.0)).append('\n');
        sb.append("DISK      ").append(bar(100.0 * disk[0] / Math.max(1, disk[1])))
                .append("  ").append(SysInfo.humanBytes(disk[0])).append(" / ")
                .append(SysInfo.humanBytes(disk[1])).append('\n');
        if (battery >= 0) {
            sb.append("BATTERY   ").append(bar(battery)).append("  ").append(battery).append('%')
                    .append(SysInfo.onAcPower() ? "  (charging)" : "").append('\n');
        }
        sb.append("UPTIME    ").append(SysInfo.uptime());

        String spoken = String.format(
                "All systems nominal. Processor at %.0f percent, memory at %.0f percent%s.",
                cpu, 100.0 * mem[0] / Math.max(1, mem[1]),
                battery >= 0 ? ", battery at " + battery + " percent" : "");

        return SkillResult.of(spoken, sb.toString());
    }

    /** A 20-cell meter, so the readout scans at a glance instead of needing to be parsed. */
    private static String bar(double percent) {
        int filled = (int) Math.round(Math.max(0, Math.min(100, percent)) / 5.0);
        return "[" + "#".repeat(filled) + ".".repeat(20 - filled) + "]";
    }
}
