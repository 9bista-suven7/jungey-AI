package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.voice.Speaker;

import java.util.Set;

/**
 * Turns speech output on and off without a restart.
 *
 * <p>The setting is written back to the config file, so muting here survives the
 * next launch - the spoken channel is the one thing a user most often wants off
 * in a hurry, and editing a properties file is no way to do it.
 */
public class VoiceSkill implements Skill {

    private static final Set<String> ON = Set.of("voice on", "unmute", "speak up", "sound on");
    private static final Set<String> OFF = Set.of("voice off", "mute", "be quiet", "silence", "sound off");

    /** Cuts off the sentence being spoken without turning speech off for good. */
    private static final Set<String> HUSH = Set.of("stop", "stop talking", "shush", "enough", "quiet");

    private static final Set<String> TEST = Set.of("voice test", "test voice", "say something");

    /** Numbers, a unit and a percentage: the things a synthetic voice usually fumbles. */
    private static final String SAMPLE =
            "Right. It is 23 degrees outside, the disk is 64% full, and you have 3 timers running. "
                    + "How does that sound?";

    private final Speaker speaker;

    public VoiceSkill(Speaker speaker) {
        this.speaker = speaker;
    }

    @Override
    public String name() {
        return "voice";
    }

    @Override
    public String description() {
        return "Turns speech output on or off, and tries it out.";
    }

    @Override
    public String[] examples() {
        return new String[]{"voice off", "voice on", "voice", "voice test"};
    }

    @Override
    public int priority() {
        return 15;
    }

    @Override
    public boolean matches(String input) {
        String s = input.trim();
        return s.equals("voice") || ON.contains(s) || OFF.contains(s) || HUSH.contains(s)
                || TEST.contains(s);
    }

    @Override
    public SkillResult run(String input) {
        String s = input.trim().toLowerCase();

        // Interrupting is not the same as muting - it stays on for the next answer.
        if (HUSH.contains(s)) {
            speaker.stop();
            return SkillResult.of("");
        }

        if (!speaker.available()) {
            return SkillResult.error(
                    "No speech engine is installed, so there is nothing to switch. "
                            + "Run scripts/setup-voice.sh for a natural one, or "
                            + "sudo apt install espeak-ng for a robotic one.");
        }

        if (TEST.contains(s)) {
            // Spoken as well as printed - the whole point is to hear how it sounds.
            return SkillResult.of(SAMPLE);
        }

        if (s.equals("voice")) {
            return SkillResult.of(speaker.muted()
                    ? "Voice is off. The engine is " + speaker.engineDetail() + "."
                    : "Voice is on, using " + speaker.engineDetail() + ".");
        }

        boolean on = ON.contains(s);
        speaker.setMuted(!on);
        return SkillResult.of(on ? "Voice on." : "Voice off.");
    }
}
