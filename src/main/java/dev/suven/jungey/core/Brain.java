package dev.suven.jungey.core;

import dev.suven.jungey.skills.*;
import dev.suven.jungey.voice.Speaker;
import dev.suven.jungey.watch.SceneWatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Routes an utterance to whichever skill claims it.
 *
 * <p>The routing is deliberately three-tier: cheap local pattern matches win first
 * so "time" or "cpu" answer instantly, network skills come next, and the LLM is the
 * catch-all that only runs when nothing structured matched. That ordering is what
 * keeps Jungey feeling immediate instead of waiting on a model for "what time is it".
 *
 * <p>Two things happen before routing. Jungey's name is taken off the front, so
 * "Jungey, open firefox" is just "open firefox". And a request made of several that
 * each have a skill - "open firefox and set a timer for ten minutes" - is carried out
 * one after the other, as a person would, instead of being handed whole to the first.
 */
public final class Brain {

    private final List<Skill> skills = new ArrayList<>();
    private final Journal journal = new Journal();
    private final SceneWatcher watcher = new SceneWatcher();
    private final Context context = new Context();
    private final Memory memory = new Memory();
    private final Sentinel sentinel;
    private final BriefingSkill briefing;
    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "jungey-worker");
        t.setDaemon(true);
        return t;
    });

    /** Where one request ends and the next begins: "…, and then …", "… then …", "…; …". */
    private static final Pattern JOIN = Pattern.compile(
            "\\s*;\\s*|,?\\s+(?:and then|and also|then|and)\\s+", Pattern.CASE_INSENSITIVE);

    public Brain(Speaker speaker, Viewport viewport) {
        sentinel = new Sentinel(viewport);
        TimerSkill timers = new TimerSkill(viewport);
        WeatherSkill weather = new WeatherSkill();
        briefing = new BriefingSkill(weather, timers, sentinel);

        // Tier 1 - local, instant.
        register(briefing);
        register(new ChatterSkill(viewport, sentinel));
        register(new MemorySkill(memory));
        register(new TvSkill(viewport));
        register(new AlertsSkill(sentinel));
        register(new ProtocolSkill(this));
        register(new TimeSkill());
        register(new MathSkill());
        register(new VoiceSkill(speaker));
        register(timers);
        register(new NoteSkill());
        register(new DiagnosticsSkill());
        register(new SystemSkill());
        register(new NetworkSkill());
        register(new SystemControlSkill());
        register(new UpdateSkill());
        register(new OcrSkill());
        register(new CameraSkill(viewport, watcher));
        register(new WatchSkill(viewport, watcher));
        register(new ScreenshotSkill(viewport));
        register(new WindowSkill());
        register(new AppLauncherSkill());
        register(new FileSearchSkill());
        register(new HelpSkill(this));
        register(new TrainingSkill(journal));

        // Tier 2 - online.
        register(weather);
        register(new WikipediaSkill());
        register(new NewsSkill());

        // Tier 3 - catch-all. Must sort last.
        LlmSkill llm = new LlmSkill(viewport, context, memory);
        register(new TranslateSkill(viewport, llm));
        register(new ClipboardSkill(viewport, llm));
        register(new VisionSkill(viewport, watcher));
        register(llm);

        skills.sort(Comparator.comparingInt(Skill::priority));

        // Loading the model is the slowest thing Jungey ever waits for, so start it now.
        pool.submit(llm::warmUp);
    }

    public void register(Skill skill) {
        skills.add(skill);
    }

    public List<Skill> skills() {
        return List.copyOf(skills);
    }

    /** Start keeping an eye on the machine. Called once the boot sequence is over. */
    public void startSentinel() {
        sentinel.start();
    }

    /**
     * Find the handler for an utterance and run it off the UI thread. Starts a new
     * {@link Turn}, so anything still answering an earlier request stops.
     *
     * @return a future completing with the result; never completes exceptionally,
     *         since a thrown skill is reported to the user as a failed result
     */
    public CompletableFuture<SkillResult> handle(String input) {
        long turn = Turn.begin();
        String text = unaddressed(input == null ? "" : input.trim());

        if (text.isEmpty()) {
            return CompletableFuture.completedFuture(SkillResult.of("I am listening."));
        }

        List<String> steps = steps(text);
        if (steps.size() > 1) {
            return CompletableFuture.supplyAsync(() -> as(turn, () -> runAll(steps, turn)), pool);
        }

        Skill chosen = route(text);
        if (chosen == null) {
            return CompletableFuture.completedFuture(SkillResult.of(Personality.confused()));
        }
        return CompletableFuture.supplyAsync(() -> as(turn, () -> execute(chosen, text)), pool);
    }

    /** The briefing given at start-up. Not an exchange anyone asked for, so it is not journaled. */
    public CompletableFuture<SkillResult> bootBriefing(boolean greet) {
        return CompletableFuture.supplyAsync(() -> {
            SkillResult r = briefing.brief(greet);
            context.record("(start-up)", briefing.name(), r);
            return r;
        }, pool);
    }

    /** The name was said mid-reply: whatever was being answered is no longer wanted. */
    public void interrupt() {
        Turn.begin();
    }

    /** The skill that would answer this, or null for none. Cheap; safe on the UI thread. */
    public Skill route(String input) {
        String normalised = unaddressed(input == null ? "" : input.trim()).toLowerCase();
        if (normalised.isEmpty()) return null;

        for (Skill s : skills) {
            try {
                if (s.matches(normalised)) return s;
            } catch (RuntimeException e) {
                // A broken matcher must never take the whole router down.
                System.err.println("[jungey] skill " + s.name() + " threw while matching: " + e);
            }
        }
        return null;
    }

    /** Run one skill here and now, on the calling thread, and journal the exchange. */
    public SkillResult execute(Skill skill, String input) {
        long started = System.nanoTime();
        SkillResult result;
        try {
            result = skill.run(input.trim());
            if (result == null) result = SkillResult.error(skill.name() + " returned nothing.");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result = SkillResult.error(skill.name() + " failed: " + msg);
        }

        // Feedback about an exchange is not itself an exchange worth learning from.
        if (!(skill instanceof TrainingSkill)) {
            journal.record(input.trim(), skill.name(), result, modelFor(skill),
                    (System.nanoTime() - started) / 1_000_000);
            context.record(input.trim(), skill.name(), result);
        }
        return result;
    }

    private static <T> T as(long turn, java.util.function.Supplier<T> work) {
        Turn.adopt(turn);
        try {
            return work.get();
        } finally {
            Turn.release();
        }
    }

    /**
     * The separate requests in an utterance, or the utterance alone. It is only split when
     * every piece is something a skill knows how to do, so "remind me to call mum and dad"
     * stays one reminder, and "what is salt and pepper" stays one question.
     */
    List<String> steps(String text) {
        String[] parts = JOIN.split(text);
        if (parts.length < 2) return List.of(text);

        List<String> steps = new ArrayList<>();
        for (String part : parts) {
            String step = part.trim();
            Skill s = step.isEmpty() ? null : route(step);
            if (s == null || s.priority() >= 9000) return List.of(text);
            steps.add(step);
        }
        return steps;
    }

    /** Several requests, one after another, answered as one reply. */
    private SkillResult runAll(List<String> steps, long turn) {
        List<String> said = new ArrayList<>();
        List<String> streamed = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        boolean anyOk = false;

        for (String step : steps) {
            if (Turn.superseded(turn)) break;
            Skill skill = route(step);
            SkillResult r = skill == null ? SkillResult.error("I did not follow \"" + step + "\".")
                    : execute(skill, step);
            anyOk |= r.ok();

            if (r.streamed()) {
                streamed.add(r.speech());
            } else if (!r.speech().isBlank()) {
                String line = said.isEmpty() ? r.speech().trim() : Personality.withoutAffirmation(r.speech().trim());
                // "4" and "9" said back to back is one number; a full stop keeps them two.
                said.add(line.matches(".*[.!?]$") ? line : line + ".");
            }
            if (r.hasDetail()) detail.append("› ").append(step).append('\n').append(r.detail()).append("\n\n");
        }

        if (said.isEmpty() && !streamed.isEmpty()) return SkillResult.streamed(String.join(" ", streamed));
        return new SkillResult(String.join(" ", said), detail.toString().trim(), anyOk || said.isEmpty());
    }

    /**
     * Take Jungey's name off the front ("Jungey, open firefox", "hey jarvis, …") and off
     * the end after a comma ("thanks, Jungey"). A name said on its own is left alone,
     * since "hey Jungey" is itself something to answer.
     */
    static String unaddressed(String text) {
        String wake = Pattern.quote(Config.get().str("voice.input.wakeWord", "purple").trim());
        String stripped = text
                .replaceFirst("(?i)^(?:(?:hey|ok|okay|hi|yo)\\s+)?(?:jungey|jarvis|" + wake + ")\\b[\\s,.:!?-]*", "")
                .replaceFirst("(?i),\\s*(?:jungey|jarvis)[.!?]*$", "")
                .trim();
        return stripped.isEmpty() ? text : stripped;
    }

    /** Which model wrote a reply, so data from before and after a model change can be told apart. */
    private static String modelFor(Skill skill) {
        Config cfg = Config.get();
        return switch (skill.name()) {
            case "converse", "translate", "clipboard" -> cfg.str("llm.model", "llama3.2:3b");
            case "vision", "watch" -> cfg.str("llm.visionModel", "moondream");
            default -> null;
        };
    }

    /** True if this utterance will need the network or a model, so the HUD can show "thinking". */
    public boolean isSlow(String input) {
        String text = unaddressed(input.trim());
        for (String step : steps(text)) {
            Skill s = route(step);
            if (s != null && s.priority() >= 200) return true;
        }
        return false;
    }

    public void shutdown() {
        pool.shutdownNow();
        sentinel.stop();
        watcher.stop();
        journal.close();
    }
}
