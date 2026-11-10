package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ADR-0039: the shared task executor, the bounded {@code await}, and the mutex.
 *
 * <p>Nothing here measures timing. A test can show that a bounded wait returns or raises, but
 * not that it was constant-time or that a thread was actually scheduled a particular way;
 * asserting on elapsed milliseconds would produce a flaky test that reads like a guarantee.
 * The one timing-shaped claim the ADR makes — that a timeout does not cancel — is tested by
 * observing that the task's side effect still happens.
 */
class ExecutorsAndCancellationTest {

    private static Interpreter host() {
        Interpreter interpreter = new Interpreter(new PrintStream(java.io.OutputStream.nullOutputStream()),
                new PrintStream(java.io.OutputStream.nullOutputStream()),
                new BufferedReader(new StringReader("")),
                java.util.List.of(), Path.of("."), new dev.oqthara.stdlib.MethodResolverImpl());
        Stdlib.install(interpreter);
        return interpreter;
    }

    private static Object call(Interpreter interpreter, String name, Object... args) {
        return interpreter.builtins().get(name).call(interpreter, new ArrayList<>(java.util.List.of(args)),
                Span.synthetic());
    }

    private static dev.oqthara.runtime.OqCallable returning(Interpreter interpreter, Object value) {
        return new dev.oqthara.runtime.NativeFunction("body", 0, 0, (host, args, span) -> value);
    }

    @Test
    void aTimeoutRaisesRatherThanReturningNothing() {
        Interpreter interpreter = host();
        dev.oqthara.runtime.OqCallable slow = new dev.oqthara.runtime.NativeFunction(
                "slow", 0, 0, (h, a, s) -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "late";
                });
        Object task = call(interpreter, "spawn", slow);
        OqError error = assertThrows(OqError.class, () -> call(interpreter, "await", task, 0.01));
        // The message names the limit the caller gave, not a default: a reader who passed
        // 0.01 should not be told about a number they never wrote.
        assertTrue(error.getMessage().contains("0.01"),
                () -> "expected the caller's own limit, got: " + error.getMessage());
    }

    @Test
    void aTimeoutDoesNotCancelTheTask() {
        // The ADR promises a timeout reports a still-running task, not a stopped one. If this
        // ever becomes a cancellation, the side effect below stops happening and this fails.
        Interpreter interpreter = host();
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        dev.oqthara.runtime.OqCallable slow = new dev.oqthara.runtime.NativeFunction(
                "slow", 0, 0, (h, a, s) -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ran.set(true);
                    return "late";
                });
        Object task = call(interpreter, "spawn", slow);
        assertThrows(OqError.class, () -> call(interpreter, "await", task, 0.01));
        assertEquals("late", call(interpreter, "await", task), "the task finished after the timeout");
        assertTrue(ran.get(), "the task's body ran even though the caller stopped waiting");
    }

    @Test
    void awaitingWithinTheLimitReturnsTheValue() {
        Interpreter interpreter = host();
        Object task = call(interpreter, "spawn", returning(interpreter, "done"));
        assertEquals("done", call(interpreter, "await", task, 5.0));
    }

    @Test
    void anAwaitLimitAboveTheSleepCeilingIsRefused() {
        Interpreter interpreter = host();
        Object task = call(interpreter, "spawn", returning(interpreter, 1L));
        OqError error = assertThrows(OqError.class, () -> call(interpreter, "await", task, 11.0));
        assertTrue(error.getMessage().contains("more than 10 seconds"),
                () -> "expected the sleep ceiling to apply, got: " + error.getMessage());
    }

    @Test
    void aFailedAwaitBackOnATaskWithATimeoutStillRethrowsTheOriginalError() {
        Interpreter interpreter = host();
        dev.oqthara.runtime.OqCallable boom = new dev.oqthara.runtime.NativeFunction(
                "boom", 0, 0, (h, a, s) -> {
                    throw new OqError("boom", s);
                });
        Object task = call(interpreter, "spawn", boom);
        OqError error = assertThrows(OqError.class, () -> call(interpreter, "await", task, 5.0));
        assertEquals("boom", error.getMessage());
    }

    @Test
    void aMutexReportsItselfAsAMutex() {
        Interpreter interpreter = host();
        Object mutex = call(interpreter, "mutex");
        assertEquals("mutex", dev.oqthara.runtime.Values.typeName(mutex));
    }

    @Test
    void aMutexStartsFreeAndIsHeldForTheDurationOfTheBody() {
        Interpreter interpreter = host();
        var mutex = (dev.oqthara.runtime.OqMutex) call(interpreter, "mutex");
        assertTrue(mutex.isFree());
        dev.oqthara.runtime.OqCallable body = new dev.oqthara.runtime.NativeFunction(
                "body", 0, 0, (h, a, s) -> {
                    assertFalse(mutex.isFree(), "the lock is held while the body runs");
                    return null;
                });
        mutex.lock(interpreter, body, Span.synthetic());
        assertTrue(mutex.isFree(), "the lock is released when the body returns");
    }

    @Test
    void aMutexIsReleasedWhenTheBodyRaises() {
        // The whole reason lock() takes a callback: the language has no `finally`, so a
        // separate unlock() would be skipped by a raising body and hang the next caller.
        Interpreter interpreter = host();
        var mutex = (dev.oqthara.runtime.OqMutex) call(interpreter, "mutex");
        dev.oqthara.runtime.OqCallable boom = new dev.oqthara.runtime.NativeFunction(
                "boom", 0, 0, (h, a, s) -> {
                    throw new OqError("boom", s);
                });
        assertThrows(OqError.class, () -> mutex.lock(interpreter, boom, Span.synthetic()));
        assertTrue(mutex.isFree(), "a raising body must not leave the mutex held");
    }

    @Test
    void aMutexIsNotReentrant() {
        Interpreter interpreter = host();
        var mutex = (dev.oqthara.runtime.OqMutex) call(interpreter, "mutex");
        dev.oqthara.runtime.OqCallable inner = new dev.oqthara.runtime.NativeFunction(
                "inner", 0, 0, (h, a, s) -> mutex.lock(interpreter,
                        returning(interpreter, 1L), s));
        OqError error = assertThrows(OqError.class,
                () -> mutex.lock(interpreter, inner, Span.synthetic()));
        assertTrue(error.getMessage().contains("already held"));
        assertTrue(mutex.isFree(), "the failed nested lock still released the outer one");
    }

    @Test
    void theSharedExecutorDoesNotFailUnderRepeatedSpawns() {
        // 200 tasks on the shared executor. A per-call executor leaked one object each; this
        // does not prove the leak is gone, but it does prove the shared one serves them all.
        Interpreter interpreter = host();
        java.util.List<Object> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tasks.add(call(interpreter, "spawn", returning(interpreter, (long) i)));
        }
        for (int i = 0; i < 200; i++) {
            assertEquals((long) i, call(interpreter, "await", tasks.get(i)));
        }
    }

    @Test
    void awaitWithoutATimeoutIsUnchanged() {
        Interpreter interpreter = host();
        Object task = call(interpreter, "spawn", returning(interpreter, "value"));
        assertEquals("value", call(interpreter, "await", task));
    }
}
