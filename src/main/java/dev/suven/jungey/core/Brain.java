package dev.suven.jungey.core;

import dev.suven.jungey.skills.*;
import dev.suven.jungey.voice.Speaker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Routes an utterance to whichever skill claims it.
 *
 * <p>The routing is deliberately three-tier: cheap local pattern matches win first
 * so "time" or "cpu" answer instantly, network skills come next, and the LLM is the
 * catch-all that only runs when nothing structured matched. That ordering is what
 * keeps Jungey feeling immediate instead of waiting on a model for "what time is it".
 */
public final class Brain {

    private final List<Skill> skills = new ArrayList<>();
    private final Journal journal = new Journal();
    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "jungey-worker");
        t.setDaemon(true);
        return t;
    });

    public Brain(Speaker speaker, Viewport viewport) {
        // Tier 1 - local, instant.
        register(new TimeSkill());
        register(new MathSkill());
        register(new VoiceSkill(speaker));
        register(new TimerSkill(viewport));
        register(new NoteSkill());
        register(new DiagnosticsSkill());
        register(new SystemSkill());
        register(new NetworkSkill());
        register(new SystemControlSkill());
        register(new UpdateSkill());
        register(new OcrSkill());
        register(new CameraSkill(viewport));
        register(new ScreenshotSkill(viewport));
        register(new WindowSkill());
        register(new AppLauncherSkill());
        register(new FileSearchSkill());
        register(new HelpSkill(this));
        register(new TrainingSkill(journal));

        // Tier 2 - online.
        register(new WeatherSkill());
        register(new WikipediaSkill());
        register(new NewsSkill());

        // Tier 3 - catch-all. Must sort last.
        LlmSkill llm = new LlmSkill(viewport);
        register(new TranslateSkill(viewport, llm));
        register(new ClipboardSkill(viewport, llm));
        register(new VisionSkill(viewport));
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

    /**
     * Find the handler for an utterance and run it off the UI thread.
     *
     * @return a future completing with the result; never completes exceptionally,
     *         since a thrown skill is reported to the user as a failed result
     */
    public CompletableFuture<SkillResult> handle(String input) {
        String normalised = input == null ? "" : input.trim().toLowerCase();

        if (normalised.isEmpty()) {
            return CompletableFuture.completedFuture(SkillResult.of("I am listening."));
        }

        Skill chosen = null;
        for (Skill s : skills) {
            try {
                if (s.matches(normalised)) {
                    chosen = s;
                    break;
                }
            } catch (RuntimeException e) {
                // A broken matcher must never take the whole router down.
                System.err.println("[jungey] skill " + s.name() + " threw while matching: " + e);
            }
        }

        if (chosen == null) {
            return CompletableFuture.completedFuture(SkillResult.of(Personality.confused()));
        }

        final Skill skill = chosen;
        return CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            SkillResult result;
            try {
                result = skill.run(input.trim());
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                result = SkillResult.error(skill.name() + " failed: " + msg);
            }

            // Feedback about an exchange is not itself an exchange worth learning from.
            if (!(skill instanceof TrainingSkill)) {
                journal.record(input.trim(), skill.name(), result, modelFor(skill),
                        (System.nanoTime() - started) / 1_000_000);
            }
            return result;
        }, pool);
    }

    /** Which model wrote a reply, so data from before and after a model change can be told apart. */
    private static String modelFor(Skill skill) {
        Config cfg = Config.get();
        return switch (skill.name()) {
            case "converse", "translate", "clipboard" -> cfg.str("llm.model", "llama3.2:3b");
            case "vision" -> cfg.str("llm.visionModel", "moondream");
            default -> null;
        };
    }

    /** True if this utterance will need the network or a model, so the HUD can show "thinking". */
    public boolean isSlow(String input) {
        String n = input.trim().toLowerCase();
        for (Skill s : skills) {
            if (s.matches(n)) {
                return s.priority() >= 200;
            }
        }
        return false;
    }

    public void shutdown() {
        pool.shutdownNow();
        journal.close();
    }
}
