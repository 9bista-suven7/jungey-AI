package dev.suven.jungey.skills;

import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.Viewport;
import dev.suven.jungey.watch.SceneWatcher;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The webcam: a live preview, stills, and video.
 *
 * <p>Capture is delegated to ffmpeg rather than pulled into the JVM, which keeps this to
 * a few process calls and matches how speech output shells out to espeak.
 *
 * <p>Sorts ahead of the launcher so "open camera" is understood as the camera rather than
 * an application named camera.
 */
public class CameraSkill implements Skill {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Webcams open dark and take a moment to settle their exposure, so the first frames
     * are thrown away - grabbing frame zero reliably produces a near-black picture.
     */
    private static final int WARMUP_FRAMES = 30;

    /** A recording nobody stops would run until the disk filled. */
    private static final int MAX_RECORDING_SECONDS = 300;

    private static final String WATCHING = "The camera is watching the scene. Say \"stop watching\" first.";

    private final Viewport viewport;
    private final SceneWatcher watcher;

    private Process recording;
    private Path recordingFile;

    public CameraSkill(Viewport viewport, SceneWatcher watcher) {
        this.viewport = viewport;
        this.watcher = watcher;
    }

    @Override
    public String name() {
        return "camera";
    }

    @Override
    public String description() {
        return "Opens the camera, takes photos and records video.";
    }

    @Override
    public String[] examples() {
        return new String[]{"open camera", "take a photo", "record video", "stop recording"};
    }

    @Override
    public int priority() {
        return 25;
    }

    @Override
    public boolean matches(String input) {
        String s = input.trim();
        return action(s) != null;
    }

    private static String action(String s) {
        if (s.matches(".*\\b(open|show|start)\\b.*\\bcamera\\b.*") || s.equals("camera")) return "open";
        if (s.matches(".*\\bclose\\b.*\\bcamera\\b.*")) return "close";
        if (s.matches(".*\\b(stop|end)\\b.*\\b(recording|video)\\b.*")) return "stop";
        if (s.matches(".*\\b(record|film)\\b.*\\bvideo\\b.*") || s.matches(".*\\bstart recording\\b.*")) return "record";
        if (s.matches(".*\\btake\\b.*\\b(photo|picture|selfie|snap|shot)\\b.*")
                || s.equals("photo") || s.equals("picture") || s.equals("selfie")) return "photo";
        return null;
    }

    @Override
    public SkillResult run(String input) throws Exception {
        String action = action(input.trim().toLowerCase());
        if (action == null) return SkillResult.error("I did not catch what to do with the camera.");

        String device = Config.get().str("camera.device", "/dev/video0");
        if (!Files.exists(Path.of(device))) {
            return SkillResult.error("No camera found at " + device + ".");
        }

        return switch (action) {
            case "open" -> openPreview(device);
            case "close" -> closePreview();
            case "photo" -> takePhoto(device);
            case "record" -> startRecording(device);
            case "stop" -> stopRecording();
            default -> SkillResult.error("I did not catch what to do with the camera.");
        };
    }

    private SkillResult openPreview(String device) {
        if (watcher.running()) return SkillResult.error(WATCHING);
        if (viewport.cameraVisible()) {
            return SkillResult.of("The camera is already open.");
        }
        if (recording != null && recording.isAlive()) {
            return SkillResult.error("I am recording. Say \"stop recording\" first.");
        }
        viewport.showCamera(device);
        return SkillResult.of(Personality.affirm() + " Camera is up.");
    }

    private SkillResult closePreview() {
        if (!viewport.cameraVisible()) {
            return SkillResult.error("The camera is not open.");
        }
        viewport.hideCamera();
        return SkillResult.of("Camera closed.");
    }

    private SkillResult takePhoto(String device) throws IOException, InterruptedException {
        Path out = destination("Pictures", "photo-" + LocalDateTime.now().format(STAMP) + ".jpg");

        // A webcam opens once only, so during a preview or a watch the picture comes off the live feed.
        byte[] live = watcher.running() ? watcher.latestJpeg() : viewport.currentFrame();
        if (live != null) {
            Files.write(out, live);
            viewport.showImage(out, out.getFileName().toString());
            return SkillResult.of("Got it.");
        }

        if (viewport.cameraVisible()) {
            return SkillResult.error("The camera is still warming up. Try again in a second.");
        }

        if (!captureStill(device, out)) {
            return SkillResult.error("The camera failed to take a photo.");
        }
        viewport.showImage(out, out.getFileName().toString());
        return SkillResult.of("Got it.");
    }

    /** One frame from the webcam, exposure allowed to settle first. */
    static boolean captureStill(String device, Path out) throws IOException, InterruptedException {
        Process p = ffmpeg(List.of(
                "-f", "v4l2", "-video_size", "1280x720", "-i", device,
                "-vf", "select=gte(n\\," + WARMUP_FRAMES + ")", "-frames:v", "1",
                "-y", out.toString()));

        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0 && Files.exists(out);
    }

    private SkillResult startRecording(String device) throws IOException, InterruptedException {
        if (recording != null && recording.isAlive()) {
            return SkillResult.error("Already recording. Say \"stop recording\" first.");
        }
        if (watcher.running()) return SkillResult.error(WATCHING);

        // Only one process can hold the camera, so the preview yields - and needs a
        // moment to actually let go of the device before ffmpeg can claim it.
        if (viewport.cameraVisible()) {
            viewport.hideCamera();
            Thread.sleep(600);
        }

        recordingFile = destination("Videos", "video-" + LocalDateTime.now().format(STAMP) + ".mp4");
        recording = ffmpeg(List.of(
                "-f", "v4l2", "-video_size", "640x480", "-i", device,
                "-t", String.valueOf(MAX_RECORDING_SECONDS),
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-y", recordingFile.toString()));

        return SkillResult.of("Recording. Say \"stop recording\" when you are done.");
    }

    private SkillResult stopRecording() throws IOException, InterruptedException {
        if (recording == null || !recording.isAlive()) {
            return SkillResult.error("Nothing is recording.");
        }

        // ffmpeg finalises the container on "q"; killing it outright leaves an unplayable file.
        try (OutputStream in = recording.getOutputStream()) {
            in.write('q');
            in.flush();
        } catch (IOException ignored) {
            // Already gone - the wait below settles it.
        }

        if (!recording.waitFor(10, TimeUnit.SECONDS)) {
            recording.destroy();
            recording.waitFor(5, TimeUnit.SECONDS);
        }

        Path file = recordingFile;
        recording = null;
        recordingFile = null;

        if (file == null || !Files.exists(file)) {
            return SkillResult.error("The recording was lost.");
        }
        return SkillResult.of("Stopped.", file + "  (" + Files.size(file) / 1024 + " KB)");
    }

    private static Process ffmpeg(List<String> args) throws IOException {
        List<String> cmd = new java.util.ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error"));
        cmd.addAll(args);
        return new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** Somewhere predictable under home, created on demand. */
    static Path destination(String folder, String fileName) throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), folder, "Jungey");
        Files.createDirectories(dir);
        return dir.resolve(fileName);
    }

    static boolean onPath(String binary) {
        try {
            return new ProcessBuilder("which", binary)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
