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
    /** The refusal text, shared so the resolver, index setter and Java bridge cannot drift. */
    public static final String IMMUTABLE = "a tuple is immutable";

    private final List<Object> items;

    public OqTuple(List<Object> items) {
        // Deliberately not `List.copyOf`: it rejects nulls, and `nothing` is represented as
        // null, so a tuple holding `nothing` would crash instead of printing `(nothing,)`.
        this.items = new ImmutableItems(items);
    }

    /**
     * The backing list, refusing writes with the tuple wording.
     *
     * <p>A plain {@code unmodifiableList} throws with a null message, so a view of the tuple —
     * {@code OqTuple} itself answers its own mutators, but {@code subList} and other views come
     * from this list — blamed "an unchangeable collection" instead of naming the tuple. Keeping
     * the wording here means every view reports the same thing the tuple does.
     */
    private static final class ImmutableItems extends java.util.AbstractList<Object> {
        private final List<Object> source;

        ImmutableItems(List<Object> source) {
            // Not `List.copyOf` here: it rejects nulls and `nothing` is a null.
            this.source = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(source));
        }

        @Override
        public Object get(int index) {
            return source.get(index);
        }

        @Override
        public int size() {
            return source.size();
        }

        @Override
        public Object set(int index, Object element) {
            throw new UnsupportedOperationException(IMMUTABLE);
        }

        @Override
        public void add(int index, Object element) {
            throw new UnsupportedOperationException(IMMUTABLE);
        }

        @Override
        public Object remove(int index) {
            throw new UnsupportedOperationException(IMMUTABLE);
        }
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
        throw new UnsupportedOperationException(IMMUTABLE);
    }

    @Override
    public void add(int index, Object element) {
        throw new UnsupportedOperationException(IMMUTABLE);
    }

    @Override
    public Object remove(int index) {
        throw new UnsupportedOperationException(IMMUTABLE);
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
