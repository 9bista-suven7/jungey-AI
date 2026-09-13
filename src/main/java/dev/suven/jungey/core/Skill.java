package dev.suven.jungey.core;

/**
 * One thing Jungey knows how to do.
 *
 * <p>Skills are asked in priority order whether they can handle an utterance.
 * The first that says yes gets to run. Keep {@link #matches} cheap - it runs on
 * the UI thread - and do the real work in {@link #run}, which runs on a worker.
 */
public interface Skill {

    /** Short id, e.g. "weather". Used in logs and in the help listing. */
    String name();

    /** One line describing what this skill does, shown by "help". */
    String description();

    /** Example phrasings shown by "help". */
    String[] examples();

    /**
     * @param input the user's utterance, already lowercased and trimmed
     * @return true if this skill wants to handle it
     */
    boolean matches(String input);

    /**
     * Do the work. Runs off the UI thread, so blocking network calls are fine here.
     *
     * @param input the user's original utterance, with case preserved
     */
    SkillResult run(String input) throws Exception;

    /** Lower runs earlier. Local/instant skills should sort before network ones. */
    default int priority() {
        return 100;
    }
}
