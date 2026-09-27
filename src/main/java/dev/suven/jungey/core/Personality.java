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

    /** Said while a slow skill is working - aloud when the question was spoken. */
    public static String thinking() {
        return pick(THINKING);
    }

    private static final List<String> THINKING = List.of(
            "One moment.",
            "Let me see.",
            "Let me check.",
            "Give me a second.",
            "Hmm, let me think."
    );

    /**
     * Said when a new request cuts across one still being answered: the small noise a
     * person makes as they let go of one thought and turn to the next, so the change of
     * subject sounds like listening rather than like a tape being stopped.
     */
    public static String transition() {
        return pick(TRANSITIONS);
    }

    private static final List<String> TRANSITIONS = List.of(
            "Oh, sure.",
            "Right, okay.",
            "Mm, got it.",
            "Of course.",
            "Ah, right.",
            "Sure thing.",
            "Okay, switching gears."
    );

    /** Said when Jungey is called to the front - Super+J, or opening it while it runs. */
    public static String summoned() {
        String sir = Config.get().honorific();
        return pick(List.of("Yes, " + sir + "?", sir.substring(0, 1).toUpperCase() + sir.substring(1) + "?",
                "At your service.", "I'm here."));
    }

    /** Every stock line worth having ready as audio before it is needed. */
    public static List<String> stockLines() {
        List<String> all = new java.util.ArrayList<>(TRANSITIONS);
        all.addAll(THINKING);
        return all;
    }

    /** Drop a leading "Right away." - once is enough when several things are done in a row. */
    public static String withoutAffirmation(String line) {
        return line.replaceFirst("^(Right away|Of course|On it|Consider it done)\\.\\s*", "");
    }

    /**
     * Turn the user's words round to be said back to them: "my car is on level 3" becomes
     * "your car is on level 3".
     */
    public static String reflect(String text) {
        StringBuilder out = new StringBuilder();
        String previous = "";
        for (String word : text.split(" ")) {
            String bare = word.toLowerCase(java.util.Locale.ENGLISH).replaceAll("[^a-z']", "");
            String tail = word.substring(Math.min(word.length(), word.replaceAll("[^A-Za-z']+$", "").length()));
            String swapped = switch (bare) {
                case "i" -> "you";
                case "me" -> "you";
                case "my" -> "your";
                case "mine" -> "yours";
                case "myself" -> "yourself";
                case "i'm" -> "you're";
                case "i've" -> "you've";
                case "i'll" -> "you'll";
                case "i'd" -> "you'd";
                case "am" -> previous.equals("i") ? "are" : null;
                case "was" -> previous.equals("i") ? "were" : null;
                default -> null;
            };
            if (!out.isEmpty()) out.append(' ');
            out.append(swapped == null ? word : swapped + tail);
            previous = bare;
        }
        return out.toString();
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
                "JUNGEY  v" + Build.version(),
                "Just a Unified Neural Gateway Engineered for You",
                "",
                "Initialising core .................. ok",
                "Mounting skill modules ............. ok",
                "Calibrating sensors ................ ok",
                "Establishing network link .......... ok",
                "Sentinel on watch .................. ok"
        );
    }
}
