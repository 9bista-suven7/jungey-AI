package dev.suven.jungey.skills;

import dev.suven.jungey.core.Config;
import dev.suven.jungey.core.Sentinel;
import dev.suven.jungey.core.Skill;
import dev.suven.jungey.core.SkillResult;
import dev.suven.jungey.core.SysInfo;
import dev.suven.jungey.core.Viewport;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * The small things people say to an assistant - thanks, hello, are you there - answered at
 * once, in character, without waiting on a model.
 *
 * <p>Half of seeming alive is answering "thank you" in a quarter of a second. Sent to the
 * model, it took three and came back as a paragraph.
 *
 * <p>This is also where a conversation is opened and closed: "stay with me" keeps the
 * microphone open between sentences, "that's all" puts it back to waiting for the name.
 */
public class ChatterSkill implements Skill {

    private static final String NAME = "(?: (?:jungey|jarvis))?";

    private static final Pattern THANKS = Pattern.compile(
            "^(?:thanks|thank you|thank you very much|thanks a lot|many thanks|cheers|much appreciated|ta)"
                    + NAME + "(?: very much)?$");
    private static final Pattern HELLO = Pattern.compile(
            "^(?:hello|hi|hey|hiya|hey there|hi there|hello there|yo|howdy)" + NAME + "$");
    private static final Pattern PRESENCE = Pattern.compile(
            "^(?:are you (?:there|awake|up|listening|online|with me|still there)|you there|still there"
                    + "|jungey|jarvis|you up|wake up|wake up daddy'?s home|daddy'?s home)$");
    private static final Pattern HOW = Pattern.compile(
            "^(?:how are you(?: doing| today| feeling)?|how'?s it going|how are things|how do you feel"
                    + "|you (?:okay|ok|alright)|are you (?:okay|ok|alright)|how are you holding up)" + NAME + "$");
    private static final Pattern WHO = Pattern.compile(
            "^(?:who are you|what are you|what'?s your name|what is your name|introduce yourself"
                    + "|tell me about yourself|who made you|who built you)$");
    private static final Pattern BACK = Pattern.compile(
            "^(?:i'?m back|i am back|i'?m home|i am home|honey i'?m home|back again|i have returned|guess who'?s back)$");
    private static final Pattern NIGHT = Pattern.compile(
            "^(?:good ?night|night night|nighty night|i'?m (?:going|off) to (?:bed|sleep)|going to bed|time for bed)"
                    + NAME + "$");
    private static final Pattern PRAISE = Pattern.compile(
            "^(?:you'?re|you are) (?:the best|amazing|awesome|brilliant|great|a genius|a legend|a star|so smart)$"
                    + "|^(?:nice work|well done|great work|nicely done|good work)" + NAME + "$");
    private static final Pattern STAY = Pattern.compile(
            "^(?:stay with me|conversation mode|let'?s (?:talk|chat)|keep listening|stay (?:on|awake|listening)"
                    + "|talk to me|chat mode)$");
    private static final Pattern DISMISS = Pattern.compile(
            "^(?:that'?s all|that is all|that will be all|that'?ll be all|that'?s it|that'?s everything"
                    + "|never ?mind|forget it|dismissed|as you were|stand by|go to sleep|sleep|we'?re done|i'?m done"
                    + "|goodbye|good bye|bye|bye bye|see you|see you later|later|over and out)(?: for now)?" + NAME + "$");

    private final Viewport viewport;
    private final Sentinel sentinel;

    public ChatterSkill(Viewport viewport, Sentinel sentinel) {
        this.viewport = viewport;
        this.sentinel = sentinel;
    }

    @Override
    public String name() {
        return "chatter";
    }

    @Override
    public String description() {
        return "Small talk, and opening or closing a conversation.";
    }

    @Override
    public String[] examples() {
        return new String[]{"thank you", "are you there", "I'm back", "stay with me", "that's all"};
    }

    @Override
    public int priority() {
        return 7;
    }

    @Override
    public boolean matches(String input) {
        String s = tidy(input);
        return THANKS.matcher(s).matches() || HELLO.matcher(s).matches() || PRESENCE.matcher(s).matches()
                || HOW.matcher(s).matches() || WHO.matcher(s).matches() || BACK.matcher(s).matches()
                || NIGHT.matcher(s).matches() || PRAISE.matcher(s).matches() || STAY.matcher(s).matches()
                || DISMISS.matcher(s).matches();
    }

    @Override
    public SkillResult run(String input) {
        String s = tidy(input);
        Config cfg = Config.get();
        String sir = cfg.honorific();
        String name = cfg.userName();

        if (STAY.matcher(s).matches()) {
            if (!viewport.converse(true)) {
                return SkillResult.of("I would, " + sir + ", but I have nothing to listen with. "
                        + "A microphone and scripts/setup-ears.sh would fix that.");
            }
            return SkillResult.of(pick("I'm all ears, " + sir + ". Say \"that's all\" when we're done.",
                    "Go ahead, I'm listening. No need for my name until you say \"that's all\"."));
        }
        if (DISMISS.matcher(s).matches()) {
            viewport.converse(false);
            return SkillResult.of(pick("Very good, " + sir + ".", "As you wish.", "I'll be here.",
                    "Standing by.", "Of course. Call if you need me."));
        }
        if (THANKS.matcher(s).matches()) {
            return SkillResult.of(pick("You're welcome, " + sir + ".", "Always a pleasure.", "Anytime.",
                    "Happy to help.", "Don't mention it."));
        }
        if (HELLO.matcher(s).matches()) {
            return SkillResult.of(pick("Hello, " + name + ". What can I do for you?",
                    "Hello, " + sir + ".", "Hi. What do you need?", "Good to hear from you, " + name + "."));
        }
        if (PRESENCE.matcher(s).matches()) {
            if (s.startsWith("wake up") || s.startsWith("daddy")) {
                return SkillResult.of(pick("I never sleep, " + sir + ". I merely wait.",
                        "Welcome home, " + sir + ". All systems are up."));
            }
            return SkillResult.of(pick("At your service, " + sir + ".", "For you, " + sir + ", always.",
                    "Right here.", "Never left, " + sir + "."));
        }
        if (HOW.matcher(s).matches()) return howAmI(sir);
        if (WHO.matcher(s).matches()) {
            return SkillResult.of("Jungey, " + sir + ". Just a Unified Neural Gateway Engineered for You. "
                    + "I run on this machine, answer to you, and keep an eye on things while you work.");
        }
        if (BACK.matcher(s).matches()) {
            List<String> worries = sentinel.concerns();
            return SkillResult.of("Welcome back, " + sir + ". " + (worries.isEmpty()
                    ? pick("All quiet while you were away.", "Nothing needed you while you were gone.")
                    : "One thing: " + worries.get(0) + "."));
        }
        if (NIGHT.matcher(s).matches()) {
            return SkillResult.of(pick("Good night, " + sir + ". I'll keep watch.",
                    "Sleep well, " + name + ".", "Good night. I'll hold the fort."));
        }
        return SkillResult.of(pick("I do try, " + sir + ".", "Flattery will get you everywhere.",
                "Thank you, " + sir + ". I'll try not to let it go to my head."));
    }

    /** "How are you" answered from the machine's actual state, which is the honest answer. */
    private static SkillResult howAmI(String sir) {
        double load = SysInfo.loadPerCore();
        double heat = SysInfo.temperatureC();
        if (load >= 0.9) {
            return SkillResult.of("A little stretched, " + sir + ". The processor is flat out, but I'm coping.");
        }
        if (heat >= 85) {
            return SkillResult.of(String.format("Warm, frankly. The processor is at %.0f degrees. Otherwise fine.", heat));
        }
        return SkillResult.of(pick("All systems nominal, " + sir + ". Thank you for asking.",
                String.format("Running at %.0f percent and feeling rather relaxed.", Math.min(100, load * 100)),
                "Very well, " + sir + ". Better for being asked."));
    }

    private static String pick(String... options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private static String tidy(String input) {
        return input.toLowerCase().replace('’', '\'').replaceAll("[^a-z0-9' ]", " ").trim().replaceAll("\\s+", " ");
    }
}
