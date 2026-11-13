package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * A number's method form and the matching builtin must answer the same thing.
 *
 * <p>They were separate implementations, and they drifted in exactly the cases where a cast to
 * {@code long} is not invisible: {@code 1e300.floor()} saturating at {@code Long.MAX_VALUE}
 * while {@code floor(1e300)} kept the decimal, and {@code Long.MIN_VALUE.abs()} staying negative
 * while {@code abs()} widened to a positive decimal. Both now call one implementation, and the
 * cases below are the ones that used to differ.
 *
 * <p>{@code is_even} and {@code is_odd} have no builtin counterpart, so their cases are pinned
 * against the value itself: the parity of a decimal is the parity it has, not the parity of the
 * {@code long} it saturates to.
 */
class NumberMethodBuiltinParityTest {

    @TempDir
    Path dir;

    private Interpreter host;

    private final MethodResolverImpl resolver = new MethodResolverImpl();

    private Interpreter host() {
        if (host == null) {
            PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            host = new Interpreter(sink, sink,
                    new java.io.BufferedReader(new java.io.StringReader("")),
                    List.of(), dir, new MethodResolverImpl());
            Stdlib.install(host);
        }
        return host;
    }

    private Object builtin(String name, Object... args) {
        OqCallable callable = host().builtins().get(name);
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(host(), values, Span.synthetic());
    }

    private Object method(Object receiver, String name, Object... args) {
        OqCallable callable = resolver.resolve(receiver, name, Span.synthetic());
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(host(), values, Span.synthetic());
    }

    /** Asserts both forms return the same value, so a later divergence fails here. */
    private void assertAgrees(Object receiver, String name) {
        assertEquals(builtin(name, receiver), method(receiver, name),
                name + " disagrees for " + receiver);
    }

    @Test
    void roundingMethodsAgreeWithTheirBuiltinsPastTheLongRange() {
        for (Object value : List.of(1e300, -1e300, 9.3e18, -9.3e18,
                (Object) Long.MAX_VALUE, (Object) Long.MIN_VALUE)) {
            assertAgrees(value, "floor");
            assertAgrees(value, "ceil");
            assertAgrees(value, "round");
            assertAgrees(value, "abs");
        }
        // The specific wrong answer this guards: 1e300 narrowed to the largest long.
        assertEquals(1e300, ((Number) method(1e300, "floor")).doubleValue(), 0.0);
        assertEquals(1e300, ((Number) method(1e300, "round")).doubleValue(), 0.0);
    }

    @Test
    void aWholeNumberResultStaysAWholeNumber() {
        assertEquals(3L, method(3.9, "floor"));
        assertEquals(4L, method(3.1, "ceil"));
        assertEquals(7L, method(-7L, "abs"));
        assertEquals(-7L, method(-7L, "round"));
    }

    /**
     * {@code Long.MIN_VALUE}'s magnitude is one past {@code Long.MAX_VALUE}, so it has no
     * whole-number answer. Both forms return the decimal rather than a negative number.
     */
    @Test
    void theSmallestLongAbsolvesToAPositiveDecimalInBothForms() {
        assertEquals(9.223372036854776e18, ((Number) method(Long.MIN_VALUE, "abs")).doubleValue(), 0.0);
        assertEquals(9.223372036854776e18, ((Number) builtin("abs", Long.MIN_VALUE)).doubleValue(), 0.0);
        assertTrue(((Number) method(Long.MIN_VALUE, "abs")).doubleValue() > 0);
    }

    @Test
    void aWrongReceiverTypeIsRefusedByTheMethodFormToo() {
        OqError error = assertThrows(OqError.class, () -> builtin("floor", (Object) "abc"));
        assertTrue(error.getMessage().contains("needs a number"), error.getMessage());
    }
    // --- parity ------------------------------------------------------------------------------

    @Test
    void parityOfADecimalOutsideTheLongRangeIsItsRealParity() {
        // Both used to be answered from the saturated long, which is odd.
        assertEquals(true, method(1e20, "is_even"));
        assertEquals(false, method(1e20, "is_odd"));
        // 2^53 + 2 is the first double past the exact-integer limit and is even.
        assertEquals(true, method(9007199254740994.0, "is_even"));
    }

    @Test
    void aWholeDecimalHasTheSameParityAsTheWholeNumberItEquals() {
        assertEquals(true, method(2.0, "is_even"));
        assertEquals(false, method(2.0, "is_odd"));
        assertEquals(true, method(4L, "is_even"));
        assertEquals(true, method(3L, "is_odd"));
    }

    /**
     * A fractional number has no parity, so it is refused instead of truncated. Truncating made
     * {@code 1.5.is_even()} answer {@code false}, which reads as "odd" and is not the question.
     */
    @Test
    void aFractionalNumberHasNoParity() {
        for (String name : List.of("is_even", "is_odd")) {
            OqError error = assertThrows(OqError.class, () -> method(1.5, name), name);
            assertTrue(error.getMessage().contains(name + "() needs a whole number, got 1.5"),
                    error.getMessage());
        }
    }
}
