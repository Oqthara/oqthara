package dev.oqthara.runtime;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A range: a start, a bound, whether the bound is included, and a step.
 *
 * <p>A range is described, never enumerated. Everything here is arithmetic on the four
 * components, so {@code length(0 to 3000000000)} and {@code (0 to 3000000000).contains(7)} cost
 * the same as they do on a three-item range. Building a list instead is what made
 * {@code for n in (0 to 10000000)} under a small heap exhaust memory before printing the first
 * number (issue #155), and {@code r[0]} do the same (issue #156).
 *
 * <p>The arithmetic is deliberately not {@code long}-native. Bounds cover the full 64-bit range
 * (ADR-0046), so {@code last - start} can overflow: {@code -9223372036854775808 to
 * 9223372036854775807} spans 2^64 values, and computing that distance in a {@code long} wrapped.
 * Counting goes through {@link BigInteger}, which is exact by construction; the hot paths
 * ({@link #iterator()}, {@link #contains(long)}, {@link #isEmpty()}) use only comparisons and
 * {@link Math#floorMod}, never a subtraction that can wrap.
 */
public record OqRange(long start, long end, boolean inclusive, long step) implements Iterable<Object> {

    public OqRange {
        if (step == 0) {
            // `Errors.current()` rather than a null span: a range built from inside the
            // runtime (by slicing, or by a stdlib method) has no span of its own, and the
            // call that produced it has already marked one (ADR-0025).
            throw new OqError("a range step cannot be 0", Errors.current(),
                    "Without a step there is no next number. Use 1 for every answer, or -1 to count down.");
        }
    }

    /** A range that advances by one, the meaning of {@code a to b} and {@code a until b}. */
    public OqRange(long start, long end, boolean inclusive) {
        this(start, end, inclusive, 1);
    }

    /**
     * Whether the walk reaches no value at all.
     *
     * <p>This is a comparison of the bounds only. It exists because {@link #size()} can now
     * raise, and truth and membership must still answer for a range that is too long to count:
     * {@code if 0 to 9223372036854775807} is a question about emptiness, not about length.
     *
     * <p>The exclusive cases at the ends of the type are the ones a naive {@code end - step}
     * gets wrong. Excluding {@code Long.MIN_VALUE} while ascending means every candidate is at
     * or below it, so nothing is reached; excluding {@code Long.MAX_VALUE} while descending is
     * the mirror image. Both are empty, and saying so avoids a wrapped last value.
     */
    public boolean isEmpty() {
        if (step > 0) {
            if (!inclusive && end == Long.MIN_VALUE) return true;
            long last = inclusive ? end : end - 1;
            return last < start;
        }
        if (!inclusive && end == Long.MAX_VALUE) return true;
        long last = inclusive ? end : end + 1;
        return last > start;
    }

    /**
     * The last value the walk reaches, which for a negative step is the smaller one.
     *
     * <p>This is the last <em>reachable</em> value, not the bound: {@code 0 to 10 by 3} walks
     * {@code 0, 3, 6, 9}, so its last value is 9. Reporting the bound instead made the walk
     * stop only on a value it never produced and run to the end of the type.
     *
     * <p>An empty range has no last value and this raises, because every caller either wants to
     * refuse or wants to answer with it. An exclusive bound of {@code Long.MIN_VALUE} counting
     * up, or {@code Long.MAX_VALUE} counting down, would otherwise wrap into the far end of the
     * type, so {@link #isEmpty()} is consulted before the bound is adjusted.
     */
    public long last() {
        if (isEmpty()) {
            throw new OqError("this range is empty", Errors.current(),
                    "Check length() before asking for the last item.");
        }
        long bound = inclusive ? end : end - Long.signum(step);
        // How far back from the bound the last step lands. Taking the two remainders
        // separately avoids the `bound - start` subtraction, which overflows for a range that
        // spans the type; the difference of two remainders cannot, because each is strictly
        // inside one step of zero.
        long offset = Math.floorMod(
                Math.floorMod(bound, step) - Math.floorMod(start, step), step);
        return bound - offset;
    }

    /**
     * How many values the walk reaches.
     *
     * <p>A count that does not fit in a {@code long} raises rather than wrapping or saturating.
     * Silence was the defect: {@code length(0 to 9223372036854775807)} answered
     * {@code -9223372036854775808} (issue #149). A barrier that returns a negative count is
     * worse than one that refuses, because every loop over it then starts at the wrong place.
     * ADR-0051 asks domain errors to raise, and "this range has 2^63 values" is not a number
     * this language can express.
     */
    public long size() {
        if (isEmpty()) return 0;
        BigInteger distance = BigInteger.valueOf(last()).subtract(BigInteger.valueOf(start));
        BigInteger count = distance.divide(BigInteger.valueOf(step)).add(BigInteger.ONE);
        if (count.bitLength() > 63) {
            throw new OqError("this range is too long to count", Errors.current(),
                    "It holds more than 9223372036854775807 numbers, which is more than a count can hold. "
                            + "Ask contains(x) instead of length().");
        }
        return count.longValue();
    }

    /**
     * Every value, as a list.
     *
     * <p>This is the one operation that must enumerate, so it is also the one place a range can
     * still exhaust memory. Refusing a count above {@code Integer.MAX_VALUE} is the honest
     * answer: a Java list cannot be that long, so the alternative is an inevitable
     * {@code OutOfMemoryError} after minutes of work. A range that fits is built one value at a
     * time by {@link #iterator()}, with no arithmetic that can wrap.
     */
    public List<Object> toList() {
        long count = size();
        if (count > Integer.MAX_VALUE) {
            throw new OqError("this range is too long to turn into a list", Errors.current(),
                    "A list holds at most 2147483647 items. Take a slice, or walk it with `for`.");
        }
        List<Object> items = new ArrayList<>((int) Math.min(count, 1024));
        for (Object value : this) items.add(value);
        return items;
    }

    /**
     * A lazy walk over the values, in order.
     *
     * <p>Nothing is materialised, so this is bounded memory at any length. The walk stops by
     * remembering the last value rather than by adding the step and testing the bound: adding
     * {@code 1} to {@code Long.MAX_VALUE} wraps to {@code Long.MIN_VALUE}, which is how
     * {@code for i in 9223372036854775807 to 9223372036854775807} printed forever. Once a value
     * has been handed out, the next one is {@code value + step}, which cannot overflow because
     * {@code value != last} and {@code last} is itself reachable.
     */
    @Override
    public Iterator<Object> iterator() {
        boolean empty = isEmpty();
        long stop = empty ? 0 : last();
        return new Iterator<>() {
            private long next = start;
            private boolean done = empty;

            @Override
            public boolean hasNext() {
                return !done;
            }

            @Override
            public Object next() {
                if (done) throw new NoSuchElementException();
                long value = next;
                if (value == stop) {
                    done = true;
                } else {
                    next = value + step;
                }
                return value;
            }
        };
    }

    /**
     * Whether {@code value} is one of the numbers this range walks.
     *
     * <p>Walking starts at {@code start} and advances by {@code step}, so a value counts only
     * when it is reachable from the start by a whole number of steps. Comparing against the
     * bounds is not enough: {@code 2 in range(0, 10, 3)} walked {@code 0, 3, 6, 9} and must be
     * false.
     *
     * <p>The remainder is taken with {@link Math#floorMod} on the value and the start
     * separately. Congruence modulo {@code |step|} is what reachability means, and this way
     * there is no {@code value - start} to overflow: the previous version wrapped that
     * subtraction, so {@code 9223372036854775806 in range(-9223372036854775808, …)} answered
     * {@code false} for a value the walk plainly reaches (issue #152).
     */
    public boolean contains(long value) {
        if (isEmpty()) return false;
        long last = last();
        if (step > 0) {
            if (value < start || value > last) return false;
        } else {
            if (value > start || value < last) return false;
        }
        return Math.floorMod(value, step) == Math.floorMod(start, step);
    }

    @Override
    public String toString() {
        String base = start + (inclusive ? " to " : " until ") + end;
        return step == 1 ? base : base + " by " + step;
    }
}
