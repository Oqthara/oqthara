package dev.oqthara.runtime;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * An immutable, fixed-length sequence of values.
 *
 * <p>Reads are shared with lists so tuple indexing, iteration and Java interop reuse the
 * existing paths. Every mutator throws {@link UnsupportedOperationException}, which is the
 * contract that separates a tuple from a list.
 */
public final class OqTuple extends AbstractList<Object> implements RandomAccess {
    private final List<Object> items;

    public OqTuple(List<Object> items) {
        this.items = List.copyOf(items);
    }

    public static OqTuple of(List<Object> items) {
        return new OqTuple(items);
    }

    @Override
    public Object get(int index) {
        return items.get(index);
    }

    @Override
    public int size() {
        return items.size();
    }

    @Override
    public Object set(int index, Object element) {
        throw new UnsupportedOperationException("a tuple is immutable");
    }

    @Override
    public void add(int index, Object element) {
        throw new UnsupportedOperationException("a tuple is immutable");
    }

    @Override
    public Object remove(int index) {
        throw new UnsupportedOperationException("a tuple is immutable");
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof OqTuple tuple)) return false;
        return items.equals(tuple.items);
    }

    @Override
    public int hashCode() {
        return items.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(Values.repr(items.get(i)));
        }
        if (items.size() == 1) sb.append(',');
        return sb.append(')').toString();
    }
}
