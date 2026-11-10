package dev.oqthara.runtime;

import java.util.List;
import java.util.Map;

/**
 * A box around one Oqthara value, giving it the equality and hash behaviour the language
 * promises. {@link OqSet} and {@link OqMap} use these as their internal keys.
 *
 * <p>This exists because those containers are keyed by hash, and Java's hash is not
 * compatible with Oqthara equality. {@link Values#equal} says that {@code 1} and
 * {@code 1.0} are the same number, but {@code Long} and {@code Double} hash differently, so
 * a raw {@code HashSet} could hold both. The consequences were that
 * <code>set([1, 1.0])</code> had length 2, that {@code 1 in set([1.0])} was false, and that
 * <code>{1: "a"}</code> could not be read back with the key {@code 1.0}. All three are now
 * correct.
 *
 * <p>Hashing is structural, matching {@link Values#equal}: lists and tuples hash by content,
 * maps by their entries, and sets by their elements. A list is therefore usable as a key
 * even though it is mutable; mutating one after using it as a key makes it unfindable,
 * exactly as already happens for {@link OqData}. A collection that contains itself cannot be
 * hashed or compared, so both stop at a bounded depth with a normal Oqthara error rather than
 * overflowing the stack.
 */
public final class Value {

    /**
     * How far nested hashing and equality look before giving up. A self-referential
     * collection overflows the JVM stack well before this, so the bound is a guard rail
     * rather than a usable limit.
     */
    private static final int MAX_DEPTH = 100;

    private final Object raw;

    private Value(Object raw) {
        this.raw = raw;
    }

    /** Boxes {@code raw}. Returns the same instance for an already-boxed value. */
    public static Value of(Object raw) {
        return raw instanceof Value v ? v : new Value(raw);
    }

    /** The value this box was made from, so callers outside the runtime never see a box. */
    public Object raw() {
        return raw;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Value)) return false;
        return Values.equal(((Value) other).raw, raw);
    }

    @Override
    public int hashCode() {
        return hash(raw, 0);
    }

    /** The structural hash of {@code value}, for {@link OqData} to delegate to. */
    static int hashing(Object value) {
        return hash(value, 0);
    }

    @Override
    public String toString() {
        return Values.repr(raw);
    }

    private static int hash(Object value, int depth) {
        if (value == null) return 0;
        if (depth > MAX_DEPTH) throw new InvalidHashException();
        if (value instanceof Long l) return Long.hashCode(l);
        if (value instanceof Double d) return hashDouble(d);
        if (value instanceof String s) return s.hashCode();
        if (value instanceof Boolean b) return b.hashCode();
        if (value instanceof OqData d) {
            int result = d.typeName().hashCode();
            for (java.util.Map.Entry<String, Object> field : d.fields().entrySet()) {
                result = 31 * result + field.getKey().hashCode();
                result = 31 * result + hash(field.getValue(), depth + 1);
            }
            return result;
        }
        if (value instanceof OqTuple t) {
            int result = 1;
            for (int i = 0; i < t.size(); i++) result = 31 * result + hash(t.get(i), depth + 1);
            return result;
        }
        if (value instanceof List<?> list) {
            int result = 1;
            for (Object item : list) result = 31 * result + hash(item, depth + 1);
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            int result = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result += hash(entry.getKey(), depth + 1) ^ hash(entry.getValue(), depth + 1);
            }
            return result;
        }
        if (value instanceof java.util.Set<?> set) {
            int result = 0;
            for (Object item : set) result += hash(item, depth + 1);
            return result;
        }
        return value.hashCode();
    }

    /**
     * Hashes a decimal so that it agrees with {@link Values#equal}: a whole decimal must hash
     * like the whole number it equals. {@code Double.hashCode(1.0)} and {@code Long.hashCode(1)}
     * differ, so the two are normalised onto the integral hash.
     */
    private static int hashDouble(double value) {
        if (Values.fitsInLong(value)) return Long.hashCode((long) value);
        return Double.hashCode(value);
    }

    /**
     * Raised by {@link #hashCode()} when a collection is nested past {@link #MAX_DEPTH}. This is
     * an {@link OqError} rather than a plain exception so that both backends report it as the
     * program's own failure — exit status 1 with a hint — instead of "internal error ... this is
     * a bug in Oqthara". Recursive data is usually a mistake in the program, not in the runtime.
     */
    static final class InvalidHashException extends OqError {
        private static final long serialVersionUID = 1L;

        InvalidHashException() {
            super("this collection contains itself, so it cannot be hashed or compared",
                    Errors.current(),
                    "A set or map key must be finite. Build a copy of the inner value with "
                            + "objects that do not point back at each other.");
        }
    }
}
