package dev.suven.jungey.skills;

import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.net.Http;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headlines, read from public RSS. No key needed, and RSS is stable in a way
 * news APIs generally are not.
 */
public class NewsSkill implements Skill {

    private static final String WORLD = "https://feeds.bbci.co.uk/news/world/rss.xml";
    private static final String TECH = "https://feeds.bbci.co.uk/news/technology/rss.xml";

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</title>", Pattern.DOTALL);

    @Override
    public String name() {
        return "news";
    }

    @Override
    public String description() {
        return "Today's headlines.";
    }

    @Override
    public String[] examples() {
        return new String[]{"news", "what's happening", "tech news"};
    }

    @Override
    public int priority() {
        return 220;
    }

    @Override
    public boolean matches(String input) {
        return input.matches(".*\\b(news|headlines|happening in the world)\\b.*")
                || input.equals("what's happening") || input.equals("whats happening");
    }

    @Override
    public SkillResult run(String input) throws Exception {
        boolean tech = input.toLowerCase().contains("tech");
        String feed = tech ? TECH : WORLD;

        List<String> headlines = parseHeadlines(Http.get(feed), 6);
        if (headlines.isEmpty()) {
            return SkillResult.error("The news feed returned nothing readable.");
        }

        StringBuilder detail = new StringBuilder((tech ? "TECHNOLOGY" : "WORLD") + " HEADLINES\n\n");
        for (int i = 0; i < headlines.size(); i++) {
            detail.append(String.format("%d. %s%n", i + 1, WikipediaSkill.wrap(headlines.get(i), 74)
                    .replace("\n", "\n   ")));
        }

        String spoken = "Top story: " + headlines.get(0) + ". There are "
                + (headlines.size() - 1) + " more on screen.";

        return SkillResult.of(spoken, detail.toString().stripTrailing());
    }

    private static List<String> parseHeadlines(String xml, int limit) {
        List<String> out = new ArrayList<>();
        Matcher items = ITEM.matcher(xml);
        while (items.find() && out.size() < limit) {
            Matcher title = TITLE.matcher(items.group(1));
            if (title.find()) {
                out.add(unescape(title.group(1).trim()));
            }
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }
}
