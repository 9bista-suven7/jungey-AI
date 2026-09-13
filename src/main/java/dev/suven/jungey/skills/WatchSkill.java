package dev.suven.jungey.skills;

import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Personality;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.Viewport;
import dev.suven.jungey.watch.SceneEvent;
import dev.suven.jungey.watch.SceneWatcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * "Watch this": keeps an eye on the scene in front of the camera and, when asked, says what
 * changed - things put down, taken away or moved.
 *
 * <p>Detection runs continuously and cheaply in {@link SceneWatcher}. Naming what changed
 * needs the vision model, which takes seconds on a CPU, so that starts in the background the
 * moment a change settles; by the time anyone asks, the answer is usually ready.
 */
public class WatchSkill implements Skill {

    private static final Pattern START = Pattern.compile(
            "^(?:please )?(?:start watching|watch (?:this|that|it|here|the \\w+(?: \\w+)?)"
                    + "|keep (?:an )?eye on (?:this|that|it|things|the \\w+(?: \\w+)?)"
                    + "|guard (?:this|that|it|the \\w+(?: \\w+)?))(?: for me)?(?: please)?$");
    private static final Pattern STOP = Pattern.compile(
            "^(?:stop watching|stop guarding|you can stop watching)(?: .*)?$");
    private static final Pattern STATUS = Pattern.compile(
            "^(?:are you (?:still )?watching|what are you watching)$");
    private static final Pattern BRIEF = Pattern.compile(
            "^(?:so )?(?:what happened|what(?:'s| has)? changed|did (?:anything|something) (?:change|move|happen)"
                    + "|(?:has )?anything (?:happened|changed|moved)|brief me|give me a brief(?:ing)?|what did you see)"
                    + "(?: (?:here|while i was (?:away|gone|out)))?$");

    /**
     * Moondream under Ollama answers a bare "what is this" with a stray token ("urn") and nothing
     * else, but reliably describes an object when told how to start the sentence.
     */
    private static final String NAME_PROMPT =
            "Describe the main object in this picture in one sentence, starting with 'The main object is'.";

    /** Where a description of the object ends and a description of its surroundings begins. */
    private static final String OBJECT_ENDS =
            "\\s+(?:with|sits|sitting|is|are|on|in|placed|lying|standing|resting|that|which|against|near|next)\\b|[,.;:]";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Each object is a vision-model call of several seconds. Naming starts as soon as a change
     * settles, so it is usually done by the time anyone asks; a briefing waits this long at most.
     */
    private static final long NAMING_WAIT_SECONDS = 45;
    private static final int MAX_BRIEFED = 5;

    private final Viewport viewport;
    private final SceneWatcher watcher;

    private final ExecutorService namer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jungey-namer");
        t.setDaemon(true);
        return t;
    });
    private final Map<Integer, Future<?>> naming = new ConcurrentHashMap<>();

    private volatile int reportedUpTo;
    private volatile LocalDateTime lastBrief;

    public WatchSkill(Viewport viewport, SceneWatcher watcher) {
        this.viewport = viewport;
        this.watcher = watcher;
        watcher.onEvent(event -> naming.put(event.id, namer.submit(() -> name(event))));
    }

    @Override
    public String name() {
        return "watch";
    }

    @Override
    public String description() {
        return "Watches the scene through the camera and tells you what changed.";
    }

    @Override
    public String[] examples() {
        return new String[]{"watch this", "what happened", "stop watching"};
    }

    /** Ahead of the camera, which would otherwise take "start watching with the camera" as "open camera". */
    @Override
    public int priority() {
        return 24;
    }

    @Override
    public boolean matches(String input) {
        String s = tidy(input);
        return START.matcher(s).matches() || STOP.matcher(s).matches()
                || STATUS.matcher(s).matches() || BRIEF.matcher(s).matches();
    }

    @Override
    public SkillResult run(String input) throws Exception {
        String s = tidy(input);
        if (START.matcher(s).matches()) return start();
        if (STOP.matcher(s).matches()) return stop();
        if (STATUS.matcher(s).matches()) return status();
        return brief();
    }

    private SkillResult start() throws Exception {
        if (watcher.running()) {
            return SkillResult.of(watcher.since() == null
                    ? "I am already watching - still taking the reference picture."
                    : "I have been watching since " + clock(watcher.since()) + ".");
        }

        String device = Config.get().str("camera.device", "/dev/video0");
        if (!Files.exists(Path.of(device))) {
            return SkillResult.error("No camera found at " + device + ".");
        }

        // Only one process can hold the camera, so the preview yields to the watcher.
        if (viewport.cameraVisible()) {
            viewport.hideCamera();
            Thread.sleep(600);
        }

        naming.clear();
        reportedUpTo = 0;
        lastBrief = null;
        watcher.start(device);

        // ffmpeg gives up within a second or so when another program holds the camera.
        for (int i = 0; i < 10 && watcher.running(); i++) Thread.sleep(150);
        if (!watcher.running()) {
            return SkillResult.error("The camera would not start. Another program may be using it.");
        }

        return SkillResult.of(Personality.affirm() + " Watching. Keep still for a few seconds while I take a "
                + "reference picture, then ask me what happened whenever you like.");
    }

    private SkillResult stop() {
        if (!watcher.running()) return SkillResult.of("I was not watching anything.");

        watcher.stop();
        int n = watcher.events().size();
        return SkillResult.of("Stopped watching. " + (n == 0
                ? "Nothing changed while I watched."
                : count(n, "change") + " while I watched - ask what happened to hear about them."));
    }

    private SkillResult status() {
        if (!watcher.running()) {
            return SkillResult.of("I am not watching anything." + (watcher.events().isEmpty()
                    ? "" : " Ask what happened to hear about the last watch."));
        }
        if (watcher.since() == null) return SkillResult.of("Watching, but still taking the reference picture.");

        int n = watcher.events().size();
        return SkillResult.of("Watching since " + clock(watcher.since()) + ". "
                + (n == 0 ? "Nothing has changed yet." : count(n, "change") + " so far."));
    }

    private SkillResult brief() throws InterruptedException {
        List<SceneEvent> all = watcher.events();
        LocalDateTime since = watcher.since();

        if (all.isEmpty()) {
            if (since == null) {
                return SkillResult.of(watcher.running()
                        ? "I am still taking the reference picture. Give me a few seconds."
                        : "I have not been watching anything. Say \"watch this\" first.");
            }
            return SkillResult.of("Nothing has changed since " + clock(since) + "." + movement());
        }

        List<SceneEvent> fresh = all.stream().filter(e -> e.id > reportedUpTo).toList();
        if (fresh.isEmpty()) {
            return SkillResult.of("Nothing new since I briefed you at " + clock(lastBrief) + "." + movement());
        }

        waitForNames(fresh);

        // A camera facing a person mostly sees that person shift about. That is one line, not a
        // list of changes - and no pictures of them.
        List<SceneEvent> things = fresh.stream().filter(e -> !aboutSomeone(e)).toList();
        int someone = fresh.size() - things.size();
        if (things.size() > MAX_BRIEFED) things = things.subList(things.size() - MAX_BRIEFED, things.size());

        StringBuilder speech = new StringBuilder();
        if (things.size() > 1) speech.append(count(things.size(), "change")).append(". ");
        for (SceneEvent e : things) {
            speech.append("At ").append(clock(e.settled)).append(", ").append(describe(e)).append(". ");
            if (e.picture != null) viewport.showImage(e.picture, "Before and after, " + clock(e.settled));
        }
        if (someone > 0) {
            speech.append("Someone moved about in front of the camera")
                    .append(someone == 1 ? "" : " " + times(someone)).append(". ");
        }
        speech.append(movement().trim());

        reportedUpTo = fresh.get(fresh.size() - 1).id;
        lastBrief = LocalDateTime.now();
        return SkillResult.of(speech.toString().trim());
    }

    private static final Pattern PERSON = Pattern.compile(
            ".*\\b(person|people|man|woman|boy|girl|child|kid|guy|lady|human|face|head|hand|arm|finger|fingers|someone|shirt|hair)\\b.*");

    /** True when everything that changed was named as a person or part of one. */
    private static boolean aboutSomeone(SceneEvent e) {
        if (e.kind != SceneEvent.Kind.CHANGE || e.changes.isEmpty()) return false;
        return e.changes.stream().allMatch(c -> person(c.label()) || person(c.afterLabel()));
    }

    private static boolean person(String label) {
        return label != null && PERSON.matcher(label).matches();
    }

    private static String describe(SceneEvent e) {
        if (e.kind == SceneEvent.Kind.LIGHTING) return "the light changed, or the camera was moved";

        return String.join(", and ", e.changes.stream().map(WatchSkill::describe).toList());
    }

    private static String describe(SceneEvent.Change c) {
        String here = where(c.from.position());
        return switch (c.type) {
            case MOVED -> the(c.label()) + " was moved from the " + c.from.position() + " to the " + c.to.position();
            case REMOVED -> the(c.label()) + " " + here + " was taken away";
            case APPEARED -> a(c.label()) + " was put down " + here;
            case SHIFTED -> the(c.label()) + " " + here + " was nudged";
            case CHANGED -> {
                String was = c.label(), now = c.afterLabel();
                yield "something " + here + " changed"
                        + (was != null && now != null && !was.equals(now) ? ": it was " + a(was) + ", now " + a(now) : "");
            }
        };
    }

    /** Name the objects in an event's close-ups. Runs in the background as soon as the event settles. */
    private static void name(SceneEvent event) {
        for (SceneEvent.Change c : event.changes) {
            c.label(object(c.type == SceneEvent.Type.APPEARED ? c.after : c.before));
            if (c.type == SceneEvent.Type.CHANGED) c.afterLabel(object(c.after));
        }
    }

    private static String object(Path crop) {
        if (crop == null) return null;
        try {
            byte[] image = Files.readAllBytes(crop);
            // The stray token sometimes swallows the start of the answer ("urn of coffee" for "a cup of
            // coffee"). The model keeps the image after the first try, so asking again costs a second or two.
            for (int attempt = 0; attempt < 3; attempt++) {
                String name = objectName(VisionSkill.ask(NAME_PROMPT, image));
                if (name != null) return name;
            }
        } catch (Exception e) {
            // No vision model, or it failed: the briefing says "something" instead.
        }
        return null;
    }

    /** "The main object is a red mug with a white interior..." becomes "red mug"; null if the answer is unusable. */
    private static String objectName(String answer) {
        // Its answers often open with a no-break space, which trim() does not remove.
        String text = answer.replace('\u00a0', ' ').trim().toLowerCase()
                .replaceFirst("^urn\\b\\s*", "")
                .replaceFirst("^the main object (?:in (?:this|the) (?:picture|image|photo) )?is\\s*", "")
                .replaceFirst("^(?:a|an|the)\\s+", "");
        String name = text.split(OBJECT_ENDS)[0].trim();

        // Starting mid-phrase means the object's own name was lost; "i" is "I could not tell".
        if (name.isEmpty() || name.matches("^(?:of|and|or|i)\\b.*")) return null;

        String[] words = name.split("\\s+");
        return words.length > 5 ? String.join(" ", Arrays.copyOf(words, 5)) : name;
    }

    private void waitForNames(List<SceneEvent> events) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(NAMING_WAIT_SECONDS);
        for (SceneEvent e : events) {
            Future<?> job = naming.get(e.id);
            if (job == null) continue;
            try {
                job.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException ex) {
                // Brief with whatever has been named so far.
            }
        }
    }

    private String movement() {
        int m = watcher.motions();
        return m == 0 ? "" : " There was also movement " + times(m) + " that left nothing changed.";
    }

    private static String where(String position) {
        if (position.equals("middle")) return "in the middle";
        if (position.equals("left") || position.equals("right")) return "on the " + position;
        return "at the " + position;
    }

    private static String the(String label) {
        return label == null ? "something" : "the " + label;
    }

    private static String a(String label) {
        if (label == null) return "something";
        return ("aeiou".indexOf(label.charAt(0)) >= 0 ? "an " : "a ") + label;
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String times(int n) {
        return n == 1 ? "once" : n == 2 ? "twice" : n + " times";
    }

    private static String clock(LocalDateTime time) {
        return time == null ? "earlier" : time.format(CLOCK);
    }

    private static String tidy(String input) {
        return input.trim().toLowerCase()
                .replaceAll("[?.!]+$", "")
                .replaceFirst("^(hey|ok|okay),?\\s+", "")
                .trim();
    }
}
