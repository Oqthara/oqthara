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
}
