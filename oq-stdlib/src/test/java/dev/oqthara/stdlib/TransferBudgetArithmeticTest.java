package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the numbers that {@code docs/spec/language.md} states about the transfer budget.
 *
 * <p>The specification claimed the step budget allowed "less than two 16 MiB calls". At one
 * step per byte and a 50,000,000-step budget the true figure is 2.98 calls, so the prose
 * understated the budget by a third. A documentation check cannot catch that: it compares
 * files, not arithmetic. These assertions derive every published number from the real
 * constants, so editing either the constant or the prose without the other fails here.
 */
class TransferBudgetArithmeticTest {

    /** The per-call ceiling, 16 MiB, as stated in the specification. */
    private static final long MAX_TEXT_BYTES = Stdlib.MAX_TEXT_BYTES;

    @Test
    void thePerCallCeilingIsSixteenMebibytes() {
        assertEquals(16_777_216L, MAX_TEXT_BYTES);
    }

    @Test
    void theStepBudgetAllowsTwoFullTransfersButNotThree() {
        java.io.PrintStream quiet =
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
        Interpreter interpreter = new Interpreter(quiet, quiet,
                new java.io.BufferedReader(new java.io.StringReader("")),
                java.util.List.of(), Path.of("."), null);
        long budget = interpreter.maxSteps();
        assertEquals(50_000_000, budget);

        long wholeTransfers = budget / MAX_TEXT_BYTES;
        assertEquals(2, wholeTransfers, "the spec says two full 16 MiB transfers fit");
        // 2 * 16,777,216 = 33,554,432, and a third would need 50,331,648.
        assertTrue(3 * MAX_TEXT_BYTES > budget,
                "a third full transfer must not fit, or the prose is wrong the other way");
    }

    @Test
    void theBudgetIsAboutTwoPointNineEightTimesOneCeiling() {
        // 50,000,000 / 16,777,216 = 2.9802..., which is what the specification now says.
        double ratio = (double) 50_000_000 / MAX_TEXT_BYTES;
        assertTrue(ratio > 2.98 && ratio < 2.99, "ratio was " + ratio);
    }
}
