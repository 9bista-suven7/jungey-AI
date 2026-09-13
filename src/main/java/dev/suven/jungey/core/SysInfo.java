package dev.suven.jungey.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
