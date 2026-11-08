package dev.oqthara.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** `print` is billed by the characters it emits, so a writing loop cannot run forever. */
    @Test
    void printLoopIsBilledByOutputVolume() {
        OqRunner.Result result = assertTimeoutPreemptively(Duration.ofSeconds(120),
                () -> OqRunner.run("""
                        n = 0
                        while n < 5000000
                          print "0123456789012345678901234567890123456789012345678901234567890123"
                          n = n + 1
                        end
                        """));
        assertNotEquals(0, result.code(), "an unbounded print loop must fail");
        assertTrue(result.stderr().contains("program ran for too long"), result.stderr());
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
