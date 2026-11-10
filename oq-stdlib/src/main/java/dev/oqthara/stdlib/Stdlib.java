package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqMap;
import dev.oqthara.runtime.OqSet;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.OqRange;
import dev.oqthara.runtime.OutputBudget;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Stdlib {
    /**
     * The largest file {@code read} will load, and the largest command output {@code run_command}
     * will keep. Both are charged against the interpreter's step budget as well, so a large
     * transfer costs proportionally rather than counting as a single step.
     */
    static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;

    /** How many steps one byte of transferred text costs. */

    /** How long {@code run_command} waits before it gives up on the child process. */
    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    /** The longest single {@code sleep} call, so one statement cannot block the program for hours. */
    static final long MAX_SLEEP_MILLIS = 10_000;



    /** The most names {@code list_files} will return from one directory. */
    static final int MAX_LIST_ENTRIES = 100_000;

    private Stdlib() {
    }

    public static void install(Interpreter interpreter) {
        installCore(interpreter);
        installText(interpreter);
        installCollections(interpreter);
        installMath(interpreter);
        installFiles(interpreter);
        installJson(interpreter);
        installTime(interpreter);
        installHttp(interpreter);
        installConcurrency(interpreter);
        installProcess(interpreter);
    }

    private static void installCore(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.variadic("print", 0, (host, args, span) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(Values.display(args.get(i)));
            }
            // The newline is output too: charging only the text let a loop of empty prints
            // stream without ever moving the counter.
            chargeOutput(host, sb);
            chargeOutput(host, System.lineSeparator());
            host.out().println(sb);
            return null;
        }));
        interpreter.defineBuiltin(NativeFunction.variadic("write_text", 0, (host, args, span) -> {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) sb.append(Values.display(arg));
            chargeOutput(host, sb);
            host.out().print(sb);
            return null;
        }));
        interpreter.defineBuiltin(NativeFunction.variadic("write", 0, (host, args, span) -> {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) sb.append(Values.display(arg));
            chargeOutput(host, sb);
            host.out().print(sb);
            return null;
        }));
        interpreter.defineBuiltin(NativeFunction.of("input", 0, (host, args, span) -> {
            host.out().flush();
            return readLineBounded(host, span);
        }));
        interpreter.defineBuiltin(NativeFunction.of("input", 1, (host, args, span) -> {
            String prompt = Values.display(args.get(0));
            // A prompt is written output, and it is caller-controlled, so it is billed too.
            chargeOutput(host, prompt);
            host.out().print(prompt);
            host.out().flush();
            return readLineBounded(host, span);
        }));
        interpreter.defineBuiltin(NativeFunction.of("text", 1, (host, args, span) -> Values.display(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("repr", 1, (host, args, span) -> Values.repr(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("number", 1, (host, args, span) -> toNumber(args.get(0), span)));
        interpreter.defineBuiltin(NativeFunction.of("type_of", 1, (host, args, span) -> Values.typeName(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("is_nothing", 1, (host, args, span) -> args.get(0) == null));
        interpreter.defineBuiltin(NativeFunction.of("is_number", 1, (host, args, span) -> Values.isNumber(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("is_text", 1, (host, args, span) -> args.get(0) instanceof String));
        interpreter.defineBuiltin(NativeFunction.of("length", 1, (host, args, span) ->
                Values.length(args.get(0), span)));
        interpreter.defineBuiltin(NativeFunction.of("range", 1, (host, args, span) -> {
            long n = wholeNumber(args.get(0), "range", span);
            return new OqRange(0, Math.max(0, n), false);
        }));
        interpreter.defineBuiltin(NativeFunction.of("range", 2, (host, args, span) ->
                new OqRange(wholeNumber(args.get(0), "range", span),
                        wholeNumber(args.get(1), "range", span), false)));
        interpreter.defineBuiltin(NativeFunction.of("range", 3, (host, args, span) -> {
            long step = wholeNumber(args.get(2), "range", span);
            if (step == 0) {
                throw new dev.oqthara.runtime.OqError("a range step cannot be 0", span,
                        "Without a step there is no next number. Use 1 for every answer, or -1 to count down.");
            }
            return new OqRange(wholeNumber(args.get(0), "range", span),
                    wholeNumber(args.get(1), "range", span), false, step);
        }));
        interpreter.defineBuiltin(NativeFunction.of("error", 1, (host, args, span) -> {
            throw new OqError(Values.display(args.get(0)), span);
        }));
        interpreter.defineBuiltin(NativeFunction.of("assert", 1, (host, args, span) -> {
            if (!Values.truthy(args.get(0))) {
                throw new OqError("assertion failed", span,
                        "The value was " + Values.repr(args.get(0)) + " but it must be true.");
            }
            return null;
        }));
        interpreter.defineBuiltin(NativeFunction.of("assert", 2, (host, args, span) -> {
            if (!Values.truthy(args.get(0))) {
                throw new OqError("assertion failed: " + Values.display(args.get(1)), span);
            }
            return null;
        }));
        interpreter.defineBuiltin(NativeFunction.of("args", 0, (host, args, span) -> new ArrayList<Object>(host.programArgs())));
        interpreter.defineBuiltin(NativeFunction.of("exit", 0, (host, args, span) -> {
            throw new ExitSignal(0);
        }));
        interpreter.defineBuiltin(NativeFunction.of("exit", 1, (host, args, span) -> {
            throw new ExitSignal((int) Values.asLong(args.get(0)));
        }));
        // Read the collection directly rather than delegating to a `sort` method. Only a list
        // has one, so `sort_by(set([3, 1]), fn)` used to fail with "set has no method 'sort'",
        // which named a method the caller never wrote.
        interpreter.defineBuiltin(NativeFunction.of("sort_by", 2, (host, args, span) -> {
            List<Object> items = new ArrayList<>(Values.iterableList(args.get(0), span));
            Object candidate = args.get(1);
            if (!(candidate instanceof OqCallable key)) {
                throw new OqError("sort_by() needs a key function", span,
                        "Pass a function: sort_by(items, fun(x) return x.score end).");
            }
            items.sort((a, b) -> Values.compare(
                    host.call(key, List.of(a), span), host.call(key, List.of(b), span), span));
            return items;
        }));
        interpreter.defineBuiltin(NativeFunction.of("map", 2, (host, args, span) ->
                invokeMethod(host, args.get(0), "map", List.of(args.get(1)), span)));
        interpreter.defineBuiltin(NativeFunction.of("filter", 2, (host, args, span) ->
                invokeMethod(host, args.get(0), "filter", List.of(args.get(1)), span)));
        interpreter.defineBuiltin(NativeFunction.of("clone", 1, (host, args, span) -> deepCopy(args.get(0))));
    }

    private static void installText(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("format", 1, (host, args, span) -> Values.display(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.variadic("join", 1, (host, args, span) -> {
            Object first = args.get(0);
            Iterable<?> items = Values.iterable(first, span);
            String separator = args.size() > 1 ? Values.display(args.get(1)) : "";
            StringBuilder sb = new StringBuilder();
            boolean firstItem = true;
            for (Object item : items) {
                if (!firstItem) sb.append(separator);
                sb.append(Values.display(item));
                firstItem = false;
            }
            return sb.toString();
        }));
        interpreter.defineBuiltin(NativeFunction.of("upper", 1, (host, args, span) ->
                Values.display(args.get(0)).toUpperCase(Locale.ROOT)));
        interpreter.defineBuiltin(NativeFunction.of("lower", 1, (host, args, span) ->
                Values.display(args.get(0)).toLowerCase(Locale.ROOT)));
        interpreter.defineBuiltin(NativeFunction.of("trim", 1, (host, args, span) ->
                Values.display(args.get(0)).strip()));
        interpreter.defineBuiltin(NativeFunction.of("contains", 2, (host, args, span) ->
                Values.contains(args.get(0), args.get(1), span)));
        interpreter.defineBuiltin(NativeFunction.of("split", 2, (host, args, span) -> {
            String text = Values.display(args.get(0));
            String separator = Values.display(args.get(1));
            return new ArrayList<Object>(List.of(text.split(Pattern.quote(separator), -1)));
        }));
        interpreter.defineBuiltin(NativeFunction.of("replace", 3, (host, args, span) ->
                Values.display(args.get(0)).replace(Values.display(args.get(1)), Values.display(args.get(2)))));
        interpreter.defineBuiltin(NativeFunction.of("regex", 2, (host, args, span) -> {
            Pattern pattern = compilePattern(args.get(0), span);
            Matcher matcher = pattern.matcher(Values.display(args.get(1)));
            List<Object> matches = new ArrayList<>();
            while (matcher.find()) {
                if (matcher.groupCount() > 0) {
                    List<Object> groups = new ArrayList<>();
                    for (int i = 0; i <= matcher.groupCount(); i++) {
                        groups.add(matcher.group(i));
                    }
                    matches.add(groups);
                } else {
                    matches.add(matcher.group());
                }
            }
            return matches;
        }));
        interpreter.defineBuiltin(NativeFunction.of("matches", 2, (host, args, span) ->
                compilePattern(args.get(0), span).matcher(Values.display(args.get(1))).find()));
    }

    private static void installCollections(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("keys", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof Map<?, ?> map)) {
                throw new OqError("keys() needs a map, got " + Values.typeName(args.get(0)), span);
            }
            return new ArrayList<Object>(map.keySet());
        }));
        interpreter.defineBuiltin(NativeFunction.of("values", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof Map<?, ?> map)) {
                throw new OqError("values() needs a map, got " + Values.typeName(args.get(0)), span);
            }
            return new ArrayList<Object>(map.values());
        }));
        interpreter.defineBuiltin(NativeFunction.of("push", 2, (host, args, span) -> {
            if (!(args.get(0) instanceof List<?> raw)) {
                throw new OqError("push() needs a list, got " + Values.typeName(args.get(0)), span);
            }
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) raw;
            list.add(args.get(1));
            return list;
        }));
        interpreter.defineBuiltin(NativeFunction.of("pop", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "pop", List.of(), span)));
        // sum/min/max/sorted each need a collection, and every family already reports its own
        // length and iterates, so they read through iterableList rather than a per-type method.
        // `sum` on a set or range previously failed with "set has no method 'sum'".
        interpreter.defineBuiltin(NativeFunction.of("sum", 1, (host, args, span) ->
                sumOf(Values.iterableList(args.get(0), span), span)));
        interpreter.defineBuiltin(NativeFunction.of("sort", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "sort", List.of(), span)));
        interpreter.defineBuiltin(NativeFunction.of("reverse", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "reverse", List.of(), span)));
        interpreter.defineBuiltin(NativeFunction.of("unique", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "unique", List.of(), span)));
        interpreter.defineBuiltin(NativeFunction.of("set", 1, (host, args, span) -> {
            if (args.get(0) instanceof Set<?> set) return set;
            Set<Object> result = new OqSet();
            for (Object item : Values.iterable(args.get(0), span)) result.add(item);
            return result;
        }));
        interpreter.defineBuiltin(NativeFunction.of("list", 1, (host, args, span) -> {
            if (args.get(0) instanceof List<?> list) return list;
            List<Object> result = new ArrayList<>();
            for (Object item : Values.iterable(args.get(0), span)) result.add(item);
            return result;
        }));
        interpreter.defineBuiltin(NativeFunction.of("enumerate", 1, (host, args, span) -> {
            List<Object> result = new ArrayList<>();
            long index = 0;
            for (Object item : Values.iterable(args.get(0), span)) {
                List<Object> pair = new ArrayList<>(2);
                pair.add(index++);
                pair.add(item);
                result.add(pair);
            }
            return result;
        }));
        interpreter.defineBuiltin(NativeFunction.of("zip", 2, (host, args, span) -> {
            List<?> a = Values.iterableList(args.get(0), span);
            List<?> b = Values.iterableList(args.get(1), span);
            List<Object> result = new ArrayList<>();
            int n = Math.min(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                result.add(new ArrayList<Object>(List.of(a.get(i), b.get(i))));
            }
            return result;
        }));
        interpreter.defineBuiltin(NativeFunction.of("range_list", 2, (host, args, span) ->
                new OqRange(Values.asLong(args.get(0)), Values.asLong(args.get(1)), false).toList()));
    }

    private static void installMath(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("abs", 1, (host, args, span) -> {
            Object value = args.get(0);
            if (value instanceof Long l) {
                // Math.abs(Long.MIN_VALUE) is negative again: the magnitude does not fit a
                // whole number, so keep the exact positive value as a decimal instead of
                // silently returning the input unchanged.
                if (l == Long.MIN_VALUE) return -((double) l);
                return Math.abs(l);
            }
            if (value instanceof Double d) return Math.abs(d);
            throw new OqError("abs() needs a number, got " + Values.typeName(value), span);
        }));
        interpreter.defineBuiltin(NativeFunction.of("floor", 1, (host, args, span) ->
                whole(Math.floor(asNumber(args.get(0), "floor", span)), "floor", span)));
        interpreter.defineBuiltin(NativeFunction.of("ceil", 1, (host, args, span) ->
                whole(Math.ceil(asNumber(args.get(0), "ceil", span)), "ceil", span)));
        interpreter.defineBuiltin(NativeFunction.of("round", 1, (host, args, span) -> {
            double value = asNumber(args.get(0), "round", span);
            requireWhole(value, "round", span);
            // Beyond the long range the double is already integral (spacing > 1), so the
            // value itself is the rounded result. Math.round would saturate at Long.MAX_VALUE.
            return Values.fitsInLong(value) ? (Object) Math.round(value) : (Object) value;
        }));
        interpreter.defineBuiltin(NativeFunction.of("round", 2, (host, args, span) -> {
            double value = asNumber(args.get(0), "round", span);
            long places = Values.asLong(args.get(1));
            if (places < 0 || places > 15) {
                throw new OqError("round() places must be between 0 and 15, got " + places, span);
            }
            double factor = Math.pow(10, places);
            double scaled = value * factor;
            if (!Double.isFinite(scaled)) {
                throw new OqError("round() cannot round " + Values.display(value)
                        + " to " + places + " places: the number is too large", span);
            }
            // A value whose scaled form does not fit a long has no fractional digits left to
            // round at this precision, so it is already the answer. Using Math.round there
            // would saturate at Long.MAX_VALUE and silently change the number.
            double rounded = Values.fitsInLong(scaled) ? Math.round(scaled) / factor : value;
            if (places == 0) return whole(rounded, "round", span);
            return rounded;
        }));
        interpreter.defineBuiltin(NativeFunction.of("sqrt", 1, (host, args, span) ->
                Math.sqrt(asNumber(args.get(0), "sqrt", span))));
        interpreter.defineBuiltin(NativeFunction.of("pow", 2, (host, args, span) ->
                Values.power(args.get(0), args.get(1), span)));
        // One argument means "the extreme of this collection"; two or more mean "among these
        // values". The arity decides, so `min([3, 1])` is 1 and `min(3, 1)` is 1 as well.
        interpreter.defineBuiltin(NativeFunction.variadic("min", 1, (host, args, span) ->
                extremeOf(args, span, "min", -1)));
        interpreter.defineBuiltin(NativeFunction.variadic("max", 1, (host, args, span) ->
                extremeOf(args, span, "max", 1)));
        interpreter.defineBuiltin(NativeFunction.of("sorted", 1, (host, args, span) ->
                sortedCopy(Values.iterableList(args.get(0), span), span, 1)));
        interpreter.defineBuiltin(NativeFunction.of("clamp", 3, (host, args, span) -> {
            Object value = args.get(0);
            if (Values.compare(value, args.get(1), span) < 0) return args.get(1);
            if (Values.compare(value, args.get(2), span) > 0) return args.get(2);
            return value;
        }));
        interpreter.defineBuiltin(NativeFunction.of("random", 0, (host, args, span) ->
                ThreadLocalRandom.current().nextDouble()));
        interpreter.defineBuiltin(NativeFunction.of("random", 2, (host, args, span) -> {
            long from = Values.asLong(args.get(0));
            long to = Values.asLong(args.get(1));
            if (to <= from) return from;
            return ThreadLocalRandom.current().nextLong(from, to);
        }));
        interpreter.defineBuiltin(NativeFunction.of("random_item", 1, (host, args, span) -> {
            List<?> items = Values.iterableList(args.get(0), span);
            if (items.isEmpty()) return null;
            return items.get(ThreadLocalRandom.current().nextInt(items.size()));
        }));
    }

    private static void installFiles(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("read", 1, (host, args, span) -> {
            Path path = resolve(host, args.get(0));
            return readBounded(host, path, span, "the file");
        }));
        interpreter.defineBuiltin(NativeFunction.of("read_lines", 1, (host, args, span) -> {
            Path path = resolve(host, args.get(0));
            // Same cap and same charge as `read`, so splitting a file into lines is not a way
            // around the limit.
            String text = readBounded(host, path, span, "the file");
            // `String.lines()` splits on \n, \r\n and \r without inventing a trailing empty
            // line, which is what `Files.readAllLines` did before this became bounded.
            List<Object> lines = new ArrayList<>();
            text.lines().forEach(lines::add);
            return lines;
        }));
        interpreter.defineBuiltin(NativeFunction.of("save", 2, (host, args, span) -> {
            Path path = resolve(host, args.get(0));
            String text = Values.display(args.get(1));
            int written = chargeWrittenText(text, "save", span);
            chargeTransfer(host, written);
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.writeString(path, text, StandardCharsets.UTF_8);
                return null;
            } catch (IOException e) {
                throw new OqError("cannot write file '" + path + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("append", 2, (host, args, span) -> {
            Path path = resolve(host, args.get(0));
            String text = Values.display(args.get(1));
            int written = chargeWrittenText(text, "append", span);
            chargeTransfer(host, written);
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.writeString(path, text, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                return null;
            } catch (IOException e) {
                throw new OqError("cannot append to file '" + path + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("exists", 1, (host, args, span) ->
                Files.exists(resolve(host, args.get(0)))));
        interpreter.defineBuiltin(NativeFunction.of("list_files", 1, (host, args, span) -> {
            Path dir = resolve(host, args.get(0));
            try (var stream = Files.list(dir)) {
                Iterator<Path> entries = stream.sorted().iterator();
                List<Object> names = new ArrayList<>();
                // The names list now holds at most the cap plus one, where it used to hold the
                // whole directory. java.util.stream.Stream.sorted() is a stateful operation and
                // still buffers every Path internally before yielding the first one, so this
                // bounds Oqthara's own list, not the sort. Removing that buffer would mean
                // dropping sorted() and changing the returned order, which the spec fixes; the
                // residual is recorded as debt rather than fixed by an ordering change here.
                while (names.size() <= MAX_LIST_ENTRIES && entries.hasNext()) {
                    names.add(entries.next().getFileName().toString());
                }
                if (names.size() > MAX_LIST_ENTRIES) {
                    throw new OqError("directory has too many entries: " + dir, span,
                            "It has more than " + MAX_LIST_ENTRIES + " entries; the limit is "
                                    + MAX_LIST_ENTRIES + ". List it in another language.");
                }
                chargeTransfer(host, names.size());
                return names;
            } catch (IOException e) {
                throw new OqError("cannot list directory '" + dir + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("delete", 1, (host, args, span) -> {
            try {
                return Files.deleteIfExists(resolve(host, args.get(0)));
            } catch (IOException e) {
                throw new OqError("cannot delete '" + args.get(0) + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("make_dir", 1, (host, args, span) -> {
            try {
                Files.createDirectories(resolve(host, args.get(0)));
                return null;
            } catch (IOException e) {
                throw new OqError("cannot create directory '" + args.get(0) + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("file_size", 1, (host, args, span) -> {
            try {
                return Files.size(resolve(host, args.get(0)));
            } catch (IOException e) {
                throw new OqError("cannot read size of '" + args.get(0) + "'", span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("path_join", 2, (host, args, span) ->
                Path.of(Values.display(args.get(0))).resolve(Values.display(args.get(1))).toString()));
        interpreter.defineBuiltin(NativeFunction.of("path_name", 1, (host, args, span) -> {
            Path path = Path.of(Values.display(args.get(0)));
            return path.getFileName() == null ? "" : path.getFileName().toString();
        }));
        interpreter.defineBuiltin(NativeFunction.of("path_parent", 1, (host, args, span) -> {
            Path parent = Path.of(Values.display(args.get(0))).getParent();
            return parent == null ? "" : parent.toString();
        }));
        interpreter.defineBuiltin(NativeFunction.of("cwd", 0, (host, args, span) ->
                host.workingDirectory().toAbsolutePath().toString()));
    }

    private static void installJson(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("json_parse", 1, (host, args, span) ->
                Json.parse(Values.display(args.get(0)), span)));
        interpreter.defineBuiltin(NativeFunction.of("json", 1, (host, args, span) -> Json.write(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("json_write", 1, (host, args, span) -> Json.write(args.get(0))));
        interpreter.defineBuiltin(NativeFunction.of("json_pretty", 1, (host, args, span) ->
                Json.writePretty(args.get(0))));
    }

    private static void installTime(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("now", 0, (host, args, span) ->
                java.time.Instant.now().toString()));
        interpreter.defineBuiltin(NativeFunction.of("today", 0, (host, args, span) ->
                LocalDate.now().toString()));
        interpreter.defineBuiltin(NativeFunction.of("datetime", 0, (host, args, span) ->
                LocalDateTime.now().withNano(0).toString()));
        interpreter.defineBuiltin(NativeFunction.of("timestamp", 0, (host, args, span) ->
                System.currentTimeMillis()));
        interpreter.defineBuiltin(NativeFunction.of("sleep", 1, (host, args, span) -> {
            long millis = (long) (Values.asDouble(args.get(0)) * 1000);
            if (millis > MAX_SLEEP_MILLIS) {
                throw new OqError("cannot sleep for more than "
                        + MAX_SLEEP_MILLIS / 1000 + " seconds", span,
                        "A long wait would block the program; the limit is "
                                + MAX_SLEEP_MILLIS / 1000 + " seconds per call.");
            }
            // Time is not a transfer: billing a millisecond as one step let a program wait
            // 50,000,000 ms (about 13.9 hours). Waiting has its own aggregate budget.
            dev.oqthara.runtime.WaitedBudget.charge(millis, span);
            try {
                Thread.sleep(Math.max(0, millis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
    }

    private static void installHttp(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("http_get", 1, (host, args, span) ->
                http(host, "GET", Values.display(args.get(0)), null, span)));
        interpreter.defineBuiltin(NativeFunction.of("http_post", 2, (host, args, span) ->
                http(host, "POST", Values.display(args.get(0)), Values.display(args.get(1)), span)));
    }

    private static Object http(OqHost host, String method, String url, String body, Span span) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "oqthara/0.1");
            if (method.equals("POST")) {
                builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                builder.header("Content-Type", "application/json");
            } else {
                builder.GET();
            }
            HttpResponse<java.io.InputStream> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            // Stream the body and stop at the cap. `ofByteArray` would buffer an unlimited
            // response in memory before the size check could run, which is the very thing the
            // check exists to prevent.
            // The request timeout only bounds the wait for headers. A server that sends headers
            // and then stalls the body would hang the program forever, so bound the body too.
            byte[] bytes = readCappedWithinDeadline(response.body(), span);
            if (bytes.length > MAX_TEXT_BYTES) {
                throw new OqError("response is too large: " + url, span,
                        "It exceeded " + MAX_TEXT_BYTES + " bytes.");
            }
            chargeTransfer(host, bytes.length);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("status", (long) response.statusCode());
            result.put("body", new String(bytes, StandardCharsets.UTF_8));
            result.put("ok", response.statusCode() >= 200 && response.statusCode() < 300);
            return new OqData("Response", result);
        } catch (IllegalArgumentException e) {
            throw new OqError("invalid URL '" + url + "'", span, e.getMessage());
        } catch (java.io.UncheckedIOException e) {
            throw new OqError("HTTP request failed: " + e.getMessage(), span,
                    "Check the URL and your network connection.");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new OqError("HTTP request failed: " + e.getMessage(), span,
                    "Check the URL and your network connection.");
        }
    }

    private static void installConcurrency(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("spawn", 1, (host, args, span) -> {
            Object callback = args.get(0);
            java.util.concurrent.CompletableFuture<Object> future =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> host.call(callback, List.of(), span),
                            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            return new dev.oqthara.runtime.OqTask(future);
        }));
        interpreter.defineBuiltin(NativeFunction.of("await", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof dev.oqthara.runtime.OqTask task)) {
                throw new OqError("await() needs a task from spawn(), got "
                        + Values.typeName(args.get(0)), span);
            }
            return task.await(span);
        }));
        interpreter.defineBuiltin(NativeFunction.of("done", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof dev.oqthara.runtime.OqTask task)) {
                throw new OqError("done() needs a task from spawn(), got "
                        + Values.typeName(args.get(0)), span);
            }
            return task.isDone();
        }));
        interpreter.defineBuiltin(NativeFunction.of("par_map", 2, (host, args, span) -> {
            List<?> items = Values.iterableList(args.get(0), span);
            Object callback = args.get(1);
            List<java.util.concurrent.CompletableFuture<Object>> futures = new ArrayList<>();
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                for (Object item : items) {
                    futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> host.call(callback, List.of(item), span), executor));
                }
            }
            List<Object> results = new ArrayList<>();
            for (var future : futures) {
                try {
                    results.add(future.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof OqError oq) throw oq;
                    throw new OqError("parallel task failed: " + cause, span);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OqError("interrupted while mapping in parallel", span);
                }
            }
            return results;
        }));
    }

    private static void installProcess(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("env", 1, (host, args, span) ->
                System.getenv(Values.display(args.get(0)))));
        interpreter.defineBuiltin(NativeFunction.of("run_command", 1, (host, args, span) -> {
            String command = Values.display(args.get(0));
            try {
                ProcessBuilder builder = new ProcessBuilder(List.of(command));
                builder.directory(host.workingDirectory().toFile());
                builder.redirectErrorStream(true);
                Process process = builder.start();
                // Read on a separate thread so a command that never exits cannot block the
                // timeout, and so output that overflows the cap is never fully in memory.
                java.util.concurrent.CompletableFuture<byte[]> output =
                        java.util.concurrent.CompletableFuture.supplyAsync(
                                () -> readCapped(process.getInputStream()));
                // One deadline covers both waits. Giving the output read a full timeout and then
                // the exit wait another would let a slow child run for twice the documented time.
                long deadline = System.nanoTime() + COMMAND_TIMEOUT.toNanos();
                byte[] bytes;
                try {
                    bytes = output.get(remaining(deadline), java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    destroyTree(process);
                    throw timeoutError(command, span);
                }
                if (bytes.length > MAX_TEXT_BYTES) {
                    destroyTree(process);
                    throw new OqError("command output is too large: " + command, span,
                            "It exceeded " + MAX_TEXT_BYTES + " bytes.");
                }
                // The reader stops at end of output, so reaching here means the child closed its
                // output. It may still be running, so spend what is left of the same deadline.
                if (!process.waitFor(remaining(deadline), java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    destroyTree(process);
                    throw timeoutError(command, span);
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                chargeTransfer(host, bytes.length);
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                result.put("code", (long) process.exitValue());
                result.put("output", text);
                return new OqData("Result", result);
            } catch (OqError error) {
                throw error;
            } catch (IOException e) {
                throw new OqError("cannot run command: " + e.getMessage(), span,
                        "run_command() takes one executable path and no arguments, and never goes "
                                + "through a shell. To pass arguments, write a script and run it.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OqError("command interrupted", span);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new OqError("cannot read command output: " + command, span, e.getMessage());
            }
        }));
        interpreter.defineBuiltin(NativeFunction.of("cpu_count", 0, (host, args, span) ->
                (long) Runtime.getRuntime().availableProcessors()));
        interpreter.defineBuiltin(NativeFunction.of("memory_mb", 0, (host, args, span) ->
                Runtime.getRuntime().totalMemory() / (1024 * 1024)));
    }

    private static Path resolve(OqHost host, Object value) {
        Path path = Path.of(Values.display(value));
        if (path.isAbsolute()) return path;
        // Relative paths resolve against the interpreter's working directory, so `oq run`
        // and a generated jar behave identically no matter where the process was started.
        Path base = host.workingDirectory();
        if (base == null) base = Path.of("").toAbsolutePath();
        return base.toAbsolutePath().resolve(path).normalize();
    }

    /**
     * Bills the program one step per unit it moved.
     *
     * <p>The unit is whatever the caller measured: bytes for the file, HTTP and process
     * transfers, characters for {@code input}, directory entries for {@code list_files}.
     *
     * <p>Builtins run as a single interpreter step, so a loop that prints a large string would
     * otherwise cost one step per iteration no matter how many bytes moved. The per-unit charge
     * makes a program pay for what it transfers. It is <em>not</em> the bound itself: each call
     * has its own size limit in the table above, and the charge only ensures a loop of small
     * transfers cannot be free. {@code host} is an {@link Interpreter} for every path that runs
     * user code; a compiled program and a bare method resolver have no counter to charge.
     */
    private static void chargeTransfer(OqHost host, long bytes) {
        if (bytes <= 0) return;
        // A per-byte step multiplier made the documented per-call ceiling unreachable: at 256
        // steps per byte the 50,000,000 budget ran out at 195,312 bytes, so `read` refused a
        // 1 MiB file with "program ran for too long" no matter what the table said. Transfers
        // are now bounded by their own size checks and billed one step per unit moved. That
        // unit is not always a byte: `input` passes characters and `list_files` passes
        // entries. Output never reaches here at all — see chargeOutput.
        if (host instanceof Interpreter interpreter) interpreter.chargeSteps(bytes);
    }

    private static void chargeOutput(OqHost host, CharSequence text) {
        // Charge the UTF-8 size, not the character count: the docs say bytes, and a multi-byte
        // character really does move more of them. Billing unconditionally matters because the
        // budget is shared with compiled bytecode, which reaches it through Arithmetic.
        chargeOutputBytes(text.toString().getBytes(StandardCharsets.UTF_8).length);
    }

    private static void chargeOutputBytes(long bytes) {
        if (bytes > 0) OutputBudget.charge(bytes);
    }

    /**
     * Refuses a single {@code save}/{@code append} larger than the documented transfer ceiling.
     *
     * <p>These do not print, so they must not draw on the output budget; without this check the
     * only limit on one call was the step budget.
     */
    private static int chargeWrittenText(String text, String verb, Span span) {
        // bytes, not characters: the limit is stated and enforced in bytes everywhere else, and
        // one astral character is four of them. The count is returned so the caller bills the
        // transfer at the same figure it checked.
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_TEXT_BYTES) {
            throw new OqError("cannot " + verb + " " + bytes + " bytes in one call", span,
                    "A single write is limited to " + MAX_TEXT_BYTES
                            + " bytes. Write it in pieces, or use append.");
        }
        return bytes;
    }

    /** What is left of a deadline, never negative, for a timed wait. */
    private static long remaining(long deadlineNanos) {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    private static OqError timeoutError(String command, Span span) {
        return new OqError("command timed out after " + COMMAND_TIMEOUT.toSeconds()
                + " seconds: " + command, span,
                "A command that never exits would block the program forever. "
                        + "Run it in the background yourself if it is expected to be slow.");
    }

    /**
     * Kills the child and everything it started. Destroying only the child leaves its own
     * children running, which is how a timed-out script leaks the process it spawned.
     */
    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads one line of input, capped and billed like every other transfer.
     *
     * <p>A plain {@code readLine()} would happily build an 80 MB string from a hostile writer and
     * would block forever on a pipe that never closes. This reads at most
     * {@link #MAX_TEXT_BYTES}, so an oversized line is refused instead of loaded.
     */
    private static String readLineBounded(OqHost host, Span span) {
        StringBuilder sb = new StringBuilder();
        boolean sawEnd = false;
        try {
            int code = host.in().read();
            while (code != -1 && code != '\n') {
                if (sb.length() >= MAX_TEXT_BYTES) {
                    throw new OqError("input line is too large", span,
                            "It is more than the " + MAX_TEXT_BYTES + " character limit. "
                                    + "Read it in another language, or feed smaller lines.");
                }
                sb.append((char) code);
                code = host.in().read();
            }
            sawEnd = code == -1;
        } catch (IOException e) {
            throw new OqError("cannot read input: " + e.getMessage(), span);
        }
        chargeTransfer(host, sb.length());
        // End of input with nothing read is `nothing`, matching a Reader that returns null.
        return sawEnd && sb.isEmpty() ? null : sb.toString();
    }

    /** Drains a stream, keeping the first {@link #MAX_TEXT_BYTES} and stopping the child there. */
    private static byte[] readCapped(java.io.InputStream stream) {
        try (stream) {
            return stream.readNBytes(MAX_TEXT_BYTES + 1);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Drains a response body on its own thread and gives up at {@link #COMMAND_TIMEOUT}.
     *
     * <p>{@link java.net.http.HttpRequest#timeout} stops at the headers, so it does not cover a
     * body that trickles or stops arriving. The reader thread is abandoned rather than joined:
     * a blocking socket read cannot be cancelled, and it ends when the connection is closed.
     */
    private static byte[] readCappedWithinDeadline(java.io.InputStream body, Span span) {
        var reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> readCapped(body));
        try {
            return reader.get(COMMAND_TIMEOUT.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            reader.cancel(true);
            throw new OqError("the response body did not finish in "
                    + COMMAND_TIMEOUT.toSeconds() + " seconds", span,
                    "The server sent headers but stalled the body. A response that never "
                            + "finishes would block the program forever.");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof java.io.UncheckedIOException io) {
                throw new OqError("cannot read the response body", span, io.getMessage());
            }
            throw new OqError("cannot read the response body", span, String.valueOf(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OqError("interrupted while reading the response body", span);
        }
    }

    /**
     * Reads at most {@link #MAX_TEXT_BYTES}, refusing anything larger instead of loading it.
     *
     * <p>The read is capped by how much it actually returns, not by {@link Files#size}. Size is
     * advisory for anything that is not a regular file: a FIFO and {@code /dev/zero} both report
     * 0 while yielding unbounded data, so trusting the reported size lets them through.
     */
    private static String readBounded(OqHost host, Path path, Span span, String what) {
        try (java.io.InputStream stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAX_TEXT_BYTES + 1);
            if (bytes.length > MAX_TEXT_BYTES) {
                throw new OqError(what + " is too large to read: " + path, span,
                        "It is more than the " + MAX_TEXT_BYTES + " byte limit. "
                                + "Read it in another language, or split it into smaller files.");
            }
            chargeTransfer(host, bytes.length);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (OqError error) {
            throw error;
        } catch (IOException e) {
            throw new OqError("cannot read file '" + path + "'", span,
                    "Check that the file exists and is readable. (" + e.getMessage() + ")");
        }
    }

    private static Pattern compilePattern(Object value, Span span) {
        try {
            return Pattern.compile(Values.display(value));
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new OqError("invalid regular expression: " + e.getDescription(), span,
                    "Check the pattern syntax.");
        }
    }

    /**
     * A numeric argument for a math builtin. {@link Values#asDouble} throws a raw
     * {@code IllegalArgumentException} for text, nothing or collections, which would reach the
     * CLI as an internal error; this reports the usual type diagnostic instead.
     */
    private static Object sumOf(List<?> items, Span span) {
        Object total = 0L;
        for (Object item : items) total = Values.add(total, item, span);
        return total;
    }

    /**
     * {@code min}/{@code max} in both arities. Oqthara has no rest parameters, so the
     * collection form and the value form are distinguished by how many arguments arrived
     * rather than by a separate spelling.
     */
    private static Object extremeOf(List<Object> args, Span span, String builtin, int sign) {
        List<?> items = args.size() == 1 ? Values.iterableList(args.get(0), span) : args;
        if (items.isEmpty()) {
            throw new OqError(builtin + "() needs at least one value", span,
                    "Check the collection is not empty before taking its extreme.");
        }
        Object best = items.get(0);
        for (int i = 1; i < items.size(); i++) {
            if (Values.compare(items.get(i), best, span) * sign > 0) best = items.get(i);
        }
        return best;
    }

    /** A sorted copy, leaving the argument untouched, as {@code sorted()} must. */
    private static List<Object> sortedCopy(List<?> items, Span span, int unused) {
        List<Object> copy = new ArrayList<>(items);
        copy.sort((a, b) -> Values.compare(a, b, span));
        return copy;
    }

    private static double asNumber(Object value, String builtin, Span span) {
        if (Values.isNumber(value)) return Values.asDouble(value);
        throw new OqError(builtin + "() needs a number, got " + Values.typeName(value), span);
    }

    /**
     * An integer argument for a builtin that needs one, with the same diagnostic a wrong type
     * gets everywhere else. {@link Values#asLong} throws a raw {@code IllegalArgumentException},
     * which reached the CLI as "internal error" instead of a program error.
     */
    private static long wholeNumber(Object value, String builtin, Span span) {
        return Values.asLong(asNumber(value, builtin, span));
    }

    /**
     * The whole-number result of {@code floor}, {@code ceil} and friends, or the decimal when
     * the value does not fit a {@code long}. Casting {@code 1e300} to {@code long} saturates at
     * {@link Long#MAX_VALUE}, which silently turns a large number into a wrong one.
     */
    private static Object whole(double value, String builtin, Span span) {
        requireWhole(value, builtin, span);
        return Values.fitsInLong(value) ? (Object) (long) value : (Object) value;
    }

    private static void requireWhole(double value, String builtin, Span span) {
        if (!Double.isFinite(value)) {
            throw new OqError(builtin + "() needs a finite number, got "
                    + Values.display(value), span);
        }
    }

    private static Object invokeMethod(OqHost host, Object receiver, String name, List<Object> args, Span span) {
        if (!(host instanceof Interpreter interpreter) || interpreter.methods() == null) {
            throw new OqError("method lookup is not available", span);
        }
        dev.oqthara.runtime.OqCallable method = interpreter.methods().resolve(receiver, name, span);
        if (method == null) {
            // Keep this in step with Methods.java: the compiled path reaches this message and
            // the interpreted path reaches that one, so a hint added to only one would make the
            // two backends disagree about the same mistake.
            if (name.equals("next") || name.equals("has_next")) {
                throw new OqError(Values.typeName(receiver) + " has no method '" + name + "'", span,
                        "Call iter() first: " + Values.typeName(receiver)
                                + " is iterable, not an iterator.");
            }
            throw new OqError(Values.typeName(receiver) + " has no method '" + name + "'", span);
        }
        return host.call(method, args, span);
    }

    private static Object toNumber(Object value, Span span) {
        if (value instanceof Long || value instanceof Double) return value;
        if (value instanceof Boolean b) return b ? 1L : 0L;
        if (value instanceof String text) {
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
        throw new OqError("cannot convert " + Values.typeName(value) + " to a number", span);
    }

    @SuppressWarnings("unchecked")
    static Object deepCopy(Object value) {
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) copy.add(deepCopy(item));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new OqMap();
            map.forEach((k, v) -> copy.put(deepCopy(k), deepCopy(v)));
            return copy;
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new OqSet();
            for (Object item : set) copy.add(deepCopy(item));
            return copy;
        }
        if (value instanceof OqData data) {
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            data.fields().forEach((k, v) -> fields.put(k, deepCopy(v)));
            return new OqData(data.typeName(), fields);
        }
        return value;
    }

    public static final class ExitSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int code;

        public ExitSignal(int code) {
            super(null, null, false, false);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    interface CallableMarker extends OqCallable {
    }
}
