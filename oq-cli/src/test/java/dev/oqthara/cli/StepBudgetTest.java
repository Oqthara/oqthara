package dev.oqthara.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The step budget is the one resource control that covers every program. These tests pin the
 * paths the baseline audit found unbounded (F-05) plus the infinite-loop case that had no test
 * at all, so the guard cannot regress into a comment.
 */
class StepBudgetTest {

    @TempDir
    Path dir;

    private OqRunner.Result runInDir(String source) {
        return OqRunner.run("<test>", source, List.of(), "", dir);
    }

    /** A runaway program must stop with a diagnostic rather than hang the runner forever. */
    @Test
    void infiniteLoopStopsWithADiagnostic() {
        OqRunner.Result result = assertTimeoutPreemptively(Duration.ofSeconds(60),
                () -> OqRunner.run("""
                        while true
                          x = 1
                        end
                        """));
        assertNotEquals(0, result.code(), "a runaway loop must fail");
        assertTrue(result.stderr().contains("program ran for too long"), result.stderr());
    }

    /** Recursion is also interpreter work, so it is billed like any other. */
    @Test
    void unboundedRecursionStopsWithADiagnostic() {
        OqRunner.Result result = assertTimeoutPreemptively(Duration.ofSeconds(60),
                () -> OqRunner.run("""
                        fun down(n)
                          return down(n + 1)
                        end
                        down(0)
                        """));
        assertNotEquals(0, result.code(), "unbounded recursion must fail");
        assertTrue(result.stderr().contains("program ran for too long")
                        || result.stderr().contains("too deep"),
                result.stderr());
    }

    /**
     * The depth guard counts Oqthara calls, but the JVM runs out of Java stack first when the
     * thread stack is small. Windows CI on JDK 25 hit that: the overflow escaped as a raw stack
     * trace and the runner still reported success. Running the recursion on a deliberately small
     * stack forces that path on every platform, so the catch cannot be dropped without this test
     * failing.
     */
    @Test
    void aStackOverflowIsReportedAsAFailureNotASuccess() throws Exception {
        AtomicReference<OqRunner.Result> result = new AtomicReference<>();
        // 256 KiB is far below the default main-thread stack, so the JVM overflows before the
        // interpreter's own 800-call depth guard can fire.
        Thread thread = new Thread(null, () -> result.set(OqRunner.run("""
                fun down(n)
                  return down(n + 1)
                end
                down(0)
                """)), "oq-small-stack", 256 * 1024);
        thread.start();
        thread.join(Duration.ofSeconds(60).toMillis());
        assertFalse(thread.isAlive(), "the recursion must finish rather than hang");
        OqRunner.Result overflowed = result.get();
        assertNotNull(overflowed, "the run must produce a result");
        assertNotEquals(0, overflowed.code(), "runaway recursion must fail");
        assertTrue(overflowed.stderr().contains("too deep"), overflowed.stderr());
        assertFalse(overflowed.stderr().contains("\tat dev.oqthara"),
                "a stack trace must never be the diagnostic: " + overflowed.stderr());
    }

    /** A writing loop hits the 16 MiB output ceiling, so it cannot run forever. */
    @Test
    void printLoopStopsAtTheOutputCeiling() {
        OqRunner.Result result = assertTimeoutPreemptively(Duration.ofSeconds(120),
                () -> OqRunner.run("""
                        n = 0
                        while n < 5000000
                          print "0123456789012345678901234567890123456789012345678901234567890123"
                          n = n + 1
                        end
                        """));
        assertNotEquals(0, result.code(), "an unbounded print loop must fail");
        assertTrue(result.stderr().contains("wrote too much output"), result.stderr());
    }

    /** A single oversized read is refused, and the refusal is a step-budget or size error. */
    @Test
    void oversizedReadIsRefused() throws IOException {
        Files.writeString(dir.resolve("big.txt"), "x".repeat(17 * 1024 * 1024));
        OqRunner.Result result = runInDir("""
                text = read("big.txt")
                print text.length()
                """);
        assertNotEquals(0, result.code(), "reading 17 MiB must fail");
        assertTrue(result.stderr().contains("too large")
                        || result.stderr().contains("ran for too long"),
                result.stderr());
    }

    /** The same program under the limit still works, so the cap is a cap and not a wall. */
    @Test
    void aSmallReadStillSucceeds() throws IOException {
        Files.writeString(dir.resolve("small.txt"), "ok");
        OqRunner.Result result = runInDir("""
                print read("small.txt")
                """);
        assertEquals(0, result.code(), result.stderr());
        assertEquals("ok\n", result.stdout());
    }
}
