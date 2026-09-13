package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.Viewport;

/**
 * Works on whatever you have just copied.
 *
 * <p>"Explain this" is the most natural thing to say about a stack trace or an error
 * message, and the clipboard is almost always where that text already is - which saves
 * retyping it into the prompt.
 */
public class ClipboardSkill implements Skill {

    /** Long clipboards are truncated: the model does not need the whole file to explain it. */
    private static final int MAX_CHARS = 4000;

    private final Viewport viewport;
    private final LlmSkill llm;

    public ClipboardSkill(Viewport viewport, LlmSkill llm) {
        this.viewport = viewport;
        this.llm = llm;
    }

    @Override
    public String name() {
        return "clipboard";
    }

    @Override
    public String description() {
        return "Reads or explains whatever you have copied.";
    }

    @Override
    public String[] examples() {
        return new String[]{"what is in my clipboard", "explain this", "summarise this"};
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public boolean matches(String input) {
        String s = input.trim();
        return s.matches(".*\\bclipboard\\b.*")
                || s.equals("explain this") || s.equals("summarise this") || s.equals("summarize this")
                || s.equals("what does this mean");
    }

    @Override
    public SkillResult run(String input) throws Exception {
        String s = input.trim().toLowerCase();
        String text = viewport.clipboardText();

        if (text == null || text.isBlank()) {
            return SkillResult.error("The clipboard is empty.");
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
        }

        // Just reading it back.
        if (s.contains("clipboard") && !s.contains("explain") && !s.contains("summar")) {
            String preview = text.length() > 200 ? text.substring(0, 200) + "…" : text;
            return SkillResult.of("Your clipboard holds " + text.length() + " characters.",
                    WikipediaSkill.wrap(preview, 78));
        }

        String task = s.contains("summar") ? "Summarise this" : "Explain this";
        return llm.run(task + ", briefly:\n\n" + text);
    }
}
