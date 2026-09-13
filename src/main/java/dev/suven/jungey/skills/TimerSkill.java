package dev.suven.jungey.skills;

import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.Viewport;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Timers and reminders - the only part of Jungey that speaks without being spoken to.
 *
 * <p>Timers live in memory and die with the process. Anything that needs to survive a
 * restart belongs in a file, and that is a different feature from "ten minutes from now".
 */
public class TimerSkill implements Skill {

    private static final Pattern DURATION = Pattern.compile(
            "(\\d+)\\s*(second|sec|minute|min|hour|hr)s?");

    private final Viewport viewport;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jungey-timers");
        t.setDaemon(true);
        return t;
    });

    private record Pending(String label, LocalTime due, ScheduledFuture<?> task) { }

    private final List<Pending> pending = new ArrayList<>();

    public TimerSkill(Viewport viewport) {
        this.viewport = viewport;
    }

    @Override
    public String name() {
        return "timers";
    }

    @Override
    public String description() {
        return "Sets timers and reminders.";
    }

    @Override
    public String[] examples() {
        return new String[]{"set a timer for 10 minutes", "remind me in 5 minutes to stretch", "what timers"};
    }

    @Override
    public int priority() {
        return 17;
    }

    @Override
    public boolean matches(String input) {
        String s = input.trim();
        return s.matches(".*\\b(timer|remind me)\\b.*") || s.equals("timers")
                || s.matches(".*\\bcancel\\b.*\\b(timer|reminder)s?\\b.*");
    }

    @Override
    public SkillResult run(String input) {
        String s = input.trim().toLowerCase();

        if (s.equals("timers") || s.matches(".*\\bwhat\\b.*\\btimers?\\b.*")) return listPending();
        if (s.matches(".*\\bcancel\\b.*")) return cancelAll();

        Duration delay = parseDuration(s);
        if (delay == null || delay.isZero()) {
            return SkillResult.error("How long? Try \"set a timer for 10 minutes\".");
        }

        String label = parseLabel(s);
        schedule(delay, label);

        String spoken = label.isBlank()
                ? Personality.affirm() + " Timer set for " + describe(delay) + "."
                : Personality.affirm() + " I will remind you to " + label + " in " + describe(delay) + ".";
        return SkillResult.of(spoken);
    }

    private void schedule(Duration delay, String label) {
        LocalTime due = LocalTime.now().plus(delay);

        ScheduledFuture<?> task = clock.schedule(() -> {
            synchronized (pending) {
                pending.removeIf(p -> p.due().equals(due) && p.label().equals(label));
            }
            viewport.announce(label.isBlank()
                    ? "Your timer has finished."
                    : "Reminder: " + label + ".");
        }, delay.toMillis(), TimeUnit.MILLISECONDS);

        synchronized (pending) {
            pending.add(new Pending(label, due, task));
        }
    }

    private SkillResult listPending() {
        synchronized (pending) {
            pending.removeIf(p -> p.task().isDone());
            if (pending.isEmpty()) return SkillResult.of("Nothing pending.");

            StringBuilder sb = new StringBuilder();
            for (Pending p : pending) {
                sb.append("• ").append(p.label().isBlank() ? "timer" : p.label())
                        .append("  at ").append(p.due().withNano(0)).append('\n');
            }
            return SkillResult.of(pending.size() + " pending.", sb.toString().trim());
        }
    }

    private SkillResult cancelAll() {
        synchronized (pending) {
            int n = 0;
            for (Pending p : pending) {
                if (p.task().cancel(false)) n++;
            }
            pending.clear();
            return n == 0 ? SkillResult.of("Nothing to cancel.")
                    : SkillResult.of("Cancelled " + n + ".");
        }
    }

    /** Sums every duration mentioned, so "1 hour 30 minutes" works. */
    static Duration parseDuration(String s) {
        Matcher m = DURATION.matcher(s);
        Duration total = Duration.ZERO;
        boolean found = false;

        while (m.find()) {
            long value = Long.parseLong(m.group(1));
            total = total.plus(switch (m.group(2)) {
                case "hour", "hr" -> Duration.ofHours(value);
                case "minute", "min" -> Duration.ofMinutes(value);
                default -> Duration.ofSeconds(value);
            });
            found = true;
        }
        return found ? total : null;
    }

    /** "remind me in 5 minutes to stretch" -> "stretch" */
    private static String parseLabel(String s) {
        Matcher m = Pattern.compile("\\bto\\s+(.+)$").matcher(s);
        if (m.find()) return m.group(1).trim();

        m = Pattern.compile("\\bremind me\\s+(?:about|that)\\s+(.+?)(?:\\s+in\\b.*)?$").matcher(s);
        if (m.find()) return m.group(1).trim();

        return "";
    }

    private static String describe(Duration d) {
        long hours = d.toHours(), minutes = d.toMinutesPart(), seconds = d.toSecondsPart();
        List<String> parts = new ArrayList<>();
        if (hours > 0) parts.add(hours + (hours == 1 ? " hour" : " hours"));
        if (minutes > 0) parts.add(minutes + (minutes == 1 ? " minute" : " minutes"));
        if (seconds > 0) parts.add(seconds + (seconds == 1 ? " second" : " seconds"));
        return String.join(" and ", parts);
    }
}
