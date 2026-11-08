package dev.oqthara.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The total time one program may spend waiting.
 *
 * <p>The per-call cap alone bounds a single {@code sleep} but not a loop of them, so a program
 * could drip ten seconds at a time forever. This counter is reset with the step budget and is
 * separate from it: time is not the same resource as computation, and billing a millisecond as
 * one step let one program wait nearly fourteen hours.
 */
public final class WaitedBudget {
    /** The most one program may wait in total, in milliseconds. */
    public static final long MAX_MILLIS = 60_000;

    private static final AtomicLong waited = new AtomicLong();

    private WaitedBudget() {
    }

    /** Records one wait, refusing it when the program has already waited long enough. */
    public static void charge(long millis, Span span) {
        long total = waited.addAndGet(Math.max(0, millis));
        if (total > MAX_MILLIS) {
            throw new OqError("the program waited for too long", span,
                    "A program may wait " + MAX_MILLIS / 1000
                            + " seconds in total; this one has reached the limit.");
        }
    }

    /** Starts a fresh program's wait budget. */
    public static void reset() {
        waited.set(0);
    }
}
