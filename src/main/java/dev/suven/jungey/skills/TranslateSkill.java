package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.Viewport;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translation, through the local model.
 *
 * <p>"Translate this" with no text means the clipboard, which is where the sentence you
 * want translated almost always already is.
 */
public class TranslateSkill implements Skill {

    private static final Pattern REQUEST = Pattern.compile(
            "^translate\\s+(.*?)\\s*(?:in)?to\\s+([a-z ]+)$");

    private final Viewport viewport;
    private final LlmSkill llm;

    public TranslateSkill(Viewport viewport, LlmSkill llm) {
        this.viewport = viewport;
        this.llm = llm;
    }

    @Override
    public String name() {
        return "translate";
    }

    @Override
    public String description() {
        return "Translates text into another language.";
    }

    @Override
    public String[] examples() {
        return new String[]{"translate this to Nepali", "translate good morning to french"};
    }

    @Override
    public int priority() {
        return 45;
    }

    @Override
    public boolean matches(String input) {
        return REQUEST.matcher(input.trim().toLowerCase()).matches();
    }

    @Override
    public SkillResult run(String input) throws Exception {
        Matcher m = REQUEST.matcher(input.trim().toLowerCase());
        if (!m.matches()) return SkillResult.error("Translate what, into what?");

        String subject = m.group(1).trim();
        String language = m.group(2).trim();

        String text;
        if (subject.isBlank() || subject.equals("this") || subject.equals("that")) {
            text = viewport.clipboardText();
            if (text == null || text.isBlank()) {
                return SkillResult.error("Nothing on the clipboard to translate.");
            }
        } else {
            text = subject;
        }

        return llm.run("Translate the following into " + language
                + ". Reply with the translation and nothing else.\n\n" + text);
    }
}
