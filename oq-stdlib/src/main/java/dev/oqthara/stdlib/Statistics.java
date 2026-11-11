package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

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
                mean(statisticsItems(args.get(0), "mean", span), span)));
        interpreter.defineBuiltin(NativeFunction.of("median", 1, (host, args, span) ->
                median(sorted(statisticsItems(args.get(0), "median", span)), span)));
        interpreter.defineBuiltin(NativeFunction.of("variance", 1, (host, args, span) ->
                normalize(sampleVariance(statisticsItems(args.get(0), "variance", span), span))));
        interpreter.defineBuiltin(NativeFunction.of("stdev", 1, (host, args, span) ->
                normalize(Math.sqrt(
                        sampleVariance(statisticsItems(args.get(0), "stdev", span), span)))));
        interpreter.defineBuiltin(NativeFunction.of("mode", 1, (host, args, span) ->
                mode(sorted(statisticsItems(args.get(0), "mode", span)), span)));
        interpreter.defineBuiltin(NativeFunction.of("percentile", 2, (host, args, span) ->
                percentile(sorted(statisticsItems(args.get(0), "percentile", span)),
                        Values.asDouble(requireNumber(args.get(1), "percentile", span)), span)));

        interpreter.defineBuiltin(NativeFunction.of("uuid", 0, (host, args, span) ->
                UUID.randomUUID().toString()));
        interpreter.defineBuiltin(NativeFunction.of("is_uuid", 1, (host, args, span) ->
                isCanonicalUuid(Values.display(args.get(0)))));

        interpreter.defineBuiltin(NativeFunction.of("heap_push", 2, (host, args, span) ->
                heapPush(args.get(0), args.get(1), span)));
        interpreter.defineBuiltin(NativeFunction.of("heap_pop", 1, (host, args, span) ->
                heapPop(args.get(0), span)));
        interpreter.defineBuiltin(NativeFunction.of("bisect_left", 2, (host, args, span) ->
                (long) bisect(ordered(args.get(0), "bisect_left", span), args.get(1), false, span)));
        interpreter.defineBuiltin(NativeFunction.of("bisect_right", 2, (host, args, span) ->
                (long) bisect(ordered(args.get(0), "bisect_right", span), args.get(1), true, span)));
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
        return normalize((sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0);
    }

    /** Sample variance, dividing by {@code n - 1}; see ADR-0041 D2. */
    private static double sampleVariance(List<Double> numbers, Span span) {
        if (numbers.size() < 2) {
            throw new OqError("variance() needs at least two items", span,
                    "A sample variance of one item is undefined. Use 0 if you mean the"
                            + " population variance of a single value.");
        }
        double mean = total(numbers, "variance", span) / numbers.size();
        double sum = 0;
        for (double number : numbers) {
            double delta = number - mean;
            sum += delta * delta;
        }
        return sum / (numbers.size() - 1);
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
            while (next < sorted.size() && sorted.get(next).equals(sorted.get(index))) next++;
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
        double weight = rank - low;
        return normalize(sorted.get(low) * (1 - weight) + sorted.get(high) * weight);
    }

    private static double total(List<Double> numbers, String builtin, Span span) {
        double sum = 0;
        for (double number : numbers) {
            sum += number;
        }
        if (Double.isInfinite(sum) || Double.isNaN(sum)) {
            throw new OqError(builtin + "() overflowed", span,
                    "The values are too large to sum.");
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
        double target = Values.asDouble(value);
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            double probe = sorted.get(middle);
            if (probe < target || (right && probe == target)) {
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

    private static int compare(Object left, Object right, Span span) {
        @SuppressWarnings("unchecked")
        Comparable<Object> comparable = (Comparable<Object>) left;
        try {
            return comparable.compareTo(right);
        } catch (ClassCastException e) {
            throw new OqError("cannot compare " + Values.typeName(left) + " with "
                    + Values.typeName(right), span);
        }
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
