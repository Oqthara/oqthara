package dev.oqthara.stdlib;

import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqFrozenSet;
import dev.oqthara.runtime.OqMap;
import dev.oqthara.runtime.OqSet;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.util.Collection;
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
        if (receiver instanceof dev.oqthara.runtime.OqIterator iterator) {
            return iteratorMethod(iterator, name, span);
        }
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
        if (receiver instanceof OqFrozenSet frozen) {
            return frozenSetMethod(frozen, name, span);
        }
        if (receiver instanceof Set<?> set) {
            return setMethod(set, name, span);
        }
        if (receiver instanceof String text) {
            return textMethod(text, name, span);
        }
        if (receiver instanceof dev.oqthara.runtime.OqRange range) {
            return rangeMethod(range, name, span);
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
        if (receiver instanceof dev.oqthara.runtime.OqMutex mutex) {
            return mutexMethod(mutex, name, span);
        }
        return null;
    }

    /**
     * Methods on a mutex, per ADR-0039 Decision 5. There are two, and both are questions a
     * program can act on: take the lock around a body, or ask whether it is free.
     */
    private OqCallable mutexMethod(
            dev.oqthara.runtime.OqMutex mutex, String name, Span span) {
        return switch (name) {
            case "lock" -> dev.oqthara.runtime.NativeFunction.of(name, 1,
                    (host, args, callSpan) -> mutex.lock(
                            host, callableArgument(name, args.get(0), 1, "m.lock(body)", callSpan), callSpan));
            case "is_free" -> fixed(name, 0, (host, args) -> mutex.isFree());
            default -> null;
        };
    }

    /**
     * The argument at a callback position, or a diagnostic naming it.
     *
     * @param position the 1-based position the caller should hear about
     * @param call     the call shape to quote, so the reader sees the expected order
     */
    private static OqCallable callableArgument(
            String name, Object value, int position, String call, Span span) {
        if (value instanceof OqCallable callable) return callable;
        throw new OqError("'" + name + "' needs a function as argument " + position, span,
                "It got " + Values.typeName(value) + ". Write " + call + ".");
    }

    /**
     * Methods on an iterator, per ADR-0020. An iterator tracks a position, so it exposes
     * advancement and a rest-question, and refuses everything that would consume it.
     */
    private OqCallable iteratorMethod(
            dev.oqthara.runtime.OqIterator iterator, String name, Span span) {
        return switch (name) {
            case "has_next" -> fixed(name, 0, (host, args) -> iterator.hasNext());
            case "next" -> dev.oqthara.runtime.NativeFunction.of(name, 0,
                    (host, args, callSpan) -> Values.iteratorNext(iterator, callSpan));
            case "iter" -> fixed(name, 0, (host, args) -> iterator);
            default -> null;
        };
    }

    private OqCallable listMethod(List<?> list, String name, Span span) {
        return switch (name) {
            case "length", "size" -> fixed(name, 0, (host, args) -> (long) list.size());
            case "iter" -> fixed(name, 0, (host, args) -> Values.iterator(list, span));
            case "push", "add" -> fixed(name, 1, (host, args) -> {
                asMutable(list).add(args.get(0));
                return null;
            });
            case "pop" -> fixed(name, 0, (host, args) -> {
                List<Object> mutable = asMutable(list);
                if (mutable.isEmpty()) throw new OqError("cannot pop from an empty list", span);
                return mutable.remove(mutable.size() - 1);
            });
            case "first" -> fixed(name, 0, (host, args) -> list.isEmpty() ? null : list.get(0));
            case "last" -> fixed(name, 0, (host, args) -> list.isEmpty() ? null : list.get(list.size() - 1));
            case "get" -> fixed(name, 1, (host, args) ->
                    Values.index(list, args.get(0), span));
            case "insert" -> fixed(name, 2, (host, args) -> {
                List<Object> mutable = asMutable(list);
                mutable.add(Values.position(args.get(0), mutable.size(), "a list", true, span), args.get(1));
                return null;
            });
            case "remove" -> fixed(name, 1, (host, args) -> {
                List<Object> mutable = asMutable(list);
                if (!(args.get(0) instanceof Number)) {
                    throw new OqError(
                            "remove takes an index, not " + Values.typeName(args.get(0)),
                            span,
                            "To remove the first matching value, use remove_value(" + "x" + ").");
                }
                return mutable.remove(Values.position(args.get(0), mutable.size(), "a list", false, span));
            });
            case "remove_value" -> fixed(name, 1, (host, args) -> {
                List<Object> mutable = asMutable(list);
                int index = indexOfValue(mutable, args.get(0));
                if (index < 0) return null;
                return mutable.remove(index);
            });
            case "map" -> fixed(name, 1, (host, args) -> {
                List<Object> result = new ArrayList<>();
                for (Object item : Values.iterable(list, span)) {
                    result.add(host.call(args.get(0), List.of(item), span));
                }
                return result;
            });
            case "filter" -> fixed(name, 1, (host, args) -> {
                List<Object> result = new ArrayList<>();
                for (Object item : Values.iterable(list, span)) {
                    if (Values.truthy(host.call(args.get(0), List.of(item), span))) {
                        result.add(item);
                    }
                }
                return result;
            });
            case "each" -> fixed(name, 1, (host, args) -> {
                for (Object item : Values.iterable(list, span)) {
                    host.call(args.get(0), List.of(item), span);
                }
                return null;
            });
            case "any" -> fixed(name, 1, (host, args) -> {
                for (Object item : Values.iterable(list, span)) {
                    if (Values.truthy(host.call(args.get(0), List.of(item), span))) return true;
                }
                return false;
            });
            case "all" -> fixed(name, 1, (host, args) -> {
                for (Object item : Values.iterable(list, span)) {
                    if (!Values.truthy(host.call(args.get(0), List.of(item), span))) return false;
                }
                return true;
            });
            case "find" -> fixed(name, 1, (host, args) -> {
                for (Object item : Values.iterable(list, span)) {
                    if (Values.truthy(host.call(args.get(0), List.of(item), span))) return item;
                }
                return null;
            });
            case "find_index" -> fixed(name, 1, (host, args) -> {
                List<?> items = Values.iterableList(list, span);
                for (int i = 0; i < items.size(); i++) {
                    if (Values.truthy(host.call(args.get(0), List.of(items.get(i)), span))) return (long) i;
                }
                return -1L;
            });
            case "reduce" -> new dev.oqthara.runtime.NativeFunction(name, 1, 2, (host, args, callSpan) -> {
                OqCallable fn = callableArgument(name, args.get(0), 1, "reduce(fn, start)", callSpan);
                int start = 0;
                Object acc;
                if (args.size() == 2) {
                    acc = args.get(1);
                } else {
                    if (list.isEmpty()) throw new OqError("cannot reduce an empty list without a start value", callSpan);
                    acc = list.get(0);
                    start = 1;
                }
                List<?> items = Values.iterableList(list, callSpan);
                for (int i = start; i < items.size(); i++) {
                    acc = host.call(fn, List.of(acc, items.get(i)), callSpan);
                }
                return acc;
            });
            case "sort" -> new dev.oqthara.runtime.NativeFunction(name, 0, 1, (host, args, callSpan) -> {
                List<Object> result = new ArrayList<>(list);
                Comparator<Object> comparator;
                if (args.isEmpty()) {
                    comparator = (a, b) -> Values.compare(a, b, callSpan);
                } else {
                    OqCallable fn = callableArgument(name, args.get(0), 1, "sort(fn)", callSpan);
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
                Set<Object> seen = new OqSet(list);
                // A tuple stays a tuple; otherwise `(1, 1, 2).unique()` silently handed back a
                // mutable list, which is the one thing a tuple is not.
                List<Object> unique = new ArrayList<>(seen);
                return list instanceof dev.oqthara.runtime.OqTuple
                        ? dev.oqthara.runtime.OqTuple.of(unique)
                        : unique;
            });
            case "flatten" -> fixed(name, 0, (host, args) -> {
                List<Object> result = new ArrayList<>();
                for (Object item : Values.iterable(list, span)) {
                    if (item instanceof List<?> inner) result.addAll(inner);
                    else result.add(item);
                }
                return result;
            });
            case "take" -> fixed(name, 1, (host, args) ->
                    new ArrayList<>(list.subList(0,
                            clampIndex(args.get(0), list.size(), span, "a count for take"))));
            case "skip" -> fixed(name, 1, (host, args) ->
                    new ArrayList<>(list.subList(
                            clampIndex(args.get(0), list.size(), span, "a count for skip"),
                            list.size())));
            case "subList", "slice" -> new dev.oqthara.runtime.NativeFunction(name, 1, 2,
                    (host, args, callSpan) -> {
                // A bound is a position, so it resolves negatives from the end, and is then
                // clamped into [0, size] before java.util.List.subList sees it -- that throws
                // IndexOutOfBoundsException outside the range, which would surface as an
                // Oqthara bug. Folding negatives to 0, as this used to, made `slice(-2, 5)`
                // return the whole list instead of its last two items.
                int from = resolvedBound(args.get(0), list.size(), span, "a slice start");
                int to = args.size() == 1
                        ? list.size()
                        : resolvedBound(args.get(1), list.size(), span, "a slice end");
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
            case "clone", "copy" -> fixed(name, 0, (host, args) -> list instanceof dev.oqthara.runtime.OqTuple
                    ? (Object) list
                    : (Object) new ArrayList<>(list));
            case "to_set", "set" -> fixed(name, 0, (host, args) -> new OqSet(list));
            case "sorted" -> fixed(name, 0, (host, args) -> {
                List<Object> copy = new ArrayList<>(list);
                copy.sort((a, b) -> Values.compare(a, b, span));
                return copy;
            });
            case "text", "to_string" -> fixed(name, 0, (host, args) -> Values.repr(list));
            default -> null;
        };
    }

    private OqCallable mapMethod(Map<?, ?> map, String name, Span span) {
        return switch (name) {
            case "length", "size" -> fixed(name, 0, (host, args) -> (long) map.size());
            case "iter" -> fixed(name, 0, (host, args) -> Values.iterator(map, span));
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
            case "has_value" -> fixed(name, 1, (host, args) -> {
                // A value lookup has to use the same equality as `==` (ADR-0017), so a map
                // holding 1 reports has_value(1.0) rather than missing it.
                for (Object value : map.values()) {
                    if (Values.equal(value, args.get(0))) return true;
                }
                return false;
            });
            case "remove" -> fixed(name, 1, (host, args) -> asMutableMap(map).remove(args.get(0)));
            case "clear" -> fixed(name, 0, (host, args) -> {
                asMutableMap(map).clear();
                return null;
            });
            case "each" -> fixed(name, 1, (host, args) -> {
                for (Map.Entry<?, ?> entry : new LinkedHashMap<>(map).entrySet()) {
                    host.call(args.get(0), List.of(entry.getKey(), entry.getValue()), span);
                }
                return null;
            });
            case "map" -> fixed(name, 1, (host, args) -> {
                List<Object> result = new ArrayList<>();
                for (Map.Entry<?, ?> entry : new LinkedHashMap<>(map).entrySet()) {
                    result.add(host.call(args.get(0), List.of(entry.getKey(), entry.getValue()), span));
                }
                return result;
            });
            case "merge", "update" -> fixed(name, 1, (host, args) -> {
                // `merge` is Python's `update`: the argument's entries win, and the receiver is
                // changed in place. Two entry points because both words are what a reader
                // reaches for, and silently picking one spelling would be worse than either.
                Map<?, ?> other = asMap(args.get(0), name, span);
                asMutableMap(map).putAll(other);
                return map;
            });
            case "pop" -> new dev.oqthara.runtime.NativeFunction(name, 1, 2, (host, args, callSpan) -> {
                Object key = args.get(0);
                if (map.containsKey(key)) return asMutableMap(map).remove(key);
                if (args.size() > 1) return args.get(1);
                throw new dev.oqthara.runtime.OqError(
                        "this map has no key " + Values.repr(key), callSpan,
                        "Check has(key) first, or pass a default: pop(key, default).");
            });
            case "items" -> fixed(name, 0, (host, args) -> {
                // A list of [key, value] pairs, which is what `enumerate` produces and what a
                // destructuring `for [k, v] in m.items()` consumes. `entries()` stays for the
                // field-style access it already offered.
                List<Object> pairs = new ArrayList<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    List<Object> pair = new ArrayList<>(2);
                    pair.add(entry.getKey());
                    pair.add(entry.getValue());
                    pairs.add(pair);
                }
                return pairs;
            });
            case "clone", "copy" -> fixed(name, 0, (host, args) -> new OqMap(map));
            case "reverse", "sorted" -> fixed(name, 0, (host, args) -> {
                List<Object> keys = new ArrayList<>(map.keySet());
                if (name.equals("sorted")) keys.sort((a, b) -> Values.compare(a, b, span));
                else java.util.Collections.reverse(keys);
                return keys;
            });
            case "to_list", "to_set" -> fixed(name, 0, (host, args) -> name.startsWith("to_set")
                    ? (Object) new OqSet(map.keySet())
                    : (Object) new ArrayList<>(map.keySet()));
            case "text", "to_string" -> fixed(name, 0, (host, args) -> Values.repr(map));
            default -> null;
        };
    }

    /**
     * Methods on a frozenset. The read-only half of the set surface, with every result keeping
     * the frozen family: {@code frozenset([1]).union([2])} is a frozenset, not a set, or the
     * immutability promise would quietly evaporate after one call (ADR-0022).
     *
     * <p>The mutators are refused by name here rather than left to {@link OqFrozenSet}'s
     * {@code add}, because that would report "a frozenset is immutable" from inside a set
     * operation the caller never wrote.
     */
    private OqCallable frozenSetMethod(OqFrozenSet frozen, String name, Span span) {
        return switch (name) {
            case "add", "remove", "clear", "update", "pop", "discard", "add_all", "remove_all",
                 "retain_all", "sort", "reverse", "shuffle", "fill" -> null;
            case "length", "size" -> fixed(name, 0, (host, args) -> (long) frozen.size());
            case "iter" -> fixed(name, 0, (host, args) -> Values.iterator(frozen, span));
            case "has", "contains" -> fixed(name, 1, (host, args) -> frozen.contains(args.get(0)));
            case "items", "list", "to_list" -> fixed(name, 0, (host, args) -> new ArrayList<>(frozen));
            case "to_set", "set" -> fixed(name, 0, (host, args) -> new OqSet(frozen));
            case "frozenset", "clone", "copy" -> fixed(name, 0, (host, args) -> frozen);
            case "union" -> fixed(name, 1, (host, args) -> {
                List<Object> merged = new ArrayList<>(frozen);
                merged.addAll(asCollection(args.get(0), span));
                return new OqFrozenSet(merged);
            });
            case "intersection" -> fixed(name, 1, (host, args) -> {
                Collection<?> other = asCollection(args.get(0), span);
                Set<Object> keep = new OqSet(other);
                List<Object> result = new ArrayList<>();
                for (Object item : frozen) if (keep.contains(item)) result.add(item);
                return new OqFrozenSet(result);
            });
            case "difference" -> fixed(name, 1, (host, args) -> {
                Collection<?> other = asCollection(args.get(0), span);
                Set<Object> drop = new OqSet(other);
                List<Object> result = new ArrayList<>();
                for (Object item : frozen) if (!drop.contains(item)) result.add(item);
                return new OqFrozenSet(result);
            });
            case "symmetric_difference" -> fixed(name, 1, (host, args) -> {
                Collection<?> other = asCollection(args.get(0), span);
                Set<Object> mine = new OqSet(frozen);
                List<Object> result = new ArrayList<>();
                for (Object item : frozen) if (!containsValue(other, item)) result.add(item);
                for (Object item : other) if (!mine.contains(item)) result.add(item);
                return new OqFrozenSet(result);
            });
            case "issubset" -> fixed(name, 1, (host, args) -> {
                Collection<?> other = asCollection(args.get(0), span);
                for (Object item : frozen) if (!containsValue(other, item)) return (Object) false;
                return (Object) true;
            });
            case "issuperset" -> fixed(name, 1, (host, args) -> {
                Set<Object> mine = new OqSet(frozen);
                for (Object item : asCollection(args.get(0), span)) {
                    if (!mine.contains(item)) return (Object) false;
                }
                return (Object) true;
            });
            case "sum" -> fixed(name, 0, (host, args) -> {
                Object total = 0L;
                for (Object item : frozen) total = Values.add(total, item, span);
                return total;
            });
            case "sorted" -> fixed(name, 0, (host, args) -> {
                List<Object> copy = new ArrayList<>(frozen);
                copy.sort((a, b) -> Values.compare(a, b, span));
                return copy;
            });
            case "min", "max" -> fixed(name, 0, (host, args) -> {
                if (frozen.isEmpty()) return null;
                Object best = frozen.iterator().next();
                for (Object item : frozen) {
                    int c = Values.compare(item, best, span);
                    if (name.equals("min") ? c < 0 : c > 0) best = item;
                }
                return best;
            });
            case "text", "to_string" -> fixed(name, 0, (host, args) -> Values.repr(frozen));
            default -> null;
        };
    }

    private OqCallable setMethod(Set<?> set, String name, Span span) {
        return switch (name) {
            case "length", "size" -> fixed(name, 0, (host, args) -> (long) set.size());
            case "iter" -> fixed(name, 0, (host, args) -> Values.iterator(set, span));
            case "add" -> fixed(name, 1, (host, args) -> {
                asMutableSet(set).add(args.get(0));
                return null;
            });
            case "remove" -> fixed(name, 1, (host, args) -> asMutableSet(set).remove(args.get(0)));
            case "has", "contains" -> fixed(name, 1, (host, args) -> set.contains(args.get(0)));
            case "items", "list", "to_list" -> fixed(name, 0, (host, args) -> new ArrayList<>(set));
            case "union" -> fixed(name, 1, (host, args) -> {
                Set<Object> result = new OqSet(set);
                result.addAll(asCollection(args.get(0), span));
                return result;
            });
            case "intersection" -> fixed(name, 1, (host, args) -> {
                Set<Object> result = new OqSet(set);
                result.retainAll(asCollection(args.get(0), span));
                return result;
            });
            case "difference" -> fixed(name, 1, (host, args) -> {
                Set<Object> result = new OqSet(set);
                result.removeAll(asCollection(args.get(0), span));
                return result;
            });
            case "symmetric_difference" -> fixed(name, 1, (host, args) -> {
                Collection<?> other = asCollection(args.get(0), span);
                Set<Object> result = new OqSet(set);
                for (Object item : other) {
                    if (result.contains(item)) result.remove(item);
                    else result.add(item);
                }
                return result;
            });
            case "issubset" -> fixed(name, 1, (host, args) -> {
                // Membership goes through Values.equal via OqSet, so 1 and 1.0 agree here as
                // they do for `==` and for `in`, per ADR-0017.
                Set<Object> other = new OqSet(asCollection(args.get(0), span));
                return other.containsAll(set);
            });
            case "issuperset" -> fixed(name, 1, (host, args) -> {
                Set<Object> other = new OqSet(asCollection(args.get(0), span));
                return set.containsAll(other);
            });
            case "clone", "copy" -> fixed(name, 0, (host, args) -> new OqSet(set));
            case "to_set", "set" -> fixed(name, 0, (host, args) -> new OqSet(set));
            case "sum" -> fixed(name, 0, (host, args) -> {
                Object total = 0L;
                for (Object item : set) total = Values.add(total, item, span);
                return total;
            });
            case "sorted" -> fixed(name, 0, (host, args) -> {
                List<Object> copy = new ArrayList<>(set);
                copy.sort((a, b) -> Values.compare(a, b, span));
                return copy;
            });
            case "reverse" -> fixed(name, 0, (host, args) -> {
                List<Object> copy = new ArrayList<>(set);
                java.util.Collections.reverse(copy);
                return copy;
            });
            case "min", "max" -> fixed(name, 0, (host, args) -> {
                if (set.isEmpty()) return null;
                Object best = set.iterator().next();
                for (Object item : set) {
                    int c = Values.compare(item, best, span);
                    if (name.equals("min") ? c < 0 : c > 0) best = item;
                }
                return best;
            });
            case "text", "to_string" -> fixed(name, 0, (host, args) -> Values.repr(set));
            default -> null;
        };
    }

    /**
     * Methods on a range. Ranges had no method surface at all, so `(0 to 4).to_list()` failed
     * even though a range is iterable and `length()` already worked on one.
     */
    private OqCallable rangeMethod(
            dev.oqthara.runtime.OqRange range, String name, Span span) {
        return switch (name) {
            case "length", "size" -> fixed(name, 0, (host, args) -> range.size());
            case "iter" -> fixed(name, 0, (host, args) -> Values.iterator(range, span));
            case "to_list", "list", "items" -> fixed(name, 0, (host, args) -> range.toList());
            // `min` and `max` are the two ends of the progression, so neither has to be walked.
            // They used to build every value first, which meant `(0 to 10000000).max()` died of
            // memory exhaustion asking a question the bounds already answer.
            case "min" -> dev.oqthara.runtime.NativeFunction.of(name, 0, (host, args, callSpan) -> {
                if (range.isEmpty()) return null;
                return range.step() > 0 ? range.start() : range.last();
            });
            case "max" -> dev.oqthara.runtime.NativeFunction.of(name, 0, (host, args, callSpan) -> {
                if (range.isEmpty()) return null;
                return range.step() > 0 ? range.last() : range.start();
            });
            // `sum` adds every value, so it genuinely enumerates. It stays materialising and is
            // therefore bounded by what a list can hold; the report records that limitation
            // rather than pretending the arithmetic is free.
            case "sum" -> fixed(name, 0, (host, args) -> {
                Object total = 0L;
                for (Object item : range.toList()) total = Values.add(total, item, span);
                return total;
            });
            // `sorted` reverses the walk when the step is negative and copies it when it is
            // positive, but it still returns a list of every value, so it enumerates by nature.
            case "sorted" -> fixed(name, 0, (host, args) -> {
                List<Object> copy = new ArrayList<>(range.toList());
                copy.sort((a, b) -> Values.compare(a, b, span));
                return copy;
            });
            // A range is an arithmetic progression, so reversing it gives another range with
            // the same shape — not a list. Returning a list would drop the family for no
            // reason, which is the rule ADR-0023 states and this case used to break.
            //
            // The endpoints come from the arithmetic, not from walking the range: a range can
            // be huge (range(0, 2_000_000_000)) and reversing it must not allocate one element
            // per step just to read the first and last. `last()` is the reachable end, which is
            // not `end` whenever the step does not divide the span: `range(0, 9, 3)` reaches 6,
            // so it reverses to `6 to 0 by -3`, not `8 to 0 by -3`.
            //
            // A step of Long.MIN_VALUE cannot be negated: there is no positive long of that
            // magnitude. Refusing says so, rather than producing a range with a wrapped step
            // that would walk in the wrong direction.
            case "reverse" -> fixed(name, 0, (host, args) -> {
                if (range.isEmpty()) {
                    return new dev.oqthara.runtime.OqRange(range.start(), range.start(), false,
                            range.step());
                }
                if (range.step() == Long.MIN_VALUE) {
                    throw new dev.oqthara.runtime.OqError(
                            "this range cannot be reversed", span,
                            "Its step is -9223372036854775808, and there is no positive step that large.");
                }
                return new dev.oqthara.runtime.OqRange(range.last(), range.start(), true,
                        -range.step());
            });
            case "to_set", "set" -> fixed(name, 0, (host, args) ->
                    new dev.oqthara.runtime.OqSet(range.toList()));
            case "contains" -> fixed(name, 1, (host, args) -> {
                Object item = args.get(0);
                // `r.contains(x)` and `x in r` are two spellings of one question, so they must
                // accept the same items. `3.0` is the same number as `3` everywhere else in the
                // language, so a whole-valued decimal counts here too (ADR-0026). A decimal
                // outside the 64-bit range is not a member: casting it would saturate at
                // Long.MAX_VALUE and match a value it does not equal (issue #153).
                if (item instanceof Long l) return range.contains(l);
                if (item instanceof Double d) {
                    return d == Math.rint(d) && Values.fitsInLong(d) && range.contains(d.longValue());
                }
                return false;
            });
            // The first and last values are the start and the reachable end. Both used to build
            // the whole list first, so `(0 to 10000000).first()` ran out of memory answering
            // with the number it already had.
            case "first" -> dev.oqthara.runtime.NativeFunction.of(name, 0, (host, args, callSpan) -> {
                if (range.isEmpty()) {
                    throw new dev.oqthara.runtime.OqError("this range is empty", callSpan,
                            "Check length() before asking for the first item.");
                }
                return range.start();
            });
            case "last" -> dev.oqthara.runtime.NativeFunction.of(name, 0, (host, args, callSpan) -> {
                if (range.isEmpty()) {
                    throw new dev.oqthara.runtime.OqError("this range is empty", callSpan,
                            "Check length() before asking for the last item.");
                }
                return range.last();
            });
            default -> null;
        };
    }

    private OqCallable textMethod(String text, String name, Span span) {
        return switch (name) {
            case "length", "size" -> fixed(name, 0, (host, args) -> (long) Values.codePointCount(text));
            case "iter" -> fixed(name, 0, (host, args) -> Values.iterator(text, span));
            case "upper", "uppercase" -> fixed(name, 0, (host, args) -> text.toUpperCase(java.util.Locale.ROOT));
            case "lower", "lowercase" -> fixed(name, 0, (host, args) -> text.toLowerCase(java.util.Locale.ROOT));
            case "trim", "strip" -> fixed(name, 0, (host, args) -> text.strip());
            case "trim_start", "strip_start" -> fixed(name, 0, (host, args) -> text.stripLeading());
            case "trim_end", "strip_end" -> fixed(name, 0, (host, args) -> text.stripTrailing());
            // An empty separator is refused rather than honoured. Java's String.split with an
            // empty pattern walks UTF-16 units, so "a😀b".split("") returned five entries, two
            // of them lone surrogate halves — values no Oqthara program can render or compare,
            // and the only place a text method exposed one (ADR-0024 says text is a sequence of
            // code points). Splitting at code-point boundaries instead would be a semantic
            // choice the reference does not make: Python raises for the same call, so refusing
            // keeps the two in step and leaves chars()/to_list() as the way to walk characters.
            case "split" -> fixed(name, 1, (host, args) -> {
                String separator = Values.display(args.get(0));
                if (separator.isEmpty()) {
                    throw new OqError("split() needs a separator that is not empty", span,
                            "Use chars() to get one value per character, or join() to reverse it.");
                }
                return new ArrayList<Object>(List.of(text.split(
                        java.util.regex.Pattern.quote(separator), -1)));
            });
            case "lines" -> fixed(name, 0, (host, args) -> new ArrayList<Object>(
                    List.of(text.split("\\R", -1))));
            case "chars" -> fixed(name, 0, (host, args) -> Values.textCharacters(text));
            case "replace" -> fixed(name, 2, (host, args) ->
                    text.replace(Values.display(args.get(0)), Values.display(args.get(1))));
            case "replace_all" -> fixed(name, 2, (host, args) ->
                    text.replaceAll(Values.display(args.get(0)), Values.display(args.get(1))));
            case "contains" -> fixed(name, 1, (host, args) -> text.contains(Values.display(args.get(0))));
            case "starts_with", "starts" -> fixed(name, 1, (host, args) ->
                    text.startsWith(Values.display(args.get(0))));
            case "ends_with", "ends" -> fixed(name, 1, (host, args) ->
                    text.endsWith(Values.display(args.get(0))));
            case "find", "index_of" -> fixed(name, 1, (host, args) -> {
                int at = text.indexOf(Values.display(args.get(0)));
                // Report the position as a character offset, so it agrees with `length` and
                // indexing per ADR-0024. A -1 "not found" stays -1.
                return at < 0 ? -1L : (long) text.codePointCount(0, at);
            });
            case "substring", "slice" -> new dev.oqthara.runtime.NativeFunction(name, 1, 2,
                    (host, args, callSpan) -> {
                        // Bounds are character offsets, per ADR-0024, so they are validated as
                        // whole numbers and converted to UTF-16 offsets before reaching
                        // String.substring. Narrowing with intValue() first truncated a
                        // fractional bound and saturated a huge one.
                        int count = Values.codePointCount(text);
                        long fromIndex = Values.integerIndex(args.get(0), callSpan, "a slice start");
                        long from = fromIndex < 0 ? count + fromIndex : fromIndex;
                        long to;
                        if (args.size() > 1) {
                            long toIndex = Values.integerIndex(args.get(1), callSpan, "a slice end");
                            to = toIndex < 0 ? count + toIndex : toIndex;
                        } else {
                            to = count;
                        }
                        from = Math.max(0, Math.min(from, count));
                        to = Math.max(from, Math.min(to, count));
                        return text.substring(
                                text.offsetByCodePoints(0, (int) from),
                                text.offsetByCodePoints(0, (int) to));
                    });
            case "repeat" -> fixed(name, 1, (host, args) -> {
                long times = Values.integerIndex(args.get(0), span, "a count for repeat");
                if (times < 0) {
                    throw new OqError("repeat() needs a count of zero or more, got " + times, span,
                            "Repeating text a negative number of times has no meaning.");
                }
                // Java's String.repeat takes an int and throws when the result would not fit one.
                // Checking the product here names the argument instead of leaking that exception
                // as an internal error (issue #145).
                long length = text.length();
                if (length > 0 && times > Integer.MAX_VALUE / length) {
                    throw new OqError("repeat() count is too large: " + times, span,
                            "The repeated text would be longer than " + Integer.MAX_VALUE
                                    + " characters, which is the most a text value can hold.");
                }
                return text.repeat((int) times);
            });
            case "pad_start" -> fixed(name, 2, (host, args) -> pad(text, args, span, true));
            case "pad_end" -> fixed(name, 2, (host, args) -> pad(text, args, span, false));
            case "capitalize" -> fixed(name, 0, (host, args) -> capitalize(text));
            case "words" -> fixed(name, 0, (host, args) -> new ArrayList<Object>(
                    List.of(text.strip().isEmpty() ? new String[0] : text.strip().split("\\s+"))));
            case "number", "to_number" -> fixed(name, 0, (host, args) -> parseNumber(text, span));
            case "characters" -> fixed(name, 0, (host, args) -> Values.textCharacters(text));
            case "to_list", "list" -> fixed(name, 0, (host, args) -> Values.textCharacters(text));
            case "to_set", "set" -> fixed(name, 0, (host, args) -> {
                Set<Object> chars = new OqSet();
                for (Object c : Values.textCharacters(text)) chars.add(c);
                return chars;
            });
            case "reverse" -> fixed(name, 0, (host, args) -> {
                // Reverse characters, not UTF-16 units: StringBuilder.reverse would reorder the
                // two halves of a surrogate pair and produce two lone surrogates per ADR-0024.
                List<Object> chars = Values.textCharacters(text);
                java.util.Collections.reverse(chars);
                StringBuilder out = new StringBuilder();
                for (Object c : chars) out.append((String) c);
                return out.toString();
            });
            case "sorted" -> fixed(name, 0, (host, args) -> {
                List<Object> chars = Values.textCharacters(text);
                chars.sort((a, b) -> Values.compare(a, b, span));
                return chars;
            });
            case "text", "to_string" -> fixed(name, 0, (host, args) -> text);
            default -> null;
        };
    }

    private OqCallable numberMethod(Object number, String name, Span span) {
        return switch (name) {
            // Every method that has a builtin counterpart delegates to the same code, so the two
            // forms cannot drift: `1e300.floor()` used to saturate at Long.MAX_VALUE because the
            // cast to long was local to this method, while floor(1e300) kept the decimal.
            case "abs" -> fixed(name, 0, (host, args) -> Stdlib.abs(number, span));
            case "floor" -> fixed(name, 0, (host, args) -> Stdlib.floor(number, span));
            case "ceil" -> fixed(name, 0, (host, args) -> Stdlib.ceil(number, span));
            case "round" -> fixed(name, 0, (host, args) -> Stdlib.round(number, span));
            case "sqrt" -> fixed(name, 0, (host, args) -> Stdlib.real(
                    StrictMath.sqrt(((Number) number).doubleValue()), "sqrt", number));
            // The method form and the builtin must agree, including on what they refuse: a
            // `(-1).sqrt()` that returned NaN while sqrt(-1) raised would be exactly the
            // divergence this project treats as a defect.
            case "sin" -> transcendentalMethod(name, number, StrictMath::sin);
            case "cos" -> transcendentalMethod(name, number, StrictMath::cos);
            case "tan" -> transcendentalMethod(name, number, StrictMath::tan);
            case "asin" -> transcendentalMethod(name, number, StrictMath::asin);
            case "acos" -> transcendentalMethod(name, number, StrictMath::acos);
            case "atan" -> transcendentalMethod(name, number, StrictMath::atan);
            case "ln" -> logarithmMethod(name, number, StrictMath::log);
            case "log10" -> logarithmMethod(name, number, StrictMath::log10);
            case "log2" -> logarithmMethod(name, number, Stdlib::log2);
            case "exp" -> transcendentalMethod(name, number, StrictMath::exp);
            case "degrees" -> transcendentalMethod(name, number, v -> v * (180.0 / StrictMath.PI));
            case "radians" -> transcendentalMethod(name, number, v -> v * (StrictMath.PI / 180.0));
            case "text", "to_string" -> fixed(name, 0, (host, args) -> Values.repr(number));
            case "is_even" -> fixed(name, 0, (host, args) -> isMultipleOfTwo(number, name, true, span));
            case "is_odd" -> fixed(name, 0, (host, args) -> isMultipleOfTwo(number, name, false, span));
            default -> null;
        };
    }

    /**
     * Whether a number is a multiple of two, without narrowing it first.
     *
     * <p>Converting to {@code long} first was wrong for every value outside the long range: a
     * decimal like {@code 1e20} is exactly even (its spacing is far wider than 2), but it used to
     * saturate at {@link Long#MAX_VALUE} and report odd. A decimal is answered directly, with no
     * conversion at all, so the parity is the one the value really has.
     *
     * <p>A decimal with a fractional part has no parity, so it is refused rather than truncated:
     * {@code 1.5.is_even()} answering {@code false} is indistinguishable from {@code 1.5} being
     * odd, which is not what a caller asked. The whole decimal {@code 2.0} is accepted because it
     * equals the whole number {@code 2}.
     */
    private static boolean isMultipleOfTwo(Object number, String name, boolean even, Span span) {
        boolean multiple;
        if (number instanceof Long l) {
            multiple = l % 2 == 0;
        } else {
            double d = (Double) number;
            if (!Double.isFinite(d) || d != Math.rint(d)) {
                throw new OqError(name + "() needs a whole number, got " + Values.display(number),
                        span,
                        "Only a number with no fractional part has a parity.");
            }
            // Every double at or beyond 2^53 is a multiple of two, and `%` on a smaller integral
            // double is exact, so no conversion to long is needed.
            multiple = d % 2 == 0;
        }
        return even == multiple;
    }

    private static dev.oqthara.runtime.NativeFunction transcendentalMethod(
            String name, Object number, java.util.function.DoubleUnaryOperator function) {
        return fixed(name, 0, (host, args) -> Stdlib.real(
                function.applyAsDouble(((Number) number).doubleValue()), name, number));
    }

    private static dev.oqthara.runtime.NativeFunction logarithmMethod(
            String name, Object number, java.util.function.DoubleUnaryOperator function) {
        return fixed(name, 0, (host, args) -> Stdlib.logarithm(
                ((Number) number).doubleValue(), name, number, function));
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

    /**
     * The first character upper-cased, the rest unchanged.
     *
     * <p>The first character is read as a code point, not a UTF-16 unit. {@code charAt(0)} reads
     * half of a surrogate pair, and {@code Character.toUpperCase(char)} cannot map a letter
     * outside the basic multilingual plane at all, so {@code "𐐨x".capitalize()} used to return
     * its input unchanged (issue #148). Reading the pair and using the {@code int} overload
     * upper-cases the letter the language sees, as ADR-0024 requires.
     */
    private static String capitalize(String text) {
        if (text.isEmpty()) return text;
        int first = text.codePointAt(0);
        int upper = Character.toUpperCase(first);
        // A mapping that changes length would need the character count kept in step with the
        // rest of the text; every single code point maps to a single code point in practice.
        int end = Character.charCount(first);
        return new String(Character.toChars(upper)) + text.substring(end);
    }

    private static String pad(String text, List<Object> args, Span span, boolean start) {
        // Widths are character counts, per ADR-0024, so the padding is built from whole
        // characters rather than trimmed to a UTF-16 length that could split a surrogate pair.
        // The width is validated as a whole number first: intValue() used to truncate 9.5 to 9.
        long widthValue = Values.integerIndex(args.get(0), span,
                start ? "a width for pad_start" : "a width for pad_end");
        String fill = args.size() > 1 ? Values.display(args.get(1)) : " ";
        List<Object> fillChars = Values.textCharacters(fill);
        if (fillChars.isEmpty()) fillChars = List.of(" ");
        long count = Values.codePointCount(text);
        long needed = widthValue - count;
        if (needed <= 0) return text;
        // A padding longer than a text value can hold is refused by name, not by an OutOfMemory
        // error or a silent wrap of the int loop counter.
        if (needed > Integer.MAX_VALUE) {
            throw new OqError("padding width is too large: " + widthValue, span,
                    "The largest width pad_start() and pad_end() can build is "
                            + (count + Integer.MAX_VALUE) + " characters.");
        }
        StringBuilder padding = new StringBuilder();
        for (int i = 0; i < (int) needed; i++) padding.append(fillChars.get(i % fillChars.size()));
        return start ? padding + text : text + padding;
    }

    /**
     * A slice bound: negatives count from the end, then the result is clamped into
     * {@code [0, size]}. Clamping a bound past either end is deliberate -- a bound delimits a
     * range, so {@code slice(i, i + 10)} must not fail on a short list.
     *
     * <p>The bound is validated through {@link Values#integerIndex} before it is clamped, so a
     * fractional or non-finite value is refused rather than truncated. Every other form that
     * takes a position -- bracket slicing, indexing, {@code take}, {@code skip} -- already does.
     *
     * <p>The clamp itself happens in {@code long}: narrowing with {@code intValue()} first turns
     * {@code 4294967297} into {@code 1}, which silently slices the wrong range, and turns a large
     * negative into a positive index.
     */
    private static int resolvedBound(Object value, int size, Span span, String which) {
        long index = Values.integerIndex(value, span, which);
        long resolved = index < 0 ? size + index : index;
        if (resolved < 0) return 0;
        if (resolved > size) return size;
        return (int) resolved;
    }

    /** {@code take}/{@code skip}: a count, so nothing is resolved from the end. */
    private static int clampIndex(Object value, int size, Span span, String which) {
        long index = Values.integerIndex(value, span, which);
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
    /** Membership across an arbitrary collection, using Oqthara equality. */
    private static boolean containsValue(Collection<?> items, Object item) {
        for (Object each : items) if (Values.equal(each, item)) return true;
        return false;
    }

    private static Set<Object> asMutableSet(Set<?> set) {
        return (Set<Object>) set;
    }

    private static Map<?, ?> asMap(Object value, String method, Span span) {
        if (value instanceof Map<?, ?> map) return map;
        throw new OqError(
                "expected a map, got " + Values.typeName(value), span,
                "`" + method + "` takes another map, not a list of pairs.");
    }

    private static List<?> asCollection(Object value, Span span) {
        if (value instanceof List<?> list) return list;
        if (value instanceof Set<?> set) return new ArrayList<>(set);
        throw new OqError("expected a list or set, got " + Values.typeName(value), span);
    }
}
