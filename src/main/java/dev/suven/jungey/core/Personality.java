package dev.suven.jungey.core;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jungey's voice. Dry, clipped, quietly amused - the register a very expensive
 * assistant uses when it already knows the answer and is waiting for you to catch up.
 */
public final class Personality {

    private Personality() {
    }

    private static String pick(List<String> options) {
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }

    /** Time-aware greeting used once, at the end of the boot sequence. */
    public static String greeting() {
        Config cfg = Config.get();
        String name = cfg.userName();
        int hour = LocalTime.now().getHour();

        String partOfDay;
        if (hour < 5) partOfDay = "You are up late";
        else if (hour < 12) partOfDay = "Good morning";
        else if (hour < 17) partOfDay = "Good afternoon";
        else if (hour < 22) partOfDay = "Good evening";
        else partOfDay = "Working late again";

        return pick(List.of(
                partOfDay + ", " + name + ". All systems nominal.",
                partOfDay + ", " + name + ". I have been expecting you.",
                partOfDay + ", " + name + ". Everything is exactly where you left it.",
                partOfDay + ", " + name + ". Standing by."
        ));
    }

    /** Said while a slow skill is working. */
    public static String thinking() {
        return pick(List.of(
                "Working on it.",
                "One moment.",
                "Let me look.",
                "Checking now.",
                "Give me a second."
        ));
    }

    /** Said when nothing matched and no LLM is available. */
    public static String confused() {
        String sir = Config.get().honorific();
        return pick(List.of(
                "I did not follow that, " + sir + ". Try \"help\" for what I can do.",
                "That one is beyond me for now. \"help\" lists my current skills.",
                "I have no skill for that yet. Ask me to \"help\" and I will show you what I do have."
        ));
    }

    /** Acknowledgement before acting. */
    public static String affirm() {
        return pick(List.of("Right away.", "Of course.", "On it.", "Consider it done."));
    }

    public static String farewell() {
        return "Powering down. Do try to sleep, " + Config.get().userName() + ".";
    }

    /** Lines printed one by one during startup. */
    public static List<String> bootSequence() {
        return List.of(
                "JUNGEY  v0.1.0",
                "Just a Unified Neural Gateway Engineered for You",
                "",
                "Initialising core .................. ok",
                "Mounting skill modules ............. ok",
                "Calibrating sensors ................ ok",
                "Establishing network link .......... ok"
        );
    }
}
