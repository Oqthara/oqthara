package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Descriptive statistics, identifiers and ordered search, per ADR-0041.
 *
 * <p>Three domains that need no new value type, no new dependency and no lifetime rule:
 * {@code statistics} (mean, median, variance, stdev, mode, percentile), {@code uuid} with
 * {@code is_uuid}, and {@code heap_push}/{@code heap_pop}/{@code bisect_left}/{@code
 * bisect_right} over ordinary lists.
 *
 * <p>The sample-versus-population question is settled here rather than left to the caller:
 * {@code variance} and {@code stdev} divide by {@code n - 1} and refuse a single item, because a
 * population variance of one item is zero while a sample variance of it is undefined, and
 * returning zero would be a wrong answer stated confidently.
 */
final class Statistics {
    private Statistics() {
    }

    static void install(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("mean", 1, (host, args, span) ->
                mean(statisticsItems(args.get(0), "mean", span), span)).named("items"));
        interpreter.defineBuiltin(NativeFunction.of("median", 1, (host, args, span) ->
                median(sorted(statisticsItems(args.get(0), "median", span)), span)).named("items"));
        interpreter.defineBuiltin(NativeFunction.of("variance", 1, (host, args, span) ->
                normalize(sampleVariance(statisticsItems(args.get(0), "variance", span), span))).named("items"));
        interpreter.defineBuiltin(NativeFunction.of("stdev", 1, (host, args, span) ->
                normalize(sampleDeviation(statisticsItems(args.get(0), "stdev", span), span,
                        true))).named("items"));
        interpreter.defineBuiltin(NativeFunction.of("mode", 1, (host, args, span) ->
                mode(sorted(statisticsItems(args.get(0), "mode", span)), span)).named("items"));
        interpreter.defineBuiltin(NativeFunction.of("percentile", 2, (host, args, span) ->
                percentile(sorted(statisticsItems(args.get(0), "percentile", span)),
                        Values.asDouble(requireNumber(args.get(1), "percentile", span)), span))
                        .named("items", "percent"));

        interpreter.defineBuiltin(NativeFunction.of("uuid", 0, (host, args, span) ->
                UUID.randomUUID().toString()));
        interpreter.defineBuiltin(NativeFunction.of("is_uuid", 1, (host, args, span) ->
                isCanonicalUuid(Values.display(args.get(0)))).named("value"));

        interpreter.defineBuiltin(NativeFunction.of("heap_push", 2, (host, args, span) ->
                heapPush(args.get(0), args.get(1), span)).named("heap", "item"));
        interpreter.defineBuiltin(NativeFunction.of("heap_pop", 1, (host, args, span) ->
                heapPop(args.get(0), span)).named("heap"));
        interpreter.defineBuiltin(NativeFunction.of("bisect_left", 2, (host, args, span) ->
                (long) bisect(ordered(args.get(0), "bisect_left", span), args.get(1), false, span))
                        .named("items", "value"));
        interpreter.defineBuiltin(NativeFunction.of("bisect_right", 2, (host, args, span) ->
                (long) bisect(ordered(args.get(0), "bisect_right", span), args.get(1), true, span))
                        .named("items", "value"));
    }

    /**
     * The items as numbers, with the offending item named. A {@code statistics} call over
     * {@code ["a", "b"]} should say which element is not a number rather than reporting that the
     * list is not numeric, because the caller's bug is almost always one bad element.
     */
    private static List<Double> numbers(Object value, String builtin, Span span) {
        List<?> items = Values.iterableList(value, span);
        List<Double> numbers = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (!Values.isNumber(item)) {
                throw new OqError(builtin + "() needs numbers, but item " + i + " is "
                        + Values.typeName(item), span,
                        "Every item must be a number.");
            }
            numbers.add(Values.asDouble(item));
        }
        return numbers;
    }

    /**
     * The items as numbers, refusing an empty input.
     *
     * <p>The emptiness rule is a statistics rule, not a coercion rule: {@code mean([])} has no
     * answer, but {@code bisect_left([], 9)} genuinely does — it is {@code 0}. Keeping the check
     * here rather than in {@link #numbers} is what lets ordered search accept an empty list.
     */
    private static List<Double> statisticsItems(Object value, String builtin, Span span) {
        List<Double> numbers = numbers(value, builtin, span);
        if (numbers.isEmpty()) {
            throw new OqError(builtin + "() needs at least one item", span,
                    "An empty list has no " + builtin + " to report.");
        }
        return numbers;
    }

    /** Ordered by the value's own natural order; used by {@code mode}, which needs no sort. */
    private static List<Double> sorted(List<Double> numbers) {
        List<Double> copy = new ArrayList<>(numbers);
        copy.sort(Comparator.naturalOrder());
        return copy;
    }

    /** The items of a sorted-list argument, coerced to numbers and left in place. */
    private static List<Double> ordered(Object value, String builtin, Span span) {
        return numbers(value, builtin, span);
    }

    private static Object mean(List<Double> numbers, Span span) {
        return normalize(total(numbers, "mean", span) / numbers.size());
    }

    /**
     * The middle value, or the mean of the two middle values for an even count.
     *
     * <p>Assumes the input is already sorted, which the caller guarantees.
     */
    private static Object median(List<Double> sorted, Span span) {
        int size = sorted.size();
        if (size % 2 == 1) return normalize(sorted.get(size / 2));
        return normalize(interpolate(sorted.get(size / 2 - 1), sorted.get(size / 2), 0.5));
    }

    /**
     * A point {@code weight} of the way from {@code low} to {@code high}, written so that
     * neither the ends of the double range nor the subnormal range changes the answer.
     *
     * <p>The obvious {@code (low + high) / 2} is wrong twice over. It overflows when both
     * ends are large and share a sign -- {@code (1e308 + 1e308) / 2} is {@code infinity}, not
     * {@code 1e308} -- and it underflows when both ends are tiny and equal, because adding two
     * subnormals and halving rounds back to them only by luck. The first is fixed by moving
     * from {@code low}, which keeps the magnitude of the difference below the magnitude of the
     * larger end; the second by returning an end exactly when the ends are equal, which is what
     * a weighted average of two equal numbers means.
     *
     * <p>Moving from {@code low} is only safe while {@code high - low} is representable. For
     * ends on opposite sides of zero the difference is the sum of the magnitudes and can
     * overflow, so those fall back to the symmetric form, which cannot: each term is at most
     * the end it came from.
     */
    private static double interpolate(double low, double high, double weight) {
        if (low == high) return low;
        double difference = high - low;
        if (Double.isFinite(difference) && (low < 0) == (high < 0)) {
            return low + difference * weight;
        }
        return low * (1 - weight) + high * weight;
    }

    /** Sample variance, dividing by {@code n - 1}; see ADR-0041 D2. */
    private static double sampleVariance(List<Double> numbers, Span span) {
        return sampleDeviation(numbers, span, false);
    }

    /**
     * The sample variance, or its square root, computed without squaring in {@code double}.
     *
     * <p>The textbook form squares each deviation and sums the squares, which fails on exactly
     * the inputs that matter most: {@code [1e308, -1e308]} has a sample variance of
     * {@code 2e616}, past the top of the range, so the intermediate square is {@code infinity}
     * even though the standard deviation {@code sqrt(2) * 1e308} is perfectly representable.
     * Taking the root of an overflowed quantity cannot recover it.
     *
     * <p>Squaring is therefore done in {@link BigDecimal}, where {@code 2e616} is an ordinary
     * number. The sum of squares is exact, and the square root is taken at
     * {@link MathContext#DECIMAL128}: 34 significant digits, far more than the 17 a double can
     * carry, so the final rounding to double is decided by the exact value rather than by the
     * precision of the intermediate. This is why the answer for {@code [2, 4, 4, 4, 5, 5, 7,
     * 9]} is unchanged to the last bit, and why {@code stdev([1e308, -1e308])} is finite.
     *
     * <p>{@code variance} on such an input still has no double to return and becomes
     * {@code infinity}; that is honest, because the variance genuinely is out of range. The
     * point of the split is that {@code stdev} no longer inherits the failure.
     *
     * <p>The sum is read through {@link #exactTotal} rather than {@link #total}. {@code total}
     * refuses a sum that is out of range, which is right for {@code mean}, whose whole answer
     * <em>is</em> that sum divided down: {@code mean([1e308, 1e308])} has no double. Here the
     * sum is only a step. Three copies of {@code 1e308} sum to {@code 3e308}, past the top of
     * the range, yet the answer is an ordinary zero. Refusing that sum would reject a question
     * whose answer exists.
     */
    private static double sampleDeviation(List<Double> numbers, Span span, boolean root) {
        if (numbers.size() < 2) {
            throw new OqError("variance() needs at least two items", span,
                    "A sample variance of one item is undefined. Use 0 if you mean the"
                            + " population variance of a single value.");
        }
        BigDecimal count = BigDecimal.valueOf(numbers.size());
        BigDecimal total = exactTotal(numbers, "variance", span);
        // Each deviation is written as the exact integer n*x - S over n, so the division is
        // deferred to a single step at the end. Rounding the mean first would leave a residue
        // on values that have none: the exact mean of three copies of 1e308 needs 309 digits,
        // and the 34 kept by DECIMAL128 are not it, so every deviation would come out near
        // 1e274 instead of at zero.
        BigDecimal squares = BigDecimal.ZERO;
        for (double number : numbers) {
            BigDecimal numerator = new BigDecimal(number).multiply(count).subtract(total);
            squares = squares.add(numerator.multiply(numerator));
        }
        // Squares of the numerators are exactly n^2 times the sum of squared deviations.
        BigDecimal divisor = count.multiply(count).multiply(BigDecimal.valueOf(numbers.size() - 1));
        BigDecimal variance = squares.divide(divisor, MathContext.DECIMAL128);
        if (!root) return variance.doubleValue();
        return variance.sqrt(MathContext.DECIMAL128).doubleValue();
    }

    /**
     * The smallest most-frequent value.
     *
     * <p>Python raises for a multimodal input; this returns the smallest instead, so the call is
     * total (ADR-0041 D4). The input is sorted, so the first value to reach the highest count
     * wins and ties resolve without a second pass.
     */
    private static Object mode(List<Double> sorted, Span span) {
        double best = sorted.get(0);
        int bestCount = 0;
        int index = 0;
        while (index < sorted.size()) {
            int next = index;
            while (next < sorted.size()
                    && Values.equal(sorted.get(next), sorted.get(index))) next++;
            int count = next - index;
            if (count > bestCount) {
                bestCount = count;
                best = sorted.get(index);
            }
            index = next;
        }
        return normalize(best);
    }

    /**
     * The value at {@code p} percent, interpolating linearly between closest ranks.
     *
     * <p>Stated because the alternatives are not equivalent: on {@code [1, 2, 3, 4]},
     * {@code percentile(items, 50)} is {@code 2.5} here and {@code 2} or {@code 3} under a
     * nearest-rank rule. {@code p} outside {@code 0..100} is a program error rather than a
     * clamp, because a clamp turns a typo into a plausible answer.
     */
    private static Object percentile(List<Double> sorted, double percent, Span span) {
        if (percent < 0 || percent > 100 || Double.isNaN(percent)) {
            throw new OqError("percentile() needs a percent between 0 and 100, got "
                    + Values.display(percent), span, "Pass 50 for the median.");
        }
        if (sorted.size() == 1) return normalize(sorted.get(0));
        double rank = percent / 100.0 * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) return normalize(sorted.get(low));
        return normalize(interpolate(sorted.get(low), sorted.get(high), rank - low));
    }

    /**
     * The sum of the items, accumulated exactly rather than left to left-to-right rounding.
     *
     * <p>Naive accumulation is order-dependent, and on this range the dependency is not a
     * rounding curiosity: {@code [1e308, 1e308, -1e308]} overflows to infinity before the
     * negative term arrives, so {@code mean} reports an overflow for a dataset whose mean is
     * plainly {@code 3.33e307}, while the same three numbers in another order answer
     * correctly. A mean that depends on permutation is not a mean.
     *
     * <p>Every finite double is an exact decimal, so summing them as {@link BigDecimal} is
     * exact and therefore independent of order by construction. That also removes the ordinary
     * cancellation error: on {@code [0.1, 0.2, 0.3]} the rounded sum is
     * {@code 0.6000000000000001} and the exact one is {@code 0.6}.
     *
     * <p>A genuinely infinite total is still an error rather than {@code infinity}. The test
     * is applied to the exact sum rather than to a running total, so it fires only when the
     * mathematical answer is out of range, never because an intermediate step was.
     */
    private static double total(List<Double> numbers, String builtin, Span span) {
        BigDecimal sum = exactTotal(numbers, builtin, span);
        double total = sum.doubleValue();
        if (!Double.isFinite(total)) {
            throw new OqError(builtin + "() overflowed", span,
                    "The values are too large to sum.");
        }
        return total;
    }

    /**
     * The exact sum of the numbers, with no range limit.
     *
     * <p>Separate from {@link #total} because the two callers disagree about the range check:
     * {@code mean} returns the sum itself, divided down, so an out-of-range sum is an
     * out-of-range answer. A deviation divides the sum first, so the sum being out of range
     * says nothing about the answer. Both agree that a value that is already infinite or
     * {@code NaN} is unsummable, since no exact decimal represents it.
     */
    private static BigDecimal exactTotal(List<Double> numbers, String builtin, Span span) {
        BigDecimal sum = BigDecimal.ZERO;
        for (double number : numbers) {
            if (!Double.isFinite(number)) {
                throw new OqError(builtin + "() overflowed", span,
                        "The values are too large to sum.");
            }
            sum = sum.add(new BigDecimal(number));
        }
        return sum;
    }

    /**
     * Pushes onto a list and restores the heap invariant by sifting up.
     *
     * <p>A binary min-heap in an ordinary list: no second value family, and the caller can still
     * <em>look</em> at the array (ADR-0041 D6). Mutates in place and returns the list, matching
     * {@code push}.
     */
    private static Object heapPush(Object target, Object value, Span span) {
        List<Object> heap = mutableList(target, "heap_push", span);
        if (!Values.isNumber(value)) {
            throw new OqError("heap_push() needs a number, got " + Values.typeName(value), span);
        }
        heap.add(value);
        int index = heap.size() - 1;
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (compare(heap.get(index), heap.get(parent), span) >= 0) break;
            swap(heap, index, parent);
            index = parent;
        }
        return heap;
    }

    /** Removes and returns the smallest element, sifting the last element down. */
    private static Object heapPop(Object target, Span span) {
        List<Object> heap = mutableList(target, "heap_pop", span);
        if (heap.isEmpty()) {
            throw new OqError("heap_pop() needs a non-empty list", span,
                    "Check the length before popping.");
        }
        Object smallest = heap.get(0);
        Object last = heap.remove(heap.size() - 1);
        if (heap.isEmpty()) return smallest;
        heap.set(0, last);
        int index = 0;
        while (true) {
            int left = 2 * index + 1;
            int right = left + 1;
            int smallestAt = index;
            if (left < heap.size() && compare(heap.get(left), heap.get(smallestAt), span) < 0) {
                smallestAt = left;
            }
            if (right < heap.size() && compare(heap.get(right), heap.get(smallestAt), span) < 0) {
                smallestAt = right;
            }
            if (smallestAt == index) break;
            swap(heap, index, smallestAt);
            index = smallestAt;
        }
        return smallest;
    }

    /**
     * The insertion index for {@code value} in a sorted list.
     *
     * <p>{@code right} chooses the first element greater than the value rather than not less
     * than it, which is the whole reason both {@code bisect_left} and {@code bisect_right}
     * exist: on {@code [1, 2, 2, 3]} the answer for {@code 2} is {@code 1} or {@code 3}.
     *
     * <p>Sortedness is assumed, not checked: verifying it on every call would turn an
     * {@code O(log n)} primitive into {@code O(n)} and punish the correct caller for the
     * incorrect one (ADR-0041 D6).
     */
    private static int bisect(List<Double> sorted, Object value, boolean right, Span span) {
        if (!Values.isNumber(value)) {
            throw new OqError("bisect needs a number, got " + Values.typeName(value), span);
        }
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            int order = Values.compare(sorted.get(middle), value, span);
            if (order < 0 || (right && order == 0)) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static List<Object> mutableList(Object value, String builtin, Span span) {
        if (value instanceof List<?> raw) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) raw;
            return list;
        }
        throw new OqError(builtin + "() needs a list, got " + Values.typeName(value), span);
    }

    /**
     * The language's own ordering, so that a heap over mixed numbers behaves like {@code <}.
     *
     * <p>Casting to {@code Comparable} was wrong in exactly the way the language is not: Java's
     * {@code Long} and {@code Double} refuse to compare with each other, so
     * {@code heap_push([1], 1.5)} failed with "cannot compare decimal with number" while the
     * expression {@code 1 < 1.5} succeeded. Deferring to {@link Values#compare} makes the heap
     * and the operator agree by construction instead of by coincidence (ADR-0041 D7).
     */
    private static int compare(Object left, Object right, Span span) {
        return Values.compare(left, right, span);
    }

    private static void swap(List<Object> list, int a, int b) {
        Object held = list.get(a);
        list.set(a, list.get(b));
        list.set(b, held);
    }

    /**
     * A whole result as an integer, the way division already behaves: {@code 6 / 3} is
     * {@code 2}, not {@code 2.0}. Keeping the two consistent means
     * {@code mean([2, 4]) == 3} is true rather than needing a conversion first.
     */
    private static Object normalize(double value) {
        if (value == Math.rint(value) && Values.fitsInLong(value)) return (long) value;
        return value;
    }

    /**
     * Whether the text is a UUID in canonical 8-4-4-4-12 lowercase form.
     *
     * <p>Deliberately stricter than {@link UUID#fromString}, which accepts brace-wrapped,
     * {@code urn:uuid:}-prefixed and 32-character unhyphenated spellings. A permissive check
     * would accept strings that other systems reject, which is the wrong direction for a
     * function whose purpose is refusing input. Any version is accepted: this asks about the
     * shape, and {@code uuid()} is what decides which version is minted.
     */
    private static boolean isCanonicalUuid(String text) {
        if (!CANONICAL_UUID.matcher(text).matches()) return false;
        try {
            return UUID.fromString(text).toString().equals(text);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static Object requireNumber(Object value, String builtin, Span span) {
        if (Values.isNumber(value)) return value;
        throw new OqError(builtin + "() needs a number, got " + Values.typeName(value), span);
    }
}
