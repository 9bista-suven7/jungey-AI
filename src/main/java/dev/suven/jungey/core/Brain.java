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
    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "jungey-worker");
        t.setDaemon(true);
        return t;
    });

    public Brain(Speaker speaker, Viewport viewport) {
        // Tier 1 - local, instant.
        register(new TimeSkill());
        register(new VoiceSkill(speaker));
        register(new SystemSkill());
        register(new CameraSkill(viewport));
        register(new ScreenshotSkill(viewport));
        register(new AppLauncherSkill());
        register(new HelpSkill(this));

        // Tier 2 - online.
        register(new WeatherSkill());
        register(new WikipediaSkill());
        register(new NewsSkill());

        // Tier 3 - catch-all. Must sort last.
        register(new LlmSkill());

        skills.sort(Comparator.comparingInt(Skill::priority));
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
            try {
                return skill.run(input.trim());
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return SkillResult.error(skill.name() + " failed: " + msg);
            }
        }, pool);
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
    }
}
