package dev.oqthara.runtime;

import java.util.ArrayList;
import java.util.List;

public record OqRange(long start, long end, boolean inclusive) {
    public long size() {
        long high = inclusive ? end : end - 1;
        if (high < start) return 0;
        return high - start + 1;
    }

    public List<Object> toList() {
        List<Object> items = new ArrayList<>();
        long high = inclusive ? end : end - 1;
        for (long i = start; i <= high; i++) {
            items.add(i);
        }
        return items;
    }

    @Override
    public String toString() {
        return start + (inclusive ? " to " : " until ") + end;
    }
}
