package dev.suven.jungey.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps an eye on the machine and speaks up when something needs you.
 *
 * <p>An assistant that only answers is a search box with a voice. This is the part that
 * notices: the battery running down, the processor pinned or running hot, memory or the
 * disk filling up, the network dropping and coming back. Each is said once, when it starts
 * to matter, and not again for a good while - a warning repeated every minute is one you
 * learn to ignore.
 *
 * <p>Everything is read from /proc and /sys, so a check costs next to nothing; only
 * naming the process responsible looks any closer, and only once there is a reason to.
 */
public final class Sentinel {

    private static final long FIRST_CHECK_S = 45;
    private static final long INTERVAL_S = 30;

    /** How long each kind of warning stays quiet after it has been given. */
    private static final Map<String, Long> COOLDOWN_MS = Map.of(
            "cpu", TimeUnit.MINUTES.toMillis(20),
            "memory", TimeUnit.MINUTES.toMillis(20),
            "heat", TimeUnit.MINUTES.toMillis(10),
            "disk", TimeUnit.HOURS.toMillis(12));

    private final Viewport viewport;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jungey-sentinel");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Long> lastSaid = new HashMap<>();

    // Battery: the lowest threshold already warned about since the charger was last in.
    private int warnedAt = 101;
    private Boolean wasCharging;
    /** Seen charging from well below full, so reaching full is news rather than the usual state. */
    private boolean climbing;

    // Sustained conditions have to hold for two checks in a row before they count.
    private int busyChecks;
    private int fullMemoryChecks;

    // Network: what we believe, and what the last check saw, so one blip is not news.
    private Boolean online;
    private Boolean lastSeen;

    public Sentinel(Viewport viewport) {
        this.viewport = viewport;
    }

    public void start() {
        clock.scheduleWithFixedDelay(this::check, FIRST_CHECK_S, INTERVAL_S, TimeUnit.SECONDS);
    }

    public void stop() {
        clock.shutdownNow();
    }

    public boolean enabled() {
        return Config.get().bool("sentinel.enabled");
    }

    public void setEnabled(boolean on) {
        Config cfg = Config.get();
        cfg.set("sentinel.enabled", String.valueOf(on));
        cfg.save();
    }

    /**
     * What is worth knowing right now, whether or not it has been said: for briefings and
     * for "anything I should know?". Empty when all is well.
     */
    public List<String> concerns() {
        List<String> out = new ArrayList<>();

        int battery = SysInfo.batteryPercent();
        if (battery >= 0 && battery <= 25 && !SysInfo.onAcPower()) {
            out.add("the battery is at " + battery + " percent");
        }
        double heat = SysInfo.temperatureC();
        if (heat >= hotCelsius()) out.add(String.format("the processor is running hot, at %.0f degrees", heat));

        long[] mem = SysInfo.memory();
        double memPct = 100.0 * mem[0] / Math.max(1, mem[1]);
        if (memPct >= 90) out.add(String.format("memory is %.0f percent full", memPct));

        long[] disk = SysInfo.disk();
        if (disk[1] > 0 && 100.0 * disk[0] / disk[1] >= 90) {
            out.add("your main drive is nearly full, " + SysInfo.humanBytes(disk[1] - disk[0]) + " left");
        }
        if (SysInfo.loadPerCore() >= 0.9) out.add("the processor is working flat out");
        if (!SysInfo.hasDefaultRoute()) out.add("we are offline");
        return out;
    }

    private void check() {
        try {
            if (!enabled()) return;
            battery();
            processor();
            memory();
            heat();
            disk();
            network();
        } catch (RuntimeException e) {
            // A failed check must never stop the ones after it.
            System.err.println("[jungey] sentinel check failed: " + e);
        }
    }

    private void battery() {
        int pct = SysInfo.batteryPercent();
        if (pct < 0) return;
        boolean charging = SysInfo.onAcPower();
        String sir = Config.get().honorific();

        if (charging) {
            if (Boolean.FALSE.equals(wasCharging) && warnedAt <= 20) {
                say("Charger connected. Thank you, " + sir + ". We are at " + pct + " percent.");
            }
            warnedAt = 101;
            if (pct < 95) climbing = true;
            if (pct >= 100 && climbing) {
                climbing = false;
                say("The battery is full, " + sir + ". Unplug whenever you like.");
            }
        } else {
            if (pct <= 5 && warnedAt > 5) {
                warnedAt = 5;
                say("Battery critical, " + pct + " percent. Save your work, " + sir
                        + ". I would rather not go dark mid-sentence.");
            } else if (pct <= 10 && warnedAt > 10) {
                warnedAt = 10;
                say("Battery at " + pct + " percent. I would plug in soon, " + sir + ".");
            } else if (pct <= 20 && warnedAt > 20) {
                warnedAt = 20;
                say("Battery at " + pct + " percent, " + sir + ". Might be time for the charger.");
            }
        }
        if (!charging) climbing = false;
        wasCharging = charging;
    }

    private void processor() {
        busyChecks = SysInfo.loadPerCore() >= 0.9 ? busyChecks + 1 : 0;
        if (busyChecks < 2 || !due("cpu")) return;

        SysInfo.Proc culprit = SysInfo.busiestProcess();
        // Busy because of Jungey's own thinking, or spread across everything: nothing to name.
        if (culprit == null || culprit.cpuPercent() < 25) return;
        said("cpu");
        say(String.format("%s has had the processor working hard for the last minute or so, "
                        + "about %.0f percent of it. Say \"what is using my cpu\" for the rest.",
                culprit.name(), culprit.cpuPercent()));
    }

    private void memory() {
        long[] mem = SysInfo.memory();
        double pct = 100.0 * mem[0] / Math.max(1, mem[1]);
        fullMemoryChecks = pct >= 92 ? fullMemoryChecks + 1 : 0;
        if (fullMemoryChecks < 2 || !due("memory")) return;

        said("memory");
        SysInfo.Proc hog = SysInfo.hungriestProcess();
        say(String.format("Memory is %.0f percent full, %s.%s", pct, Config.get().honorific(),
                hog == null ? "" : " " + hog.name() + " is holding the most, " + SysInfo.humanBytes(hog.rssBytes()) + "."));
    }

    private void heat() {
        double c = SysInfo.temperatureC();
        if (c < hotCelsius() || !due("heat")) return;
        said("heat");
        say(String.format("Running hot, %s: the processor is at %.0f degrees. Check nothing is blocking the vents.",
                Config.get().honorific(), c));
    }

    private void disk() {
        long[] disk = SysInfo.disk();
        if (disk[1] <= 0 || 100.0 * disk[0] / disk[1] < 95 || !due("disk")) return;
        said("disk");
        say("Your main drive is " + Math.round(100.0 * disk[0] / disk[1]) + " percent full, only "
                + SysInfo.humanBytes(disk[1] - disk[0]) + " left. Say \"how much disk space\" to see where it went.");
    }

    private void network() {
        boolean now = SysInfo.hasDefaultRoute();
        boolean steady = lastSeen != null && lastSeen == now;
        lastSeen = now;
        if (!steady) return;

        if (online == null) {
            online = now;   // how things were at start-up is not news
            return;
        }
        if (online == now) return;
        online = now;
        say(now ? "We are back online." : "We have lost the network, " + Config.get().honorific() + ".");
    }

    private boolean due(String kind) {
        Long last = lastSaid.get(kind);
        return last == null || System.currentTimeMillis() - last >= COOLDOWN_MS.get(kind);
    }

    private void said(String kind) {
        lastSaid.put(kind, System.currentTimeMillis());
    }

    private static int hotCelsius() {
        return Config.get().intv("sentinel.hotCelsius", 90);
    }

    private void say(String text) {
        viewport.announce(text);
    }
}
