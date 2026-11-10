package dev.oqthara.stdlib;

import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqTuple;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bounds behaviour for {@code subList}, {@code slice}, {@code take} and {@code skip}.
 *
 * <p>{@code docs/spec/language.md} promises that "indices are clamped to the sequence". The
 * implementation clamped only {@code from < 0} and {@code to > size}, so every other
 * out-of-range bound reached {@code java.util.List.subList} and surfaced as {@code internal
 * error: IndexOutOfBoundsException} — an Oqthara bug reported for a condition the language
 * says is legal. {@code (0, -1)} was the worst case: the repair {@code if (from > to) from =
 * to} copied the negative end into the start and made the range invalid in a new way.
 *
 * <p>Each case below asserts the exact slice the contract implies, not merely that no
 * exception escaped, because a silent wrong answer would pass an
 * {@code assertDoesNotThrow}.
 */
class SequenceIndexClampTest {

    private final MethodResolverImpl resolver = new MethodResolverImpl();
    private final Span span = Span.synthetic();

    private List<Object> list(long... values) {
        List<Object> items = new ArrayList<>(values.length);
        for (long value : values) items.add(value);
        return items;
    }

    @SuppressWarnings("unchecked")
    private List<Object> slice(List<Object> target, Object... args) {
        OqCallable callable = resolver.resolve(target, "subList", span);
        return (List<Object>) callable.call(null, List.of(args), span);
    }

    private List<Object> invoke(List<Object> target, String name, Object arg) {
        OqCallable callable = resolver.resolve(target, name, span);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) callable.call(null, List.of(arg), span);
        return result;
    }

    @Test
    void aNegativeStartCountsFromTheEnd() {
        // Was `internal error: IndexOutOfBoundsException: fromIndex = -2` before any handling.
        // Clamping it to 0, as it did after that, made -2 mean the start instead of the end;
        // a negative bound is a position from the end, as `xs[-1]` already is. See ADR-0019.
        assertEquals(list(4), slice(list(1, 2, 3, 4, 5), -2L, 4L));
    }

    @Test
    void aNegativeEndCountsFromTheEnd() {
        // from = 0 > to = -1 used to set from = -1 and throw from the JDK; folding negatives to
        // 0 made every negative end empty. A negative bound counts from the end (ADR-0019), and
        // a bound resolved past the start is clamped to 0, which still gives an empty range.
        assertEquals(list(1, 2), slice(list(1, 2, 3), 0L, -1L));
        assertEquals(List.of(), slice(list(1, 2, 3), -5L, -9L));
    }

    @Test
    void aStartBeyondTheSizeIsClampedToTheSize() {
        assertEquals(List.of(), slice(list(1, 2, 3), 99L, 120L));
    }

    @Test
    void anEndBeyondTheSizeIsClampedToTheSize() {
        assertEquals(list(2, 3), slice(list(1, 2, 3), 1L, 99L));
    }

    @Test
    void anExtremelyLargePositiveIndexSaturatesInsteadOfWrapping() {
        // 4294967297 is 2^32 + 1. A narrowing long-to-int cast turns it into 1, which would
        // wrongly return a one-element slice from the second item instead of the empty tail.
        assertEquals(List.of(), slice(list(1, 2, 3), 4294967297L, 4294967297L));
        assertEquals(list(1, 2, 3), slice(list(1, 2, 3), 0L, 4294967297L));
        assertEquals(list(1, 2, 3), invoke(list(1, 2, 3), "take", 4294967297L));
    }

    @Test
    void anExtremelyLargeNegativeIndexIsClampedToZero() {
        // Long.MIN_VALUE + 2^32 would wrap positive under a narrowing cast.
        assertEquals(list(1, 2), slice(list(1, 2, 3), -4294967297L, 2L));
    }

    @Test
    void anInvertedRangeIsEmptyRatherThanAnError() {
        // start > end is a caller mistake, but the contract clamps rather than throws. Both
        // ends are valid indices here, so only an explicit ordering rule can answer it.
        assertEquals(List.of(), slice(list(1, 2, 3, 4), 3L, 1L));
    }

    @Test
    void anEmptyListSlicesToEmptyForEveryBound() {
        for (long[] bounds : List.of(new long[] {0, 0}, new long[] {-1, 5}, new long[] {5, -1})) {
            assertEquals(List.of(), slice(List.of(), bounds[0], bounds[1]),
                    "bounds " + bounds[0] + ", " + bounds[1]);
        }
    }

    @Test
    void aNormalInRangeSliceIsUnchanged() {
        assertEquals(list(20, 30), slice(list(10, 20, 30, 40), 1L, 3L));
    }

    @Test
    void aSingleArgumentSliceRunsToTheEnd() {
        assertEquals(list(2, 3), slice(list(1, 2, 3), 1L));
    }

    @Test
    void aSlicedTupleStaysATuple() {
        Object sliced = resolver.resolve(new OqTuple(List.of(1L, 2L, 3L)), "subList", span)
                .call(null, List.of(-1L, 9L), span);
        assertEquals(OqTuple.class, sliced.getClass());
        assertEquals(List.of(3L), sliced);
    }

    @Test
    void takeAndSkipClampTheSameWay() {
        assertEquals(List.of(1L, 2L), invoke(list(1, 2, 3), "take", 2L));
        assertEquals(list(1, 2, 3), invoke(list(1, 2, 3), "take", 99L));
        assertEquals(List.of(), invoke(list(1, 2, 3), "take", -4L));
        assertEquals(List.of(3L), invoke(list(1, 2, 3), "skip", 2L));
        assertEquals(List.of(), invoke(list(1, 2, 3), "skip", 99L));
        assertEquals(list(1, 2, 3), invoke(list(1, 2, 3), "skip", -4L));
    }
}
