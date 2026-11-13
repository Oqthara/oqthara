package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cases for defects found by fuzzing the stdlib builtins.
 *
 * <p>Every case here reached the CLI as {@code internal error: IllegalArgumentException} or
 * {@code NullPointerException} with exit 70, because the builtin called {@code Values.asLong}
 * or {@code Values.asDouble} directly. A user mistake must be an {@code OqError}, never an
 * internal error, so each test asserts the error type, not just that something was thrown.
 */
class StdlibFuzzRegressionTest {
    private static Interpreter interpreter;

    private static synchronized Interpreter host() {
        if (interpreter == null) {
            PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            Interpreter created = new Interpreter(sink, sink,
                    new java.io.BufferedReader(new java.io.StringReader("")),
                    List.of(), Path.of("."), new MethodResolverImpl());
            Stdlib.install(created);
            interpreter = created;
        }
        return interpreter;
    }

    private static Object call(String name, Object... args) {
        OqCallable callable = host().builtins().get(name);
        // A plain ArrayList, not List.of: `nothing` is null and List.of rejects it, which
        // would hide the very behaviour this harness exists to check.
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(host(), values, Span.synthetic());
    }

    private static void rejectsWithOqError(String name, Object... args) {
        // Arrays.toString, not List.of: `nothing` is null and List.of rejects it.
        OqError error = assertThrows(OqError.class, () -> call(name, args),
                name + " must reject " + java.util.Arrays.toString(args) + " with an OqError");
        assertTrue(error.getMessage().contains("needs a number"),
                name + " message was: " + error.getMessage());
    }

    @Test
    void twoArgumentMathBuiltinsRejectNonNumbersAsProgramErrors() {
        // atan2 and hypot were added with the transcendentals; the loop above covers only
        // the one-argument functions, and a wrong first argument must not escape as an
        // "internal error".
        for (String name : List.of("atan2", "hypot")) {
            rejectsWithOqError(name, "abc", 1L);
            rejectsWithOqError(name, 1L, "abc");
            rejectsWithOqError(name, new Object[]{null, 1L});
            rejectsWithOqError(name, new Object[]{1L, null});
        }
    }

    @Test
    void numericBuiltinsRejectNonNumbersAsProgramErrors() {
        // Each of these used to escape as a raw IllegalArgumentException from Values.asLong
        // or Values.asDouble, which the CLI renders as "internal error ... This is a bug".
        for (String name : List.of("floor", "ceil", "round", "sqrt", "range", "abs",
                "sin", "cos", "tan", "asin", "acos", "atan", "ln", "log10", "log2", "exp",
                "degrees", "radians")) {
            rejectsWithOqError(name, "abc");
            // An explicit array: `call(name, null)` would pass a null varargs array.
            rejectsWithOqError(name, new Object[]{null});
        }
    }

    @Test
    void rangeStillAcceptsValidBounds() {
        // The guard must not change behaviour for real arguments.
        assertEquals("0 until 3", String.valueOf(call("range", 3L)));
        assertEquals("2 until 5", String.valueOf(call("range", 2L, 5L)));
        // A whole decimal equals the whole number it names, so it is accepted, as `1 == 1.0`.
        assertEquals("0 until 5", String.valueOf(call("range", 0.0, 5.0, 1.0)));
        assertEquals("[0, 1, 2, 3, 4]", String.valueOf(call("range_list", 0.0, 5.0)));
    }

    /**
     * A count, a position, a step and a decimal-place count are whole numbers. A fractional
     * argument used to be truncated, so {@code range(0, 5, 1.5)} walked every integer: the
     * program asked one question and got the answer to another, with no diagnostic.
     */
    @Test
    void aFractionalWholeNumberArgumentIsRefusedInsteadOfTruncated() {
        rejectsWholeNumber("range", 0L, 5L, 1.5);
        rejectsWholeNumber("range", 0.5, 5L, 1L);
        rejectsWholeNumber("range", 0L, 5.5, 1L);
        rejectsWholeNumber("range_list", 0.5, 5.5);
        rejectsWholeNumber("range_list", 0L, 5.5);
        rejectsWholeNumber("random", 1.5, 2.5);
        rejectsWholeNumber("random", 1L, 2.5);
        rejectsWholeNumber("round", 1.25, 1.5);
    }

    /**
     * The decimal in {@code 1e300} has no fractional part, but its magnitude is past the long
     * range, so casting saturates at {@link Long#MAX_VALUE} and the program silently walks a
     * different interval. The refusal names the value so the surprise is visible.
     */
    @Test
    void aWholeNumberArgumentPastTheLongRangeIsRefusedRatherThanSaturated() {
        for (Object bound : new Object[]{1e300, -1e300, Double.POSITIVE_INFINITY, Double.NaN}) {
            rejectsWholeNumber("range", bound, 5L, 1L);
            rejectsWholeNumber("range_list", 0L, bound);
        }
    }

    private void rejectsWholeNumber(String name, Object... args) {
        OqError error = assertThrows(OqError.class, () -> call(name, args),
                name + " must reject " + java.util.Arrays.toString(args));
        assertTrue(error.getMessage().contains(name + "() needs a whole number, got "),
                name + " message was: " + error.getMessage());
    }

    @Test
    void wholeNumberResultsDoNotSaturateAtTheLongLimit() {
        // Casting 1e300 to long saturates at Long.MAX_VALUE, silently turning a large number
        // into a different one. A value that does not fit stays a double.
        assertEquals(3L, call("floor", 3.9));
        assertEquals(4L, call("ceil", 3.1));
        Object huge = call("floor", 1e300);
        assertTrue(huge instanceof Double, "a value beyond long must stay a double: " + huge);
        assertEquals(1e300, (Double) huge, 0.0);
    }

    @Test
    void absOfTheSmallestLongStaysNonNegative() {
        // Math.abs(Long.MIN_VALUE) is itself, so the input came back unchanged and negative.
        // The magnitude does not fit a long, so the exact positive value is returned as a
        // decimal rather than silently staying negative.
        Object result = call("abs", Long.MIN_VALUE);
        assertTrue(result instanceof Double, "must widen, not saturate: " + result);
        assertEquals(9.223372036854776E18, (Double) result, 0.0);
    }

    @Test
    void powerThatDoesNotFitALongStaysADouble() {
        // `(long) Double.POSITIVE_INFINITY` is Long.MAX_VALUE, so an unchecked cast turned an
        // unrepresentable power into a plausible-looking wrong answer.
        assertEquals(1024L, call("pow", 2L, 10L));
        Object huge = call("pow", 10L, 400L);
        assertTrue(huge instanceof Double, "must not saturate to a long: " + huge);
        assertEquals(Double.POSITIVE_INFINITY, (Double) huge, 0.0);
        assertEquals(0.5, call("pow", 2L, -1L));
    }

    @Test
    void jsonWriterRejectsNonFiniteNumbersInsteadOfEmittingInvalidJson() {
        // "Infinity" and "NaN" are not JSON and the reader rejects them, so writing them
        // produced a document this very library could not read back.
        for (double value : new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NaN}) {
            OqError error = assertThrows(OqError.class, () -> Json.write(value),
                    "must refuse to write " + value);
            assertTrue(error.getMessage().contains("JSON"), error.getMessage());
        }
    }

    @Test
    void jsonWriterKeepsLargeFiniteNumbersExact() {
        // Round-trip stability. A whole-valued double is written without a fraction, so it
        // reads back as a Long when it fits; the numeric value is what must be preserved.
        for (double value : new double[]{1e300, -1e300, 1.5e18}) {
            String text = Json.write(value);
            Object back = Json.parse(text, Span.synthetic());
            assertEquals(value, ((Number) back).doubleValue(), 0.0,
                    "round trip changed " + value + " through " + text);
        }
    }

    @Test
    void jsonReaderRejectsNumbersThatOverflowToInfinity() {
        // "1e400" is syntactically a valid JSON number but has no finite double value.
        OqError error = assertThrows(OqError.class,
                () -> Json.parse("1e400", Span.synthetic()));
        assertTrue(error.getMessage().startsWith("invalid JSON"), error.getMessage());
        assertTrue(error.getMessage().contains("out of range"), error.getMessage());
    }
}
