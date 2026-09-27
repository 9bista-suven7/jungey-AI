package dev.suven.jungey.skills;

import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Sentinel;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.SysInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The morning briefing: time, weather, the machine, the todo list and what is coming up,
 * in one breath.
 *
 * <p>Given unasked on the first start of each day, and on request any time after. It only
 * says what is worth hearing - a full battery or an empty reminder list goes unmentioned.
 */
public class BriefingSkill implements Skill {

    private static final Pattern ASK = Pattern.compile(
            "^(?:(?:good )?(?:morning|afternoon|evening)(?: (?:jungey|jarvis))?"
                    + "|(?:give me )?(?:the |my |a )?(?:daily |morning |evening |quick )?briefing(?: please)?"
                    + "|brief me on (?:the day|today|everything)|catch me up|what did i miss"
                    + "|what'?s (?:on )?(?:my|the) (?:day|agenda|schedule)(?: look(?:ing)? like)?(?: today)?"
                    + "|what'?s the plan(?: for)?(?: today)?|how'?s my day(?: looking)?|what'?s up today)$");

    private static final Pattern GREETING = Pattern.compile("^(?:good )?(?:morning|afternoon|evening).*");

    private static final Path TODO = Path.of(System.getProperty("user.home"), "Documents", "Jungey", "todo.md");
    private static final Path BRIEFED = Path.of(System.getProperty("user.home"), ".local", "share", "jungey", "briefed");

    private static final DateTimeFormatter SPOKEN_TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private final WeatherSkill weather;
    private final TimerSkill timers;
    private final Sentinel sentinel;

    public BriefingSkill(WeatherSkill weather, TimerSkill timers, Sentinel sentinel) {
        this.weather = weather;
        this.timers = timers;
        this.sentinel = sentinel;
    }

    @Override
    public String name() {
        return "briefing";
    }

    @Override
    public String description() {
        return "A briefing on the day: weather, the machine, your todo list and reminders.";
    }

    @Override
    public String[] examples() {
        return new String[]{"good morning", "briefing", "what's my day look like", "catch me up"};
    }

    @Override
    public int priority() {
        return 6;   // before time, which would otherwise take anything mentioning "day"
    }

    @Override
    public boolean matches(String input) {
        return ASK.matcher(tidy(input)).matches();
    }

    @Override
    public SkillResult run(String input) {
        return brief(GREETING.matcher(tidy(input)).matches());
    }

    /** True the first time this is asked on a given day - whether the boot briefing is due. */
    public static boolean firstStartToday() {
        String today = LocalDate.now().toString();
        try {
            if (Files.exists(BRIEFED) && Files.readString(BRIEFED).trim().equals(today)) return false;
            Files.createDirectories(BRIEFED.getParent());
            Files.writeString(BRIEFED, today);
        } catch (IOException e) {
            // Unwritable: better a briefing every start than none.
        }
        return true;
    }

    /** @param greet open with "Good morning" - false when a greeting has just been said */
    public SkillResult brief(boolean greet) {
        Config cfg = Config.get();
        String sir = cfg.honorific();
        LocalDateTime now = LocalDateTime.now();

        // The weather is the only part that leaves the machine; start it first, wait least.
        CompletableFuture<SkillResult> outside = CompletableFuture.supplyAsync(() -> {
            try {
                return weather.run("weather");
            } catch (Exception e) {
                return null;
            }
        });

        List<String> said = new ArrayList<>();
        StringBuilder detail = new StringBuilder();

        if (greet) said.add(partOfDay(now.toLocalTime()) + ", " + cfg.userName() + ".");
        said.add("It's " + now.format(SPOKEN_TIME) + " on " + now.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.FULL, Locale.ENGLISH) + ", the " + ordinal(now.getDayOfMonth()) + ".");
        detail.append(String.format("TIME       %s%n",
                now.format(DateTimeFormatter.ofPattern("HH:mm  EEEE d MMMM", Locale.ENGLISH))));

        SkillResult w = null;
        try {
            w = outside.get(6, TimeUnit.SECONDS);
        } catch (Exception e) {
            outside.cancel(true);
        }
        if (w != null && w.ok()) {
            said.add(w.speech());
            detail.append("WEATHER    ").append(w.speech()).append('\n');
        }

        List<String> worries = sentinel.concerns();
        int battery = SysInfo.batteryPercent();
        if (battery >= 0) {
            detail.append("BATTERY    ").append(battery).append('%')
                    .append(SysInfo.onAcPower() ? " (charging)" : "").append('\n');
        }
        if (!worries.isEmpty()) {
            said.add("Worth knowing: " + String.join(", and ", worries) + ".");
            detail.append("WATCH      ").append(String.join("; ", worries)).append('\n');
        } else if (battery >= 0 && battery < 100 && !SysInfo.onAcPower()) {
            said.add("Battery at " + battery + " percent.");
        }

        List<String> todo = openTodos();
        if (!todo.isEmpty()) {
            said.add(todo.size() == 1
                    ? "One thing on your todo list: " + todo.get(0) + "."
                    : todo.size() + " things on your todo list, starting with " + todo.get(0) + ".");
            detail.append("TODO       ").append(String.join("\n           ", todo.stream().limit(5).toList())).append('\n');
        }

        List<String> upcoming = timers.pendingLines();
        if (!upcoming.isEmpty()) {
            said.add(upcoming.size() == 1
                    ? "One reminder pending: " + upcoming.get(0) + "."
                    : upcoming.size() + " reminders pending, the next is " + upcoming.get(0) + ".");
            detail.append("REMINDERS  ").append(String.join("\n           ", upcoming)).append('\n');
        }

        if (worries.isEmpty() && todo.isEmpty() && upcoming.isEmpty()) {
            said.add("Nothing needs you yet, " + sir + ".");
        }
        return SkillResult.of(String.join(" ", said), detail.toString().trim());
    }

    /** Unticked items from the todo file NoteSkill keeps, without their dates. */
    private static List<String> openTodos() {
        try {
            if (!Files.exists(TODO)) return List.of();
            return Files.readAllLines(TODO).stream()
                    .filter(l -> l.startsWith("- [ ]"))
                    .map(l -> l.substring(5).replaceAll("\\s+_\\(.*?\\)_\\s*$", "").trim())
                    .filter(l -> !l.isEmpty())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String partOfDay(LocalTime t) {
        int h = t.getHour();
        if (h < 5) return "You're up late";
        if (h < 12) return "Good morning";
        if (h < 17) return "Good afternoon";
        return "Good evening";
    }

    private static String ordinal(int day) {
        if (day >= 11 && day <= 13) return day + "th";
        return day + switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    private static String tidy(String input) {
        return input.toLowerCase().replace('’', '\'').replaceAll("[^a-z0-9' ]", " ").trim().replaceAll("\\s+", " ");
    }
}
