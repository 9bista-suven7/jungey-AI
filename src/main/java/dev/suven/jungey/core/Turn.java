package dev.suven.jungey.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Which request Jungey is answering now.
 *
 * <p>Every new command starts a turn, and so does being addressed by name mid-reply.
 * Work still going on for an older turn - a model halfway through a paragraph - is then
 * superseded: it stops speaking and stops generating, rather than carrying on underneath
 * the new answer so that two replies take turns sentence by sentence.
 */
public final class Turn {

    private static final AtomicLong latest = new AtomicLong();

    /** The turn a worker thread is busy with; unset on threads not working for one. */
    private static final ThreadLocal<Long> owner = new ThreadLocal<>();

    private Turn() {
    }

    /** Start a new turn, superseding whatever is still under way. */
    public static long begin() {
        return latest.incrementAndGet();
    }

    public static long latest() {
        return latest.get();
    }

    /** The turn the calling thread is working for, or the latest when it is not working for one. */
    public static long mine() {
        Long t = owner.get();
        return t == null ? latest.get() : t;
    }

    public static boolean superseded(long turn) {
        return turn != latest.get();
    }

    static void adopt(long turn) {
        owner.set(turn);
    }

    static void release() {
        owner.remove();
    }
}
