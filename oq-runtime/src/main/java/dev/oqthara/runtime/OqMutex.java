package dev.oqthara.runtime;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A mutual-exclusion lock for read-modify-write across tasks (ADR-0039, Decision 5).
 *
 * <p>The lock is deliberately not reentrant. Two subtasks that both lock it would otherwise
 * succeed silently and produce the very interleaving the mutex exists to prevent, so a second
 * acquisition by the same thread raises instead of waiting for itself.
 */
public final class OqMutex {
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @return whether the lock is free at this instant. Advisory only: another task may take
     *     it before the caller acts on the answer, so it is for diagnostics, not for control.
     */
    public boolean isFree() {
        return !lock.isLocked();
    }

    /**
     * Acquires the lock, runs {@code body}, and releases it however the body ends.
     *
     * <p>The release is in a {@code finally} on the JVM side because the language has no
     * {@code finally}: a body that raises would otherwise leave the mutex held forever, and a
     * permanently held mutex is a hang with no diagnostic.
     *
     * @param body a callback the caller supplies; it runs on the acquiring thread
     * @param span the call site, for the re-entry error
     * @return whatever the body returned
     */
    public Object lock(OqHost host, OqCallable body, Span span) {
        // Checked before acquiring, not after a failed one: ReentrantLock grants a second
        // acquisition to the same thread, so a nested lock would silently succeed and produce
        // exactly the interleaving this exists to prevent.
        if (lock.isHeldByCurrentThread()) {
            throw new OqError("this mutex is already held by this task", span,
                    "A mutex is not reentrant. Take it once, around the whole "
                            + "read-modify-write, instead of nesting two locked calls.");
        }
        lock.lock();
        try {
            return host.call(body, java.util.List.of(), span);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return isFree() ? "<mutex free>" : "<mutex held>";
    }
}
