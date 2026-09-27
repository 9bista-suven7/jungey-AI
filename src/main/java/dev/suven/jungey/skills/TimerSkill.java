package dev.suven.jungey.skills;

import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.Viewport;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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

    /** "at 5", "at 5pm", "at 17:30", "at 5:30 p.m." */
    private static final Pattern CLOCK = Pattern.compile(
            "\\bat\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)?(?=\\s|$|[.,!?])");

    private static final DateTimeFormatter SPOKEN_CLOCK = DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH);

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
        LocalDateTime at = delay == null ? parseClock(s, LocalDateTime.now()) : null;
        if (at != null) delay = Duration.between(LocalDateTime.now(), at);
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return SkillResult.error("How long? Try \"set a timer for 10 minutes\" or \"remind me at 5pm to call home\".");
        }

        String label = parseLabel(s);
        schedule(delay, label);

        String when = at != null ? "at " + at.format(SPOKEN_CLOCK) : "in " + describe(delay);
        String spoken = label.isBlank()
                ? Personality.affirm() + (at != null ? " I will call you " + when + "." : " Timer set for " + describe(delay) + ".")
                : Personality.affirm() + " I will remind you to " + label + " " + when + ".";
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

    /** What is still to come, as "stretch at 10:30", soonest first - for briefings. */
    public List<String> pendingLines() {
        synchronized (pending) {
            pending.removeIf(p -> p.task().isDone());
            return pending.stream()
                    .sorted(java.util.Comparator.comparing(p -> p.task().getDelay(TimeUnit.MILLISECONDS)))
                    .map(p -> (p.label().isBlank() ? "a timer" : p.label()) + " at " + p.due().withNano(0).withSecond(0))
                    .toList();
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

    /**
     * The next time the clock reads what was said. Without am or pm, "at 5" is whichever
     * five o'clock comes next - nobody sets a reminder for twelve hours' time by accident.
     */
    static LocalDateTime parseClock(String s, LocalDateTime now) {
        Matcher m = CLOCK.matcher(s);
        if (!m.find()) return null;

        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        String half = m.group(3) == null ? "" : m.group(3).replace(".", "");
        if (hour > 23 || minute > 59 || (!half.isEmpty() && (hour < 1 || hour > 12))) return null;

        List<Integer> hours = new ArrayList<>();
        if (half.equals("am")) hours.add(hour % 12);
        else if (half.equals("pm")) hours.add(hour % 12 + 12);
        else if (hour <= 12) {
            hours.add(hour % 12);
            hours.add(hour % 12 + 12);
        } else hours.add(hour);

        LocalDateTime best = null;
        for (int h : hours) {
            LocalDateTime t = now.withHour(h).withMinute(minute).withSecond(0).withNano(0);
            if (!t.isAfter(now)) t = t.plusDays(1);
            if (best == null || t.isBefore(best)) best = t;
        }
        return best;
    }

    /** "remind me in 5 minutes to stretch" -> "stretch" */
    static String parseLabel(String s) {
        String label = "";
        Matcher m = Pattern.compile("\\bto\\s+(.+)$").matcher(s);
        if (m.find()) {
            label = m.group(1).trim();
        } else {
            m = Pattern.compile("\\bremind me\\s+(?:about|that)\\s+(.+)$").matcher(s);
            if (m.find()) label = m.group(1).trim();
        }
        // "remind me to stretch in 5 minutes": the when belongs to the timer, not the label.
        return label.replaceFirst("\\s+(?:in|after)\\s+\\d+\\s*(?:second|sec|minute|min|hour|hr)s?\\b.*$", "")
                .replaceFirst("\\s+at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.?|p\\.m\\.?)?\\s*$", "")
                .trim();
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
