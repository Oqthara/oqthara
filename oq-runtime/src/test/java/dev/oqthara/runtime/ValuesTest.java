package dev.oqthara.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValuesTest {
    private final Span span = Span.synthetic();

    @Test
    void addsNumbers() {
        assertEquals(3L, Values.add(1L, 2L, span));
        assertEquals(3.5, Values.add(1L, 2.5, span));
    }

    @Test
    void concatenatesText() {
        assertEquals("ab", Values.add("a", "b", span));
        assertEquals("a1", Values.add("a", 1L, span));
    }

    @Test
    void dividesIntegersExactly() {
        assertEquals(2L, Values.divide(4L, 2L, span));
        assertEquals(2.5, Values.divide(5L, 2L, span));
    }

    @Test
    void comparesStrings() {
        assertTrue(Values.compare("a", "b", span) < 0);
    }

    @Test
    void indexesLists() {
        assertEquals(1L, Values.index(List.of(1L, 2L, 3L), 0L, span));
        assertEquals(3L, Values.index(List.of(1L, 2L, 3L), -1L, span));
    }

    @Test
    void indexesMaps() {
        assertEquals(1L, Values.index(Map.of("a", 1L), "a", span));
    }

    @Test
    void truthiness() {
        assertFalse(Values.truthy(null));
        assertFalse(Values.truthy(0L));
        assertFalse(Values.truthy(""));
        assertFalse(Values.truthy(List.of()));
        assertTrue(Values.truthy(1L));
        assertTrue(Values.truthy("x"));
    }

    @Test
    void formatsDecimals() {
        assertEquals("1.5", Values.formatDouble(1.5));
        assertEquals("2.0", Values.formatDouble(2.0));
        assertEquals("0.25", Values.formatDouble(0.25));
    }

    /**
     * The property that made the old twelve-place rule untenable: the text of a decimal must
     * read back as the same bits. Twelve fractional places cannot hold a value that needs
     * more, and any value below {@code 5e-13} collapsed to {@code 0.0}.
     */
    @Test
    void formattingADecimalRoundTripsThroughParsing() {
        double[] values = {
            1.5, 2.0, 0.25, 0.0001, 1e-4, 9.999999999999999e-5, 1e-13, 1e-320, 4.9e-324,
            0.1 + 0.2, 1.1234567890123457, Math.PI, Math.sqrt(2), 1e15, 1e16, 1e308, -1e308,
            1.7e308, 9007199254740992.0, 1234567890123456.0, 1.4142135623730951e308, -0.0,
        };
        for (double value : values) {
            String text = Values.formatDouble(value);
            assertEquals(value, Double.parseDouble(text),
                    "text " + text + " did not read back as the value it came from");
        }
    }

    @Test
    void verySmallDecimalsKeepTheirSignificantDigits() {
        assertEquals("1.0e-13", Values.formatDouble(1e-13));
        assertEquals("4.9e-324", Values.formatDouble(4.9e-324));
        assertEquals("1.0e-320", Values.formatDouble(1e-320));
        assertEquals("1.1234567890123457", Values.formatDouble(1.1234567890123457));
    }

    @Test
    void valuesWithFewFractionalPlacesKeepThePlainForm() {
        // Everything the old formatter already printed losslessly stays spelled the same way,
        // however large the magnitude, so the change is confined to the values it got wrong.
        assertEquals("0.0001", Values.formatDouble(1e-4));
        assertEquals("1000000000000000.0", Values.formatDouble(1e15));
        assertEquals("1234567890123456.0", Values.formatDouble(1234567890123456.0));
        assertEquals("10000000000000000.0", Values.formatDouble(1e16));
        // The twelfth fractional place is the last one the old projection could hold exactly.
        assertEquals("0.000000000001", Values.formatDouble(1e-12));
        assertEquals("1.0e-13", Values.formatDouble(1e-13));
    }

    @Test
    void signedZeroAndTheNonFiniteSpellingsAreExplicit() {
        assertEquals("0.0", Values.formatDouble(0.0));
        assertEquals("-0.0", Values.formatDouble(-0.0));
        assertEquals("NaN", Values.formatDouble(Double.NaN));
        assertEquals("infinity", Values.formatDouble(Double.POSITIVE_INFINITY));
        assertEquals("-infinity", Values.formatDouble(Double.NEGATIVE_INFINITY));
    }

    @Test
    void equalityAcrossNumbers() {
        assertTrue(Values.equal(1L, 1.0));
        assertTrue(Values.equal("a", "a"));
        assertFalse(Values.equal(1L, 2L));
    }

    @Test
    void tupleIsItsOwnType() {
        assertEquals("tuple", Values.typeName(new OqTuple(List.of(1L, 2L))));
        assertEquals("list", Values.typeName(List.of(1L, 2L)));
    }

    @Test
    void tupleReprUsesParentheses() {
        assertEquals("(1, 2)", Values.repr(new OqTuple(List.of(1L, 2L))));
        assertEquals("(1,)", Values.repr(new OqTuple(List.of(1L))));
        assertEquals("((1, 2), 3)", Values.repr(new OqTuple(
                List.of(new OqTuple(List.of(1L, 2L)), 3L))));
    }

    @Test
    void tupleNeverEqualsAList() {
        assertTrue(Values.equal(new OqTuple(List.of(1L, 2L)), new OqTuple(List.of(1L, 2L))));
        assertFalse(Values.equal(new OqTuple(List.of(1L, 2L)), List.of(1L, 2L)));
        assertFalse(Values.equal(List.of(1L, 2L), new OqTuple(List.of(1L, 2L))));
    }

    @Test
    void tupleComparesLexicographically() {
        assertTrue(Values.compare(new OqTuple(List.of(1L, 2L)),
                new OqTuple(List.of(1L, 3L)), span) < 0);
        assertTrue(Values.compare(new OqTuple(List.of(1L, 2L)),
                new OqTuple(List.of(1L, 2L, 0L)), span) < 0);
    }

    @Test
    void tupleIsImmutable() {
        OqTuple tuple = new OqTuple(List.of(1L, 2L));
        assertThrows(UnsupportedOperationException.class, () -> tuple.set(0, 9L));
        assertThrows(UnsupportedOperationException.class, () -> tuple.add(3L));
        assertThrows(UnsupportedOperationException.class, () -> tuple.remove(0));
        assertThrows(OqError.class, () -> Values.setIndex(tuple, 0L, 9L, span));
    }

    @Test
    void aTupleCanHoldNothing() {
        OqTuple tuple = new OqTuple(java.util.Arrays.asList(null, 1L));
        assertEquals(2, tuple.size());
        assertEquals("(nothing, 1)", Values.repr(tuple));
        assertEquals("tuple", Values.typeName(tuple));
    }

    @Test
    void aTupleOfOnlyNothingIsStillATuple() {
        OqTuple tuple = new OqTuple(java.util.Collections.singletonList(null));
        assertEquals(1, tuple.size());
        assertEquals("(nothing,)", Values.repr(tuple));
    }

    @Test
    void tupleArithmeticIsRefused() {
        OqTuple tuple = new OqTuple(List.of(1L, 2L));
        OqError added = assertThrows(OqError.class,
                () -> Values.add(tuple, List.of(3L), span));
        assertEquals("cannot add a tuple", added.getMessage());
        OqError times = assertThrows(OqError.class,
                () -> Values.multiply(tuple, 2L, span));
        assertEquals("cannot multiply a tuple", times.getMessage());
        OqError reverse = assertThrows(OqError.class,
                () -> Values.multiply(2L, tuple, span));
        assertEquals("cannot multiply number by tuple", reverse.getMessage());
    }

    @Test
    void repeatingTextPastTheLimitIsRefused() {
        OqError error = assertThrows(OqError.class,
                () -> Values.multiply("ab", 4294967297L, span));
        assertEquals("the repeated text would be 8589934594 characters", error.getMessage());
    }

    @Test
    void repeatingAListPastTheLimitIsRefused() {
        OqError error = assertThrows(OqError.class,
                () -> Values.multiply(List.of(1L), 4294967297L, span));
        assertEquals("the repeated list would have 4294967297 items", error.getMessage());
    }

    @Test
    void aRepetitionThatOverflowsTheProductIsRefused() {
        // `"ab" * 4611686018427387905` overflowed a long product to a negative number, slipped
        // past the limit check, and silently repeated once. The product is now exact.
        OqError error = assertThrows(OqError.class,
                () -> Values.multiply("ab", 4611686018427387905L, span));
        assertEquals("the repetition is too large to build", error.getMessage());
    }

    @Test
    void repeatingEmptyTextAnyNumberOfTimesStaysEmpty() {
        // The size guard casts the requested count to int once it knows the product, and an
        // empty text makes that product zero no matter how large the count is. Checking for
        // empty text first keeps `"" * 3000000000` from becoming a negative-count crash.
        assertEquals("", Values.multiply("", 3_000_000_000L, span));
        assertEquals("", Values.multiply("", 0L, span));
        assertEquals("", Values.multiply("", -2L, span));
    }

    @Test
    void repeatingAnEmptyListAnyNumberOfTimesStaysEmpty() {
        // The size guard sees size zero and passes, but the loop still ran `n` times: a four-
        // quadrillion-iteration loop that appended nothing. Empty is answered up front.
        // Preemptively, because the unguarded version does not fail this test - it hangs it.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertEquals(List.of(), Values.multiply(List.of(), 4_611_686_018_427_387_905L, span));
            assertEquals(List.of(), Values.multiply(List.of(), 0L, span));
        });
    }

    @Test
    void anEmptyTextRepeatFinishesImmediatelyToo() {
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertEquals("", Values.multiply("", 3_000_000_000L, span)));
    }

    @Test
    void anOverflowingListRepetitionIsRefused() {
        OqError error = assertThrows(OqError.class,
                () -> Values.multiply(List.of(1L, 2L), 4611686018427387905L, span));
        assertEquals("the repetition is too large to build", error.getMessage());
    }

    @Test
    void tupleSupportsMembership() {
        OqTuple tuple = new OqTuple(List.of(1L, 2L));
        assertTrue(Values.contains(tuple, 2L, span));
        assertFalse(Values.contains(tuple, 9L, span));
    }

    @Test
    void reprOfNested() {
        assertEquals("[1, 2]", Values.repr(List.of(1L, 2L)));
        assertEquals("\"x\"", Values.repr("x"));
        assertEquals("nothing", Values.repr(null));
    }

    @Test
    void arangeLongerThanIntStillCountsCorrectly() {
        // Every number in Oqthara is 64-bit, so length() of a three-billion-element range must
        // report three billion. size() narrowed the range count to int and wrapped: length(0 to
        // 3000000000) printed -1294967295. length() now counts a range in long.
        assertEquals(3_000_000_001L, Values.length(new OqRange(0, 3_000_000_000L, true), span));
        assertEquals(3_000_000_000L, Values.length(new OqRange(0, 3_000_000_000L, false), span));
        // The shapes that were already correct keep their answer.
        assertEquals(5L, Values.length("hello", span));
        assertEquals(3L, Values.length(List.of(1L, 2L, 3L), span));
        assertEquals(1L, Values.length(new OqRange(1, 1, true), span));
        // size() keeps its int signature for compatibility, and stays exact for a range that fits.
        assertEquals(11, Values.size(new OqRange(0, 10, true), span));
    }

    @Test
    void lengthOfAnUnsupportedShapeStillFails() {
        OqError error = assertThrows(OqError.class, () -> Values.length(5L, span));
        assertTrue(error.getMessage().contains("length() needs"), error.getMessage());
    }

    /**
     * A strided range holds only the numbers its walk reaches. Membership used to compare
     * against the bounds alone, so `2 in range(0, 10, 3)` was true even though the walk is
     * `0, 3, 6, 9`. The step and the direction both have to agree with the start.
     */
    @Test
    void aStridedRangeExcludesNumbersBetweenItsSteps() {
        OqRange forward = new OqRange(0, 10, false, 3);
        assertTrue(Values.contains(forward, 0L, span));
        assertTrue(Values.contains(forward, 3L, span));
        assertTrue(Values.contains(forward, 6L, span));
        assertTrue(Values.contains(forward, 9L, span));
        assertFalse(Values.contains(forward, 1L, span));
        assertFalse(Values.contains(forward, 2L, span));
        assertFalse(Values.contains(forward, 7L, span));
        assertFalse(Values.contains(forward, 10L, span));
        assertFalse(Values.contains(forward, 12L, span));
        assertFalse(Values.contains(forward, -1L, span));
    }

    @Test
    void aBackwardRangeHoldsOnlyTheNumbersItWalksDownTo() {
        // `5 to 1 by -1` was empty under the old bounds test, which assumed end > start.
        OqRange backward = new OqRange(5, 1, true, -1);
        assertEquals(5L, Values.length(backward, span));
        for (long n = 1; n <= 5; n++) {
            assertTrue(Values.contains(backward, n, span), n + " is in 5 to 1 by -1");
        }
        assertFalse(Values.contains(backward, 0L, span));
        assertFalse(Values.contains(backward, 6L, span));

        // A strided walk down is still strided. `range(9, 1, -2)` is exclusive, so it stops
        // before reaching 1 and walks 9, 7, 5, 3.
        OqRange strided = new OqRange(9, 1, false, -2);
        assertEquals(List.of(9L, 7L, 5L, 3L), strided.toList());
        assertTrue(Values.contains(strided, 9L, span));
        assertTrue(Values.contains(strided, 7L, span));
        assertTrue(Values.contains(strided, 3L, span));
        assertFalse(Values.contains(strided, 1L, span));
        assertFalse(Values.contains(strided, 8L, span));

        // Making the same bounds inclusive reaches 1, and membership follows the walk.
        OqRange inclusive = new OqRange(9, 1, true, -2);
        assertEquals(List.of(9L, 7L, 5L, 3L, 1L), inclusive.toList());
        assertTrue(Values.contains(inclusive, 1L, span));
    }

    @Test
    void membershipAgreesWithTheWalkItCanBeMaterialisedInto() {
        // The property the bug violated: `in` must answer yes exactly for the numbers that
        // `.to_list()` contains. Checked over a spread of starts, bounds and steps.
        long[][] shapes = {
                {0, 10, 1}, {0, 10, 3}, {0, 10, 7}, {3, 3, 1}, {3, 3, 5},
                {9, 1, -1}, {9, 1, -2}, {0, 0, -1}, {-5, 5, 4}, {5, -5, -4},
        };
        for (long[] shape : shapes) {
            OqRange range = new OqRange(shape[0], shape[1], true, shape[2]);
            List<Object> walked = range.toList();
            for (long n = -12; n <= 12; n++) {
                assertEquals(walked.contains(n), Values.contains(range, n, span),
                        n + " in " + range + " vs " + walked);
            }
        }
    }

    @Test
    void anEmptyRangeHoldsNothing() {
        // `range(1, 0)` steps forward but its end is behind its start, so nothing is reachable.
        OqRange empty = new OqRange(1, 0, false, 1);
        assertEquals(0L, Values.length(empty, span));
        assertFalse(Values.contains(empty, 0L, span));
        assertFalse(Values.contains(empty, 1L, span));
    }

    /**
     * Every text {@code repr} emits must be readable back as source, which
     * {@link OqData#rebuildHint} relies on when it tells the caller to paste a line back. Two
     * characters broke that: a brace opens interpolation, so {@code repr("{x}")} was {@code "{x}"}
     * and pasting it produced the value of {@code x}; and a NUL had no spelling at all.
     */
    @Test
    void reprEscapesTheCharactersThatWouldChangeMeaningWhenPastedBack() {
        assertEquals("\"\\0\"", Values.repr("\0"));
        assertEquals("\"\\n\"", Values.repr("\n"));
        assertEquals("\"\\t\"", Values.repr("\t"));
        assertEquals("\"\\r\"", Values.repr("\r"));
        assertEquals("\"\\\"\"", Values.repr("\""));
        assertEquals("\"\\\\\"", Values.repr("\\"));
        // Braces follow the parser's pairing: an opening brace is doubled, and a closing brace
        // is doubled only while an opened one is still owed a partner. A lone closing brace is
        // already literal, so doubling it would add a character rather than remove one.
        assertEquals("\"{{x}}\"", Values.repr("{x}"));
        assertEquals("\"{{a}} and {{b}}\"", Values.repr("{a} and {b}"));
        assertEquals("\"{{{{not a template}}}}\"", Values.repr("{{not a template}}"));
        assertEquals("\"a} b\"", Values.repr("a} b"));
        assertEquals("\"}} a\"", Values.repr("}} a"));
    }

    @Test
    void aFractionalOrNonNumericItemIsNeverInARange() {
        OqRange range = new OqRange(0, 5, true, 1);
        assertFalse(Values.contains(range, 3.5d, span));
        assertFalse(Values.contains(range, "3", span));
        // A whole-valued decimal is a number, so it converts like an integer.
        assertTrue(Values.contains(range, 3.0d, span));
    }
}
