package dev.oqthara.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The one output ceiling shared by the interpreter and by compiled bytecode.
 *
 * <p>Both backends write to the same stream, so they must bill the same counter. When each kept
 * its own, a program could fill one to its limit and then repeat that through the other, emitting
 * several times the documented cap without ever tripping it.
 */
public final class OutputBudget {
    /** The most one program may write, in UTF-8 bytes. */
    public static final long MAX_BYTES = 16L * 1024 * 1024;

    private static final AtomicLong WRITTEN = new AtomicLong();

    private OutputBudget() {
    }

    /**
     * Bills {@code bytes} before they are written, so a refused call emits nothing.
     *
     * @throws OqError when the program has gone over the ceiling
     */
    public static void charge(long bytes) {
        if (bytes <= 0) return;
        long total = WRITTEN.addAndGet(bytes);
        if (total > MAX_BYTES) {
            throw new OqError("the program wrote too much output", Span.synthetic(),
                    "It wrote more than " + MAX_BYTES + " bytes. Write less, or send the data"
                            + " to a file instead.");
        }
    }

    /** Discards the count, so each run starts with a full ceiling. */
    public static void reset() {
        WRITTEN.set(0);
    }
}
