package dev.oqthara.stdlib;

import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MethodResolverImpl implements dev.oqthara.runtime.MethodResolver {
    @Override
    public OqCallable resolve(Object receiver, String name, Span span) {
        if (receiver instanceof List<?> list) {
            if (receiver instanceof dev.oqthara.runtime.OqTuple) {
                OqCallable refusal = tupleRefusal(name, span);
                if (refusal != null) return refusal;
            }
            return listMethod(list, name, span);
        }
        if (receiver instanceof Map<?, ?> map) {
            return mapMethod(map, name, span);
        }
        if (receiver instanceof Set<?> set) {
            return setMethod(set, name, span);
        }
        if (receiver instanceof String text) {
            return textMethod(text, name, span);
        }
        if (Values.isNumber(receiver)) {
            return numberMethod(receiver, name, span);
        }
        if (receiver instanceof OqData data) {
            if (data.has(name)) {
                return new dev.oqthara.runtime.NativeFunction(name, 0, 0,
                        (host, args, callSpan) -> data.get(name));
            }
            return null;
        }
        return null;
    }

    private OqCallable listMethod(List<?> list, String name, Span span) {
        return switch (name) {
            case "length", "size" -> fixed(name, 0, (host, args) -> (long) list.size());
            case "push", "add" -> fixed(name, 1, (host, args) ->
                    mutateList(list, span, () -> {
                        asMutable(list).add(args.get(0));
                        return null;
                    }));
            case "pop" -> fixed(name, 0, (host, args) ->
                    mutateList(list, span, () -> {
                        List<Object> mutable = asMutable(list);
                        if (mutable.isEmpty()) throw new OqError("cannot pop from an empty list", span);
                        return mutable.remove(mutable.size() - 1);
                    }));
            case "first" -> fixed(name, 0, (host, args) -> list.isEmpty() ? null : list.get(0));
            case "last" -> fixed(name, 0, (host, args) -> list.isEmpty() ? null : list.get(list.size() - 1));
            case "get" -> fixed(name, 1, (host, args) ->
                    Values.index(list, args.get(0), span));
            case "insert" -> fixed(name, 2, (host, args) ->
                    mutateList(list, span, () -> {
                        asMutable(list).add(((Number) args.get(0)).intValue(), args.get(1));
                        return null;
                    }));
            case "remove" -> fixed(name, 1, (host, args) ->
                    mutateList(list, span, () -> {
                        List<Object> mutable = asMutable(list);
                        int index = args.get(0) instanceof Number n
                                ? n.intValue()
                                : indexOfValue(mutable, args.get(0));
                        if (index < 0 || index >= mutable.size()) return null;
                        return mutable.remove(index);
                    }));
            case "remove_value" -> fixed(name, 1, (host, args) ->
                    mutateList(list, span, () -> {
                        List<Object> mutable = asMutable(list);
                        int index = indexOfValue(mutable, args.get(0));
                        if (index < 0) return null;
                        return mutable.remove(index);
                    }));
            case "map" -> fixed(name, 1, (host, args) -> {
                List<Object> result = new ArrayList<>();
                for (Object item : list) {
                    result.add(host.call(args.get(0), List.of(item), span));
                }
                return result;
            });
            case "filter" -> fixed(name, 1, (host, args) -> {
                List<Object> result = new ArrayList<>();
                for (Object item : list) {
                    if (Values.truthy(host.call(args.get(0), List.of(item), span))) {
                        result.add(item);
                    }
                }
                return result;
            });
            case "each" -> fixed(name, 1, (host, args) -> {
                for (Object item : list) {
                    host.call(args.get(0), List.of(item), span);
                }
                return null;
            });
            case "any" -> fixed(name, 1, (host, args) -> {
                for (Object item : list) {
                    if (Values.truthy(host.call(args.get(0), List.of(item), span))) return true;
                }
                return false;
            });
            case "all" -> fixed(name, 1, (host, args) -> {
                for (Object item : list) {
                    if (!Values.truthy(host.call(args.get(0), List.of(item), span))) return false;
                }
                return true;
            });
            case "find" -> fixed(name, 1, (host, args) -> {
                for (Object item : list) {
                    if (Values.truthy(host.call(args.get(0), List.of(item), span))) return item;
                }
                return null;
            });
            case "find_index" -> fixed(name, 1, (host, args) -> {
                for (int i = 0; i < list.size(); i++) {
                    if (Values.truthy(host.call(args.get(0), List.of(list.get(i)), span))) return (long) i;
                }
                return -1L;
            });
            case "reduce" -> new dev.oqthara.runtime.NativeFunction(name, 1, 2, (host, args, callSpan) -> {
                OqCallable fn = (OqCallable) args.get(0);
                int start = 0;
                Object acc;
                if (args.size() == 2) {
                    acc = args.get(1);
                } else {
                    if (list.isEmpty()) throw new OqError("cannot reduce an empty list without a start value", callSpan);
                    acc = list.get(0);
                    start = 1;
                }
                for (int i = start; i < list.size(); i++) {
                    acc = host.call(fn, List.of(acc, list.get(i)), callSpan);
                }
                return acc;
            });
            case "sort" -> new dev.oqthara.runtime.NativeFunction(name, 0, 1, (host, args, callSpan) -> {
                List<Object> result = new ArrayList<>(list);
                Comparator<Object> comparator;
                if (args.isEmpty()) {
                    comparator = (a, b) -> Values.compare(a, b, callSpan);
                } else {
                    OqCallable fn = (OqCallable) args.get(0);
                    comparator = (a, b) -> {
                        Object keyA = host.call(fn, List.of(a), callSpan);
                        Object keyB = host.call(fn, List.of(b), callSpan);
                        return Values.compare(keyA, keyB, callSpan);
                    };
                }
                result.sort(comparator);
                return result;
            });
            case "sorted_desc" -> fixed(name, 0, (host, args) -> {
                List<Object> result = new ArrayList<>(list);
                result.sort((a, b) -> Values.compare(b, a, span));
                return result;
            });
            case "reverse" -> fixed(name, 0, (host, args) -> {
                List<Object> result = new ArrayList<>(list);
                java.util.Collections.reverse(result);
                return result;
            });
            case "join" -> new dev.oqthara.runtime.NativeFunction(name, 0, 1, (host, args, callSpan) -> {
                String separator = args.isEmpty() ? "" : Values.display(args.get(0));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(separator);
                    sb.append(Values.display(list.get(i)));
                }
                return sb.toString();
            });
            case "contains" -> fixed(name, 1, (host, args) ->
                    Values.contains(list, args.get(0), span));
            case "unique" -> fixed(name, 0, (host, args) -> {
                Set<Object> seen = new LinkedHashSet<>(list);
                // A tuple stays a tuple; otherwise `(1, 1, 2).unique()` silently handed back a
                // mutable list, which is the one thing a tuple is not.
                List<Object> unique = new ArrayList<>(seen);
                return list instanceof dev.oqthara.runtime.OqTuple
                        ? dev.oqthara.runtime.OqTuple.of(unique)
                        : unique;
            });
            case "flatten" -> fixed(name, 0, (host, args) -> {
                List<Object> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof List<?> inner) result.addAll(inner);
                    else result.add(item);
                }
                return result;
            });
            case "take" -> fixed(name, 1, (host, args) ->
                    new ArrayList<>(list.subList(0, clampIndex(args.get(0), list.size()))));
            case "skip" -> fixed(name, 1, (host, args) ->
                    new ArrayList<>(list.subList(clampIndex(args.get(0), list.size()), list.size())));
            case "subList", "slice" -> new dev.oqthara.runtime.NativeFunction(name, 1, 2,
                    (host, args, callSpan) -> {
                // Both bounds land in [0, size] before touching java.util.List.subList, which
                // throws IndexOutOfBoundsException for anything else and would surface an
                // Oqthara bug to the program.
                int from = clampIndex(args.get(0), list.size());
                int to = args.size() == 1
                        ? list.size()
                        : clampIndex(args.get(1), list.size());
                // An inverted range is empty, not an error: the spec promises clamping, and a
                // caller that computed start > end gets the same empty answer as start == end.
                // Java's subList would throw here, so the two indices are ordered explicitly.
                if (from > to) from = to;
                // A copy, never java.util.List.subList: that returns a live view whose mutators
                // are not in Oqthara's list API, so they fell through to Java reflection. On a
                // tuple the view also lost the immutable identity, so `t.subList(0,2).set(...)`
                // reached the JDK instead of the tuple refusal.
                List<Object> sliced = new ArrayList<>(list.subList(from, to));
                return list instanceof dev.oqthara.runtime.OqTuple
                        ? dev.oqthara.runtime.OqTuple.of(sliced)
                        : sliced;
            });
            case "sum" -> fixed(name, 0, (host, args) -> sum(list, span));
            case "min" -> fixed(name, 0, (host, args) -> extreme(list, span, true));
            case "max" -> fixed(name, 0, (host, args) -> extreme(list, span, false));
            case "clone", "copy" -> fixed(name, 0, (host, args) -> new ArrayList<>(list));
            case "text", "to_string" -> fixed(name, 0, (host, args) -> Values.repr(list));
            default -> null;
        };
    }

    private OqCallable mapMethod(Map<?, ?> map, String name, Span span) {
        return switch (name) {
            case "length", "size" -> fixed(name, 0, (host, args) -> (long) map.size());
            case "keys" -> fixed(name, 0, (host, args) -> new ArrayList<>(map.keySet()));
            case "values" -> fixed(name, 0, (host, args) -> new ArrayList<>(map.values()));
            case "entries" -> fixed(name, 0, (host, args) -> {
                List<Object> entries = new ArrayList<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                    item.put("key", entry.getKey());
                    item.put("value", entry.getValue());
                    entries.add(new OqData("Entry", item));
                }
                return entries;
            });
            case "get" -> new dev.oqthara.runtime.NativeFunction(name, 1, 2, (host, args, callSpan) ->
                    map.containsKey(args.get(0)) ? map.get(args.get(0)) : (args.size() > 1 ? args.get(1) : null));
            case "set", "put" -> fixed(name, 2, (host, args) -> {
                asMutableMap(map).put(args.get(0), args.get(1));
                return null;
            });
            case "has", "contains", "contains_key" -> fixed(name, 1, (host, args) -> map.containsKey(args.get(0)));
            case "has_value" -> fixed(name, 1, (host, args) -> map.containsValue(args.get(0)));
            case "remove" -> fixed(name, 1, (host, args) -> asMutableMap(map).remove(args.get(0)));
            case "clear" -> fixed(name, 0, (host, args) -> {
                asMutableMap(map).clear();
                return null;
            });
            case "each" -> fixed(name, 1, (host, args) -> {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    host.call(args.get(0), List.of(entry.getKey(), entry.getValue()), span);
                }
                return null;
            });
            case "map" -> fixed(name, 1, (host, args) -> {
                List<Object> result = new ArrayList<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    result.add(host.call(args.get(0), List.of(entry.getKey(), entry.getValue()), span));
                }
                return result;
            });
            case "clone", "copy" -> fixed(name, 0, (host, args) -> new LinkedHashMap<>(map));
            case "text", "to_string" -> fixed(name, 0, (host, args) -> Values.repr(map));
            default -> null;
        };
    }

    private OqCallable setMethod(Set<?> set, String name, Span span) {
        return switch (name) {
            case "length", "size" -> fixed(name, 0, (host, args) -> (long) set.size());
            case "add" -> fixed(name, 1, (host, args) -> {
                asMutableSet(set).add(args.get(0));
                return null;
            });
            case "remove" -> fixed(name, 1, (host, args) -> asMutableSet(set).remove(args.get(0)));
            case "has", "contains" -> fixed(name, 1, (host, args) -> set.contains(args.get(0)));
            case "items", "list", "to_list" -> fixed(name, 0, (host, args) -> new ArrayList<>(set));
            case "union" -> fixed(name, 1, (host, args) -> {
                Set<Object> result = new LinkedHashSet<>(set);
                result.addAll(asCollection(args.get(0), span));
                return result;
            });
            case "intersection" -> fixed(name, 1, (host, args) -> {
                Set<Object> result = new LinkedHashSet<>(set);
                result.retainAll(asCollection(args.get(0), span));
                return result;
            });
            case "difference" -> fixed(name, 1, (host, args) -> {
                Set<Object> result = new LinkedHashSet<>(set);
                result.removeAll(asCollection(args.get(0), span));
                return result;
            });
            case "text", "to_string" -> fixed(name, 0, (host, args) -> Values.repr(set));
            default -> null;
        };
    }

    private OqCallable textMethod(String text, String name, Span span) {
        return switch (name) {
            case "length", "size" -> fixed(name, 0, (host, args) -> (long) text.length());
            case "upper", "uppercase" -> fixed(name, 0, (host, args) -> text.toUpperCase(java.util.Locale.ROOT));
            case "lower", "lowercase" -> fixed(name, 0, (host, args) -> text.toLowerCase(java.util.Locale.ROOT));
            case "trim", "strip" -> fixed(name, 0, (host, args) -> text.strip());
            case "trim_start", "strip_start" -> fixed(name, 0, (host, args) -> text.stripLeading());
            case "trim_end", "strip_end" -> fixed(name, 0, (host, args) -> text.stripTrailing());
            case "split" -> fixed(name, 1, (host, args) ->
                    new ArrayList<Object>(List.of(text.split(java.util.regex.Pattern.quote(
                            Values.display(args.get(0))), -1))));
            case "lines" -> fixed(name, 0, (host, args) -> new ArrayList<Object>(
                    List.of(text.split("\\R", -1))));
            case "chars" -> fixed(name, 0, (host, args) -> {
                List<Object> chars = new ArrayList<>();
                text.codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
                return chars;
            });
            case "replace" -> fixed(name, 2, (host, args) ->
                    text.replace(Values.display(args.get(0)), Values.display(args.get(1))));
            case "replace_all" -> fixed(name, 2, (host, args) ->
                    text.replaceAll(Values.display(args.get(0)), Values.display(args.get(1))));
            case "contains" -> fixed(name, 1, (host, args) -> text.contains(Values.display(args.get(0))));
            case "starts_with", "starts" -> fixed(name, 1, (host, args) ->
                    text.startsWith(Values.display(args.get(0))));
            case "ends_with", "ends" -> fixed(name, 1, (host, args) ->
                    text.endsWith(Values.display(args.get(0))));
            case "find", "index_of" -> fixed(name, 1, (host, args) ->
                    (long) text.indexOf(Values.display(args.get(0))));
            case "substring", "slice" -> new dev.oqthara.runtime.NativeFunction(name, 1, 2,
                    (host, args, callSpan) -> {
                        int from = ((Number) args.get(0)).intValue();
                        if (from < 0) from = text.length() + from;
                        int to = args.size() > 1 ? ((Number) args.get(1)).intValue() : text.length();
                        if (to < 0) to = text.length() + to;
                        from = Math.max(0, Math.min(from, text.length()));
                        to = Math.max(from, Math.min(to, text.length()));
                        return text.substring(from, to);
                    });
            case "repeat" -> fixed(name, 1, (host, args) ->
                    text.repeat(((Number) args.get(0)).intValue()));
            case "pad_start" -> fixed(name, 2, (host, args) -> pad(text, args, true));
            case "pad_end" -> fixed(name, 2, (host, args) -> pad(text, args, false));
            case "capitalize" -> fixed(name, 0, (host, args) ->
                    text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1));
            case "words" -> fixed(name, 0, (host, args) -> new ArrayList<Object>(
                    List.of(text.strip().isEmpty() ? new String[0] : text.strip().split("\\s+"))));
            case "number", "to_number" -> fixed(name, 0, (host, args) -> parseNumber(text, span));
            case "characters" -> fixed(name, 0, (host, args) -> {
                List<Object> chars = new ArrayList<>();
                for (int i = 0; i < text.length(); i++) chars.add(String.valueOf(text.charAt(i)));
                return chars;
            });
            case "text", "to_string" -> fixed(name, 0, (host, args) -> text);
            default -> null;
        };
    }

    private OqCallable numberMethod(Object number, String name, Span span) {
        return switch (name) {
            case "abs" -> fixed(name, 0, (host, args) -> number instanceof Long l
                    ? (Object) Math.abs(l) : (Object) Math.abs(((Number) number).doubleValue()));
            case "floor" -> fixed(name, 0, (host, args) -> (long) Math.floor(((Number) number).doubleValue()));
            case "ceil" -> fixed(name, 0, (host, args) -> (long) Math.ceil(((Number) number).doubleValue()));
            case "round" -> fixed(name, 0, (host, args) -> Math.round(((Number) number).doubleValue()));
            case "sqrt" -> fixed(name, 0, (host, args) -> Math.sqrt(((Number) number).doubleValue()));
            case "text", "to_string" -> fixed(name, 0, (host, args) -> Values.repr(number));
            case "is_even" -> fixed(name, 0, (host, args) -> ((Number) number).longValue() % 2 == 0);
            case "is_odd" -> fixed(name, 0, (host, args) -> ((Number) number).longValue() % 2 != 0);
            default -> null;
        };
    }

    private static Object parseNumber(String text, Span span) {
        String trimmed = text.strip();
        try {
            if (trimmed.contains(".") || trimmed.contains("e") || trimmed.contains("E")) {
                return Double.parseDouble(trimmed);
            }
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new OqError("cannot read \"" + text + "\" as a number", span,
                    "Text must look like 42 or 3.14 to be converted to a number.");
        }
    }

    private static String pad(String text, List<Object> args, boolean start) {
        int width = ((Number) args.get(0)).intValue();
        String fill = args.size() > 1 ? Values.display(args.get(1)) : " ";
        if (fill.isEmpty()) fill = " ";
        StringBuilder padding = new StringBuilder();
        while (text.length() + padding.length() < width) {
            padding.append(fill);
        }
        if (padding.length() > width - text.length()) {
            padding.setLength(Math.max(0, width - text.length()));
        }
        return start ? padding + text : text + padding;
    }

    /**
     * Clamps {@code value} to {@code [0, size]} without letting a long wider than an int wrap.
     *
     * <p>Narrowing with {@code intValue()} first turns {@code 4294967297} into {@code 1}, which
     * silently slices the wrong range, and turns a large negative into a positive index. The
     * clamp happens in {@code long} so neither can occur.
     */
    private static int clampIndex(Object value, int size) {
        long index = ((Number) value).longValue();
        if (index < 0) return 0;
        if (index > size) return size;
        return (int) index;
    }

    private static dev.oqthara.runtime.NativeFunction fixed(String name, int arity,
                                                            java.util.function.BiFunction<OqHost, List<Object>, Object> body) {
        return dev.oqthara.runtime.NativeFunction.of(name, arity,
                (host, args, span) -> body.apply(host, args));
    }

    private static Object sum(List<?> list, Span span) {
        Object total = 0L;
        for (Object item : list) {
            total = Values.add(total, item, span);
        }
        return total;
    }

    private static Object extreme(List<?> list, Span span, boolean min) {
        if (list.isEmpty()) return null;
        Object best = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            int comparison = Values.compare(list.get(i), best, span);
            if (min ? comparison < 0 : comparison > 0) best = list.get(i);
        }
        return best;
    }

    private static int indexOfValue(List<Object> list, Object value) {
        for (int i = 0; i < list.size(); i++) {
            if (Values.equal(list.get(i), value)) return i;
        }
        return -1;
    }

    /**
     * Turns a host collection's refusal to mutate into a language-level error.
     *
     * <p>Java factories such as {@code List.of(...)} return read-only lists. They still
     * implement {@link List}, so normal list method resolution reaches the mutator above.
     * Letting their {@link UnsupportedOperationException} escape would crash the interpreter
     * instead of reporting an ordinary program error.
     */
    private static <T> T mutateList(List<?> list, Span span,
                                    java.util.function.Supplier<T> mutation) {
        try {
            return mutation.get();
        } catch (UnsupportedOperationException unsupported) {
            throw new OqError("cannot modify this list", span,
                    "The underlying Java list does not support mutation. Use a mutable list or make a copy first.");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asMutable(List<?> list) {
        return (List<Object>) list;
    }

    /** Tuple method names that would mutate, refused with a clear message instead of a raw Java error. */
    private static dev.oqthara.runtime.OqCallable tupleRefusal(String name, Span span) {
        if (!TUPLE_MUTATORS.contains(name)) return null;
        return dev.oqthara.runtime.NativeFunction.variadic(name, 0,
                (host, args, callSpan) -> {
                    throw new OqError(dev.oqthara.runtime.OqTuple.IMMUTABLE, span,
                            "Tuples cannot change after they are made. Build a new tuple with"
                                    + " the items you want, or use a list when the items must change.");
                });
    }

    /**
     * Every name that would mutate a sequence. The last group is not in Oqthara's list API at
     * all, but Java reflection on a tuple finds the {@link java.util.List} methods, so naming
     * them here is what turns a leaked {@code UnsupportedOperationException} into a program error.
     */
    private static final java.util.Set<String> TUPLE_MUTATORS = java.util.Set.of(
            "push", "add", "pop", "insert", "remove", "remove_value", "clear", "sort",
            "reverse", "sort_by", "shuffle", "fill",
            "set", "addAll", "removeAll", "retainAll", "removeIf", "replaceAll");

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> asMutableMap(Map<?, ?> map) {
        return (Map<Object, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> asMutableSet(Set<?> set) {
        return (Set<Object>) set;
    }

    private static List<?> asCollection(Object value, Span span) {
        if (value instanceof List<?> list) return list;
        if (value instanceof Set<?> set) return new ArrayList<>(set);
        throw new OqError("expected a list or set, got " + Values.typeName(value), span);
    }
}
