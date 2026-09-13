package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Reads the text on screen, exactly.
 *
 * <p>Sorts ahead of the vision model on purpose. A vision model describes a picture and
 * will happily invent a plausible version number or misread a stack trace; OCR returns
 * the characters that are actually there. "Read my screen" wants the latter.
 */
public class OcrSkill implements Skill {

    @Override
    public String name() {
        return "ocr";
    }

    @Override
    public String description() {
        return "Reads the text on your screen exactly.";
    }

    @Override
    public String[] examples() {
        return new String[]{"read my screen", "extract the text"};
    }

    @Override
    public int priority() {
        return 24;
    }

    @Override
    public boolean matches(String input) {
        String s = input.trim();
        return s.matches(".*\\bread\\b.*\\b(my|the)\\s+screen\\b.*")
                || s.equals("ocr") || s.equals("read that") || s.equals("read this")
                || s.matches(".*\\b(extract|copy|grab)\\b.*\\btext\\b.*");
    }

    /** "Read that" means a region the user picks; "read my screen" means all of it. */
    private static boolean wantsRegion(String s) {
        return s.equals("read that") || s.equals("read this");
    }

    @Override
    public SkillResult run(String input) throws Exception {
        if (!CameraSkill.onPath("tesseract")) {
            return SkillResult.error("Reading text needs tesseract. Try: sudo apt install tesseract-ocr");
        }

        boolean region = wantsRegion(input.trim().toLowerCase());
        Path shot = Files.createTempFile("jungey-ocr-", ".png");
        try {
            try {
                if (region) {
                    ScreenshotSkill.captureRegion(shot);
                } else {
                    ScreenshotSkill.captureScreen(shot);
                }
            } catch (IllegalStateException e) {
                return SkillResult.error(e.getMessage());
            }

            Process p = new ProcessBuilder("tesseract", shot.toString(), "stdout")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();

            String text = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return SkillResult.error("Reading the screen took too long.");
            }

            text = text.replaceAll("\n{3,}", "\n\n").trim();
            if (text.isBlank()) {
                return SkillResult.error("I could not find any text on screen.");
            }

            long words = text.split("\\s+").length;
            return SkillResult.of("I read " + words + " words off the screen.", text);
        } finally {
            Files.deleteIfExists(shot);
        }
    }
}
