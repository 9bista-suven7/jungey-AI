package dev.suven.jungey.core;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * What has just happened, across every skill.
 *
 * <p>The model only ever saw its own side of the conversation, so after "open firefox"
 * it had no idea Firefox had been opened, and "why did that take so long?" meant nothing
 * to it. Every exchange lands here, whichever skill answered it, and the model is shown
 * the recent ones - which is most of what makes an assistant seem to be paying attention.
 */
public final class Context {

    public record Exchange(LocalDateTime at, String input, String skill, String reply) {
    }

    private static final int KEEP = 12;

    private final Deque<Exchange> recent = new ArrayDeque<>();

    public synchronized void record(String input, String skill, SkillResult result) {
        String reply = result.speech() == null ? "" : result.speech().replaceAll("\\s+", " ").trim();
        if (reply.length() > 200) reply = reply.substring(0, 200) + "…";
        recent.addLast(new Exchange(LocalDateTime.now(), input, skill, reply));
        while (recent.size() > KEEP) recent.removeFirst();
    }

    /** Exchanges from the last so long, oldest first, leaving out one skill's own. */
    public synchronized List<Exchange> since(Duration window, String exceptSkill) {
        LocalDateTime cutoff = LocalDateTime.now().minus(window);
        return recent.stream()
                .filter(e -> e.at().isAfter(cutoff))
                .filter(e -> !e.skill().equals(exceptSkill))
                .toList();
    }
}
