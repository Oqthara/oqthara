package dev.oqthara.runtime;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * An Oqthara set: insertion-ordered, and keyed by Oqthara equality rather than Java's.
 *
 * <p>The backing store holds {@link Value} boxes, but this class implements
 * {@code Set<Object>} and unwraps on the way out, so code that iterates a set sees ordinary
 * values and never a box. {@code set([1, 1.0])} has one item because the boxes of those two
 * values are equal and hash alike; {@code {1: "a"}[1.0]} finds its entry for the same reason.
 */
public final class OqSet extends AbstractSet<Object> {

    private final LinkedHashSet<Value> backing;

    public OqSet() {
        this.backing = new LinkedHashSet<>();
    }

    public OqSet(int expectedSize) {
        this.backing = new LinkedHashSet<>(Math.max(1, expectedSize));
    }

    /** Copies {@code items}, so equal values of different numeric types collapse as they load. */
    public OqSet(Collection<?> items) {
        this.backing = new LinkedHashSet<>(Math.max(1, items.size()));
        addAll(items);
    }

    @Override
    public boolean add(Object item) {
        return backing.add(Value.of(item));
    }

    @Override
    public boolean contains(Object item) {
        return backing.contains(Value.of(item));
    }

    @Override
    public boolean remove(Object item) {
        return backing.remove(Value.of(item));
    }

    /**
     * Removes every item of {@code items}. {@link AbstractSet#removeAll} is not usable here:
     * it picks its strategy from {@code size()}, and the failing path calls {@code remove} on
     * whichever side is larger, which would test raw values against boxes.
     */
    @Override
    public boolean removeAll(Collection<?> items) {
        boolean changed = false;
        for (Object item : items) changed |= remove(item);
        return changed;
    }

    /** Keeps only the items in {@code items}, for the same reason as {@link #removeAll}. */
    @Override
    public boolean retainAll(Collection<?> items) {
        OqSet keep = new OqSet(items);
        boolean changed = false;
        Iterator<Value> each = backing.iterator();
        while (each.hasNext()) {
            if (!keep.contains(each.next().raw())) {
                each.remove();
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public Iterator<Object> iterator() {
        Iterator<Value> each = backing.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return each.hasNext();
            }

            @Override
            public Object next() {
                return each.next().raw();
            }

            @Override
            public void remove() {
                each.remove();
            }
        };
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public Object[] toArray() {
        Object[] result = new Object[backing.size()];
        int i = 0;
        for (Value value : backing) result[i++] = value.raw();
        return result;
    }

    @Override
    public String toString() {
        return Values.repr(this);
    }
}
