package dev.suven.jungey.core;

/**
 * What a skill hands back: a line to show, an optional different line to speak,
 * and an optional block of detail rendered in monospace beneath it.
 *
 * @param speech   what Jungey says out loud - kept short, since spoken text
 *                 should not read like a wall of terminal output
 * @param detail   optional monospace block (tables, readouts); may be null
 * @param ok       false marks a failure, which the HUD tints red
 * @param streamed true if the reply was already shown and spoken as it arrived, so the
 *                 HUD only records it; speech then holds the full text
 */
public record SkillResult(String speech, String detail, boolean ok, boolean streamed) {

    public SkillResult(String speech, String detail, boolean ok) {
        this(speech, detail, ok, false);
    }

    public static SkillResult streamed(String fullText) {
        return new SkillResult(fullText, null, true, true);
    }

    public static SkillResult of(String speech) {
        return new SkillResult(speech, null, true);
    }

    public static SkillResult of(String speech, String detail) {
        return new SkillResult(speech, detail, true);
    }

    public static SkillResult error(String speech) {
        return new SkillResult(speech, null, false);
    }

    public boolean hasDetail() {
        return detail != null && !detail.isBlank();
    }
}
