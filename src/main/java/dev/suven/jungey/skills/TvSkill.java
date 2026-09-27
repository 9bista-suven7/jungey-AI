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
 * the TV", "TV volume down by 5".
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
            "^(?:play|put on|find|search(?: youtube)? for)\\s+(.+?)\\s+on youtube" + ON_TV + "?$");
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
        return new String[]{"turn on the TV", "open Netflix on the TV", "play lofi hip hop on YouTube on the TV",
                "TV volume down by 5", "switch the TV to HDMI 2", "pair the TV"};
    }

    @Override
    public int priority() {
        // Ahead of this computer's own volume and app launching, which would otherwise take
        // "TV volume up" and "open Netflix on the TV" for themselves.
        return 9;
    }

    @Override
    public boolean matches(String input) {
        return command(tidy(input)) != null;
    }

    @Override
    public SkillResult run(String input) throws Exception {
        List<String> args = command(tidy(input));
        if (args == null) return SkillResult.error("I did not catch what to do with the TV.");

        String binary = binary();
        if (binary == null) {
            return SkillResult.error("Jungey TV is not installed. Run apps/tv/install.sh to add it.");
        }
        if (args.getFirst().equals("pair")) {
            viewport.announce("Look at the TV and choose Allow when it asks.");
        }

        // Matching lowercased the words, but a video ID is case-sensitive: use the link as given.
        Matcher link = Pattern.compile(LINK.pattern(), Pattern.CASE_INSENSITIVE).matcher(input);
        if (args.getFirst().equals("youtube") && link.find()) args = List.of("youtube", link.group(1));

        List<String> cmd = new ArrayList<>(List.of(binary, "--json"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // Waking the TV and then finding a video can take half a minute; pairing waits for a person.
        if (!p.waitFor(args.getFirst().equals("pair") ? 50 : 45, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return SkillResult.error("The TV is taking too long to answer.");
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        JsonNode reply;
        try {
            // The answer is the last JSON line; anything else on stdout is not for us.
            reply = MAPPER.readTree(out.lines().filter(l -> l.startsWith("{")).reduce((a, b) -> b).orElse("{}"));
        } catch (IOException e) {
            return SkillResult.error("Jungey TV answered something I could not read.");
        }
        String message = reply.path("message").asText("");
        if (reply.path("ok").asBoolean(false)) return SkillResult.of(message.isBlank() ? "Done." : message);

        if (reply.path("problem").asText().equals("not_paired")) {
            return SkillResult.error("I am not paired with the TV yet. Say \"pair the TV\", then choose Allow on it.");
        }
        return SkillResult.error(message.isBlank() ? "The TV did not do that." : message);
    }

    /**
     * What to ask Jungey TV for, as its command-line arguments - or null if this is not
     * about the TV at all.
     */
    static List<String> command(String s) {
        if (s.isEmpty() || s.matches("^(remember|forget|note|add to|remind)\\b.*")) return null;

        Matcher link = LINK.matcher(s);
        if (link.find() && TV.matcher(s).find()) return List.of("youtube", link.group(1));
        if (!TV.matcher(s).find()) return null;

        if (s.matches(".*\\b(pair|connect|link)\\b.*")) return List.of("pair");
        if (s.matches(".*\\bis the (tv|television) (on|off)\\b.*") || s.matches("^(tv|television) status$")) {
            return List.of("status");
        }
        if (s.matches(".*\\b(what|which|list|show)\\b.*\\bapps\\b.*")) return List.of("apps");

        Matcher m = YOUTUBE.matcher(s);
        if (m.matches()) return List.of("youtube", m.group(1));

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
