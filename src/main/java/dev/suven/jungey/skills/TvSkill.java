package dev.suven.jungey.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.Viewport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The TV, by voice: "turn on the TV", "open Netflix on the TV", "play lofi on YouTube on
 * the TV", "TV volume down by 5", "watch Stranger Things on Netflix".
 *
 * <p>Searches give choices rather than a guess: "search YouTube for lofi on the TV" reads
 * out the top three and remembers the rest for a few minutes, so "play number two" plays
 * that one and "show them" opens Jungey TV's window at all of them.
 *
 * <p>The TV itself is Jungey TV's business - a separate app, apps/tv, with a remote window
 * of its own. This only works out what was asked and runs {@code jungey-tv --json} to do
 * it, saying whatever it answers. Nothing here matches unless the TV is mentioned, so
 * "volume up" still means this computer.
 */
public class TvSkill implements Skill {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern TV = Pattern.compile("\\b(tv|television|telly)\\b");
    private static final String ON_TV = "(?:\\s+on\\s+(?:the\\s+)?(?:tv|television|telly))";

    private static final Pattern YOUTUBE = Pattern.compile(
            "^(?:play|put on)\\s+(.+?)\\s+on youtube" + ON_TV + "?$");
    private static final Pattern SEARCH_YOUTUBE = Pattern.compile(
            "^(?:search|look up|find)\\s+(?:on\\s+)?(?:youtube\\s+)?(?:for\\s+)?(.+?)\\s+on youtube" + ON_TV + "?$"
                    + "|^(?:search|look up|find)\\s+(?:on\\s+)?youtube\\s+(?:for\\s+)?(.+?)" + ON_TV + "$");
    private static final Pattern SEARCH = Pattern.compile(
            "^(?:search|look up|find)\\s+(?:for\\s+)?(.+?)" + ON_TV + "$");
    private static final Pattern WATCH = Pattern.compile("^(?:watch|stream)\\s+(.+?)" + ON_TV + "$");
    /** "watch the office on peacock" is about the TV even when the TV goes unsaid. */
    private static final Pattern WATCH_ON_SERVICE = Pattern.compile(
            "^(?:watch|stream|put on)\\s+(.+?)\\s+on\\s+(netflix|prime video|amazon prime|prime|amazon|hulu|disney plus"
                    + "|disney\\+|disney|hbo max|max|peacock|paramount plus|paramount|apple tv|tubi|pluto|fandango|vudu)"
                    + ON_TV + "?$");
    private static final Pattern TYPE = Pattern.compile("^type\\s+(.+?)" + ON_TV + "$", Pattern.CASE_INSENSITIVE);
    /** "play number two", "watch the first one" - a choice from the last search. */
    private static final Pattern PICK = Pattern.compile(
            "^(?:play|watch|open|put on)\\s+(?:the\\s+)?(?:number\\s+)?"
                    + "(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|first|second|third|fourth|fifth|sixth"
                    + "|seventh|eighth|ninth|tenth|last)(?:\\s+one)?" + ON_TV + "?$");
    private static final Pattern SHOW = Pattern.compile(
            "^show\\s+(?:me\\s+)?(?:them|all|all of them|the results|the rest|more)" + ON_TV + "?$");
    private static final List<String> ORDINALS = List.of("first", "second", "third", "fourth", "fifth", "sixth",
            "seventh", "eighth", "ninth", "tenth");
    private static final List<String> NUMBERS = List.of("one", "two", "three", "four", "five", "six", "seven",
            "eight", "nine", "ten");
    /** How long the last search's results can be chosen from by number. */
    private static final long REMEMBER_MS = 10 * 60_000;
    private static final Pattern PLAY_ON_TV = Pattern.compile("^(?:play|put on|put)\\s+(.+?)" + ON_TV + "$");
    private static final Pattern OPEN = Pattern.compile(
            "^(?:open|launch|start|go to|switch to|put on)\\s+(.+?)" + ON_TV + "$"
                    + "|^(?:tv|television)[, ]+(?:open|launch|start)\\s+(.+)$");
    private static final Pattern LINK = Pattern.compile("(https?://\\S*(?:youtube\\.com|youtu\\.be)\\S*)");
    private static final Pattern APP_WORDS = Pattern.compile(
            "\\b(netflix|prime|amazon|disney|hulu|spotify|youtube|apple tv|max|hbo|plex|twitch|peacock|paramount)\\b");

    private static final Pattern ON = Pattern.compile(
            "\\b(?:turn|switch|power)\\s+on\\s+(?:the\\s+)?(?:tv|television)\\b"
                    + "|\\b(?:turn|switch|power)\\s+(?:the\\s+)?(?:tv|television)\\s+on\\b"
                    + "|^(?:tv|television)\\s+on$|\\bwake(?:\\s+up)?\\s+(?:the\\s+)?(?:tv|television)\\b");
    private static final Pattern OFF = Pattern.compile(
            "\\b(?:turn|switch|power)\\s+off\\s+(?:the\\s+)?(?:tv|television)\\b"
                    + "|\\b(?:turn|switch|power)\\s+(?:the\\s+)?(?:tv|television)\\s+off\\b"
                    + "|^(?:tv|television)\\s+off$");
    private static final Pattern VOLUME_UP = Pattern.compile(
            "\\bvolume up\\b|\\blouder\\b|\\bturn up\\b|\\bturn\\s+(?:the\\s+)?(?:tv|television|volume|it)\\s+up\\b"
                    + "|\\b(?:raise|increase)\\s+(?:the\\s+)?(?:tv\\s+)?volume\\b");
    private static final Pattern VOLUME_DOWN = Pattern.compile(
            "\\bvolume down\\b|\\bquieter\\b|\\bsofter\\b|\\bturn down\\b|\\bturn\\s+(?:the\\s+)?(?:tv|television|volume|it)\\s+down\\b"
                    + "|\\b(?:lower|decrease|reduce)\\s+(?:the\\s+)?(?:tv\\s+)?volume\\b");
    private static final Pattern STEPS = Pattern.compile("\\bby\\s+(\\d+)\\b|\\b(\\d+)\\s+(?:steps|notches|times|levels)\\b");
    private static final Pattern CHANNEL = Pattern.compile("\\bchannel\\s+(up|down)\\b|\\b(next|previous)\\s+channel\\b");
    private static final Pattern HDMI = Pattern.compile("\\bhdmi\\s*([1-4])\\b");
    private static final Pattern BUTTON = Pattern.compile(
            "\\b(home|back|up|down|left|right|ok|okay|select|enter|pause|play|resume|stop|menu|exit|rewind|fast forward|guide|info)\\b");

    private final Viewport viewport;

    /** The last search's results, for "play number two" and "show them". */
    private record Results(boolean videos, String query, JsonNode items, long at) {
        boolean fresh() {
            return System.currentTimeMillis() - at < REMEMBER_MS;
        }
    }

    private volatile Results last;

    public TvSkill(Viewport viewport) {
        this.viewport = viewport;
    }

    @Override
    public String name() {
        return "tv";
    }

    @Override
    public String description() {
        return "Controls the TV through Jungey TV: power, volume, inputs, apps and YouTube.";
    }

    @Override
    public String[] examples() {
        return new String[]{"turn on the TV", "open Netflix on the TV", "watch Stranger Things on Netflix",
                "search YouTube for lofi on the TV", "play number two", "TV volume down by 5",
                "switch the TV to HDMI 2", "type friends on the TV", "pair the TV"};
    }

    @Override
    public int priority() {
        // Ahead of this computer's own volume and app launching, which would otherwise take
        // "TV volume up" and "open Netflix on the TV" for themselves.
        return 9;
    }

    @Override
    public boolean matches(String input) {
        String s = tidy(input);
        return command(s) != null || chosen(s) != null || showing(s);
    }

    @Override
    public SkillResult run(String input) throws Exception {
        String s = tidy(input);
        String binary = binary();
        if (binary == null) {
            return SkillResult.error("Jungey TV is not installed. Run apps/tv/install.sh to add it.");
        }

        if (showing(s)) {
            Results r = last;
            List<String> cmd = new ArrayList<>(List.of(binary, "browse"));
            if (r.videos()) cmd.add("youtube");
            cmd.add(r.query());
            // A window: started and left to run, not waited for.
            new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return SkillResult.of("Here they all are, on your screen.");
        }

        List<String> args = chosen(s);
        if (args == null) args = command(s);
        if (args == null) return SkillResult.error("I did not catch what to do with the TV.");
        if (args.getFirst().equals("pair")) {
            viewport.announce("Look at the TV and choose Allow when it asks.");
        }

        // Matching lowercased the words, but a video ID and typed text keep their case: use them as given.
        Matcher link = Pattern.compile(LINK.pattern(), Pattern.CASE_INSENSITIVE).matcher(input);
        if (args.getFirst().equals("youtube") && link.find()) args = List.of("youtube", link.group(1));
        Matcher typed = TYPE.matcher(input.trim().replaceAll("[.!?]+$", ""));
        if (args.getFirst().equals("type") && typed.matches()) args = List.of("type", typed.group(1));

        JsonNode reply = call(binary, args);
        if (reply == null) return SkillResult.error("The TV is taking too long to answer.");
        String message = reply.path("message").asText("");
        if (!reply.path("ok").asBoolean(false)) {
            if (reply.path("problem").asText().equals("not_paired")) {
                return SkillResult.error("I am not paired with the TV yet. Say \"pair the TV\", then choose Allow on it.");
            }
            return SkillResult.error(message.isBlank() ? "The TV did not do that." : message);
        }
        if (args.getFirst().equals("search")) return results(args, reply);
        return SkillResult.of(message.isBlank() ? "Done." : message);
    }

    /** Run jungey-tv and read its answer: the last JSON line it prints. Null if it took too long. */
    private static JsonNode call(String binary, List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(binary, "--json"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // Waking the TV and then finding a video can take half a minute; pairing waits for a person.
        if (!p.waitFor(args.getFirst().equals("pair") ? 50 : 45, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return null;
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            return MAPPER.readTree(out.lines().filter(l -> l.startsWith("{")).reduce((a, b) -> b).orElse("{}"));
        } catch (IOException e) {
            return MAPPER.createObjectNode().put("ok", false).put("message", "Jungey TV answered something I could not read.");
        }
    }

    /** Search results, read out three at a time and listed in full on screen, then remembered. */
    private SkillResult results(List<String> args, JsonNode reply) {
        boolean videos = args.size() > 1 && args.get(1).equals("youtube");
        String query = args.getLast();
        JsonNode items = reply.path("data");
        if (!items.isArray() || items.isEmpty()) return SkillResult.of("Nothing turned up for " + query + ".");
        last = new Results(videos, query, items, System.currentTimeMillis());

        StringBuilder said = new StringBuilder(videos ? "On YouTube: " : "");
        StringBuilder listed = new StringBuilder();
        for (int i = 0; i < Math.min(10, items.size()); i++) {
            JsonNode it = items.get(i);
            String line = videos ? video(it) : title(it);
            listed.append(i + 1).append(". ").append(line).append('\n');
            if (i < 3) said.append(NUMBERS.get(i)).append(", ").append(line).append(i < 2 ? "; " : ". ");
        }
        said.append(videos ? "Say play number one, two or three - or show them, to see all " + items.size() + "."
                : "Say watch number one, two or three - or show them.");
        return SkillResult.of(said.toString(), listed.toString().stripTrailing());
    }

    /** "lofi house radio, by Lofi Girl" - the title cut down to what is worth hearing. */
    private static String video(JsonNode v) {
        String title = speakable(v.path("title").asText("a video"));
        String channel = v.path("channel").asText("");
        return title + (channel.isBlank() ? "" : ", by " + speakable(channel));
    }

    /** "The Office from 2005, on Peacock". */
    private static String title(JsonNode t) {
        String name = t.path("name").asText("");
        int year = t.path("year").asInt(0);
        List<String> where = new ArrayList<>();
        for (JsonNode o : t.path("offers")) {
            String service = o.path("service").asText("");
            String type = o.path("type").asText("");
            if (service.isBlank() || where.size() == 2) continue;
            where.add((type.equals("rent") ? "to rent on " : type.equals("buy") ? "to buy on " : type.startsWith("free") ? "free on " : "on ")
                    + service.replaceFirst("(?i)\\s+(standard with ads|premium|tv store|amazon channel)$", ""));
        }
        return name + (year > 0 ? " from " + year : "") + (where.isEmpty() ? ", not streaming" : ", " + String.join(" and ", where));
    }

    /** Titles are written for eyes: cut at the first "|", drop emoji and shouting, keep it short. */
    private static String speakable(String text) {
        String t = text.split("\\s[|｜•]\\s|\\s[-–—]\\s|\\s[\\[(]")[0]
                .replaceAll("[^\\p{L}\\p{N}\\s'&,.:!?-]", "")
                .replaceAll("\\s+", " ").trim();
        if (t.equals(t.toUpperCase(Locale.ENGLISH)) && t.length() > 4) t = t.toLowerCase(Locale.ENGLISH);
        String[] words = t.split(" ");
        return words.length > 9 ? String.join(" ", java.util.Arrays.copyOf(words, 9)) : t;
    }

    /** "play number two" after a search: the arguments for that result, or null. */
    List<String> chosen(String s) {
        Results r = last;
        if (r == null || !r.fresh()) return null;
        Matcher m = PICK.matcher(s);
        if (!m.matches()) return null;
        String which = m.group(1);
        int index = which.equals("last") ? r.items().size() - 1
                : which.matches("\\d+") ? Integer.parseInt(which) - 1
                : Math.max(NUMBERS.indexOf(which), ORDINALS.indexOf(which));
        if (index < 0 || index >= r.items().size()) return null;
        JsonNode it = r.items().get(index);
        if (r.videos()) return List.of("youtube", "https://youtu.be/" + it.path("id").asText());
        List<String> args = new ArrayList<>(List.of("watch", it.path("name").asText()));
        if (it.path("year").asInt(0) > 0) args.addAll(List.of("--year", String.valueOf(it.path("year").asInt())));
        return args;
    }

    /** "show them": the last search's results, in Jungey TV's window. */
    private boolean showing(String s) {
        Results r = last;
        return r != null && r.fresh() && SHOW.matcher(s).matches();
    }

    /**
     * What to ask Jungey TV for, as its command-line arguments - or null if this is not
     * about the TV at all.
     */
    static List<String> command(String s) {
        if (s.isEmpty() || s.matches("^(remember|forget|note|add to|remind)\\b.*")) return null;

        Matcher link = LINK.matcher(s);
        if (link.find() && TV.matcher(s).find()) return List.of("youtube", link.group(1));
        Matcher service = WATCH_ON_SERVICE.matcher(s);
        if (service.matches()) return List.of("watch", service.group(1) + " on " + service.group(2));
        if (!TV.matcher(s).find()) return null;

        if (s.matches(".*\\bis the (tv|television) (on|off)\\b.*") || s.matches("^(tv|television) status$")) {
            return List.of("status");
        }
        if (s.matches(".*\\b(what|which|list|show)\\b.*\\bapps\\b.*")) return List.of("apps");

        if (s.matches("^(?:pair|connect)\\b.*")) return List.of("pair");
        Matcher m = SEARCH_YOUTUBE.matcher(s);
        if (m.matches()) return List.of("search", "youtube", m.group(1) != null ? m.group(1) : m.group(2));
        m = SEARCH.matcher(s);
        if (m.matches()) return List.of("search", m.group(1));
        m = YOUTUBE.matcher(s);
        if (m.matches()) return List.of("youtube", m.group(1));
        m = TYPE.matcher(s);
        if (m.matches()) return List.of("type", m.group(1));
        m = WATCH.matcher(s);
        if (m.matches()) return List.of("watch", m.group(1));

        if (ON.matcher(s).find()) return List.of("on");
        if (OFF.matcher(s).find()) return List.of("off");

        m = HDMI.matcher(s);
        if (m.find()) return List.of("source", "hdmi" + m.group(1));

        m = OPEN.matcher(s);
        if (m.matches()) {
            String app = m.group(1) != null ? m.group(1) : m.group(2);
            return List.of("open", app);
        }
        m = PLAY_ON_TV.matcher(s);
        if (m.matches()) {
            String what = m.group(1);
            // "play Netflix on the TV" opens the app; anything else is a video to find.
            return APP_WORDS.matcher(what).find() && what.split("\\s+").length <= 3
                    ? List.of("open", what) : List.of("youtube", what);
        }

        if (VOLUME_UP.matcher(s).find()) return List.of("volume", "up", steps(s));
        if (VOLUME_DOWN.matcher(s).find()) return List.of("volume", "down", steps(s));
        if (s.matches(".*\\b(un)?mute\\b.*")) return List.of("mute");

        m = CHANNEL.matcher(s);
        if (m.find()) {
            String way = m.group(1) != null ? m.group(1) : m.group(2).equals("next") ? "up" : "down";
            return List.of("channel", way);
        }
        if (s.matches(".*\\b(source|input|inputs)\\b.*")) return List.of("source");

        m = BUTTON.matcher(s);
        if (m.find()) return List.of("key", m.group(1).replace("fast forward", "forward"));
        return null;
    }

    private static String steps(String s) {
        Matcher m = STEPS.matcher(s);
        if (!m.find()) return "3";
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    /** Lower case, without the politeness and punctuation around the request. */
    static String tidy(String input) {
        return input.toLowerCase(Locale.ENGLISH).trim()
                .replaceAll("[.!?]+$", "")
                .replaceAll("^(please|can you|could you|would you|will you)\\s+", "")
                .replaceAll("\\s+please$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** jungey-tv on the PATH, in ~/.local/bin, or where tv.command says. */
    private static String binary() {
        String configured = Config.get().str("tv.command", "jungey-tv");
        if (configured.contains("/")) return Files.isExecutable(Path.of(configured)) ? configured : null;
        if (CameraSkill.onPath(configured)) return configured;
        for (Path p : List.of(Path.of(System.getProperty("user.home"), ".local", "bin", configured),
                Path.of("/usr/bin", configured))) {
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }
}
