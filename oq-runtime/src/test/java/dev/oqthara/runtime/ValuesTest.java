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
    void rangeSizeDoesNotNarrowToInt() {
        assertEquals(3_000_000_001L,
                Values.size(new OqRange(0L, 3_000_000_000L, true), span));
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
}
