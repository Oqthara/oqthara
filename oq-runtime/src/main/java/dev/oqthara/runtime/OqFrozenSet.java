package dev.oqthara.runtime;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * An Oqthara frozenset: insertion-ordered and never mutable, so it can be a map key or a
 * member of another set (ADR-0022).
 *
 * <p>This is the hashable half of the set surface. {@link OqSet} hashes structurally, so
 * inserting one into a map and then mutating it makes the entry unreachable; a frozenset
 * cannot be mutated, so the pair of ideas stays consistent. Membership uses {@link Value}
 * boxes for the same reason {@link OqSet} does, so {@code frozenset([1, 1.0])} has one item.
 *
 * <p>Equality is by members, matching {@link Values#equal}. A frozenset is deliberately not
 * equal to a set even when they hold the same items: they are different types with different
 * mutation promises, and ADR-0022 records that choice.
 */
public final class OqFrozenSet extends AbstractSet<Object> {

    private final LinkedHashSet<Value> backing;

    public OqFrozenSet() {
        this.backing = new LinkedHashSet<>();
    }

    /** Copies {@code items}, rejecting any that could later be mutated. */
    public OqFrozenSet(Collection<?> items) {
        this.backing = new LinkedHashSet<>(Math.max(1, items.size()));
        for (Object item : items) seed(item);
    }

    /** Fills the backing store during construction; the public mutators all refuse. */
    private void seed(Object item) {
        Value.requireHashable(item, "a set member");
        backing.add(Value.of(item));
    }

    @Override
    public boolean contains(Object item) {
        return backing.contains(Value.of(item));
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
    public Object[] toArray() {
        Object[] result = new Object[backing.size()];
        int i = 0;
        for (Value value : backing) result[i++] = value.raw();
        return result;
    }

    @Override
    public boolean add(Object item) {
        throw new OqError("a frozenset is immutable", null,
                "Build a new frozenset with union, intersection, difference or "
                        + "symmetric_difference instead. If you need to change it in place, use set.");
    }

    @Override
    public boolean remove(Object item) {
        throw new OqError("a frozenset is immutable", null,
                "Build a new frozenset without the item using difference instead.");
    }

    @Override
    public void clear() {
        throw new OqError("a frozenset is immutable", null,
                "Build an empty frozenset with frozenset() instead.");
    }

    @Override
    public boolean addAll(Collection<?> items) {
        throw new OqError("a frozenset is immutable", null,
                "Build a new frozenset with union instead.");
    }

    @Override
    public boolean removeAll(Collection<?> items) {
        throw new OqError("a frozenset is immutable", null,
                "Build a new frozenset with difference instead.");
    }

    @Override
    public boolean retainAll(Collection<?> items) {
        throw new OqError("a frozenset is immutable", null,
                "Build a new frozenset with intersection instead.");
    }

    @Override
    public boolean removeIf(java.util.function.Predicate<? super Object> filter) {
        throw new OqError("a frozenset is immutable", null,
                "Build a new frozenset from a filtered collection instead.");
    }

    @Override
    public String toString() {
        return Values.repr(this);
    }
}
