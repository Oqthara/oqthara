package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {
    private String ok(String source) {
        OqRunner.Result result = OqRunner.run(source);
        assertEquals(0, result.code(), "program failed: " + result.stderr());
        return result.stdout();
    }

    @Test
    void spawnReturnsAValue() {
        assertEquals("42\n", ok("""
                task = spawn(fun()
                  return 42
                end)
                print await(task)
                """));
    }

    @Test
    void twoTasksBothComplete() {
        String out = ok("""
                a = spawn(fun()
                  return "a"
                end)
                b = spawn(fun()
                  return "b"
                end)
                print await(a)
                print await(b)
                """);
        assertEquals("a\nb\n", out);
    }

    @Test
    void taskErrorPropagatesToAwait() {
        assertEquals("caught: boom\n", ok("""
                task = spawn(fun()
                  error("boom")
                end)
                try
                  await(task)
                catch e
                  print "caught: {e.message}"
                end
                """));
    }

    @Test
    void parMapPreservesOrder() {
        assertEquals("[1, 4, 9, 16]\n", ok("print par_map([1, 2, 3, 4], x -> x * x)"));
    }

    @Test
    void parMapPropagatesErrors() {
        OqRunner.Result result = OqRunner.run("""
                try
                  par_map([1, 2], x -> error("bad"))
                catch e
                  print "caught"
                end
                """);
        assertEquals("caught\n", result.stdout());
    }

    @Test
    void doneReflectsCompletion() {
        assertEquals("true\n", ok("""
                task = spawn(fun()
                  return 1
                end)
                await(task)
                print done(task)
                """));
    }

    @Test
    void awaitRejectsNonTask() {
        OqRunner.Result result = OqRunner.run("await(1)");
        assertEquals(1, result.code());
        assertTrue(result.stderr().contains("needs a task"), result.stderr());
    }

    @Test
    void manyTasksDoNotLeakStack() {
        assertEquals("5050\n", ok("""
                tasks = []
                for n in 1 to 100
                  tasks.push(spawn(fun()
                    return n
                  end))
                end
                total = 0
                for task in tasks
                  total = total + await(task)
                end
                print total
                """));
    }

    @Test
    void nestedBlockLambdaInCallParses() {
        assertEquals("3\n", ok("""
                run = fun(block)
                  return block()
                end
                print run(fun()
                  return 3
                end)
                """));
    }

    /**
     * Recursion depth is per execution, not per interpreter.
     *
     * <p>Every task a program spawns runs on the same {@code Interpreter} instance, so a shared
     * depth counter is corrupted by concurrency: the increments and the {@code finally}
     * decrements interleave, a level can be lost or counted twice, and a depth that should be
     * shallow reads as deep. The failure is not a crash but a wrong answer — a call refused with
     * "call stack is too deep" from a chain only a few frames deep.
     *
     * <p>Reproducing that reliably needs many tasks recursing at once, so each task recurses to a
     * depth close to the limit. Every task must succeed, and each must return the exact depth it
     * counted: if decrements leak into another task's counter, a later task starts at a nonzero
     * depth and its own count is wrong even when no refusal happens. A single shared counter
     * cannot satisfy both assertions.
     */
    /**
     * Recursion depth belongs to one execution, so concurrent tasks cannot disturb each other.
     *
     * <p>Depth is deliberately shallow. An earlier version of this test recursed 350 frames,
     * which passes on a workstation and fails on the JDK 25 CI runner: the JVM's Java stack
     * overflows before Oqthara's own 800-frame guard fires, so the failure reported a stack
     * overflow rather than the accounting bug and appeared only on some machines. Depth is a
     * property of <em>overlap</em>, not of magnitude — a shared counter is corrupted the moment
     * two tasks are inside a call at the same time, and `sleep` holds each task in its frame long
     * enough for that to happen reliably. Forty frames reproduces the defect on every run while
     * staying nowhere near any stack limit.
     *
     * <p>Measured against the shared-`int` version, this program raised "call stack is too deep"
     * on 5 runs out of 5.
     */
    @Test
    void concurrentRecursionDoesNotCorruptTheDepthCount() {
        String out = ok("""
                fun descend(n, acc)
                  if n == 0
                    return acc
                  end
                  sleep(0.001)
                  return descend(n - 1, acc + 1)
                end
                tasks = []
                for i in 1 to 40
                  tasks.push(spawn(fun()
                    return descend(40, 0)
                  end))
                end
                total = 0
                for task in tasks
                  value = await(task)
                  if value != 40
                    error("depth counted {value}, expected 40")
                  end
                  total = total + 1
                end
                print total
                """);
        assertEquals("40\n", out);
    }

    /**
     * A task that fails mid-recursion must not disturb tasks running beside it.
     *
     * <p>This is the asymmetric-unwind case: one task travels down, throws, and unwinds through
     * the catch, while its neighbours are still inside their own calls. With a shared counter the
     * unwinding task's decrements are subtracted from whatever its neighbours have counted, so the
     * neighbours either see a depth that never existed or exhaust the ceiling that belongs to
     * someone else. The failing task is expected to fail and its error is caught; what is asserted
     * is that every other task counts its frames exactly.
     *
     * <p>Verified against the shared-`int` version, where this program fails.
     */
    @Test
    void aFailedTaskDoesNotPoisonConcurrentRecursion() {
        assertEquals("39\n", ok("""
                fun descend(n)
                  if n == 0
                    error("bottom")
                  end
                  sleep(0.001)
                  return descend(n - 1)
                end
                fun count(n, acc)
                  if n == 0
                    return acc
                  end
                  sleep(0.001)
                  return count(n - 1, acc + 1)
                end
                tasks = [spawn(fun()
                  return descend(40)
                end)]
                for i in 1 to 39
                  tasks.push(spawn(fun()
                    return count(40, 0)
                  end))
                end
                ok = 0
                for task in tasks
                  try
                    value = await(task)
                    if value != 40
                      error("counted {value}, expected 40")
                    end
                    ok = ok + 1
                  catch e
                    if e.message != "bottom"
                      error("unexpected: {e.message}")
                    end
                  end
                end
                print ok
                """));
    }
}
