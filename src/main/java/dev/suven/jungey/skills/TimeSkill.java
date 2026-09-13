package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Time, date and day of the week. Entirely local, answers in microseconds. */
public class TimeSkill implements Skill {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String description() {
        return "The current time, date and day.";
    }

    @Override
    public String[] examples() {
        return new String[]{"what time is it", "what's the date", "what day is it"};
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean matches(String input) {
        return input.matches(".*\\b(time|date|day|today)\\b.*")
                && !input.contains("timer")
                && !input.contains("weather");
    }

    @Override
    public SkillResult run(String input) {
        String lower = input.toLowerCase();
        ZoneId zone = ZoneId.systemDefault();

        if (lower.contains("time")) {
            String now = LocalTime.now(zone).format(CLOCK);
            return SkillResult.of("It is " + now + ".");
        }

        LocalDate today = LocalDate.now(zone);
        if (lower.contains("day") && !lower.contains("date")) {
            return SkillResult.of("It is " + today.getDayOfWeek()
                    .getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + ".");
        }

        return SkillResult.of("Today is " + today.format(DATE) + ".");
    }
}
