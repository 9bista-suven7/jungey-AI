package dev.suven.jungey.skills;

import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Sentinel;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.util.List;
import java.util.regex.Pattern;

/** Turns the {@link Sentinel}'s unprompted warnings on and off, and asks it how things are. */
public class AlertsSkill implements Skill {

    private static final Pattern OFF = Pattern.compile(
            "^(?:(?:system )?alerts off|turn off (?:the )?alerts|stop (?:the )?alerts|no (?:more )?alerts"
                    + "|don'?t interrupt me|stop interrupting(?: me)?|quiet mode)$");
    private static final Pattern ON = Pattern.compile(
            "^(?:(?:system )?alerts on|turn on (?:the )?alerts|start (?:the )?alerts|resume alerts)$");
    private static final Pattern ASK = Pattern.compile(
            "^(?:anything i should know(?: about)?|any (?:alerts|problems|issues|warnings)"
                    + "|is (?:everything|all) (?:ok|okay|alright|fine|good)|everything (?:ok|okay|alright|fine|good)"
                    + "|all good|how'?s (?:the|my) (?:machine|computer|laptop) doing)$");

    private final Sentinel sentinel;

    public AlertsSkill(Sentinel sentinel) {
        this.sentinel = sentinel;
    }

    @Override
    public String name() {
        return "alerts";
    }

    @Override
    public String description() {
        return "Speaks up about battery, heat, memory, disk and network without being asked.";
    }

    @Override
    public String[] examples() {
        return new String[]{"anything I should know", "alerts off", "alerts on"};
    }

    @Override
    public int priority() {
        return 14;   // ahead of system, which would take anything that says "system"
    }

    @Override
    public boolean matches(String input) {
        String s = tidy(input);
        return OFF.matcher(s).matches() || ON.matcher(s).matches() || ASK.matcher(s).matches();
    }

    @Override
    public SkillResult run(String input) {
        String s = tidy(input);
        String sir = Config.get().honorific();

        if (OFF.matcher(s).matches()) {
            sentinel.setEnabled(false);
            return SkillResult.of("Understood. I'll keep my observations to myself until you say \"alerts on\".");
        }
        if (ON.matcher(s).matches()) {
            sentinel.setEnabled(true);
            return SkillResult.of("Alerts on. I'll speak up if anything needs you, " + sir + ".");
        }

        List<String> worries = sentinel.concerns();
        if (worries.isEmpty()) return SkillResult.of("All clear, " + sir + ". Nothing needs your attention.");
        String first = worries.get(0);
        return SkillResult.of(Character.toUpperCase(first.charAt(0)) + first.substring(1)
                + (worries.size() > 1 ? ", and " + String.join(", and ", worries.subList(1, worries.size())) : "")
                + ".");
    }

    private static String tidy(String input) {
        return input.toLowerCase().replace('’', '\'').replaceAll("[^a-z0-9' ]", " ").trim().replaceAll("\\s+", " ");
    }
}
