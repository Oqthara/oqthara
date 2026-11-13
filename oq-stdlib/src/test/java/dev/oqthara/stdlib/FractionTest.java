package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.core.Parser;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqFraction;
import dev.oqthara.runtime.OqSet;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fraction type and its surface, per ADR-0053.
 *
 * <p>The cases that matter are the ones that separate an exact rational from a {@code double}
 * wearing a fraction's clothes: the near-identical comparison, the tie at a negative half, and
 * the arithmetic whose decimal answer would be wrong. Everything else pins that the parts stay
 * normalised, since "two fractions are equal exactly when their parts are equal" is the property
 * that makes {@code ==} and set membership work.
 */
class FractionTest {

    @TempDir
    Path dir;

    private Interpreter host;

    private Interpreter host() {
        if (host == null) {
            PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            host = new Interpreter(sink, sink, new BufferedReader(new StringReader("")),
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

    /** Runs a program and returns what it printed, so display and {@code ==} are measured. */
    private String output(String source) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        Interpreter interpreter = new Interpreter(out, out, new BufferedReader(new StringReader("")),
                List.of(), dir, new MethodResolverImpl());
        Stdlib.install(interpreter);
        interpreter.run(Parser.parse("test", source));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static long whole(Object value) {
        return ((Number) value).longValue();
    }

    private OqFraction fraction(long numerator, long denominator) {
        return (OqFraction) call("fraction", numerator, denominator);
    }

    // ---------------------------------------------------------------- normalisation

    @Test
    void signsMoveToTheNumeratorAndTheValueIsReduced() {
        assertEquals("1/2", fraction(2, 4).toString());
        assertEquals("1/2", fraction(-2, -4).toString());
        assertEquals("-1/2", fraction(2, -4).toString());
        assertEquals("-1/2", fraction(-2, 4).toString());
        assertEquals("0", fraction(0, 5).toString());
        assertEquals("-7", fraction(-14, 2).toString());
    }

    @Test
    void aZeroDenominatorIsRefused() {
        OqError error = assertThrows(OqError.class, () -> call("fraction", 1L, 0L));
        assertTrue(error.getMessage().contains("denominator"), error.getMessage());
        assertTrue(error.getMessage().contains("0"), error.getMessage());
    }

    @Test
    void aNonIntegralDecimalIsRefusedRatherThanTruncated() {
        OqError error = assertThrows(OqError.class, () -> call("fraction", 0.5, 1L));
        assertTrue(error.getMessage().contains("numerator"), error.getMessage());
        assertTrue(error.getMessage().contains("0.5"), error.getMessage());
        // The hint has to say why, because truncating would have produced a plausible answer.
        assertTrue(error.hint() != null && error.hint().contains("rounding"),
                String.valueOf(error.hint()));
    }

    @Test
    void anIntegralDecimalIsAccepted() {
        assertEquals("1/2", ((OqFraction) call("fraction", 2.0, 4.0)).toString());
        assertEquals("3", ((OqFraction) call("fraction", 3.0, 1L)).toString());
    }

    @Test
    void aNonFiniteOrForeignInputIsRefused() {
        assertThrows(OqError.class, () -> call("fraction", Double.NaN, 1L));
        assertThrows(OqError.class, () -> call("fraction", Double.POSITIVE_INFINITY, 1L));
        assertThrows(OqError.class, () -> call("fraction", "1", 2L));
        assertThrows(OqError.class, () -> call("fraction", 1L, "2"));
    }

    @Test
    void thePartsComeBackAsWholeNumbers() {
        OqFraction threeQuarters = fraction(3, 4);
        assertEquals(3L, whole(call("fraction_num", threeQuarters)));
        assertEquals(4L, whole(call("fraction_den", threeQuarters)));
    }

    @Test
    void thePartsOfANegativeFractionKeepTheSignOnTheNumerator() {
        OqFraction negative = (OqFraction) call("fraction_of", "2/-4");
        assertEquals(-1L, whole(call("fraction_num", negative)));
        assertEquals(2L, whole(call("fraction_den", negative)));
    }

    // ---------------------------------------------------------------- parsing

    @Test
    void parseAcceptsTheThreeForms() {
        assertEquals("3/4", call("fraction_of", "3/4").toString());
        assertEquals("-2/5", call("fraction_of", "-2/5").toString());
        assertEquals("7", call("fraction_of", "7").toString());
        assertEquals("3/4", call("fraction_of", "  3 / 4  ").toString());
        assertEquals("0", call("fraction_of", "0/9").toString());
    }

    @Test
    void parseRefusesAnythingElseAndNamesTheAcceptedForms() {
        for (String text : List.of("3/4/5", "0.75", "abc", "", "/4", "3/", "1e3")) {
            OqError error = assertThrows(OqError.class, () -> call("fraction_of", text),
                    "expected a refusal for " + text);
            assertTrue(error.hint() != null && error.hint().contains("n/d"),
                    error.hint());
        }
        // A parseable denominator of zero reaches the fraction constructor's refusal instead,
        // which is the more specific message.
        OqError zero = assertThrows(OqError.class, () -> call("fraction_of", "1/0"));
        assertTrue(zero.getMessage().contains("denominator"), zero.getMessage());
    }

    @Test
    void parseRefusesANonTextArgument() {
        OqError error = assertThrows(OqError.class, () -> call("fraction_of", 3L));
        assertTrue(error.getMessage().contains("text"), error.getMessage());
    }

    // ---------------------------------------------------------------- arithmetic

    @Test
    void additionAndSubtractionAreExact() {
        assertEquals("1/2", call("fraction_add", fraction(1, 3), fraction(1, 6)).toString());
        assertEquals("5/6", call("fraction_add", fraction(2, 3), fraction(1, 6)).toString());
        assertEquals("-1/6", call("fraction_sub", fraction(1, 6), fraction(1, 3)).toString());
        assertEquals("0", call("fraction_add", fraction(1, 3), fraction(-1, 3)).toString());
    }

    @Test
    void multiplicationAndDivisionAreExact() {
        assertEquals("1/6", call("fraction_mul", fraction(1, 3), fraction(1, 2)).toString());
        assertEquals("3/2", call("fraction_div", fraction(1, 2), fraction(1, 3)).toString());
        assertEquals("-3/2", call("fraction_div", fraction(1, 2), fraction(-1, 3)).toString());
    }

    @Test
    void dividingByZeroIsRefused() {
        OqError error = assertThrows(OqError.class,
                () -> call("fraction_div", fraction(1, 2), fraction(0, 5)));
        assertTrue(error.getMessage().contains("division by zero"), error.getMessage());
    }

    @Test
    void negationAndAbsoluteValueKeepTheValueNormalised() {
        assertEquals("-1/2", call("fraction_neg", fraction(1, 2)).toString());
        assertEquals("1/2", call("fraction_neg", fraction(-1, 2)).toString());
        assertEquals("1/2", call("fraction_abs", fraction(-1, 2)).toString());
        assertEquals("1/2", call("fraction_abs", fraction(1, 2)).toString());
    }

    @Test
    void everyOperationNamesTheArgumentItCouldNotUse() {
        for (String name : List.of("fraction_text", "fraction_num", "fraction_den",
                "fraction_neg", "fraction_abs", "fraction_floor", "fraction_ceil",
                "fraction_round")) {
            OqError error = assertThrows(OqError.class, () -> call(name, 1L), name);
            assertTrue(error.getMessage().contains("fraction"), error.getMessage());
        }
        OqError error = assertThrows(OqError.class, () -> call("fraction_add", 1L, fraction(1, 2)));
        assertTrue(error.getMessage().contains("fraction"), error.getMessage());
    }

    // ---------------------------------------------------------------- comparison

    /**
     * {@code 9999999999999999/30000000000000000} and {@code 10000000000000001/30000000000000000}
     * sit either side of {@code 1/3} in the seventeenth significant digit, and both are the same
     * {@code double} as it. Cross-multiplication still separates all three, which is the whole
     * reason {@code fraction_compare} does not convert.
     */
    @Test
    void comparisonSeparatesValuesThatAgreeAsDecimals() {
        OqFraction third = fraction(1, 3);
        OqFraction below = (OqFraction) call("fraction_of", "9999999999999999/30000000000000000");
        OqFraction above = (OqFraction) call("fraction_of", "10000000000000001/30000000000000000");
        double thirdAsDouble = 1.0 / 3.0;
        assertEquals(thirdAsDouble,
                9999999999999999.0 / 30000000000000000.0,
                "the pair must be indistinguishable as doubles for this test to mean anything");
        assertEquals(thirdAsDouble,
                10000000000000001.0 / 30000000000000000.0,
                "the pair must be indistinguishable as doubles for this test to mean anything");
        assertEquals(-1L, whole(call("fraction_compare", below, third)));
        assertEquals(1L, whole(call("fraction_compare", third, below)));
        assertEquals(1L, whole(call("fraction_compare", above, third)));
        assertEquals(-1L, whole(call("fraction_compare", below, above)));
    }

    @Test
    void comparisonIsZeroForEquivalentFormsAndOrdersOtherwise() {
        assertEquals(0L, whole(call("fraction_compare", fraction(1, 2), fraction(2, 4))));
        assertEquals(-1L, whole(call("fraction_compare", fraction(1, 3), fraction(1, 2))));
        assertEquals(1L, whole(call("fraction_compare", fraction(1, 2), fraction(1, 3))));
        assertEquals(-1L, whole(call("fraction_compare", fraction(-1, 2), fraction(1, 2))));
        assertEquals(-1L, whole(call("fraction_compare", fraction(-2, 3), fraction(-1, 2))));
    }

    // ---------------------------------------------------------------- whole numbers

    @Test
    void floorAndCeilAreExactOnBothSigns() {
        assertEquals(1L, whole(call("fraction_floor", fraction(3, 2))));
        assertEquals(2L, whole(call("fraction_ceil", fraction(3, 2))));
        assertEquals(-2L, whole(call("fraction_floor", fraction(-3, 2))));
        assertEquals(-1L, whole(call("fraction_ceil", fraction(-3, 2))));
        assertEquals(4L, whole(call("fraction_floor", fraction(4, 1))));
        assertEquals(4L, whole(call("fraction_ceil", fraction(4, 1))));
    }

    /**
     * The same rule {@code RoundTieTest} pins for decimals: a tie goes toward positive infinity,
     * so the three negative halves move toward zero. Every expected value was taken from the
     * built CLI running {@code round} on the equivalent decimal, not from this file's reading of
     * the implementation.
     */
    @Test
    void roundingMatchesTheLanguagesTieRule() {
        assertEquals(1L, whole(call("fraction_round", fraction(1, 2))));
        assertEquals(2L, whole(call("fraction_round", fraction(3, 2))));
        assertEquals(3L, whole(call("fraction_round", fraction(5, 2))));
        assertEquals(0L, whole(call("fraction_round", fraction(-1, 2))));
        assertEquals(-1L, whole(call("fraction_round", fraction(-3, 2))));
        assertEquals(-2L, whole(call("fraction_round", fraction(-5, 2))));
    }

    @Test
    void roundingAgreesWithTheDecimalBuiltinAtTheSameInputs() {
        // Pinning the agreement rather than restating the rule: if either side changed, one of
        // these would fail.
        assertEquals(whole(call("round", -0.5)), whole(call("fraction_round", fraction(-1, 2))));
        assertEquals(whole(call("round", -1.5)), whole(call("fraction_round", fraction(-3, 2))));
        assertEquals(whole(call("round", -2.5)), whole(call("fraction_round", fraction(-5, 2))));
        assertEquals(whole(call("round", 2.5)), whole(call("fraction_round", fraction(5, 2))));
    }

    @Test
    void roundingIsNotATieRoundsToTheNearest() {
        assertEquals(1L, whole(call("fraction_round", fraction(3, 5))));
        assertEquals(0L, whole(call("fraction_round", fraction(2, 5))));
        assertEquals(-1L, whole(call("fraction_round", fraction(-3, 5))));
        assertEquals(0L, whole(call("fraction_round", fraction(-2, 5))));
    }

    // ---------------------------------------------------------------- powers

    @Test
    void integerPowersIncludingNegativeOnes() {
        assertEquals("4/9", call("fraction_pow", fraction(2, 3), 2L).toString());
        assertEquals("9/4", call("fraction_pow", fraction(2, 3), -2L).toString());
        assertEquals("1", call("fraction_pow", fraction(2, 3), 0L).toString());
        assertEquals("-8/27", call("fraction_pow", fraction(-2, 3), 3L).toString());
    }

    @Test
    void zeroToANegativePowerIsRefusedAsDivisionByZero() {
        OqError error = assertThrows(OqError.class,
                () -> call("fraction_pow", fraction(0, 1), -1L));
        assertTrue(error.getMessage().contains("division by zero"), error.getMessage());
    }

    @Test
    void anExponentBeyondTheBoundIsRefused() {
        OqFraction third = fraction(2, 3);
        OqError error = assertThrows(OqError.class,
                () -> call("fraction_pow", third, 10_001L));
        assertTrue(error.getMessage().contains("10000"), error.getMessage());
        assertTrue(error.getMessage().contains("10001"), error.getMessage());
        assertThrows(OqError.class, () -> call("fraction_pow", third, -10_001L));
        // The boundary itself is allowed, so the bound is a limit rather than an off-by-one.
        assertEquals(10_000, OqFraction.MAX_EXPONENT);
        assertEquals("1", call("fraction_pow", fraction(-1, 1), 10_000L).toString());
    }

    @Test
    void aNonIntegralExponentIsRefused() {
        OqError error = assertThrows(OqError.class,
                () -> call("fraction_pow", fraction(4, 9), 0.5));
        assertTrue(error.getMessage().contains("whole-number exponent"), error.getMessage());
        assertTrue(error.hint() != null && error.hint().contains("pow()"),
                String.valueOf(error.hint()));
    }

    // ---------------------------------------------------------------- magnitude

    /**
     * The bound that makes the type safe is on the parts, not on an exponent, so the obvious way
     * around an exponent bound does not work: squaring doubles the bit length every round, and
     * the fifteenth squaring from {@code 3/2} is refused like any other oversized result.
     */
    @Test
    void repeatedSquaringIsRefusedRatherThanGrowingWithoutLimit() {
        OqError error = assertThrows(OqError.class, () -> output("""
                f = fraction(3, 2)
                n = 0
                while n < 64
                  f = fraction_mul(f, f)
                  n = n + 1
                end
                print fraction_text(f)
                """));
        assertTrue(error.getMessage().contains(String.valueOf(OqFraction.MAX_BITS)),
                error.getMessage());
        assertTrue(error.getMessage().contains("numerator"), error.getMessage());
        // Refused with an explanation of what to do, rather than a bare limit.
        assertTrue(error.hint() != null && error.hint().contains("fraction_to_decimal()"),
                String.valueOf(error.hint()));
    }

    /**
     * The bound is checked after reduction, so it limits the value rather than the product that
     * produced it. A pair that cancels back down is accepted however large its parts were, which
     * is what keeps the bound from refusing arithmetic that is about to become small again.
     */
    @Test
    void aPartThatReducesBackDownIsAllowedPastTheBound() {
        BigInteger big = BigInteger.ONE.shiftLeft(OqFraction.MAX_BITS + 1_000);
        assertEquals("2", new OqFraction(big, big.shiftRight(1)).toString());
        assertEquals("1/2", new OqFraction(big, big.shiftLeft(1)).toString());
    }

    @Test
    void aPartAtTheBoundIsAllowedAndOneBitPastIsRefused() {
        BigInteger largest = BigInteger.ONE.shiftLeft(OqFraction.MAX_BITS - 1);
        assertEquals(OqFraction.MAX_BITS, largest.bitLength());
        assertEquals(largest, new OqFraction(largest, BigInteger.ONE).numerator());

        OqError numerator = assertThrows(OqError.class,
                () -> new OqFraction(largest.shiftLeft(1), BigInteger.ONE));
        assertTrue(numerator.getMessage().contains("numerator"), numerator.getMessage());
        assertTrue(numerator.getMessage().contains(String.valueOf(OqFraction.MAX_BITS)),
                numerator.getMessage());

        OqError denominator = assertThrows(OqError.class,
                () -> new OqFraction(BigInteger.ONE, largest.shiftLeft(1)));
        assertTrue(denominator.getMessage().contains("denominator"), denominator.getMessage());
    }

    /**
     * An exponent inside {@code MAX_EXPONENT} whose result is still too large is refused, and
     * refused before the power is built: the projection cannot reject a value the constructor
     * would have accepted, so this is the same refusal reached sooner.
     */
    @Test
    void aPowerTooLargeForItsExponentIsRefusedBeforeItIsBuilt() {
        OqError error = assertThrows(OqError.class,
                () -> call("fraction_pow", fraction(1_234_567, 7_654_321), 10_000L));
        assertTrue(error.getMessage().contains("numerator"), error.getMessage());
        assertTrue(error.getMessage().contains("32768"), error.getMessage());
        assertTrue(error.getMessage().contains("at least"), error.getMessage());
        // A base near one grows far more slowly, so the largest accepted exponent still answers.
        assertEquals("1", call("fraction_pow", fraction(1, 1), 10_000L).toString());
    }

    // ---------------------------------------------------------------- decimals

    @Test
    void toDecimalStatesItsPrecisionAndRoundsHalfUp() {
        assertEquals("0.3333", call("fraction_to_decimal", fraction(1, 3), 4L));
        assertEquals("0.6667", call("fraction_to_decimal", fraction(2, 3), 4L));
        assertEquals("-0.3333", call("fraction_to_decimal", fraction(-1, 3), 4L));
        assertEquals("0.5000", call("fraction_to_decimal", fraction(1, 2), 4L));
        assertEquals("0", call("fraction_to_decimal", fraction(1, 3), 0L));
        assertEquals("1", call("fraction_to_decimal", fraction(2, 3), 0L));
        assertEquals("-1", call("fraction_to_decimal", fraction(-2, 3), 0L));
        assertEquals("0.13", call("fraction_to_decimal", fraction(1, 8), 2L));
    }

    @Test
    void toDecimalBoundsItsPlacesLikeRoundDoes() {
        OqError error = assertThrows(OqError.class,
                () -> call("fraction_to_decimal", fraction(1, 3), 16L));
        assertTrue(error.getMessage().contains("between 0 and 15"), error.getMessage());
        assertThrows(OqError.class, () -> call("fraction_to_decimal", fraction(1, 3), -1L));
    }

    // ---------------------------------------------------------------- the value itself

    @Test
    void typeOfSaysFraction() {
        assertEquals("fraction", Values.typeName(fraction(1, 2)));
        assertEquals("fraction\n", output("print type_of(fraction(2, 4))"));
    }

    /**
     * The {@code OqFraction} branch in {@code Values.typeName} comes after the {@code Double}
     * branch, so adding the type did not reclassify a decimal. The two names are what keeps an
     * exact rational distinguishable from an inexact one, and the order is the whole reason the
     * exact-decimal domain is still unclaimed.
     */
    @Test
    void decimalsStillReportAsDecimal() {
        assertEquals("decimal", Values.typeName(1.5));
        assertEquals("decimal", Values.typeName(1.0e-9));
        assertEquals("number", Values.typeName(2L));
        assertEquals("decimal\nnumber\ndecimal\nfraction\n", output("""
                print type_of(1.5)
                print type_of(2)
                print type_of(1.0)
                print type_of(fraction(1, 2))
                """));
    }

    /**
     * The property the normalised form exists for. Two spellings of the same rational are one
     * value, so {@code ==} and the containers work without knowing this type exists.
     */
    @Test
    void equivalentFormsAreEqual() {
        assertTrue(Values.equal(fraction(1, 2), fraction(2, 4)));
        assertEquals(fraction(1, 2), fraction(2, 4));
        assertEquals(fraction(1, 2).hashCode(), fraction(2, 4).hashCode());
        assertEquals("true\n", output("print fraction(1, 2) == fraction(2, 4)"));
        assertEquals("false\n", output("print fraction(1, 2) == fraction(1, 3)"));
        assertEquals("true\n", output("print fraction(1, 2) != fraction(1, 3)"));
    }

    @Test
    void aFractionIsNotEqualToANumber() {
        assertNotEquals(fraction(2, 1), 2L);
        assertEquals("false\n", output("print fraction(2, 1) == 2"));
    }

    @Test
    void hashingMakesSetMembershipCollapse() {
        OqSet set = new OqSet();
        set.add(fraction(1, 2));
        set.add(fraction(2, 4));
        set.add(fraction(1, 3));
        assertEquals(2, set.size());
        assertTrue(set.contains(fraction(2, 4)));
    }

    @Test
    void displayRendersTheCanonicalForm() {
        assertEquals("1/2", Values.display(fraction(1, 2)));
        assertEquals("1/2", Values.repr(fraction(2, 4)));
        assertEquals("7", Values.display(fraction(14, 2)));
        assertEquals("-1/2", Values.display(fraction(-2, 4)));
        assertEquals("1/2\n", output("print fraction(2, 4)"));
        assertEquals("1/2\n", output("print fraction_text(fraction_add(fraction(1, 3), fraction(1, 6)))"));
    }

    @Test
    void aFractionRendersInsideAContainer() {
        assertEquals("[1/2, 1/3]\n", output("print [fraction(2, 4), fraction(1, 3)]"));
    }

    @Test
    void zeroIsFalsyAndEverythingElseIsTruthy() {
        assertEquals("false\n", output("if fraction(0, 5)\n  print \"true\"\nelse\n  print \"false\"\nend"));
        assertEquals("true\n", output("if fraction(-1, 5)\n  print \"true\"\nelse\n  print \"false\"\nend"));
    }
}
