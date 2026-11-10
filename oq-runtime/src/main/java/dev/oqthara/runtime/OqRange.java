package dev.oqthara.runtime;

import java.util.ArrayList;
import java.util.List;

public record OqRange(long start, long end, boolean inclusive, long step) {

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

    /** The last value the walk reaches, which for a negative step is the smaller one. */
    private long high() {
        return inclusive ? end : end - Long.signum(step);
    }

    public long size() {
        long last = high();
        if (step > 0) {
            if (last < start) return 0;
            return (last - start) / step + 1;
        }
        if (last > start) return 0;
        // Both divisions are of non-positive numbers. Work on magnitudes so integer division
        // toward zero does not round the result the wrong way.
        return (start - last) / -step + 1;
    }

    public List<Object> toList() {
        List<Object> items = new ArrayList<>();
        long last = high();
        if (step > 0) {
            for (long i = start; i <= last; i += step) items.add(i);
        } else {
            for (long i = start; i >= last; i += step) items.add(i);
        }
        return items;
    }

    @Override
    public String toString() {
        String base = start + (inclusive ? " to " : " until ") + end;
        return step == 1 ? base : base + " by " + step;
    }
}
