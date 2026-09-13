package dev.suven.jungey.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The catch-all. Anything no structured skill claimed gets handed to a local model
 * running under Ollama, which keeps conversation on-device.
 *
 * <p>This sorts last of all skills on purpose: a model should never be asked
 * "what time is it" when a three-line local skill answers it instantly.
 */
public class LlmSkill implements Skill {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** Last few turns, so follow-up questions have context. Bounded to keep prompts small. */
    private final Deque<String[]> history = new ArrayDeque<>();
    private static final int MAX_TURNS = 6;

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
        body.put("stream", false);

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

        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> res;
        try {
            res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            return SkillResult.error(
                    "My reasoning core is offline. Start Ollama with \"ollama serve\", then pull a model: "
                            + "ollama pull " + model);
        }

        if (res.statusCode() == 404) {
            return SkillResult.error("Ollama is running but does not have \"" + model
                    + "\". Run: ollama pull " + model);
        }
        if (res.statusCode() / 100 != 2) {
            return SkillResult.error("Model backend returned HTTP " + res.statusCode() + ".");
        }

        JsonNode json = MAPPER.readTree(res.body());
        String reply = json.path("message").path("content").asText("").trim();
        if (reply.isEmpty()) {
            return SkillResult.error("The model returned nothing.");
        }

        remember(input, reply);

        // Long answers are shown in full but spoken only in part.
        if (reply.length() > 260) {
            return SkillResult.of(firstSentences(reply), WikipediaSkill.wrap(reply, 78));
        }
        return SkillResult.of(reply);
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

    private static String firstSentences(String text) {
        String[] parts = text.split("(?<=[.!?])\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() + part.length() > 240) break;
            sb.append(part).append(' ');
        }
        return sb.isEmpty() ? text.substring(0, Math.min(240, text.length())) : sb.toString().trim();
    }
}
