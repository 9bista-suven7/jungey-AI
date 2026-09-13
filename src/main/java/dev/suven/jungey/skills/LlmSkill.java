package dev.suven.jungey.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.Viewport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The catch-all. Anything no structured skill claimed gets handed to a local model
 * running under Ollama, which keeps conversation on-device.
 *
 * <p>This sorts last of all skills on purpose: a model should never be asked
 * "what time is it" when a three-line local skill answers it instantly.
 *
 * <p>On a CPU a small model writes about eight tokens a second, so the reply is streamed:
 * each sentence is shown and spoken as soon as it exists instead of after the whole answer.
 */
public class LlmSkill implements Skill {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** Last few turns, so follow-up questions have context. Bounded to keep prompts small. */
    private final Deque<String[]> history = new ArrayDeque<>();
    private static final int MAX_TURNS = 6;

    /** Long answers are shown in full but spoken only in part. */
    private static final int SPOKEN_LIMIT = 240;

    /** A sentence ends at terminal punctuation followed by space, or at a line break. */
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?]+[\"')\\]]*\\s+|\\n+");

    private final Viewport viewport;

    public LlmSkill(Viewport viewport) {
        this.viewport = viewport;
    }

    @Override
    public String name() {
        return "converse";
    }

    @Override
    public String description() {
        return "Open conversation and reasoning, via a local model.";
    }

    @Override
    public String[] examples() {
        return new String[]{"explain recursion to me", "write me a haiku about rain", "why is the sky blue"};
    }

    @Override
    public int priority() {
        return 9000;   // always last
    }

    @Override
    public boolean matches(String input) {
        return true;   // the catch-all
    }

    /** How long Ollama keeps a model in memory after a request. Reloading it from disk is the slow part. */
    public static String keepAlive() {
        return Config.get().str("llm.keepAlive", "60m");
    }

    /**
     * Load the model ahead of the first question. From a slow disk that takes most of a
     * minute, which is better spent at boot than while someone waits for an answer.
     */
    public void warmUp() {
        Config cfg = Config.get();
        if (!cfg.str("llm.backend", "ollama").equals("ollama")) return;

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", cfg.str("llm.model", "llama3.2:3b"));
        body.put("keep_alive", keepAlive());

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.str("llm.url", "http://localhost:11434") + "/api/generate"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            // Ollama is not running or lacks the model; the first real question reports that.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public SkillResult run(String input) throws Exception {
        Config cfg = Config.get();
        if (!cfg.str("llm.backend", "ollama").equals("ollama")) {
            return SkillResult.error("No model backend is configured.");
        }

        String base = cfg.str("llm.url", "http://localhost:11434");
        String model = cfg.str("llm.model", "llama3.2:3b");

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.put("keep_alive", keepAlive());

        var messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt());

        for (String[] turn : history) {
            ObjectNode u = messages.addObject();
            u.put("role", "user");
            u.put("content", turn[0]);
            ObjectNode a = messages.addObject();
            a.put("role", "assistant");
            a.put("content", turn[1]);
        }

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", input);

        // Generous, because a cold model loads from disk before the first token arrives.
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<Stream<String>> res;
        try {
            res = CLIENT.send(req, HttpResponse.BodyHandlers.ofLines());
        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            return SkillResult.error(
                    "My reasoning core is offline. Start Ollama with \"ollama serve\", then pull a model: "
                            + "ollama pull " + model);
        }

        if (res.statusCode() / 100 != 2) {
            res.body().close();
            if (res.statusCode() == 404) {
                return SkillResult.error("Ollama is running but does not have \"" + model
                        + "\". Run: ollama pull " + model);
            }
            return SkillResult.error("Model backend returned HTTP " + res.statusCode() + ".");
        }

        StringBuilder reply = new StringBuilder();
        StringBuilder pending = new StringBuilder();
        Viewport.ReplyStream out = null;
        int spoken = 0;

        try (Stream<String> lines = res.body()) {
            Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                if (line.isBlank()) continue;

                JsonNode json = MAPPER.readTree(line);
                if (json.hasNonNull("error")) {
                    if (reply.isEmpty()) {
                        return SkillResult.error("The model failed: " + json.get("error").asText());
                    }
                    break;
                }

                String chunk = json.path("message").path("content").asText("");
                if (out == null) {
                    chunk = chunk.stripLeading();
                }
                if (!chunk.isEmpty()) {
                    if (out == null) out = viewport.beginReply();
                    out.text(chunk);
                    reply.append(chunk);
                    pending.append(chunk);
                    spoken = speakFinishedSentences(out, pending, spoken);
                }

                if (json.path("done").asBoolean(false)) break;
            }
        } catch (IOException | UncheckedIOException e) {
            // Cut off mid-answer: keep what arrived, but with nothing at all it is a failure.
            if (reply.isEmpty()) throw e;
        }

        if (out == null) {
            return SkillResult.error("The model returned nothing.");
        }

        // Whatever trails the last full stop.
        speak(out, pending.toString().trim(), spoken);

        String text = reply.toString().trim();
        remember(input, text);
        return SkillResult.streamed(text);
    }

    /** Speak each complete sentence in the buffer and drop it from there. */
    private static int speakFinishedSentences(Viewport.ReplyStream out, StringBuilder pending, int spoken) {
        Matcher m = SENTENCE_END.matcher(pending);
        int consumed = 0;
        while (m.find()) {
            spoken = speak(out, pending.substring(consumed, m.end()).trim(), spoken);
            consumed = m.end();
        }
        pending.delete(0, consumed);
        return spoken;
    }

    /**
     * Speak a sentence if it still fits the spoken budget. The first sentence is always
     * spoken; once one does not fit, the rest of the answer is left to the screen.
     */
    private static int speak(Viewport.ReplyStream out, String sentence, int spoken) {
        if (sentence.isEmpty()) return spoken;

        boolean fits = spoken == 0
                || (spoken <= SPOKEN_LIMIT && spoken + sentence.length() <= SPOKEN_LIMIT);
        if (!fits) return Integer.MAX_VALUE;

        out.sentence(sentence);
        return spoken + sentence.length();
    }

    private void remember(String user, String assistant) {
        history.addLast(new String[]{user, assistant});
        while (history.size() > MAX_TURNS) {
            history.removeFirst();
        }
    }

    private static String systemPrompt() {
        Config cfg = Config.get();
        return """
                You are Jungey, a personal assistant running locally on %s's Linux machine.
                Your manner is calm, dry and economical - think of a very capable butler who
                is never flustered. Address the user as %s when it fits naturally, but do not
                overdo it. Answer in at most three sentences unless asked for detail. Never
                mention that you are a language model.""".formatted(cfg.userName(), cfg.honorific());
    }
}
