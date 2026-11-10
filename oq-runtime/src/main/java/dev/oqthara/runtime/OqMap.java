package dev.oqthara.runtime;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An Oqthara map: insertion-ordered, and keyed by Oqthara equality rather than Java's.
 *
 * <p>Like {@link OqSet} the backing store holds {@link Value} boxes and this class unwraps on
 * the way out, so {@code keys()}, {@code values()} and {@code entries()} hand back ordinary
 * values. A lookup with a whole decimal finds an entry written with a whole number, which is
 * what {@link Values#equal} promises everywhere else.
 */
public final class OqMap extends AbstractMap<Object, Object> {

    private final LinkedHashMap<Value, Object> backing;

    public OqMap() {
        this.backing = new LinkedHashMap<>();
    }

    public OqMap(int expectedSize) {
        this.backing = new LinkedHashMap<>(Math.max(1, expectedSize));
    }

    /** Copies {@code source}, so equivalent keys collapse as they are copied. */
    public OqMap(Map<?, ?> source) {
        this.backing = new LinkedHashMap<>(Math.max(1, source.size()));
        putAll(source);
    }

    @Override
    public Object put(Object key, Object value) {
        return backing.put(Value.of(key), value);
    }

    @Override
    public Object get(Object key) {
        return backing.get(Value.of(key));
    }

    @Override
    public boolean containsKey(Object key) {
        return backing.containsKey(Value.of(key));
    }

    @Override
    public Object remove(Object key) {
        return backing.remove(Value.of(key));
    }

    @Override
    public void putAll(Map<?, ?> source) {
        for (Map.Entry<?, ?> entry : source.entrySet()) put(entry.getKey(), entry.getValue());
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
    public Set<Object> keySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Object> iterator() {
                Iterator<Value> each = backing.keySet().iterator();
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
            public boolean contains(Object key) {
                return backing.containsKey(Value.of(key));
            }

            @Override
            public boolean remove(Object key) {
                return backing.remove(Value.of(key)) != null;
            }

            @Override
            public void clear() {
                backing.clear();
            }
        };
    }

    @Override
    public Collection<Object> values() {
        return backing.values();
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<Object, Object>> iterator() {
                Iterator<Map.Entry<Value, Object>> each = backing.entrySet().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return each.hasNext();
                    }

                    @Override
                    public Map.Entry<Object, Object> next() {
                        Map.Entry<Value, Object> entry = each.next();
                        return new AbstractMap.SimpleImmutableEntry<>(entry.getKey().raw(), entry.getValue());
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
            public void clear() {
                backing.clear();
            }
        };
    }

    /** The keys as a plain list, in insertion order. */
    public List<Object> rawKeys() {
        List<Object> result = new ArrayList<>(backing.size());
        for (Value key : backing.keySet()) result.add(key.raw());
        return result;
    }

    /** The key at {@code index} in insertion order. */
    public Object keyAt(int index) {
        int i = 0;
        for (Value key : backing.keySet()) {
            if (i++ == index) return key.raw();
        }
        throw new IndexOutOfBoundsException(index);
    }

    @Override
    public String toString() {
        return Values.repr(this);
    }
}
