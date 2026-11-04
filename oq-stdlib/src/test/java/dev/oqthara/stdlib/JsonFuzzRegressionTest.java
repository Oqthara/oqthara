package dev.oqthara.stdlib;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cases for defects found by fuzzing the JSON reader.
 */
class JsonFuzzRegressionTest {
    private static Object parse(String text) {
        return Json.parse(text, Span.synthetic());
    }

    private static void reject(String text) {
        OqError error = assertThrows(OqError.class, () -> parse(text),
                "should reject: " + text);
        assertTrue(error.getMessage().startsWith("invalid JSON"), error.getMessage());
    }

    @Test
    void bareDotIsRejectedNotACrash() {
        // Found by Jazzer: "." reached Double.parseDouble and escaped as a raw
        // NumberFormatException, which the CLI would report as an internal error.
        reject(".");
        reject("-.");
        reject("+1");
        reject(".5");
    }

    @Test
    void incompleteFractionsAndExponentsAreRejected() {
        reject("1.");
        reject("1.e5");
        reject("1e");
        reject("1e+");
        reject("-");
    }

    @Test
    void validNumbersStillParse() {
        assertEquals(1L, parse("1"));
        assertEquals(-1L, parse("-1"));
        assertEquals(1.5, parse("1.5"));
        assertEquals(1000.0, parse("1e3"));
        assertEquals(0L, parse("0"));
        assertEquals(0.5, parse("0.5"));
    }

    @Test
    void hugeIntegersBecomeDoublesInsteadOfCrashing() {
        Object value = parse("99999999999999999999999999999999999999");
        assertTrue(value instanceof Double, "expected a double, got " + value.getClass());
    }

    @Test
    void deeplyNestedArraysAreRejectedWithADiagnostic() {
        // Found by Jazzer on CI (where the test JVM stack is smaller): 1884 levels
        // overflowed the reader. The limit makes it deterministic on every JVM.
        for (int depth : List.of(300, 1884, 5000)) {
            String text = "[".repeat(depth) + "]".repeat(depth);
            OqError error = assertThrows(OqError.class, () -> parse(text),
                    "depth " + depth + " must be rejected, not overflow");
            assertTrue(error.getMessage().contains("nested too deeply"), error.getMessage());
        }
        // Realistic documents still parse.
        Object value = parse("[".repeat(100) + "]".repeat(100));
        assertTrue(value instanceof List);
    }

    @Test
    void writerSurvivesEveryParsedValue() {
        for (String text : List.of("1", "1.5", "\"x\"", "[]", "{}", "[[1,2],[3]]",
                "{\"a\":{\"b\":[1,2,3]}}", "true", "null")) {
            Object value = parse(text);
            Json.write(value);
            Json.writePretty(value);
        }
    }
}
