package dev.oqthara.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        if (value instanceof Set<?>) return "set";
        if (value instanceof OqRange) return "range";
        if (value instanceof OqData d) return d.typeName();
        if (value instanceof OqCallable c) return "function " + c.name();
        return "java " + value.getClass().getSimpleName();
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

    public static boolean equal(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof OqTuple != b instanceof OqTuple) return false;
        if (a instanceof Long x && b instanceof Double y) return x.doubleValue() == y;
        if (a instanceof Double x && b instanceof Long y) return x == y.doubleValue();
        if (isNumber(a) && isNumber(b)) return asDouble(a) == asDouble(b);
        return a.equals(b);
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
            Map<Object, Object> result = new LinkedHashMap<>();
            x.forEach(result::put);
            y.forEach(result::put);
            return result;
        }
        if (a instanceof Set<?> x && b instanceof Set<?> y) {
            Set<Object> result = new LinkedHashSet<>(x);
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
            Set<Object> result = new LinkedHashSet<>(x);
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
            long size = l.size() * n;
            if (size > MAX_COLLECTION_SIZE) {
                throw new OqError("the repeated list would have " + size + " items", span,
                        "The limit is " + MAX_COLLECTION_SIZE
                                + ". Repeat fewer times, or build the list as you need it.");
            }
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
        if (text.length() * n > MAX_REPEAT_LENGTH) {
            throw new OqError("the repeated text would be " + (text.length() * n) + " characters", span,
                    "The limit is " + MAX_REPEAT_LENGTH
                            + ". Repeat fewer times, or write it in pieces.");
        }
        return text.repeat((int) n);
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

    public static Object index(Object target, Object key, Span span) {
        if (target == null) {
            throw new OqError("cannot index nothing", span, "The value is nothing; check for a missing result.");
        }
        if (target instanceof List<?> list) {
            long i = integerIndex(key, span);
            long index = i < 0 ? list.size() + i : i;
            if (index < 0 || index >= list.size()) {
                String kind = target instanceof OqTuple ? "a tuple" : "a list";
                throw new OqError(
                        "index " + i + " is out of range for " + kind + " of length " + list.size(),
                        span,
                        "Valid indexes are 0 to " + (list.size() - 1) + (list.isEmpty() ? " (the list is empty)" : "")
                                + ", or negative from the end.");
            }
            return list.get((int) index);
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(key);
        }
        if (target instanceof String s) {
            long i = integerIndex(key, span);
            long index = i < 0 ? s.length() + i : i;
            if (index < 0 || index >= s.length()) {
                throw new OqError("index " + i + " is out of range for text of length " + s.length(), span);
            }
            return String.valueOf(s.charAt((int) index));
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
            throw new OqError("a tuple is immutable", span,
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
            map.put(key, value);
            return;
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

    public static long integerIndex(Object key, Span span) {
        if (key instanceof Long l) return l;
        if (key instanceof Double d && d == Math.rint(d)) return d.longValue();
        throw new OqError("index must be a number, got " + typeName(key), span);
    }

    public static int size(Object value, Span span) {
        if (value instanceof String s) return s.length();
        if (value instanceof List<?> l) return l.size();
        if (value instanceof Map<?, ?> m) return m.size();
        if (value instanceof Set<?> s) return s.size();
        if (value instanceof OqRange r) return (int) r.size();
        throw new OqError("length() needs text, list, map, set or range, got " + typeName(value), span);
    }

    public static Iterable<?> iterable(Object value, Span span) {
        if (value instanceof List<?> l) return l;
        if (value instanceof Set<?> s) return s;
        if (value instanceof OqRange r) return r.toList();
        if (value instanceof Map<?, ?> m) return m.keySet();
        if (value instanceof String s) {
            List<Object> chars = new ArrayList<>();
            for (int i = 0; i < s.length(); i++) chars.add(String.valueOf(s.charAt(i)));
            return chars;
        }
        throw new OqError("cannot loop over " + typeName(value), span,
                "Loops work with lists, sets, maps (keys), ranges and text. "
                        + (isNumber(value)
                        ? "For a count, use a range: for n in 1 to " + display(value)
                        : "This is " + typeName(value) + "."));
    }

    public static boolean contains(Object container, Object item, Span span) {
        if (container instanceof List<?> l) return l.stream().anyMatch(x -> equal(x, item));
        if (container instanceof Set<?> s) return s.contains(item);
        if (container instanceof Map<?, ?> m) return m.containsKey(item);
        if (container instanceof String s) return s.contains(display(item));
        if (container instanceof OqRange r) {
            if (!isNumber(item)) return false;
            double d = asDouble(item);
            if (d != Math.rint(d)) return false;
            long l = (long) d;
            return l >= r.start() && (r.inclusive() ? l <= r.end() : l < r.end());
        }
        throw new OqError("cannot check membership in " + typeName(container), span);
    }
}
