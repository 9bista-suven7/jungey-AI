package dev.suven.jungey.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.Viewport;
import dev.suven.jungey.watch.SceneWatcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Answers questions about what is on screen or in front of the camera, by sending a
 * single frame to a local vision model.
 *
 * <p>Sorts ahead of the plain conversation skill so "what is on my screen" is looked at
 * rather than guessed at, and behind the structured skills so it never intercepts a
 * command that a cheap local matcher already handles.
 */
public class VisionSkill implements Skill {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** Vision on a CPU is slow enough that a full-resolution screen is not worth sending. */
    private static final int MAX_WIDTH = 1024;

    private final Viewport viewport;
    private final SceneWatcher watcher;

    public VisionSkill(Viewport viewport, SceneWatcher watcher) {
        this.viewport = viewport;
        this.watcher = watcher;
    }

    @Override
    public String name() {
        return "vision";
    }

    @Override
    public String description() {
        return "Looks at your screen or through the camera and answers questions about it.";
    }

    @Override
    public String[] examples() {
        return new String[]{"what is on my screen", "what does this error mean", "what am I holding"};
    }

    /**
     * Ahead of the encyclopedia, which claims anything shaped like "what is ...", but still
     * in the range the router treats as slow - a local vision model is not instant.
     */
    @Override
    public int priority() {
        return 205;
    }

    @Override
    public boolean matches(String input) {
        return target(input.trim()) != null;
    }

    /** @return "screen", "camera", or null when this is not a question about something visible */
    private static String target(String s) {
        // Without a looking verb this is a statement about a screen, not a request to look at one.
        if (!s.matches(".*\\b(look|see|seeing|read|describe|explain|what|what's)\\b.*")) return null;

        // "my screen" and "the screen" are requests; "a screen door" is not.
        // "What's this" points at something; the user draws a box around it.
        if (s.equals("what is this") || s.equals("what's this")
                || s.equals("what is that") || s.equals("what's that")
                || s.matches("^(look at|explain) (this|that)$")) return "region";

        if (s.matches(".*\\b(my|the)\\s+(screen|display|monitor)\\b.*")
                || s.matches(".*\\bthis error\\b.*")) return "screen";

        if (s.matches(".*\\b(my|the)\\s+(camera|webcam)\\b.*")
                || s.matches(".*\\bam i holding\\b.*")
                || s.matches(".*\\bin front of me\\b.*")
                || s.matches(".*\\bdo you see\\b.*")) return "camera";

        return null;
    }

    @Override
    public SkillResult run(String input) throws Exception {
        String what = target(input.trim().toLowerCase());
        if (what == null) return SkillResult.error("I am not sure what to look at.");

        Path shot = Files.createTempFile("jungey-vision-", what.equals("camera") ? ".jpg" : ".png");
        try {
            if (!grab(what, shot)) {
                return SkillResult.error("I could not get a picture to look at.");
            }

            Path small = shrink(shot);
            String answer = ask(input.trim(), Files.readAllBytes(small));
            if (!small.equals(shot)) Files.deleteIfExists(small);

            viewport.showImage(shot, what);
            return answer.length() > 260
                    ? SkillResult.of(answer.substring(0, 240), WikipediaSkill.wrap(answer, 78))
                    : SkillResult.of(answer);
        } finally {
            // The picture stays only as long as the question takes.
            Files.deleteIfExists(shot);
        }
    }

    private boolean grab(String what, Path out) throws IOException, InterruptedException {
        if (what.equals("screen") || what.equals("region")) {
            try {
                if (what.equals("region")) {
                    ScreenshotSkill.captureRegion(out);
                } else {
                    ScreenshotSkill.captureScreen(out);
                }
                return true;
            } catch (IllegalStateException e) {
                return false;
            }
        }

        // While watching, the watcher holds the camera; its newest frame is as good as a fresh one.
        byte[] live = watcher.running() ? watcher.latestJpeg() : viewport.currentFrame();
        if (live != null) {
            Files.write(out, live);
            return true;
        }
        return CameraSkill.captureStill(Config.get().str("camera.device", "/dev/video0"), out);
    }

    /** Scale the picture down so the model is not kept waiting on pixels it cannot use. */
    private static Path shrink(Path source) throws IOException, InterruptedException {
        if (!CameraSkill.onPath("ffmpeg")) return source;

        Path out = Files.createTempFile("jungey-vision-small-", ".jpg");
        Process p = new ProcessBuilder(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", source.toString(),
                "-vf", "scale='min(" + MAX_WIDTH + ",iw)':-2", "-q:v", "4", "-y", out.toString()))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0 || !Files.exists(out)) {
            Files.deleteIfExists(out);
            return source;
        }
        return out;
    }

    /** One question about one image, answered by the local vision model. Also used to name what the watcher saw. */
    public static String ask(String question, byte[] image) throws IOException, InterruptedException {
        Config cfg = Config.get();
        String base = cfg.str("llm.url", "http://localhost:11434");
        String model = cfg.str("llm.visionModel", "moondream");

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("keep_alive", LlmSkill.keepAlive());

        ObjectNode message = body.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", question);
        message.putArray("images").add(Base64.getEncoder().encodeToString(image));

        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> res;
        try {
            res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            throw new IOException("My eyes are offline. Start Ollama with \"ollama serve\".");
        }

        if (res.statusCode() == 404) {
            throw new IOException("No vision model installed. Run: ollama pull " + model);
        }
        if (res.statusCode() / 100 != 2) {
            throw new IOException("The vision model returned HTTP " + res.statusCode() + ".");
        }

        JsonNode json = MAPPER.readTree(res.body());
        String reply = json.path("message").path("content").asText("").trim();
        return reply.isEmpty() ? "I could not make anything of that." : reply;
    }
}
