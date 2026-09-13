package dev.suven.jungey.skills;

import com.fasterxml.jackson.databind.JsonNode;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.net.Http;

/**
 * Factual lookups via Wikipedia's REST summary endpoint - no key, fast, and it
 * returns a clean one-paragraph extract that is ideal for reading aloud.
 */
public class WikipediaSkill implements Skill {

    @Override
    public String name() {
        return "lookup";
    }

    @Override
    public String description() {
        return "Look up a person, place, thing or event.";
    }

    @Override
    public String[] examples() {
        return new String[]{"who is Nikola Tesla", "what is quantum entanglement", "tell me about Nepal"};
    }

    @Override
    public int priority() {
        return 210;
    }

    @Override
    public boolean matches(String input) {
        return input.matches("^(who|what)\\s+(is|was|are|were)\\s+.+")
                || input.startsWith("tell me about ")
                || input.startsWith("look up ")
                || input.startsWith("define ");
    }

    @Override
    public SkillResult run(String input) throws Exception {
        String query = input
                .replaceFirst("(?i)^(who|what)\\s+(is|was|are|were)\\s+", "")
                .replaceFirst("(?i)^tell me about\\s+", "")
                .replaceFirst("(?i)^look up\\s+", "")
                .replaceFirst("(?i)^define\\s+", "")
                .replaceAll("[?.!]+$", "")
                .trim();

        if (query.isEmpty()) return SkillResult.error("Look up what?");

        // Search first, so loose phrasing still lands on the right article.
        String searchUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json"
                + "&srlimit=1&srsearch=" + Http.enc(query);
        JsonNode search = Http.getJson(searchUrl);
        JsonNode hits = search.path("query").path("search");

        if (!hits.isArray() || hits.isEmpty()) {
            return SkillResult.error("I found nothing on \"" + query + "\".");
        }

        String title = hits.get(0).path("title").asText();
        JsonNode summary = Http.getJson(
                "https://en.wikipedia.org/api/rest_v1/page/summary/" + Http.enc(title.replace(' ', '_')));

        String extract = summary.path("extract").asText("");
        if (extract.isBlank()) {
            return SkillResult.error("I found the article for " + title + " but it had no summary.");
        }

        // Speak the first two sentences; show the whole extract on screen.
        String spoken = firstSentences(extract, 2);
        String detail = title.toUpperCase() + "\n\n" + wrap(extract, 78)
                + "\n\nsource: en.wikipedia.org/wiki/" + title.replace(' ', '_');

        return SkillResult.of(spoken, detail);
    }

    private static String firstSentences(String text, int count) {
        String[] parts = text.split("(?<=[.!?])\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(count, parts.length); i++) {
            sb.append(parts[i]).append(' ');
        }
        return sb.toString().trim();
    }

    /** Hard-wrap for the monospace detail pane. */
    static String wrap(String text, int width) {
        StringBuilder out = new StringBuilder();
        int lineLen = 0;
        for (String word : text.split("\\s+")) {
            if (lineLen + word.length() + 1 > width) {
                out.append('\n');
                lineLen = 0;
            } else if (lineLen > 0) {
                out.append(' ');
                lineLen++;
            }
            out.append(word);
            lineLen += word.length();
        }
        return out.toString();
    }
}
