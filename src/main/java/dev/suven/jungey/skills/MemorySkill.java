package dev.suven.jungey.skills;

import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Memory;
import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Remember that…", "what do you remember", "forget…" - and answering from memory.
 *
 * <p>A question about your own things - "where did I park?" - is answered here when
 * something remembered fits it, before it can be mistaken for a file search or sent to the
 * model. Everything else remembered still reaches the model, with every question.
 */
public class MemorySkill implements Skill {

    private static final Pattern REMEMBER = Pattern.compile(
            "^(?:please )?(?:remember|don'?t forget|do not forget|keep in mind|note that|make a note that)"
                    + "(?: that)?[,:]?\\s+(?!to\\b)(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST = Pattern.compile(
            "^(?:what do you (?:remember|know)(?: about me)?|what have i told you|what did i tell you"
                    + "|show (?:me )?(?:your|my) memor(?:y|ies)|memories)$");
    private static final Pattern ABOUT = Pattern.compile(
            "^what do you (?:remember|know) about (.+)$");
    private static final Pattern FORGET_ALL = Pattern.compile(
            "^forget everything(?: (?:you know )?about me| i told you)?$");
    private static final Pattern FORGET = Pattern.compile(
            "^forget (?:that |about |what i said about )?(.+)$");
    /** A question about the user's own things, which memory might answer. */
    private static final Pattern PERSONAL_QUESTION = Pattern.compile(
            "^(?:where|what|when|who|which|how)\\b.*\\b(?:my|i|me)\\b.*");

    /** How much of a question a remembered fact must cover to be its answer. */
    private static final double ANSWERS = 0.6;

    private final Memory memory;

    public MemorySkill(Memory memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "Remembers things you tell it, for good, and answers from them.";
    }

    @Override
    public String[] examples() {
        return new String[]{"remember that my car is on level 3", "where is my car",
                "what do you remember", "forget about the car"};
    }

    @Override
    public int priority() {
        return 9;
    }

    @Override
    public boolean matches(String input) {
        String s = tidy(input);
        if (REMEMBER.matcher(s).matches() || LIST.matcher(s).matches() || ABOUT.matcher(s).matches()
                || FORGET_ALL.matcher(s).matches()) {
            return true;
        }
        // "forget it" closes a conversation; only forget something that is actually there.
        Matcher f = FORGET.matcher(s);
        if (f.matches() && !f.group(1).matches("it|that|this")) return true;
        return PERSONAL_QUESTION.matcher(s).matches() && !memory.recall(s, ANSWERS).isEmpty();
    }

    @Override
    public SkillResult run(String input) throws Exception {
        String s = tidy(input);
        String sir = Config.get().honorific();

        Matcher m = REMEMBER.matcher(input.trim().replaceAll("[.!]+$", ""));
        if (m.matches()) {
            String fact = m.group(1).trim();
            memory.add(fact);
            return SkillResult.of("Noted, " + sir + ". I'll remember that " + Personality.reflect(fact) + ".");
        }

        if (LIST.matcher(s).matches()) {
            List<String> facts = memory.facts();
            if (facts.isEmpty()) {
                return SkillResult.of("Nothing yet, " + sir + ". Say \"remember that…\" and I will keep it.");
            }
            String spoken = facts.size() <= 3
                    ? "You told me " + String.join("; and ", facts.stream().map(Personality::reflect).toList()) + "."
                    : "I remember " + facts.size() + " things you told me. They're on screen.";
            return SkillResult.of(spoken, bullets(facts));
        }

        m = ABOUT.matcher(s);
        if (m.matches()) return answer(m.group(1), sir, 0);

        if (FORGET_ALL.matcher(s).matches()) {
            int n = memory.forgetAll();
            return SkillResult.of(n == 0 ? "There was nothing to forget."
                    : "Done. " + n + " things forgotten. The old list is kept as memory.md.bak, just in case.");
        }

        m = FORGET.matcher(s);
        if (m.matches()) {
            int n = memory.forget(m.group(1));
            return n == 0 ? SkillResult.of("I had nothing about that.")
                    : SkillResult.of("Forgotten" + (n > 1 ? ", all " + n + " of them." : "."));
        }

        return answer(s, sir, ANSWERS);
    }

    private SkillResult answer(String question, String sir, double coverage) {
        List<String> found = memory.recall(question, coverage);
        if (found.isEmpty()) return SkillResult.of("You haven't told me anything about that, " + sir + ".");
        String best = Personality.reflect(found.get(0));
        return found.size() == 1
                ? SkillResult.of("You told me " + best + ".")
                : SkillResult.of("You told me " + best + ". There's a little more on screen.", bullets(found));
    }

    private static String bullets(List<String> facts) {
        StringBuilder sb = new StringBuilder();
        for (String f : facts) sb.append("• ").append(f).append('\n');
        return sb.toString().trim();
    }

    private static String tidy(String input) {
        return input.toLowerCase().replace('’', '\'').replaceAll("[^a-z0-9' ]", " ").trim().replaceAll("\\s+", " ");
    }
}
