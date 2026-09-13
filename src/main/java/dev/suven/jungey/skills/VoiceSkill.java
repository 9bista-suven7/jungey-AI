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
        return "Turns speech output on or off.";
    }

    @Override
    public String[] examples() {
        return new String[]{"voice off", "voice on", "voice"};
    }

    @Override
    public int priority() {
        return 15;
    }

    @Override
    public boolean matches(String input) {
        String s = input.trim();
        return s.equals("voice") || ON.contains(s) || OFF.contains(s);
    }

    @Override
    public SkillResult run(String input) {
        String s = input.trim().toLowerCase();

        if (!speaker.available()) {
            return SkillResult.error(
                    "No speech engine is installed, so there is nothing to switch. "
                            + "Install one with: sudo apt install espeak-ng");
        }

        if (s.equals("voice")) {
            return SkillResult.of(speaker.muted()
                    ? "Voice is off."
                    : "Voice is on, using " + speaker.engineName() + ".");
        }

        boolean on = ON.contains(s);
        speaker.setMuted(!on);
        return SkillResult.of(on ? "Voice on." : "Voice off.");
    }
}
