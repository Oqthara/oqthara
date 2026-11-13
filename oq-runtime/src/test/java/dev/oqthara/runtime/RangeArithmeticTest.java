package dev.oqthara.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Counting, walking, and membership for a range whose bounds reach the ends of the 64-bit type.
 *
 * <p>A range is described by its bounds, never enumerated. Every defect asserted below came
 * from an arithmetic step that assumed the count, the distance, or the next value after the
 * bound fits in the signed type: the cardinality of {@code -9223372036854775808 to -1} was
 * reported as {@code -9223372036854775808}, {@code first()} on a ten-million element range
 * allocated all ten million, and a counter loop reached {@code Long.MAX_VALUE} and wrapped
 * rather than stopping.
 *
 * <p>The tests are stated as the arithmetic the language promises — the count, the last
 * reachable value, the walk — rather than as "no exception", because a silent wrap is exactly
 * the failure being guarded against.
 */
class RangeArithmeticTest {

    private final Span span = Span.synthetic();

    private List<Object> walked(OqRange range) {
        return range.toList();
    }

    @Test
    void aCountCoversTheWholeLongType() {
        // 2^63 numbers, one more than Long.MAX_VALUE can hold, so the count is refused rather
        // than wrapped into a negative "length". The old arithmetic reported -9223372036854775808.
        OqError error = assertThrows(OqError.class,
                () -> Values.length(new OqRange(-9223372036854775808L, -1L, true), span));
        assertTrue(error.getMessage().contains("too long to count"), error.getMessage());
        assertFalse(error.getMessage().contains("internal error"), error.getMessage());

        // The largest count that still fits is answered exactly.
        assertEquals(Long.MAX_VALUE, Values.length(new OqRange(0L, Long.MAX_VALUE, false), span));
        assertEquals(Long.MAX_VALUE, Values.length(new OqRange(0L, Long.MAX_VALUE - 1, true), span));
    }

    @Test
    void aCountThatFitsIsExact() {
        assertEquals(11L, Values.length(new OqRange(0L, 10L, true), span));
        assertEquals(10L, Values.length(new OqRange(0L, 10L, false), span));
        assertEquals(5L, Values.length(new OqRange(5L, 1L, true, -1L), span));
        // A step narrows the count: 0..10 by 3 walks 0, 3, 6, 9.
        assertEquals(4L, new OqRange(0L, 10L, true, 3L).size());
        assertEquals(4L, new OqRange(0L, 10L, false, 3L).size());
    }

    @Test
    void anEmptyRangeCountsZeroAndNeverRaises() {
        // `5 to 1` counts up by one, so the bound is already behind the start.
        assertEquals(0L, Values.length(new OqRange(1L, 0L, false), span));
        assertEquals(0L, Values.length(new OqRange(1L, 0L, false, 1L), span));
        assertEquals(0L, Values.length(new OqRange(5L, 3L, true, 1L), span));
        // Excluding the far end of the type leaves nothing to walk, and the count says so
        // instead of computing a wrapped last value.
        assertEquals(0L, Values.length(new OqRange(0L, Long.MIN_VALUE, false), span));
        assertEquals(0L, Values.length(new OqRange(0L, Long.MAX_VALUE, false, -1L), span));
        // An empty range is falsy, and asking whether it is empty must not try to count it.
        assertFalse(Values.truthy(new OqRange(1L, 0L, false)));
        assertTrue(Values.truthy(new OqRange(0L, Long.MAX_VALUE, true)));
    }

    @Test
    void aSingletonAtEitherEndOfTheTypeWalksOnce() {
        // The walk stops on the value it was seeded with, so reaching Long.MAX_VALUE ends the
        // loop instead of incrementing into Long.MIN_VALUE and looping forever.
        assertEquals(List.of(Long.MAX_VALUE), walked(new OqRange(Long.MAX_VALUE, Long.MAX_VALUE, true)));
        assertEquals(List.of(Long.MIN_VALUE), walked(new OqRange(Long.MIN_VALUE, Long.MIN_VALUE, true)));
        assertEquals(1L, Values.length(new OqRange(Long.MAX_VALUE, Long.MAX_VALUE, true), span));
        assertEquals(1L, Values.length(new OqRange(Long.MIN_VALUE, Long.MIN_VALUE, true), span));
    }

    @Test
    void aWalkCrossingTheTypeBoundaryTerminates() {
        // Three values starting two below the max, which is the shape that used to wrap.
        assertEquals(List.of(Long.MAX_VALUE - 2, Long.MAX_VALUE - 1, Long.MAX_VALUE),
                walked(new OqRange(Long.MAX_VALUE - 2, Long.MAX_VALUE, true)));
        assertEquals(List.of(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MIN_VALUE + 2),
                new OqRange(Long.MIN_VALUE, Long.MIN_VALUE + 2, true, 1L).toList());
        // Counting down into the minimum is the mirror case.
        assertEquals(List.of(Long.MIN_VALUE + 2, Long.MIN_VALUE + 1, Long.MIN_VALUE),
                new OqRange(Long.MIN_VALUE + 2, Long.MIN_VALUE, true, -1L).toList());
    }

    @Test
    void membershipAnswersWithoutWalkingTheRange() {
        // `contains` is a bounds test plus a step congruence, so it is O(1) even for a range
        // that spans the whole type. The old form converted the item through a double, which
        // lost the low bits above 2^53 and disagreed with `in`.
        OqRange full = new OqRange(0L, Long.MAX_VALUE, true);
        assertTrue(full.contains(Long.MAX_VALUE));
        assertTrue(full.contains(0L));
        assertTrue(full.contains(9_007_199_254_740_993L));
        assertFalse(full.contains(-1L));

        // A strided range excludes the numbers between its steps, in both directions.
        OqRange strided = new OqRange(Long.MIN_VALUE, Long.MAX_VALUE, true, 3L);
        assertTrue(strided.contains(1L));
        assertTrue(strided.contains(4L));
        assertFalse(strided.contains(2L));

        OqRange backward = new OqRange(9L, 1L, false, -2L);
        assertTrue(backward.contains(9L));
        assertTrue(backward.contains(3L));
        assertFalse(backward.contains(1L));
    }

    @Test
    void membershipAgreesWithTheWalkAtTheEndsOfTheType() {
        // The property: `x in r` is true exactly when the walk reaches x. Checked on ranges
        // that straddle the type boundary, where a wrapped step changes which values exist.
        long[][] shapes = {
                {Long.MAX_VALUE - 2, Long.MAX_VALUE, 1},
                {Long.MIN_VALUE, Long.MIN_VALUE + 2, 1},
                {Long.MIN_VALUE + 2, Long.MIN_VALUE, -1},
                {Long.MAX_VALUE, Long.MAX_VALUE - 2, -1},
                {Long.MAX_VALUE - 4, Long.MAX_VALUE, 3},
                {Long.MIN_VALUE, Long.MIN_VALUE + 4, 4},
        };
        for (long[] shape : shapes) {
            OqRange range = new OqRange(shape[0], shape[1], true, shape[2]);
            List<Object> walked = walked(range);
            assertEquals(shape[0] == shape[1] ? 1 : walked.size(), walked.size());
            for (Object value : walked) {
                assertTrue(range.contains((Long) value), value + " is walked by " + range);
            }
        }
    }

    @Test
    void aDecimalItemIsInARangeOnlyWhenItNamesAWholeNumber() {
        // ADR-0017: a whole-valued decimal is the same number as the integer. A fractional one
        // names no step and is never a member, and neither is text.
        OqRange range = new OqRange(0L, 10L, true);
        assertTrue(Values.contains(range, 3.0d, span));
        assertFalse(Values.contains(range, 3.5d, span));
        assertFalse(Values.contains(range, "3", span));
        // 9.223372036854776e18 is above Long.MAX_VALUE, so it names no member either.
        assertFalse(Values.contains(new OqRange(0L, Long.MAX_VALUE, true), 9.223372036854776e18, span));
    }

    @Test
    void theLastValueIsAnsweredWithoutWalking() {
        assertEquals(10L, new OqRange(0L, 10L, true).last());
        assertEquals(9L, new OqRange(0L, 10L, false).last());
        assertEquals(1L, new OqRange(5L, 1L, true, -1L).last());
        assertEquals(2L, new OqRange(5L, 1L, false, -1L).last());
        assertEquals(Long.MAX_VALUE, new OqRange(0L, Long.MAX_VALUE, true).last());
        assertEquals(Long.MIN_VALUE, new OqRange(0L, Long.MIN_VALUE, true, -1L).last());
        // An empty range has no last value, and the refusal is a user error with a hint.
        OqError error = assertThrows(OqError.class, () -> new OqRange(1L, 0L, false).last());
        assertTrue(error.getMessage().contains("empty"), error.getMessage());
        assertFalse(error.getMessage().contains("internal error"), error.getMessage());
    }

    @Test
    void materialisingAHugeRangeIsRefusedRatherThanExhaustingMemory() {
        // #151: `to_list()` on a range longer than any list used to try to allocate it. The
        // count is checked before a list is built. Three billion fits a long but not a list.
        OqError error = assertThrows(OqError.class,
                () -> new OqRange(0L, 3_000_000_000L, true).toList());
        assertTrue(error.getMessage().contains("too long to turn into a list"), error.getMessage());
        assertFalse(error.getMessage().contains("internal error"), error.getMessage());

        // A count that does not even fit a long is refused one step earlier, by the count.
        OqError uncountable = assertThrows(OqError.class,
                () -> new OqRange(-1L, Long.MAX_VALUE, true).toList());
        assertTrue(uncountable.getMessage().contains("too long to count"), uncountable.getMessage());
        assertFalse(uncountable.getMessage().contains("internal error"), uncountable.getMessage());

        // A range that fits is still materialised exactly.
        assertEquals(List.of(Long.MAX_VALUE), new OqRange(Long.MAX_VALUE, Long.MAX_VALUE, true).toList());
        assertEquals(List.of(0L, 1L, 2L), new OqRange(0L, 2L, true).toList());
    }

    @Test
    void anIteratorOverASingletonStopsAfterOneValue() {
        var iterator = new OqRange(Long.MAX_VALUE, Long.MAX_VALUE, true).iterator();
        assertTrue(iterator.hasNext());
        assertEquals(Long.MAX_VALUE, iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    void anEmptyRangeHasNoValuesToIterate() {
        var iterator = new OqRange(1L, 0L, false).iterator();
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    void aSizeTooLargeForKeyAnIntIsRefused() {
        // `size()` keeps its int signature for callers outside this repository, so a count that
        // does not fit raises instead of narrowing to a negative number.
        assertEquals(11, Values.size(new OqRange(0L, 10L, true), span));
        OqError error = assertThrows(OqError.class,
                () -> Values.size(new OqRange(0L, 3_000_000_000L, true), span));
        assertTrue(error.getMessage().contains("too long to report as an int size"), error.getMessage());
        // length() is the 64-bit answer for the same range and does not raise.
        assertEquals(3_000_000_001L, Values.length(new OqRange(0L, 3_000_000_000L, true), span));
    }

    @Test
    void aForwardSliceTooLongForAListIsRefusedBeforeItIsBuilt() {
        // #156: a forward slice is the common case, and its length is `end - start`, which the
        // first version of the guard computed as `start - end` and therefore never saw as too
        // long. The window below was accepted and then exhausted memory instead of refusing.
        OqRange range = new OqRange(0L, 4_000_000_000L, true);
        OqError error = assertThrows(OqError.class,
                () -> Values.slice(range, 0L, 3_000_000_000L, null, span));
        assertTrue(error.getMessage().contains("this slice is too long to produce"),
                error.getMessage());
        assertFalse(error.getMessage().contains("internal error"), error.getMessage());

        // A forward window that fits is still answered by index arithmetic, and the bounds are
        // resolved and clamped exactly as a list slice resolves them.
        assertEquals(List.of(2L, 3L, 4L), Values.slice(range, 2L, 5L, null, span));
        assertEquals(List.of(2L), Values.slice(range, 2L, 3L, null, span));
        // A negative bound counts from the end, exactly as it does on a list.
        assertEquals(List.of(4_000_000_000L), Values.slice(range, -1L, null, null, span));
    }

    @Test
    void aDescendingSliceCountsItsWindowTheSameWayAsAnAscendingOne() {
        // The count of a reversed window is the same distance taken the other way, so a step of
        // 2 over 11 positions names 6 values whichever end the walk starts from.
        OqRange range = new OqRange(0L, 10L, true);
        assertEquals(List.of(10L, 8L, 6L, 4L, 2L, 0L), Values.slice(range, null, null, -2L, span));
        assertEquals(List.of(9L, 6L, 3L, 0L), Values.slice(range, 9L, null, -3L, span));

        OqError error = assertThrows(OqError.class,
                () -> Values.slice(new OqRange(0L, 4_000_000_000L, true), null, null, -1L, span));
        assertTrue(error.getMessage().contains("this slice is too long to produce"),
                error.getMessage());
    }

    @Test
    void aSliceWindowAtTheTopOfTheTypeDoesNotOverflowItsProduct() {
        // A window over the last values of the type has positions whose product with the step
        // overflows a long even though every value it names fits. The arithmetic is done as a
        // big integer so the window is answered rather than wrapping into a different range.
        OqRange range = new OqRange(9_223_372_036_854_775_798L, Long.MAX_VALUE, true);
        assertEquals(List.of(9_223_372_036_854_775_798L, 9_223_372_036_854_775_801L,
                        9_223_372_036_854_775_804L, Long.MAX_VALUE),
                Values.slice(range, 0L, 10L, 3L, span));
    }

    @Test
    void aRangeIsItsOwnIterableSoTheLoopCopiesNothing() {
        // ADR-0016 copies a mutable collection before iterating so the loop sees a stable
        // snapshot. A range is immutable, so the copy only cost memory: a ten-million element
        // loop allocated ten million entries before the first iteration. The range itself is
        // returned, which is what makes the loop lazy.
        OqRange range = new OqRange(0L, 10_000_000L, true);
        assertTrue(Values.iterable(range, span) == range);
        assertEquals(0L, Values.iterable(range, span).iterator().next());
    }

    @Test
    void aSliceOfAStridedRangeStartsAtTheStridedPosition() {
        // The window start is a position in the range, so it scales by the step exactly like
        // the offset does. Adding it unscaled was invisible on unit steps, which is why only
        // the strided cases below catch it.
        OqRange stepped = new OqRange(0L, 10L, true, 2L);
        assertEquals(List.of(2L, 4L), Values.slice(stepped, 1L, 3L, 1L, span));
        assertEquals(List.of(0L, 4L, 8L), Values.slice(stepped, 0L, 5L, 2L, span));
        assertEquals(List.of(8L), Values.slice(stepped, 4L, 5L, 1L, span));
    }

    @Test
    void aSliceOfADescendingRangeStartsAtTheStridedPosition() {
        // The reversed range names 9, 8, 7... so position 1 is 8. Returning 10 was not even a
        // member of the range, which is the sharpest way to see that the start was unscaled.
        OqRange descending = new OqRange(9L, 0L, true, -1L);
        assertEquals(List.of(8L, 7L), Values.slice(descending, 1L, 3L, 1L, span));
        assertTrue(descending.contains(9L));
        assertFalse(descending.contains(10L));
        assertEquals(List.of(9L, 7L, 5L), Values.slice(descending, 0L, 5L, 2L, span));
    }

    @Test
    void indexingAndSlicingAStridedRangeAgree() {
        // They are two spellings of the same position, and the bug made them disagree.
        OqRange stepped = new OqRange(0L, 9L, true, 3L);
        for (long position = 0; position < 4; position++) {
            assertEquals(List.of(Values.index(stepped, position, span)),
                    Values.slice(stepped, position, position + 1L, 1L, span));
        }
    }
}
