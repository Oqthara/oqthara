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
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OqError("interrupted while waiting for a task", span);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OqError oq) throw oq;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new OqError("task failed: " + cause, span);
        }
    }

    @Override
    public String toString() {
        return "<task" + (isDone() ? " done>" : " running>");
    }
}
