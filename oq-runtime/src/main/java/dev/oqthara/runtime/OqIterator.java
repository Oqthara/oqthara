package dev.oqthara.runtime;

import java.util.Iterator;

/**
 * A position in a collection, per ADR-0020.
 *
 * <p>An iterator is consumed once, unlike an iterable which can be walked repeatedly. It is
 * deliberately not a {@link java.util.Collection}: it has no length, cannot be indexed, and
 * cannot be tested for membership, because each of those would consume or depend on the
 * walk's position.
 *
 * <p>Equality is identity. Two iterators over the same list at different positions are not
 * the same value, and comparing positions would expose mutable state through {@code ==}.
 */
public final class OqIterator {

    private final Iterator<?> source;

    /** What was iterated, kept so {@code repr} can name the source. */
    private final Object origin;

    public OqIterator(Iterator<?> source, Object origin) {
        this.source = source;
        this.origin = origin;
    }

    public boolean hasNext() {
        return source.hasNext();
    }

    public Object next() {
        return source.next();
    }

    public Object origin() {
        return origin;
    }

    @Override
    public String toString() {
        return "iterator(" + Values.repr(origin) + ")";
    }
}
