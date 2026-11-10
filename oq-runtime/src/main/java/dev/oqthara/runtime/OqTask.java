package dev.oqthara.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A handle to work running on a virtual thread. {@code await()} blocks until the work
 * finishes and returns its value, re-raising any Oqthara error in the calling context.
 */
public final class OqTask {
    private final CompletableFuture<Object> future;

    public OqTask(CompletableFuture<Object> future) {
        this.future = future;
    }

    public boolean isDone() {
        return future.isDone();
    }

    public Object await(Span span) {
        long start = System.nanoTime();
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OqError("interrupted while waiting for a task", span);
        } catch (ExecutionException e) {
            throw rethrow(e, span);
        } finally {
            // Charged after the wait because only the wait itself can be measured; charging
            // nothing let an unbounded await loop sleep forever without touching the budget.
            WaitedBudget.charge(elapsedMillis(start), span);
        }
    }

    /**
     * Waits at most {@code millis} and raises if the task is still running (ADR-0039, D3).
     *
     * <p>The task is <em>not</em> cancelled. The interpreter checks for interruption nowhere,
     * so a cancelled loop would keep running and keep mutating shared state after the caller
     * had been told it stopped. Reporting a stop that cannot be enforced is worse than
     * reporting a timeout.
     */
    public Object awaitWithin(long millis, Span span) {
        // The timeout is a ceiling, not a cost. Charging it up front spent the whole budget on
        // tasks that had already finished: eight awaits of instant work with a 10-second limit
        // reported "waited for too long" in microseconds.
        long start = System.nanoTime();
        try {
            if (future.isDone()) return future.get();
            return future.get(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new OqError("the task did not finish within "
                    + (millis / 1000.0) + " seconds", span,
                    "It is still running; await() without a limit waits for it. The task is "
                            + "not cancelled, and any work it does continues.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OqError("interrupted while waiting for a task", span);
        } catch (ExecutionException e) {
            throw rethrow(e, span);
        } finally {
            WaitedBudget.charge(elapsedMillis(start), span);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static RuntimeException rethrow(ExecutionException e, Span span) {
        Throwable cause = e.getCause();
        if (cause instanceof OqError oq) return oq;
        if (cause instanceof RuntimeException runtime) return runtime;
        return new OqError("task failed: " + cause, span);
    }

    @Override
    public String toString() {
        return "<task" + (isDone() ? " done>" : " running>");
    }
}
