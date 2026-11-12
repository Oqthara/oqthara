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
    @Test
    void concurrentRecursionDoesNotCorruptTheDepthCount() {
        String out = ok("""
                fun descend(n, acc)
                  if n == 0
                    return acc
                  end
                  return descend(n - 1, acc + 1)
                end
                tasks = []
                for i in 1 to 40
                  tasks.push(spawn(fun()
                    return descend(350, 0)
                  end))
                end
                total = 0
                for task in tasks
                  value = await(task)
                  if value != 350
                    error("depth counted {value}, expected 350")
                  end
                  total = total + 1
                end
                print total
                """);
        assertEquals("40\n", out);
    }

    /**
     * A completed task must leave no depth behind for whatever runs next.
     *
     * <p>This is the same defect seen from the sequential side: a leaked increment from an
     * abandoned or failed task is invisible until something recurses afterwards and fails early.
     * A task that fails inside deep recursion is the case most likely to leak, because the
     * unwinding path is the one that has to restore the counter.
     */
    @Test
    void aFailedTaskDoesNotPoisonLaterRecursion() {
        assertEquals("deep ok\n", ok("""
                fun descend(n)
                  if n == 0
                    error("bottom")
                  end
                  return descend(n - 1)
                end
                task = spawn(fun()
                  return descend(300)
                end)
                try
                  await(task)
                catch e
                  caught = e.message
                end
                fun again(n)
                  if n == 0
                    return "deep ok"
                  end
                  return again(n - 1)
                end
                print again(350)
                """));
    }
}
