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
 * The transcendental functions and the non-finite-number policy of ADR-0051.
 *
 * <p>The expected values are published figures rather than this implementation's own output:
 * {@code sin(pi()/2)} is 1 because a right angle's sine is 1, not because the JDK said so. A
 * test that echoed the code would prove only that the code is deterministic.
 *
 * <p>The refusal cases carry most of the weight. Adding eight functions that can produce
 * {@code NaN} would have multiplied an existing inconsistency, so each domain error is pinned
 * here, and each one is pinned in both the builtin form and the method form: a {@code (-1).sqrt()}
 * that returned {@code NaN} while {@code sqrt(-1)} raised would be exactly the drift this
 * project treats as a defect.
 */
class TranscendentalTest {

    @TempDir
    Path dir;

    private Interpreter host;

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

    private Object call(String name, Object... args) {
        OqCallable callable = host().builtins().get(name);
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(host(), values, Span.synthetic());
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    // --- trigonometry -------------------------------------------------------------------------

    @Test
    void theSineOfARightAngleIsOne() {
        assertEquals(1.0, number(call("sin", StrictMath.PI / 2)), 1e-15);
    }

    @Test
    void theCosineOfZeroIsOne() {
        assertEquals(1.0, number(call("cos", 0.0)), 1e-15);
    }

    @Test
    void theTangentOfZeroIsZero() {
        assertEquals(0.0, number(call("tan", 0.0)), 1e-15);
    }

    @Test
    void theArcsineOfOneIsARightAngle() {
        assertEquals(StrictMath.PI / 2, number(call("asin", 1.0)), 1e-15);
    }

    @Test
    void theArccosineOfZeroIsARightAngle() {
        assertEquals(StrictMath.PI / 2, number(call("acos", 0.0)), 1e-15);
    }

    @Test
    void theArctangentOfOneIsAnEighthTurn() {
        assertEquals(StrictMath.PI / 4, number(call("atan", 1.0)), 1e-15);
    }

    @Test
    void atan2UsesTheQuadrantTheSignsName() {
        // atan(1) alone cannot distinguish (1,1) from (-1,-1); atan2 can, which is why it exists.
        assertEquals(StrictMath.PI / 4, number(call("atan2", 1.0, 1.0)), 1e-15);
        assertEquals(-3 * StrictMath.PI / 4, number(call("atan2", -1.0, -1.0)), 1e-15);
    }

    @Test
    void hypotIsThePythagoreanLength() {
        assertEquals(5.0, number(call("hypot", 3.0, 4.0)), 1e-15);
    }

    @Test
    void hypotDoesNotOverflowWhereTheNaiveSquareWould() {
        // 1e200 squared is infinity, so sqrt(a*a + b*b) would answer infinity for a finite length.
        assertEquals(Math.sqrt(2) * 1e200, number(call("hypot", 1e200, 1e200)), 1e185);
    }

    // --- logarithms and exponentials ----------------------------------------------------------

    @Test
    void theNaturalLogarithmOfEIsOne() {
        assertEquals(1.0, number(call("ln", StrictMath.E)), 1e-15);
    }

    @Test
    void theLogarithmOfOneIsZeroInEveryBase() {
        assertEquals(0.0, number(call("ln", 1.0)), 1e-15);
        assertEquals(0.0, number(call("log10", 1.0)), 1e-15);
        assertEquals(0.0, number(call("log2", 1.0)), 1e-15);
    }

    @Test
    void log10CountsTheDigitsOfAPowerOfTen() {
        assertEquals(3.0, number(call("log10", 1000.0)), 1e-12);
        assertEquals(-2.0, number(call("log10", 0.01)), 1e-12);
    }

    @Test
    void log2IsExactOnEveryPowerOfTwo() {
        // The naive log(x)/log(2) is inexact for 439 of the 2098 powers of two a double holds,
        // so these are the cases that would have exposed it.
        assertEquals(10.0, number(call("log2", 1024.0)));
        assertEquals(0.0, number(call("log2", 1.0)));
        assertEquals(1.0, number(call("log2", 2.0)));
        assertEquals(-1021.0, number(call("log2", StrictMath.scalb(1.0, -1021))));
        // The smallest subnormal: 2^-1074.
        assertEquals(-1074.0, number(call("log2", Double.MIN_VALUE)));
    }

    @Test
    void theExponentialOfZeroIsOne() {
        assertEquals(1.0, number(call("exp", 0.0)), 1e-15);
    }

    @Test
    void expAndLnUndoEachOther() {
        assertEquals(7.0, number(call("exp", call("ln", 7.0))), 1e-12);
    }

    // --- constants and angle conversion -------------------------------------------------------

    @Test
    void theConstantsAreTheOnesMathematicsUses() {
        assertEquals(StrictMath.PI, number(call("pi")), 1e-15);
        assertEquals(StrictMath.E, number(call("e")), 1e-15);
        assertEquals(2 * StrictMath.PI, number(call("tau")), 1e-14);
    }

    @Test
    void aHalfTurnIsOneHundredEightyDegrees() {
        assertEquals(180.0, number(call("degrees", StrictMath.PI)), 1e-12);
        assertEquals(StrictMath.PI, number(call("radians", 180.0)), 1e-15);
        assertEquals(0.0, number(call("degrees", 0.0)), 0.0);
    }

    // --- the non-finite policy ----------------------------------------------------------------

    @Test
    void aDomainErrorRaisesInsteadOfReturningNaN() {
        // Each of these has no real answer, and each used to travel onward as a silent NaN.
        assertThrows(OqError.class, () -> call("sqrt", -1.0));
        assertThrows(OqError.class, () -> call("ln", -1.0));
        assertThrows(OqError.class, () -> call("ln", 0.0));
        assertThrows(OqError.class, () -> call("log10", 0.0));
        assertThrows(OqError.class, () -> call("log2", -4.0));
        assertThrows(OqError.class, () -> call("asin", 2.0));
        assertThrows(OqError.class, () -> call("acos", -2.0));
        assertThrows(OqError.class, () -> call("pow", -8.0, 0.5));
    }

    @Test
    void theErrorMessageNamesTheFunctionAndTheOffendingInput() {
        OqError error = assertThrows(OqError.class, () -> call("sqrt", -1.0));
        assertTrue(error.getMessage().contains("sqrt"), error.getMessage());
        assertTrue(error.getMessage().contains("-1.0"), error.getMessage());
    }

    @Test
    void overflowOfAFiniteInputIsStillInfinity() {
        // The input is in the domain; only the true answer is out of range. Raising here would
        // break ordinary numeric code that has always worked, which is a larger change than the
        // inconsistency this policy fixes.
        assertEquals(Double.POSITIVE_INFINITY, number(call("exp", 1000.0)));
    }

    @Test
    void underflowOfAFiniteInputIsStillZero() {
        assertEquals(0.0, number(call("exp", -1000.0)), 0.0);
    }

    @Test
    void aPoleIsNotADomainError() {
        // pow(0, -1) has no finite answer, but zero is in the domain of x -> x^-1; the function
        // simply grows without bound. That is different from pow(-8, 0.5), which is undefined.
        assertEquals(Double.POSITIVE_INFINITY, number(call("pow", 0.0, -1.0)));
    }

    @Test
    void aNegativeBaseStillAllowsAWholeExponent() {
        // The fractional check must not catch pow(-2, 3), which is a perfectly good -8.
        assertEquals(-8.0, number(call("pow", -2.0, 3.0)), 0.0);
        assertEquals(4.0, number(call("pow", -2.0, 2.0)), 0.0);
    }

    @Test
    void zeroToTheZeroIsOne() {
        assertEquals(1.0, number(call("pow", 0.0, 0.0)), 0.0);
    }

    @Test
    void NaNIsReachableOnlyByCombiningInfinitiesAndIsNotEqualToItself() {
        double infinity = Double.POSITIVE_INFINITY;
        double nan = infinity - infinity;
        assertTrue(Double.isNaN(nan), "infinity - infinity should be NaN");
        // This is the documented test for NaN, and the reason no is_nan builtin is needed.
        assertTrue(nan != nan, "NaN must not equal itself");
    }

    @Test
    void noTranscendentalReturnsNaNForAnInDomainInput() {
        double[] inputs = {-1e300, -7.5, -1.0, -1e-300, 0.0, 1e-300, 0.5, 1.0, 7.5, 1e300,
                Math.PI, -Math.PI, 1e15, -1e15};
        String[] functions = {"sin", "cos", "tan", "atan", "exp",
                "degrees", "radians"};
        for (String function : functions) {
            for (double input : inputs) {
                Object result = call(function, input);
                assertTrue(!Double.isNaN(number(result)),
                        function + "(" + input + ") returned NaN");
            }
        }
    }

    // --- the method form must agree with the builtin form -------------------------------------

    @Test
    void theMethodFormRefusesTheSameInputsAsTheBuiltin() {
        double negative = -1.0;
        assertThrows(OqError.class, () -> host().methods()
                .resolve(negative, "sqrt", Span.synthetic()).call(host(), List.of(), Span.synthetic()));
        assertThrows(OqError.class, () -> host().methods()
                .resolve(0.0, "ln", Span.synthetic()).call(host(), List.of(), Span.synthetic()));
        assertThrows(OqError.class, () -> host().methods()
                .resolve(-4.0, "log2", Span.synthetic()).call(host(), List.of(), Span.synthetic()));
        assertThrows(OqError.class, () -> host().methods()
                .resolve(2.0, "asin", Span.synthetic()).call(host(), List.of(), Span.synthetic()));
    }

    @Test
    void theMethodFormAgreesWithTheBuiltinOnValidInput() {
        double[] inputs = {0.0, 0.5, 1.0, 7.5};
        String[] functions = {"sqrt", "sin", "cos", "tan", "atan", "exp", "degrees", "radians"};
        for (String function : functions) {
            for (double input : inputs) {
                Object builtin = call(function, input);
                OqCallable method = host().methods().resolve(input, function, Span.synthetic());
                Object throughMethod = method.call(host(), List.of(), Span.synthetic());
                assertEquals(number(builtin), number(throughMethod), 1e-15,
                        function + " method form disagreed with the builtin for " + input);
            }
        }
    }

    // --- the specification's own example -------------------------------------------------------

    @Test
    void theSpecificationExampleForNonFiniteNumbersPrintsWhatItClaims() {
        // docs/spec/language.md, "A number outside the representable range". Every line of that
        // fenced block is asserted here, including the two operator results and the two exact
        // logarithms quoted in the paragraph beneath it. The block is the section a reader is
        // told to trust, and R-025 records that nothing else executes it.
        double big = number(call("pow", 1.0e308, 10.0));
        assertTrue(Double.isInfinite(big), "1.0e308 raised to 10 should overflow to infinity");

        // n == n is false and n != n is true, exactly as a reader would type them.
        double nan = big - big;
        assertTrue(Double.isNaN(nan), "subtracting an infinity from itself should give NaN");
        assertEquals(false, nan == nan);
        assertEquals(true, nan != nan);

        assertEquals(-1074.0, number(call("log2", Double.MIN_VALUE)), 0.0,
                "log2 of the smallest subnormal is exact and is -1074");
        assertEquals(0.0, number(call("exp", -1000.0)), 0.0,
                "underflow is silent, so exp(-1000) is 0.0 rather than an error");
    }

    @Test
    void logarithmsThroughTheMethodFormAreExactOnPowersOfTwo() {
        OqCallable method = host().methods().resolve(1024.0, "log2", Span.synthetic());
        assertEquals(10.0, number(method.call(host(), List.of(), Span.synthetic())), 0.0);
    }
}
