package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A scope is read by a spawned task while the thread that spawned it keeps writing.
 *
 * <p>{@code spawn} runs its task on {@code TaskRunner.EXECUTOR} and hands it the same closure
 * environment the spawning thread is still using. Both touch one map: the task reads names out
 * of it, and the caller assigns new ones. That is ordinary concurrency, not a misuse.
 *
 * <p>It surfaced as {@code 'count' is not defined} from a program that had defined {@code count}
 * and never removed it -- an error about the program's own text that the program had not made.
 * The observed rate was low, but the same failure appeared at over ten times the baseline rate
 * once the scope held more bindings, which is the signature of a resize rather than of logic:
 * a {@code HashMap} that grows while another thread reads can expose a bucket array in which the
 * reader's key has not yet been placed.
 *
 * <p>These tests do not reproduce the race by timing. A probabilistic test that fails one run in
 * forty cannot distinguish a fix from luck, and the earlier attempt to use one produced a
 * mutation that passed. They instead pin the property the race violates: every name that was
 * defined stays readable, and every binding stays whole, while another thread writes.
 */
class EnvConcurrencyTest {

    private static final Span NOWHERE = Span.at("<test>", 1, 1);

    /**
     * A name defined before the writers started is still readable after they stop.
     *
     * <p>This is the exact shape of the program-level failure: {@code count} was bound, then
     * other work ran, and reading {@code count} reported it missing.
     */
    @Test
    @Timeout(30)
    void aNameDefinedBeforeConcurrentWritesStaysReadable() throws Exception {
        Env env = new Env();
        env.define("count", "the function", true);

        AtomicReference<Throwable> failed = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int id = t;
            Thread writer = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        env.define("w" + id + "_" + i, i, true);
                    }
                } catch (Throwable problem) {
                    failed.compareAndSet(null, problem);
                }
            });
            writer.setDaemon(true);
            writers.add(writer);
            writer.start();
        }

        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 20_000; i++) {
                    Object value = env.get("count", NOWHERE);
                    if (!"the function".equals(value)) {
                        throw new AssertionError("count changed under a concurrent write: " + value);
                    }
                }
            } catch (Throwable problem) {
                failed.compareAndSet(null, problem);
            }
        });
        reader.setDaemon(true);
        reader.start();

        start.countDown();
        for (Thread writer : writers) writer.join(TimeUnit.SECONDS.toMillis(20));
        reader.join(TimeUnit.SECONDS.toMillis(20));

        if (failed.get() instanceof OqError error) {
            throw new AssertionError("a defined name was reported missing: " + error.getMessage());
        }
        if (failed.get() != null) throw new AssertionError(failed.get());
        // Each writer's names must all be readable, not merely present in some count. A size
        // check would pass for a map that lost one name and duplicated another.
        assertNotNull(env.get("count", NOWHERE));
        for (int t = 0; t < 4; t++) {
            for (int i = 0; i < 2_000; i++) {
                assertEquals(i, env.get("w" + t + "_" + i, NOWHERE),
                        "writer " + t + " lost w" + t + "_" + i);
            }
        }
    }

    /**
     * The map refuses nothing and loses nothing when many threads define distinct names.
     *
     * <p>{@code ConcurrentHashMap} rejects a null value, so this also pins that a scope never
     * holds one: {@code nothing} is carried inside {@link Env}'s binding, not as the map value.
     */
    @Test
    @Timeout(30)
    void concurrentDefinesAllLandAndNoneCollide() throws Exception {
        Env env = new Env();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < 8; t++) {
            int id = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 1_000; i++) {
                        env.define("n" + id + "_" + i, i, true);
                    }
                } catch (Throwable problem) {
                    failures.add(problem);
                }
            });
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join(TimeUnit.SECONDS.toMillis(20));

        assertEquals(List.of(), failures, "no define may fail");
        for (int t = 0; t < 8; t++) {
            for (int i = 0; i < 1_000; i++) {
                assertEquals(i, env.get("n" + t + "_" + i, NOWHERE),
                        "thread " + t + " lost n" + t + "_" + i);
            }
        }
    }
}
