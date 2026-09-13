package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.Viewport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Captures the screen.
 *
 * <p>Prefers the desktop's own screenshot tool, which understands the compositor and
 * multiple monitors, and falls back to ffmpeg's X11 grab where there is none.
 */
public class ScreenshotSkill implements Skill {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Viewport viewport;

    public ScreenshotSkill(Viewport viewport) {
        this.viewport = viewport;
    }

    @Override
    public String name() {
        return "screenshot";
    }

    @Override
    public String description() {
        return "Captures the screen to a file.";
    }

    @Override
    public String[] examples() {
        return new String[]{"screenshot", "take a screenshot"};
    }

    @Override
    public int priority() {
        return 26;
    }

    @Override
    public boolean matches(String input) {
        String s = input.trim();
        return s.equals("screenshot") || s.equals("screen shot")
                || s.matches(".*\\b(take|grab|capture)\\b.*\\b(screenshot|screen shot|screen)\\b.*");
    }

    @Override
    public SkillResult run(String input) throws Exception {
        Path out = CameraSkill.destination("Pictures", "screen-" + LocalDateTime.now().format(STAMP) + ".png");
        try {
            captureScreen(out);
        } catch (IllegalStateException e) {
            return SkillResult.error(e.getMessage());
        }
        viewport.showImage(out, out.getFileName() + "  (" + Files.size(out) / 1024 + " KB)");
        return SkillResult.of("Captured.");
    }

    /**
     * Grab the whole screen into {@code out}.
     *
     * @throws IllegalStateException if nothing on this machine can take a screenshot
     */
    static void captureScreen(Path out) throws IOException, InterruptedException {
        capture(out, false);
    }

    /**
     * Let the user drag a box, and capture only that. Blocks until they have chosen, so
     * the timeout is generous - a person picking a region is slower than a screen grab.
     */
    static void captureRegion(Path out) throws IOException, InterruptedException {
        capture(out, true);
    }

    private static void capture(Path out, boolean region) throws IOException, InterruptedException {
        List<String> cmd;
        if (CameraSkill.onPath("xfce4-screenshooter")) {
            cmd = List.of("xfce4-screenshooter", region ? "-r" : "-f", "-s", out.toString());
        } else if (region && CameraSkill.onPath("gnome-screenshot")) {
            cmd = List.of("gnome-screenshot", "-a", "-f", out.toString());
        } else if (CameraSkill.onPath("gnome-screenshot")) {
            cmd = List.of("gnome-screenshot", "-f", out.toString());
        } else if (CameraSkill.onPath("ffmpeg")) {
            String display = System.getenv("DISPLAY");
            if (display == null || display.isBlank()) {
                throw new IllegalStateException("No display to capture.");
            }
            cmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-f", "x11grab", "-i", display, "-frames:v", "1", "-y", out.toString());
        } else {
            throw new IllegalStateException(
                    "No screenshot tool installed. Try: sudo apt install xfce4-screenshooter");
        }

        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        // Picking a region is a human action, so it gets far longer than a plain grab.
        if (!p.waitFor(region ? 120 : 20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("The screenshot timed out.");
        }
        if (!Files.exists(out) || Files.size(out) == 0) {
            throw new IllegalStateException(region
                    ? "No region was selected." : "The screenshot failed.");
        }
    }
}
