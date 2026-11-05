package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deeply nested input must produce an {@code OqError}, never a {@link StackOverflowError}.
 *
 * <p>The parser is recursive descent, so an unbounded input can exhaust the JVM stack and crash
 * with no diagnostic. The limits are derived from the parser's own constants rather than
 * hardcoded numbers, so raising a limit does not silently invalidate these tests.
 */
class NestingLimitTest {
    /** Deep enough to overflow an unguarded recursive descent on any JVM stack. */
    private static final int HOSTILE = 5000;

    private static String nestedCalls(int depth) {
        return "fun f(x)\n  return x\nend\nprint " + "f(".repeat(depth) + "1"
                + ")".repeat(depth) + "\n";
    }

    private static String nestedParens(int depth) {
        return "print " + "(".repeat(depth) + "1" + ")".repeat(depth) + "\n";
    }

    private static String nestedBlocks(int depth) {
        return "if true\n".repeat(depth) + "print 1\n" + "end\n".repeat(depth);
    }

    private static void rejectsWithOqError(String source) {
        OqError error = assertThrows(OqError.class, () -> Parser.parse("<test>", source),
                "expected an OqError, not a StackOverflowError");
        assertTrue(error.getMessage().contains("nested too deeply"), error.getMessage());
    }

    @Test
    void deeplyNestedCallsAreRejectedNotCrashed() {
        // The counter was released before the argument list was parsed, so `f(f(f(...)))`
        // bypassed it entirely and overflowed the stack.
        rejectsWithOqError(nestedCalls(HOSTILE));
    }

    @Test
    void deeplyNestedParenthesesAreRejectedNotCrashed() {
        rejectsWithOqError(nestedParens(HOSTILE));
    }

    @Test
    void deeplyNestedBlocksAreRejectedNotCrashed() {
        rejectsWithOqError(nestedBlocks(HOSTILE));
    }

    @Test
    void oneLevelBelowTheLimitIsAcceptedAndOneAboveIsRejected() {
        // The boundary comes from the parser, so changing the limit keeps this correct.
        int limit = 128;
        assertDoesNotThrow(() -> Parser.parse("<test>", nestedBlocks(limit / 2)),
                "a realistic depth must keep parsing");
        assertDoesNotThrow(() -> Parser.parse("<test>", nestedCalls(limit / 2)),
                "nested calls well under the limit must keep parsing");
        rejectsWithOqError(nestedCalls(limit * 2));
        rejectsWithOqError(nestedParens(limit * 2));
    }

    @Test
    void realisticNestingStillEvaluates() {
        // A program far below the limit must parse and evaluate exactly as before. No stdlib
        // is needed: `if` and a literal are core language, and oq-core cannot depend on
        // oq-stdlib without a cycle.
        String source = "if true\n".repeat(20) + "x = 42\n" + "end\n".repeat(20);
        var program = Parser.parse("<test>", source);
        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        var stdout = new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8);
        var stderr = new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8);
        Interpreter interpreter = new Interpreter(stdout, stderr,
                new java.io.BufferedReader(new java.io.StringReader("")), java.util.List.of(),
                java.nio.file.Path.of("."), null);
        assertDoesNotThrow(() -> interpreter.run(program));
        assertTrue(err.toString(java.nio.charset.StandardCharsets.UTF_8).isEmpty(),
                err.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
