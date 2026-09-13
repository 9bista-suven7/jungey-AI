package dev.suven.jungey.skills;

import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Volume, brightness and locking the screen.
 *
 * <p>Deliberately avoids the bare word "mute", which already means silencing Jungey's own
 * voice - speaking over the user is a different problem from the speakers being loud.
 */
public class SystemControlSkill implements Skill {

    private static final int VOLUME_STEP = 10;

    @Override
    public String name() {
        return "controls";
    }

    @Override
    public String description() {
        return "Volume, brightness and locking the screen.";
    }

    @Override
    public String[] examples() {
        return new String[]{"volume up", "set volume to 40", "brighter", "lock the screen"};
    }

    @Override
    public int priority() {
        return 22;
    }

    @Override
    public boolean matches(String input) {
        return action(input.trim()) != null;
    }

    private static String action(String s) {
        if (s.matches(".*\\block\\b.*\\b(screen|computer|it)\\b.*") || s.equals("lock")) return "lock";
        if (s.matches("(set )?volume (to )?\\d+%?")) return "volume-set";
        if (s.matches(".*\\bvolume up\\b.*") || s.equals("louder")) return "volume-up";
        if (s.matches(".*\\bvolume down\\b.*") || s.equals("quieter") || s.equals("softer")) return "volume-down";
        if (s.matches(".*\\bmute\\b.*\\b(audio|sound|system|speakers)\\b.*")) return "audio-mute";
        if (s.matches(".*\\bunmute\\b.*\\b(audio|sound|system|speakers)\\b.*")) return "audio-unmute";
        if (s.equals("brighter") || s.matches(".*\\bbrightness up\\b.*")) return "bright-up";
        if (s.equals("dimmer") || s.matches(".*\\bbrightness down\\b.*")) return "bright-down";
        if (s.equals("volume")) return "volume-read";
        return null;
    }

    @Override
    public SkillResult run(String input) throws Exception {
        String s = input.trim().toLowerCase();
        String action = action(s);
        if (action == null) return SkillResult.error("I did not catch that control.");

        return switch (action) {
            case "lock" -> lock();
            case "volume-up" -> volume("+" + VOLUME_STEP + "%", "Volume up.");
            case "volume-down" -> volume("-" + VOLUME_STEP + "%", "Volume down.");
            case "volume-set" -> setVolume(s);
            case "audio-mute" -> mute("1", "Audio muted.");
            case "audio-unmute" -> mute("0", "Audio back on.");
            case "volume-read" -> readVolume();
            case "bright-up" -> brightness("+10%", "Brighter.");
            case "bright-down" -> brightness("10%-", "Dimmer.");
            default -> SkillResult.error("I did not catch that control.");
        };
    }

    private SkillResult lock() throws IOException {
        if (CameraSkill.onPath("xflock4")) {
            new ProcessBuilder("xflock4").start();
        } else if (CameraSkill.onPath("xdg-screensaver")) {
            new ProcessBuilder("xdg-screensaver", "lock").start();
        } else {
            return SkillResult.error("I could not find a way to lock this screen.");
        }
        return SkillResult.of("Locking up.");
    }

    private SkillResult volume(String change, String reply) throws Exception {
        if (!CameraSkill.onPath("pactl")) {
            return SkillResult.error("No audio control available.");
        }
        run(List.of("pactl", "set-sink-volume", "@DEFAULT_SINK@", change));
        return SkillResult.of(reply + " " + currentVolume());
    }

    private SkillResult setVolume(String s) throws Exception {
        String digits = s.replaceAll("\\D+", "");
        if (digits.isEmpty()) return SkillResult.error("What level?");

        int level = Math.min(100, Integer.parseInt(digits));
        run(List.of("pactl", "set-sink-volume", "@DEFAULT_SINK@", level + "%"));
        return SkillResult.of(Personality.affirm() + " Volume at " + level + " percent.");
    }

    private SkillResult mute(String state, String reply) throws Exception {
        run(List.of("pactl", "set-sink-mute", "@DEFAULT_SINK@", state));
        return SkillResult.of(reply);
    }

    private SkillResult readVolume() throws Exception {
        String now = currentVolume();
        return now.isEmpty() ? SkillResult.error("I could not read the volume.") : SkillResult.of(now);
    }

    private SkillResult brightness(String change, String reply) throws Exception {
        if (!CameraSkill.onPath("brightnessctl")) {
            return SkillResult.error(
                    "Brightness needs brightnessctl, since /sys/class/backlight is root-only. "
                            + "Try: sudo apt install brightnessctl");
        }
        run(List.of("brightnessctl", "set", change));
        return SkillResult.of(reply);
    }

    /** e.g. "Volume is at 45 percent." */
    private String currentVolume() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("pactl", "get-sink-volume", "@DEFAULT_SINK@")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(5, TimeUnit.SECONDS);

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)%").matcher(out);
        return m.find() ? "Volume is at " + m.group(1) + " percent." : "";
    }

    private static void run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        p.waitFor(10, TimeUnit.SECONDS);
    }
}
