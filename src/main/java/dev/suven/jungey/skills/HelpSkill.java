package dev.suven.jungey.skills;

import dev.suven.jungey.core.Brain;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

/** Lists every registered skill and its example phrasings. */
public class HelpSkill implements Skill {

    private final Brain brain;

    public HelpSkill(Brain brain) {
        this.brain = brain;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "List what I can do.";
    }

    @Override
    public String[] examples() {
        return new String[]{"help", "what can you do"};
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public boolean matches(String input) {
        return input.equals("help") || input.equals("?")
                || input.matches(".*\\bwhat can you do\\b.*")
                || input.matches(".*\\byour skills\\b.*")
                || input.equals("commands");
    }

    @Override
    public SkillResult run(String input) {
        StringBuilder sb = new StringBuilder();
        for (Skill s : brain.skills()) {
            if (s.name().equals("help")) continue;
            sb.append(String.format("%-10s %s%n", s.name().toUpperCase(), s.description()));
            for (String ex : s.examples()) {
                sb.append("           › ").append(ex).append('\n');
            }
            sb.append('\n');
        }
        sb.append("           › exit    (shut down)");

        return SkillResult.of("Here is what I can do.", sb.toString());
    }
}
