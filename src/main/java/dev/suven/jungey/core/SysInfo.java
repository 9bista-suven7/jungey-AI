package dev.suven.jungey.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Reads machine vitals straight from /proc and /sys. No dependencies, no shelling out -
 * these are plain text files on every Linux box, which keeps this fast enough to poll
 * once a second for the status bar.
 */
public final class SysInfo {

    private static long lastIdle = 0;
    private static long lastTotal = 0;

    private SysInfo() {
    }

    /**
     * CPU load as a percentage, measured as the delta since the previous call.
     * The first call has no baseline and returns 0.
     */
    public static synchronized double cpuPercent() {
        try {
            String line = Files.readAllLines(Path.of("/proc/stat")).get(0);
            String[] parts = line.trim().split("\\s+");

            long idle = 0, total = 0;
            for (int i = 1; i < parts.length; i++) {
                long v = Long.parseLong(parts[i]);
                total += v;
                if (i == 4 || i == 5) idle += v;   // idle + iowait
            }

            long dTotal = total - lastTotal;
            long dIdle = idle - lastIdle;
            lastTotal = total;
            lastIdle = idle;

            if (dTotal <= 0) return 0;
            return Math.max(0, Math.min(100, 100.0 * (dTotal - dIdle) / dTotal));
        } catch (Exception e) {
            return 0;
        }
    }

    /** @return {usedKb, totalKb} */
    public static long[] memory() {
        long total = 0, available = 0;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemTotal:")) total = parseKb(line);
                else if (line.startsWith("MemAvailable:")) available = parseKb(line);
                if (total > 0 && available > 0) break;
            }
        } catch (IOException ignored) {
        }
        return new long[]{total - available, total};
    }

    private static long parseKb(String line) {
        String[] p = line.trim().split("\\s+");
        return p.length > 1 ? Long.parseLong(p[1]) : 0;
    }

    /** Battery charge 0-100, or -1 on a desktop with no battery. */
    public static int batteryPercent() {
        try (var stream = Files.list(Path.of("/sys/class/power_supply"))) {
            for (Path p : stream.toList()) {
                Path cap = p.resolve("capacity");
                Path type = p.resolve("type");
                if (Files.exists(cap) && Files.exists(type)
                        && Files.readString(type).trim().equalsIgnoreCase("Battery")) {
                    return Integer.parseInt(Files.readString(cap).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public static boolean onAcPower() {
        try (var stream = Files.list(Path.of("/sys/class/power_supply"))) {
            for (Path p : stream.toList()) {
                Path online = p.resolve("online");
                if (Files.exists(online) && Files.readString(online).trim().equals("1")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Uptime rendered as "3d 4h 12m". */
    public static String uptime() {
        try {
            double seconds = Double.parseDouble(Files.readString(Path.of("/proc/uptime")).trim().split("\\s+")[0]);
            long s = (long) seconds;
            long days = s / 86400;
            long hours = (s % 86400) / 3600;
            long mins = (s % 3600) / 60;
            StringBuilder sb = new StringBuilder();
            if (days > 0) sb.append(days).append("d ");
            if (days > 0 || hours > 0) sb.append(hours).append("h ");
            sb.append(mins).append("m");
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** @return {usedBytes, totalBytes} for the filesystem holding the home directory */
    public static long[] disk() {
        try {
            var store = Files.getFileStore(Path.of(System.getProperty("user.home")));
            long total = store.getTotalSpace();
            return new long[]{total - store.getUsableSpace(), total};
        } catch (IOException e) {
            return new long[]{0, 0};
        }
    }

    public static String cpuModel() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.startsWith("model name")) {
                    return line.split(":", 2)[1].trim();
                }
            }
        } catch (IOException ignored) {
        }
        return System.getProperty("os.arch", "unknown");
    }

    public static int cores() {
        return Runtime.getRuntime().availableProcessors();
    }

    public static String hostName() {
        try {
            return Files.readString(Path.of("/etc/hostname")).trim();
        } catch (IOException e) {
            return "localhost";
        }
    }

    /** Pretty distro name, e.g. "Linux Mint 21.3". */
    public static String distro() {
        try {
            List<String> lines = Files.readAllLines(Path.of("/etc/os-release"));
            for (String line : lines) {
                if (line.startsWith("PRETTY_NAME=")) {
                    return line.split("=", 2)[1].replace("\"", "").trim();
                }
            }
        } catch (IOException ignored) {
        }
        return System.getProperty("os.name", "Linux");
    }

    /** The one-minute load average divided by the core count: 1.0 means every core busy. */
    public static double loadPerCore() {
        try {
            String first = Files.readString(Path.of("/proc/loadavg")).trim().split("\\s+")[0];
            return Double.parseDouble(first) / cores();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The hottest thermal sensor, in degrees Celsius, or -1 where there are none (most
     * virtual machines). Readings above 125 are a driver reporting nonsense, not a fire.
     */
    public static double temperatureC() {
        double hottest = -1;
        try (var zones = Files.list(Path.of("/sys/class/thermal"))) {
            for (Path zone : zones.filter(z -> z.getFileName().toString().startsWith("thermal_zone")).toList()) {
                try {
                    double c = Long.parseLong(Files.readString(zone.resolve("temp")).trim()) / 1000.0;
                    if (c > 0 && c < 125) hottest = Math.max(hottest, c);
                } catch (Exception ignored) {
                    // Some zones refuse to be read while their device sleeps.
                }
            }
        } catch (IOException ignored) {
        }
        return hottest;
    }

    /**
     * Whether there is a route out of this machine. Read from the kernel's routing table,
     * so it costs a file read rather than a process - cheap enough to ask every few seconds.
     */
    public static boolean hasDefaultRoute() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/net/route")).stream().skip(1).toList()) {
                String[] f = line.trim().split("\\s+");
                if (f.length > 1 && f[1].equals("00000000")) return true;
            }
            for (String line : Files.readAllLines(Path.of("/proc/net/ipv6_route"))) {
                String[] f = line.trim().split("\\s+");
                if (f.length > 9 && f[0].equals("00000000000000000000000000000000") && f[1].equals("00")
                        && !f[9].equals("lo")) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** A process, with how hard it is working and how much memory it holds. */
    public record Proc(long pid, String name, double cpuPercent, long rssBytes) {
    }

    /** Programs that are part of Jungey itself, and never worth complaining about. */
    private static final Set<String> OWN = Set.of("ollama", "ollama_llama_server", "llama-server",
            "piper", "whisper-server");

    /**
     * The process using the most CPU right now - measured over the next second, not averaged
     * over its whole life the way ps reports it - as a share of the whole machine. Jungey and
     * the helpers it runs are left out. Null when nothing could be read.
     */
    public static Proc busiestProcess() {
        Set<Long> ours = own();
        Map<Long, Long> before = cpuTicks(ours);
        long start = System.nanoTime();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        Map<Long, Long> after = cpuTicks(ours);
        double seconds = (System.nanoTime() - start) / 1e9;

        long best = -1;
        long bestTicks = 0;
        for (var e : after.entrySet()) {
            long used = e.getValue() - before.getOrDefault(e.getKey(), e.getValue());
            if (used > bestTicks) {
                bestTicks = used;
                best = e.getKey();
            }
        }
        if (best < 0) return null;

        // /proc counts in clock ticks, 100 a second on every mainstream kernel.
        double percent = 100.0 * bestTicks / 100.0 / seconds / cores();
        return new Proc(best, name(best), percent, rss(best));
    }

    /** The process holding the most memory, leaving out Jungey and its helpers; null if none. */
    public static Proc hungriestProcess() {
        Set<Long> ours = own();
        long best = -1;
        long bestRss = 0;
        for (long pid : pids()) {
            if (ours.contains(pid)) continue;
            long rss = rss(pid);
            if (rss > bestRss && !OWN.contains(name(pid))) {
                bestRss = rss;
                best = pid;
            }
        }
        return best < 0 ? null : new Proc(best, name(best), 0, bestRss);
    }

    private static Set<Long> own() {
        ProcessHandle self = ProcessHandle.current();
        Set<Long> ours = self.descendants().map(ProcessHandle::pid).collect(Collectors.toSet());
        ours.add(self.pid());
        return ours;
    }

    private static List<Long> pids() {
        try (var entries = Files.list(Path.of("/proc"))) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(n -> n.chars().allMatch(Character::isDigit))
                    .map(Long::parseLong)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** utime + stime for every process that is not ours. */
    private static Map<Long, Long> cpuTicks(Set<Long> ours) {
        Map<Long, Long> ticks = new HashMap<>();
        for (long pid : pids()) {
            if (ours.contains(pid)) continue;
            try {
                String stat = Files.readString(Path.of("/proc", String.valueOf(pid), "stat"));
                // The name sits in brackets and may itself hold spaces; count fields after it.
                String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                if (!OWN.contains(name(pid))) ticks.put(pid, Long.parseLong(f[11]) + Long.parseLong(f[12]));
            } catch (Exception ignored) {
                // Exited between listing and reading.
            }
        }
        return ticks;
    }

    private static String name(long pid) {
        try {
            return Files.readString(Path.of("/proc", String.valueOf(pid), "comm")).trim();
        } catch (IOException e) {
            return "?";
        }
    }

    private static long rss(long pid) {
        try {
            String[] f = Files.readString(Path.of("/proc", String.valueOf(pid), "statm")).trim().split("\\s+");
            return Long.parseLong(f[1]) * 4096;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The title of the window in front, or "" when it cannot be told - no X server, or
     * neither xdotool nor xprop installed. Jungey's own window is reported as "".
     */
    public static String activeWindow() {
        String title = "";
        if (onPath("xdotool")) {
            title = run("xdotool", "getactivewindow", "getwindowname");
        } else if (onPath("xprop")) {
            String root = run("xprop", "-root", "_NET_ACTIVE_WINDOW");
            int hash = root.indexOf('#');
            if (hash >= 0) {
                String id = root.substring(hash + 1).trim().split("[\\s,]+")[0];
                String name = run("xprop", "-id", id, "_NET_WM_NAME");
                int quote = name.indexOf('"');
                if (quote >= 0 && name.endsWith("\"")) title = name.substring(quote + 1, name.length() - 1);
            }
        }
        title = title.trim();
        return title.equals("Jungey") ? "" : title;
    }

    private static boolean onPath(String binary) {
        for (String dir : System.getenv().getOrDefault("PATH", "/usr/bin:/bin").split(":")) {
            if (Files.isExecutable(Path.of(dir, binary))) return true;
        }
        return false;
    }

    private static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly();
            return out.trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, units[i]);
    }
}
