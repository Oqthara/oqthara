package dev.oqthara.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Values {
    private Values() {
    }

    public static final Object NONE = null;

    public static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Long l) return l != 0L;
        if (value instanceof Double d) return d != 0.0;
        if (value instanceof String s) return !s.isEmpty();
        if (value instanceof List<?> l) return !l.isEmpty();
        if (value instanceof Map<?, ?> m) return !m.isEmpty();
        if (value instanceof Set<?> s) return !s.isEmpty();
        if (value instanceof OqRange r) return r.size() > 0;
        return true;
    }

    public static boolean isNumber(Object value) {
        return value instanceof Long || value instanceof Double;
    }

    /** The first double that no longer fits in a {@code long}: 2^63. */
    private static final double LONG_LIMIT = 0x1.0p63;

    /**
     * True when casting {@code value} to {@code long} is exact and cannot saturate. Every
     * conversion that formats a decimal as a whole number goes through this, so a huge double
     * becomes {@code 1.0E300} instead of silently turning into {@link Long#MAX_VALUE}.
     */
    public static boolean fitsInLong(double value) {
        return Double.isFinite(value) && value >= -LONG_LIMIT && value < LONG_LIMIT;
    }

    public static double asDouble(Object value) {
        if (value instanceof Long l) return l;
        if (value instanceof Double d) return d;
        throw new IllegalArgumentException("not a number: " + (value == null ? "null" : value.getClass()));
    }

    public static long asLong(Object value) {
        if (value instanceof Long l) return l;
        if (value instanceof Double d) return d.longValue();
        if (value instanceof Boolean) throw new IllegalArgumentException("not a number: boolean");
        throw new IllegalArgumentException("not a number: " + (value == null ? "null" : value.getClass()));
    }

    public static String typeName(Object value) {
        if (value == null) return "nothing";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Long) return "number";
        if (value instanceof Double) return "decimal";
        if (value instanceof String) return "text";
        if (value instanceof OqTuple) return "tuple";
        if (value instanceof List<?>) return "list";
        if (value instanceof Map<?, ?>) return "map";
        if (value instanceof OqFrozenSet) return "frozenset";
        if (value instanceof Set<?>) return "set";
        if (value instanceof OqRange) return "range";
        if (value instanceof OqData d) return d.typeName();
        if (value instanceof OqInstance instance) return instance.className();
        if (value instanceof OqIterator) return "iterator";
        if (value instanceof OqTask) return "task";
        if (value instanceof OqMutex) return "mutex";
        if (value instanceof OqCallable c) return "function " + c.name();
        return "java " + value.getClass().getSimpleName();
    }

    /**
     * Explains why a value cannot be called, saying what the value is and what can be done.
     *
     * <p>Saying only "which cannot be called" is wrong for a Java object: its methods are
     * callable, and a value returned by Java such as {@code Comparator.naturalOrder()} answers to
     * {@code compare} perfectly well. What such a value cannot do is stand where the language
     * expects a {@code fun}, so the help names that difference instead of implying the object is
     * inert.
     */
    public static String notCallableHelp(Object value) {
        String name = typeName(value);
        if (name.startsWith("java ")) {
            return "It is " + name + ", a Java object: call its methods with '.', but it is not"
                    + " a fun.";
        }
        return "It is " + name + ", which cannot be called.";
    }

    public static String display(Object value) {
        if (value == null) return "nothing";
        if (value instanceof String s) return s;
        return repr(value);
    }

    public static String repr(Object value) {
        if (value == null) return "nothing";
        if (value instanceof Boolean b) return b ? "true" : "false";
        if (value instanceof Long l) return l.toString();
        if (value instanceof Double d) return formatDouble(d);
        if (value instanceof String s) return quote(s);
        if (value instanceof OqTuple tuple) {
            StringBuilder sb = new StringBuilder("(");
            int size = tuple.size();
            for (int i = 0; i < size; i++) {
                if (i > 0) sb.append(", ");
                sb.append(repr(tuple.get(i)));
            }
            if (size == 1) sb.append(',');
            return sb.append(')').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(repr(list.get(i)));
            }
            return sb.append(']').toString();
        }
        if (value instanceof OqFrozenSet frozen) {
            StringBuilder sb = new StringBuilder("frozenset({");
            boolean first = true;
            for (Object item : frozen) {
                if (!first) sb.append(", ");
                sb.append(repr(item));
                first = false;
            }
            return sb.append("})").toString();
        }
        if (value instanceof Set<?> set) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Object item : set) {
                if (!first) sb.append(", ");
                sb.append(repr(item));
                first = false;
            }
            return sb.append('}').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
                first = false;
            }
            return sb.append('}').toString();
        }
        if (value instanceof OqRange r) return r.toString();
        if (value instanceof OqIterator it) return it.toString();
        if (value instanceof OqInstance instance) return "<" + instance.className() + " object>";
        if (value instanceof OqData d) {
            StringBuilder sb = new StringBuilder(d.typeName()).append('(');
            boolean first = true;
            for (Map.Entry<String, Object> e : d.fields().entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append(": ").append(repr(e.getValue()));
                first = false;
            }
            return sb.append(')').toString();
        }
        if (value instanceof OqCallable c) return "<function " + c.name() + ">";
        return String.valueOf(value);
    }

    public static String formatDouble(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "infinity" : "-infinity";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.format(java.util.Locale.ROOT, "%.1f", d);
        }
        String s = String.format(java.util.Locale.ROOT, "%.12f", d);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s + "0";
        return s;
    }

    /**
     * The number of characters in {@code s}, counting code points rather than UTF-16 units.
     *
     * <p>Text is a sequence of code points per [ADR-0024]; a character outside the basic
     * multilingual plane occupies two {@code char}s but is one character to the language.
     */
    public static int codePointCount(String s) {
        return s.codePointCount(0, s.length());
    }

    /**
     * The character at {@code index}, counting code points rather than UTF-16 units.
     *
     * <p>The caller is responsible for bounds; {@link #index} checks against
     * {@link #codePointCount} first.
     */
    public static String codePointAt(String s, int index) {
        return new String(Character.toChars(s.codePointAt(s.offsetByCodePoints(0, index))));
    }

    /**
     * Every character of {@code s} as a one-character text, in order.
     *
     * <p>This is the single implementation behind iteration, {@code .to_list()},
     * {@code .to_set()}, {@code .sorted()} and destructuring, so that all of them agree on
     * what a character is.
     */
    public static List<Object> textCharacters(String s) {
        List<Object> chars = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            chars.add(new String(Character.toChars(cp)));
            i += Character.charCount(cp);
        }
        return chars;
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /**
     * The one definition of {@code ==}, applied at every depth (ADR-0017). Numbers widen, lists
     * and tuples compare structurally in order, maps and sets compare without order, and a
     * tuple never equals a list. Before ADR-0017 the numeric rule stopped at the container
     * boundary, so {@code 1 == 1.0} was true while <code>[1] == [1.0]</code> was false.
     */
    public static boolean equal(Object a, Object b) {
        if (a == null || b == null) return a == b;
        // A class instance is a reference type (ADR-0027 D5): equality is identity, and the
        // fields are never consulted. This must precede the container branches below.
        if (a instanceof OqInstance || b instanceof OqInstance) return a == b;
        if (a instanceof OqData x && b instanceof OqData y) return x.equals(y);
        if (a instanceof OqTuple != b instanceof OqTuple) return false;
        if (a instanceof Long x && b instanceof Double y) return x.doubleValue() == y;
        if (a instanceof Double x && b instanceof Long y) return x == y.doubleValue();
        if (isNumber(a) && isNumber(b)) return asDouble(a) == asDouble(b);
        if (a instanceof OqTuple x && b instanceof OqTuple y) return tuplesEqual(x, y);
        if (a instanceof List<?> x && b instanceof List<?> y) return listsEqual(x, y);
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) return mapsEqual(x, y);
        if (a instanceof OqFrozenSet != b instanceof OqFrozenSet) return false;
        if (a instanceof Set<?> x && b instanceof Set<?> y) return setsEqual(x, y);
        return a.equals(b);
    }

    /**
     * Compares the fields of two {@link OqData} values, in declaration order, using
     * {@link #equal} so numbers widen inside an object.
     */
    static boolean dataFieldsEqual(java.util.LinkedHashMap<String, Object> a,
                                   java.util.LinkedHashMap<String, Object> b) {
        if (a.size() != b.size()) return false;
        java.util.Iterator<java.util.Map.Entry<String, Object>> left = a.entrySet().iterator();
        java.util.Iterator<java.util.Map.Entry<String, Object>> right = b.entrySet().iterator();
        while (left.hasNext() && right.hasNext()) {
            java.util.Map.Entry<String, Object> l = left.next();
            java.util.Map.Entry<String, Object> r = right.next();
            if (!l.getKey().equals(r.getKey())) return false;
            if (!equal(l.getValue(), r.getValue())) return false;
        }
        return true;
    }

    private static boolean tuplesEqual(OqTuple a, OqTuple b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!equal(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private static boolean listsEqual(List<?> a, List<?> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!equal(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    /**
     * Two maps are equal when they have the same size and each key of one finds an equal value
     * in the other. Keys are matched with {@link #equal} rather than by hash, so a map built
     * with {@code {1: "a"}} equals one built with {@code {1.0: "a"}} regardless of the
     * container that backs either.
     */
    private static boolean mapsEqual(Map<?, ?> a, Map<?, ?> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<?, ?> entry : a.entrySet()) {
            boolean found = false;
            for (Map.Entry<?, ?> other : b.entrySet()) {
                if (equal(entry.getKey(), other.getKey())) {
                    if (!equal(entry.getValue(), other.getValue())) return false;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** Two sets are equal when they have the same size and every member of one is in the other. */
    private static boolean setsEqual(Set<?> a, Set<?> b) {
        if (a.size() != b.size()) return false;
        for (Object item : a) {
            boolean found = false;
            for (Object other : b) {
                if (equal(item, other)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    public static int compare(Object a, Object b, Span span) {
        if (isNumber(a) && isNumber(b)) {
            return Double.compare(asDouble(a), asDouble(b));
        }
        if (a instanceof String x && b instanceof String y) {
            return x.compareTo(y);
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return Boolean.compare(x, y);
        }
        if (a instanceof OqTuple != b instanceof OqTuple) {
            throw new OqError("cannot compare " + typeName(a) + " with " + typeName(b), span,
                    "A tuple is compared with another tuple, and a list with another list."
                            + " Convert the tuple with list(...) first.");
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            int limit = Math.min(x.size(), y.size());
            for (int i = 0; i < limit; i++) {
                int comparison = compare(x.get(i), y.get(i), span);
                if (comparison != 0) return comparison;
            }
            return Integer.compare(x.size(), y.size());
        }
        throw new OqError("cannot compare " + typeName(a) + " with " + typeName(b), span,
                "Comparison works between two numbers, two texts, two booleans or two lists."
                        + hintFor(a, b));
    }

    public static Object negate(Object value, Span span) {
        if (value instanceof Long l) return -l;
        if (value instanceof Double d) return -d;
        throw new OqError("cannot negate " + typeName(value), span,
                "The '-' operator needs a number, but this is " + typeName(value) + ". "
                        + "Use text(number) to convert a number to text, or number(text) the other way.");
    }

    private static String hintFor(Object a, Object b) {
        if (isNumber(a) != isNumber(b)) {
            return " One side is a number and the other is " + typeName(isNumber(a) ? b : a)
                    + "; convert it with number(...) or text(...).";
        }
        if (a instanceof String && b instanceof String) return "";
        return " Both sides must be the same kind of value.";
    }

    public static Object not(Object value) {
        return !truthy(value);
    }

    public static Object add(Object a, Object b, Span span) {
        if (isNumber(a) && isNumber(b)) {
            if (a instanceof Long x && b instanceof Long y) return x + y;
            return asDouble(a) + asDouble(b);
        }
        if (a instanceof String || b instanceof String) {
            return display(a) + display(b);
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            requireNotTuples("add", a, b, span);
            List<Object> result = new ArrayList<>(x);
            result.addAll(y);
            return result;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            Map<Object, Object> result = new OqMap();
            x.forEach(result::put);
            y.forEach(result::put);
            return result;
        }
        if (a instanceof Set<?> x && b instanceof Set<?> y) {
            Set<Object> result = new OqSet(x);
            result.addAll(y);
            return result;
        }
        throw new OqError(
                "cannot add " + typeName(a) + " and " + typeName(b),
                span,
                "+ works with numbers, text, lists, maps and sets.");
    }

    public static Object subtract(Object a, Object b, Span span) {
        if (isNumber(a) && isNumber(b)) {
            if (a instanceof Long x && b instanceof Long y) return x - y;
            return asDouble(a) - asDouble(b);
        }
        if (a instanceof Set<?> x && b instanceof Set<?> y) {
            Set<Object> result = new OqSet(x);
            result.removeAll(y);
            return result;
        }
        throw new OqError("cannot subtract " + typeName(b) + " from " + typeName(a), span);
    }

    public static Object multiply(Object a, Object b, Span span) {
        if (isNumber(a) && isNumber(b)) {
            if (a instanceof Long x && b instanceof Long y) return x * y;
            return asDouble(a) * asDouble(b);
        }
        if (a instanceof String s && b instanceof Long n) return repeat(s, n, span);
        if (a instanceof Long n && b instanceof String s) return repeat(s, n, span);
        if (a instanceof List<?> l && b instanceof Long n) {
            requireNotTuples("multiply", a, b, span);
            long size = repeatCount(l.size(), n, span);
            if (size > MAX_COLLECTION_SIZE) {
                throw new OqError("the repeated list would have " + size + " items", span,
                        "The limit is " + MAX_COLLECTION_SIZE
                                + ". Repeat fewer times, or build the list as you need it.");
            }
            // An empty list has size 0 whatever the count, so the guard above cannot see a
            // billion repetitions of it and the loop below would run that many times. There is
            // only one answer, so return it without looping.
            if (l.isEmpty()) return new ArrayList<>();
            List<Object> result = new ArrayList<>();
            for (long i = 0; i < n; i++) result.addAll(l);
            return result;
        }
        throw new OqError("cannot multiply " + typeName(a) + " by " + typeName(b), span);
    }

    /**
     * Refuses arithmetic on tuples. They are read-only sequences, and because they share the list
     * code path, ordinary list arithmetic would silently turn them into mutable lists.
     */
    private static void requireNotTuples(String operation, Object a, Object b, Span span) {
        if (a instanceof OqTuple || b instanceof OqTuple) {
            throw new OqError("cannot " + operation + " a tuple", span,
                    "Tuples do not support arithmetic in Alpha 3. Convert one with list(...) "
                            + "first if you need list behaviour.");
        }
    }

    /** The most a single repetition will build, so one expression cannot exhaust the heap. */
    private static final long MAX_REPEAT_LENGTH = 64L * 1024 * 1024;

    private static final long MAX_COLLECTION_SIZE = 10_000_000L;

    /**
     * Repeats text. {@link String#repeat} takes an {@code int}, so a {@code Long} count above
     * {@link Integer#MAX_VALUE} must be refused, not silently wrapped to a small number.
     */
    private static String repeat(String text, Long count, Span span) {
        long n = Math.max(0, count);
        // Empty text repeats to empty text whatever the count, and the total below stays 0, so
        // the guard would not fire and the cast at the end would truncate a count in the billions
        // to a negative number. There is one answer, so give it without casting.
        if (text.isEmpty() || n == 0) return "";
        long total = repeatCount(text.length(), n, span);
        if (total > MAX_REPEAT_LENGTH) {
            throw new OqError("the repeated text would be " + total + " characters", span,
                    "The limit is " + MAX_REPEAT_LENGTH
                            + ". Repeat fewer times, or write it in pieces.");
        }
        // A non-empty text makes `total` at least `n`, and `total` was just bounded by
        // MAX_REPEAT_LENGTH, which is far below Integer.MAX_VALUE. The cast is safe here only
        // because of that ordering.
        return text.repeat((int) n);
    }

    /**
     * Multiplies a size by a repeat count without overflowing.
     *
     * <p>A plain {@code size * n} wraps silently, so a huge count could produce a tiny,
     * wrong result and then be cast to an {@code int} that no longer means anything.
     */
    private static long repeatCount(long size, long n, Span span) {
        try {
            return Math.multiplyExact(size, n);
        } catch (ArithmeticException overflow) {
            throw new OqError("the repetition is too large to build", span,
                    "The count is larger than the machine can hold. Repeat fewer times.");
        }
    }

    public static Object divide(Object a, Object b, Span span) {
        if (isNumber(a) && isNumber(b)) {
            double divisor = asDouble(b);
            if (divisor == 0.0) throw new OqError("division by zero", span, "Check the value you are dividing by.");
            if (a instanceof Long x && b instanceof Long y && x % y == 0) return x / y;
            return asDouble(a) / divisor;
        }
        throw new OqError("cannot divide " + typeName(a) + " by " + typeName(b), span);
    }

    public static Object modulo(Object a, Object b, Span span) {
        if (isNumber(a) && isNumber(b)) {
            if (a instanceof Long x && b instanceof Long y && y != 0) return x % y;
            double divisor = asDouble(b);
            if (divisor == 0.0) throw new OqError("division by zero", span);
            return asDouble(a) % divisor;
        }
        throw new OqError("cannot take remainder of " + typeName(a) + " and " + typeName(b), span);
    }

    public static Object power(Object a, Object b, Span span) {
        if (isNumber(a) && isNumber(b)) {
            double result = Math.pow(asDouble(a), asDouble(b));
            // `Math.pow(10, 400)` is infinity, and `(long) Double.POSITIVE_INFINITY` is
            // Long.MAX_VALUE, so an unchecked cast turned an unrepresentable power into a
            // plausible-looking wrong answer. Only an exact whole value that fits stays a
            // long; everything else keeps its double, including infinity.
            if (a instanceof Long && b instanceof Long n && n >= 0
                    && result == Math.rint(result) && fitsInLong(result)) {
                return (long) result;
            }
            return result;
        }
        throw new OqError("cannot raise " + typeName(a) + " to " + typeName(b), span);
    }

    /**
     * Resolves a position in a sequence, applying the one index rule the language has.
     *
     * <p>Negatives count from the end, so {@code -1} is the last element. A position that names
     * nothing is refused here rather than at a JDK call, which is what turned {@code insert} past
     * the end into an {@code IndexOutOfBoundsException} reported as a defect in the language.
     *
     * <p>{@code allowEnd} admits {@code size} as a position. Only {@code insert} passes true: at
     * the end it appends, and every other consumer of this method reads or removes an element that
     * must already be there. Callers wanting count semantics ({@code take}, {@code skip},
     * {@code subList}) clamp instead and must not come here.
     *
     * @return a resolved index in {@code [0, size)} when {@code allowEnd} is false, or
     *     {@code [0, size]} when it is true
     */
    public static int position(Object key, int size, String kind, boolean allowEnd, Span span) {
        long i = integerIndex(key, span);
        long position = i < 0 ? size + i : i;
        long limit = allowEnd ? size : size - 1;
        if (position < 0 || position > limit) {
            throw new OqError(
                    "index " + i + " is out of range for " + kind + " of length " + size,
                    span,
                    "Valid indexes are 0 to " + (size - 1) + (size == 0 ? " (the list is empty)" : "")
                            + ", or negative from the end.");
        }
        return (int) position;
    }

    public static Object index(Object target, Object key, Span span) {
        if (target == null) {
            throw new OqError("cannot index nothing", span, "The value is nothing; check for a missing result.");
        }
        if (target instanceof List<?> list) {
            String kind = target instanceof OqTuple ? "a tuple" : "a list";
            return list.get(position(key, list.size(), kind, false, span));
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(key);
        }
        if (target instanceof String s) {
            long i = integerIndex(key, span);
            int count = codePointCount(s);
            long index = i < 0 ? count + i : i;
            if (index < 0 || index >= count) {
                throw new OqError("index " + i + " is out of range for text of length " + count, span);
            }
            return codePointAt(s, (int) index);
        }
        if (target instanceof OqData d) {
            if (key instanceof String name) return d.get(name);
            throw new OqError("data field name must be text, got " + typeName(key), span);
        }
        if (target instanceof OqRange r) {
            long i = integerIndex(key, span);
            List<Object> items = r.toList();
            long index = i < 0 ? items.size() + i : i;
            if (index < 0 || index >= items.size()) {
                throw new OqError("index " + i + " is out of range for " + r, span);
            }
            return items.get((int) index);
        }
        throw new OqError("cannot index " + typeName(target), span);
    }

    public static void setIndex(Object target, Object key, Object value, Span span) {
        if (target instanceof OqTuple) {
            throw new OqError(OqTuple.IMMUTABLE, span,
                    "Tuples cannot change after they are made. Build a new tuple instead,"
                            + " or use a list if the items need to change.");
        }
        if (target instanceof List<?> raw) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) raw;
            long i = integerIndex(key, span);
            long index = i < 0 ? list.size() + i : i;
            if (index < 0 || index >= list.size()) {
                throw new OqError(
                        "index " + i + " is out of range for a list of length " + list.size(), span,
                        "To add a new item at the end, use push(list, value) or list += [value].");
            }
            list.set((int) index, value);
            return;
        }
        if (target instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) raw;
            Value.requireHashable(key, "a map key", span);
            map.put(key, value);
            return;
        }
        // An instance has no index form. Writing through brackets used to reach a declared field
        // directly and so skipped the privacy rule that dot-assignment enforces, while there was
        // no matching read path to justify it (ADR-0027 D7). Fields are named, not numbered.
        if (target instanceof OqInstance instance) {
            String hint = instance.fields().isEmpty()
                    ? "Declare the field in the class header, then assign it by name."
                    : "Write the field by name with a dot, for example: obj."
                            + instance.fields().keySet().iterator().next();
            throw new OqError("type " + instance.className() + " cannot be assigned by index", span,
                    hint);
        }
        if (target instanceof OqData d) {
            if (key instanceof String name) {
                if (!d.has(name)) {
                    throw new OqError("type " + d.typeName() + " has no field named '" + name + "'", span);
                }
                d.set(name, value);
                return;
            }
            throw new OqError("data field name must be text, got " + typeName(key), span);
        }
        throw new OqError("cannot assign into " + typeName(target), span);
    }

    /**
     * Resolves a slice bound against a sequence of the given length.
     *
     * <p>An omitted bound is supplied here as {@code null} and means the corresponding end. A
     * present bound is a position, so it follows the same negative rule as an index, and is then
     * clamped: a bound past the end names the end. That differs from {@link #position} on purpose
     * -- a bound delimits a range, so {@code xs[i:i + 10]} must not fail because {@code xs} is
     * short, whereas an index selects one element and there is no element to mean "off the end".
     */
    private static int sliceBound(Object bound, int size, int whenOmitted, String which, Span span) {
        if (bound == null) return whenOmitted;
        long i = integerIndex(bound, span, which);
        long resolved = i < 0 ? size + i : i;
        if (resolved < 0) return 0;
        if (resolved > size) return size;
        return (int) resolved;
    }

    /**
     * Slices a list, tuple, range or text.
     *
     * <p>Bounds are positions and are clamped after the negative rule; a step of zero is refused
     * because it names no traversal. The result is always a new value, and a text slice is text,
     * because a substring is what a text slice is for.
     */
    public static Object slice(Object target, Object from, Object to, Object step, Span span) {
        if (target == null) {
            throw new OqError("cannot slice nothing", span,
                    "The value is nothing; check for a missing result.");
        }
        long stride = 1;
        if (step != null) {
            stride = integerIndex(step, span, "a slice step");
            if (stride == 0) {
                throw new OqError("a slice step cannot be 0", span,
                        "A step of 1 takes every item, 2 takes every other one, and -1 reverses.");
            }
        }

        if (target instanceof String s) {
            StringBuilder out = new StringBuilder();
            for (Object c : sliceItems(textCharacters(s), from, to, stride, span)) out.append((String) c);
            return out.toString();
        }
        if (target instanceof OqRange r) {
            return new ArrayList<>(sliceItems(r.toList(), from, to, stride, span));
        }
        if (target instanceof OqTuple) {
            // A tuple stays a tuple, matching `subList` on a tuple: immutability is part of the
            // value, and a caller who sliced one to inspect a run should not be handed a mutable
            // copy by accident.
            return OqTuple.of(sliceItems((List<?>) target, from, to, stride, span));
        }
        if (target instanceof List<?> list) {
            return new ArrayList<>(sliceItems(list, from, to, stride, span));
        }
        if (target instanceof OqSet set) {
            return new ArrayList<>(sliceItems(new ArrayList<>(set), from, to, stride, span));
        }
        throw new OqError("cannot slice " + typeName(target), span,
                "Slicing works on a list, a tuple, a range, text or a set.");
    }

    private static List<Object> sliceItems(List<?> list, Object from, Object to, long stride, Span span) {
        int size = list.size();
        List<Object> out = new ArrayList<>();

        if (stride > 0) {
            int start = sliceBound(from, size, 0, "a slice start", span);
            int end = sliceBound(to, size, size, "a slice end", span);
            for (long i = start; i < end; i += stride) out.add(list.get((int) i));
            return out;
        }

        // Travelling backwards, an omitted bound means the far end in that direction, and a
        // supplied bound that already meant "from the end" is resolved after the negative rule
        // like any other position.
        int start = sliceBound(from, size, size - 1, "a slice start", span);
        if (start >= size) start = size - 1;
        int end = sliceBound(to, size, -1, "a slice end", span);
        for (long i = start; i > end && i >= 0; i += stride) out.add(list.get((int) i));
        return out;
    }

    public static long integerIndex(Object key, Span span) {
        return integerIndex(key, span, "an index");
    }

    private static long integerIndex(Object key, Span span, String what) {
        if (key instanceof Long l) return l;
        if (key instanceof Double d && d == Math.rint(d)) return d.longValue();
        throw new OqError(what + " must be a whole number, got " + typeName(key), span);
    }

    public static int size(Object value, Span span) {
        if (value instanceof String s) return codePointCount(s);
        if (value instanceof List<?> l) return l.size();
        if (value instanceof Map<?, ?> m) return m.size();
        if (value instanceof Set<?> s) return s.size();
        if (value instanceof OqRange r) return (int) r.size();
        if (value instanceof OqIterator) {
            throw new OqError("an iterator has no length", span,
                    "An iterator is a position, not a collection. Use has_next() to ask what is left.");
        }
        throw new OqError("length() needs text, list, map, set or range, got " + typeName(value), span);
    }

    /**
     * The count behind {@code length()}, which must be a 64-bit number like every other number.
     *
     * <p>{@link #size} narrows a range's size to {@code int}, so a range longer than
     * {@code Integer.MAX_VALUE} came back negative: {@code length(0 to 3000000000)} printed
     * {@code -1294967295}. A range is counted in {@code long} here and every other shape still
     * goes through {@link #size}. {@code size} keeps its {@code int} signature because this class
     * is published as {@code dev.oqthara:oq-runtime}; widening the return type would break
     * callers outside this repository.
     */
    public static long length(Object value, Span span) {
        if (value instanceof OqRange r) return r.size();
        return size(value, span);
    }

    /**
     * The ordered elements of a value a destructuring target list can take apart, or
     * {@code null} when the value has no such shape. Maps yield their keys and text yields
     * its characters, matching what {@code for ... in} iterates.
     */
    public static List<Object> destructuringParts(Object value) {
        if (value instanceof OqData) return null;
        if (value instanceof OqInstance) return null;
        if (value instanceof List<?> list) return new ArrayList<>(list);
        if (value instanceof Set<?> set) return new ArrayList<>(set);
        if (value instanceof OqRange range) return range.toList();
        if (value instanceof Map<?, ?> map) return new ArrayList<>(map.keySet());
        if (value instanceof String text) return textCharacters(text);
        return null;
    }

    /**
     * The parts of {@code value}, checked against {@code count} targets. This is the single
     * implementation of the destructuring rule: the interpreter and compiled code both call
     * it, so a program cannot be accepted by one and rejected by the other.
     */
    public static List<Object> destructuringParts(Object value, int count, Span span) {
        List<Object> parts = destructuringParts(value);
        if (parts == null) {
            throw new OqError("cannot destructure " + typeName(value), span,
                    "Destructuring works with tuples, lists, sets, maps (keys), "
                            + "ranges and text.");
        }
        if (parts.size() != count) {
            throw new OqError("cannot destructure " + parts.size() + " item"
                    + (parts.size() == 1 ? "" : "s") + " into " + count + " names", span,
                    "The number of names must match the number of items exactly; "
                            + "there is no rest target.");
        }
        return parts;
    }

    /**
     * The items a loop or a collection function walks, captured when iteration starts.
     *
     * <p>The result is always a <em>snapshot</em>: {@link List#copyOf}
     * (or its mutable equivalent) rather than the caller's own collection. Returning the live
     * collection let the loop body reach back into it, and {@code java.util} then threw
     * {@code ConcurrentModificationException} from a place that had no Oqthara diagnostic to
     * give, so a program that appended to a list while looping over it died with
     * {@code internal error: ... This is a bug in Oqthara}. Snapshotting makes that program
     * well defined instead: it visits exactly the items present when the loop began. The
     * alternative — refusing the mutation — cannot work, because the loop cannot know which
     * of its own operations are the ones mutating, and the runtime has no way to attribute a
     * mutation to a particular loop.
     *
     * <p>Both backends share this one function, so the interpreter and compiled bytecode
     * necessarily agree on which items are visited.
     */
    /**
     * The snapshot from {@link #iterable} as a list, for code that needs indexing or a size.
     *
     * <p>{@code iterable} has to return {@link Iterable} because that is all a loop needs, but
     * its result is always a list in practice. Callers used to write
     * {@code (List<?>) iterable(...)} and were right by accident: the cast compiled, then
     * {@code zip({1, 2}, {3, 4})} threw {@code ClassCastException} because a set went straight
     * through. Going through this method makes the list-ness a fact of the API rather than an
     * assumption at each call site.
     */
    public static List<?> iterableList(Object value, Span span) {
        Iterable<?> items = iterable(value, span);
        if (items instanceof List<?> list) return list;
        List<Object> copy = new ArrayList<>();
        for (Object item : items) copy.add(item);
        return copy;
    }

    public static Iterable<?> iterable(Object value, Span span) {
        // A for loop over an iterator drains it, per ADR-0020: an iterator is a position, so
        // there is nothing to copy and re-walking it would repeat items forever.
        if (value instanceof OqIterator it) {
            List<Object> rest = new ArrayList<>();
            while (it.hasNext()) rest.add(it.next());
            return rest;
        }
        if (value instanceof List<?> l) return new ArrayList<>(l);
        if (value instanceof Set<?> s) return new ArrayList<>(s);
        if (value instanceof OqRange r) return r.toList();
        if (value instanceof Map<?, ?> m) return new ArrayList<>(m.keySet());
        if (value instanceof String s) return textCharacters(s);
        throw new OqError("cannot loop over " + typeName(value), span,
                "Loops work with lists, sets, maps (keys), ranges and text. "
                        + (isNumber(value)
                        ? "For a count, use a range: for n in 1 to " + display(value)
                        : "This is " + typeName(value) + "."));
    }

    /**
     * A live iterator over {@code value}, per ADR-0020.
     *
     * <p>Unlike {@link #iterable}, this does not copy. A {@code for} loop copies because
     * ADR-0016 promises that mutating a collection inside the loop cannot disturb the walk;
     * an explicit iterator is the caller asking to walk the real thing, and copying would
     * make it a slower {@code for} with no extra guarantee.
     */
    public static OqIterator iterator(Object value, Span span) {
        if (value instanceof OqIterator existing) return existing;
        if (value instanceof List<?> l) return new OqIterator(l.iterator(), value);
        if (value instanceof Set<?> s) return new OqIterator(s.iterator(), value);
        if (value instanceof OqRange r) return new OqIterator(r.toList().iterator(), value);
        if (value instanceof Map<?, ?> m) return new OqIterator(m.keySet().iterator(), value);
        if (value instanceof String s) return new OqIterator(textCharacters(s).iterator(), value);
        throw new OqError("cannot iterate over " + typeName(value), span,
                "iter() works with lists, tuples, sets, maps (keys), ranges and text. "
                        + (isNumber(value)
                        ? "For a count, use a range: (1 to " + display(value) + ").iter()"
                        : "This is " + typeName(value) + "."));
    }

    /** The next item of {@code it}, or an error naming the fix when it is spent. */
    /**
     * Advance a live iterator, translating the host's fail-fast check into an Oqthara error.
     *
     * <p>An iterator reads the collection it came from rather than a copy (ADR-0020), so
     * changing that collection mid-walk makes {@code java.util} throw
     * {@code ConcurrentModificationException}. That escaped as
     * {@code internal error: ... This is a bug in Oqthara}, which is wrong twice: it is not
     * a bug, and it tells the user nothing. The walk is genuinely invalid, so the error says
     * so and names the fix. {@code for} is unaffected because it snapshots (ADR-0016).
     */
    public static Object iteratorNext(OqIterator it, Span span) {
        if (!it.hasNext()) {
            throw new OqError("this iterator is exhausted", span,
                    "Check has_next() before calling next().");
        }
        try {
            return it.next();
        } catch (java.util.ConcurrentModificationException e) {
            throw new OqError("the collection changed while this iterator was walking it", span,
                    "An iterator reads the live collection, so it cannot survive a change. "
                            + "Use `for`, which walks a snapshot, or finish iterating first.");
        }
    }

    public static boolean contains(Object container, Object item, Span span) {        if (container instanceof List<?> l) return l.stream().anyMatch(x -> equal(x, item));
        if (container instanceof Set<?> s) return s.contains(item);
        if (container instanceof Map<?, ?> m) return m.containsKey(item);
        if (container instanceof String s) return s.contains(display(item));
        if (container instanceof OqRange r) {
            if (!isNumber(item)) return false;
            double d = asDouble(item);
            if (d != Math.rint(d)) return false;
            return r.contains((long) d);
        }
        throw new OqError("cannot check membership in " + typeName(container), span);
    }
}
