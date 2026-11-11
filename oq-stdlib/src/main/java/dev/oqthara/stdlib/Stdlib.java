package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqMap;
import dev.oqthara.runtime.OqFrozenSet;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
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

    /**
     * The TCP handshake, and only that. A host that is unreachable entirely is a different failure
     * from a server that accepts and then thinks, and both should not share one number.
     */
    static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * The wait for response headers. {@link java.net.http.HttpRequest#timeout} stops here, so this
     * does not bound the body; {@link #HTTP_BODY_TIMEOUT} does (ADR-0037, Decision 6).
     */
    static final Duration HTTP_HEADER_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The wait for the body once headers arrive. Separate from {@link #COMMAND_TIMEOUT} so that a
     * stalled server reports an HTTP deadline rather than the subprocess timeout it used to borrow.
     */
    static final Duration HTTP_BODY_TIMEOUT = Duration.ofSeconds(30);

    /** The longest single {@code sleep} call, so one statement cannot block the program for hours. */
    static final long MAX_SLEEP_MILLIS = 10_000;

    /**
     * The one executor {@code spawn} hands tasks to. Created on first use and never shut down:
     * a per-call executor was unreachable and never closed, so the leak grew with the number of
     * tasks. Virtual threads do not keep the JVM alive, so leaving this open changes nothing
     * observable when a program ends (ADR-0039, Decision 2).
     */
    private static final class TaskRunner {
        static final java.util.concurrent.ExecutorService EXECUTOR =
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }



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
        installFileOps(interpreter);
        Patterns.install(interpreter);
        TextWrap.install(interpreter);
        Compression.install(interpreter);
        Statistics.install(interpreter);
        installJson(interpreter);
        installEncoding(interpreter);
        installCsv(interpreter);
        installDates(interpreter);
        installTime(interpreter);
        installUrls(interpreter);
        installHttp(interpreter);
        installConcurrency(interpreter);
        installProcess(interpreter);
        installLogging(interpreter);
        installArguments(interpreter);
        DatabaseModule.install(interpreter);
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
        }).named("prompt"));
        interpreter.defineBuiltin(NativeFunction.of("text", 1, (host, args, span) -> Values.display(args.get(0)))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("repr", 1, (host, args, span) -> Values.repr(args.get(0)))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("number", 1, (host, args, span) -> toNumber(args.get(0), span))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("type_of", 1, (host, args, span) -> Values.typeName(args.get(0)))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("is_nothing", 1,
                (host, args, span) -> args.get(0) == null).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("is_number", 1, (host, args, span) -> Values.isNumber(args.get(0)))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("is_text", 1,
                (host, args, span) -> args.get(0) instanceof String).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("length", 1, (host, args, span) ->
                Values.length(args.get(0), span)).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("range", 1, (host, args, span) -> {
            long n = wholeNumber(args.get(0), "range", span);
            return new OqRange(0, Math.max(0, n), false);
        }).named("stop"));
        interpreter.defineBuiltin(NativeFunction.of("range", 2, (host, args, span) ->
                new OqRange(wholeNumber(args.get(0), "range", span),
                        wholeNumber(args.get(1), "range", span), false)).named("start", "stop"));
        interpreter.defineBuiltin(NativeFunction.of("range", 3, (host, args, span) -> {
            long step = wholeNumber(args.get(2), "range", span);
            if (step == 0) {
                throw new dev.oqthara.runtime.OqError("a range step cannot be 0", span,
                        "Without a step there is no next number. Use 1 for every answer, or -1 to count down.");
            }
            return new OqRange(wholeNumber(args.get(0), "range", span),
                    wholeNumber(args.get(1), "range", span), false, step);
        }).named("start", "stop", "step"));
        interpreter.defineBuiltin(NativeFunction.of("error", 1, (host, args, span) -> {
            throw new OqError(Values.display(args.get(0)), span);
        }).named("message"));
        interpreter.defineBuiltin(NativeFunction.of("assert", 1, (host, args, span) -> {
            if (!Values.truthy(args.get(0))) {
                throw new OqError("assertion failed", span,
                        "The value was " + Values.repr(args.get(0)) + " but it must be true.");
            }
            return null;
        }).named("condition"));
        interpreter.defineBuiltin(NativeFunction.of("assert", 2, (host, args, span) -> {
            if (!Values.truthy(args.get(0))) {
                throw new OqError("assertion failed: " + Values.display(args.get(1)), span);
            }
            return null;
        }).named("condition", "message"));
        interpreter.defineBuiltin(NativeFunction.of("args", 0,
                (host, args, span) -> new ArrayList<Object>(host.programArgs())));
        interpreter.defineBuiltin(NativeFunction.of("exit", 0, (host, args, span) -> {
            throw new ExitSignal(0);
        }));
        interpreter.defineBuiltin(NativeFunction.of("exit", 1, (host, args, span) -> {
            throw new ExitSignal((int) Values.asLong(args.get(0)));
        }).named("code"));
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
        }).named("items", "key"));
        interpreter.defineBuiltin(NativeFunction.of("map", 2, (host, args, span) ->
                invokeMethod(host, args.get(0), "map", List.of(args.get(1)), span)).named("items", "function"));
        interpreter.defineBuiltin(NativeFunction.of("filter", 2, (host, args, span) ->
                invokeMethod(host, args.get(0), "filter", List.of(args.get(1)), span)).named("items", "predicate"));
        interpreter.defineBuiltin(NativeFunction.of("clone", 1, (host, args, span) -> deepCopy(args.get(0)))
                .named("value"));
    }

    private static void installText(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("format", 1, (host, args, span) -> Values.display(args.get(0)))
                .named("value"));
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
        }).named("items", "separator"));
        interpreter.defineBuiltin(NativeFunction.of("upper", 1, (host, args, span) ->
                Values.display(args.get(0)).toUpperCase(Locale.ROOT)).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("lower", 1, (host, args, span) ->
                Values.display(args.get(0)).toLowerCase(Locale.ROOT)).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("trim", 1, (host, args, span) ->
                Values.display(args.get(0)).strip()).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("contains", 2, (host, args, span) ->
                Values.contains(args.get(0), args.get(1), span)).named("container", "item"));
        interpreter.defineBuiltin(NativeFunction.of("split", 2, (host, args, span) -> {
            String text = Values.display(args.get(0));
            String separator = Values.display(args.get(1));
            return new ArrayList<Object>(List.of(text.split(Pattern.quote(separator), -1)));
        }).named("text", "separator"));
        interpreter.defineBuiltin(NativeFunction.of("replace", 3, (host, args, span) ->
                Values.display(args.get(0)).replace(Values.display(args.get(1)), Values.display(args.get(2))))
                        .named("text", "old", "new"));
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
        }).named("pattern", "text"));
        interpreter.defineBuiltin(NativeFunction.of("matches", 2, (host, args, span) ->
                compilePattern(args.get(0), span).matcher(Values.display(args.get(1))).find())
                        .named("pattern", "text"));
    }

    private static void installCollections(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("keys", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof Map<?, ?> map)) {
                throw new OqError("keys() needs a map, got " + Values.typeName(args.get(0)), span);
            }
            return new ArrayList<Object>(map.keySet());
        }).named("map"));
        interpreter.defineBuiltin(NativeFunction.of("values", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof Map<?, ?> map)) {
                throw new OqError("values() needs a map, got " + Values.typeName(args.get(0)), span);
            }
            return new ArrayList<Object>(map.values());
        }).named("map"));
        interpreter.defineBuiltin(NativeFunction.of("push", 2, (host, args, span) -> {
            if (!(args.get(0) instanceof List<?> raw)) {
                throw new OqError("push() needs a list, got " + Values.typeName(args.get(0)), span);
            }
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) raw;
            list.add(args.get(1));
            return list;
        }).named("list", "item"));
        interpreter.defineBuiltin(NativeFunction.of("pop", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "pop", List.of(), span)).named("list"));
        // sum/min/max/sorted each need a collection, and every family already reports its own
        // length and iterates, so they read through iterableList rather than a per-type method.
        // `sum` on a set or range previously failed with "set has no method 'sum'".
        interpreter.defineBuiltin(NativeFunction.of("sum", 1, (host, args, span) ->
                sumOf(Values.iterableList(args.get(0), span), span)).named("items"));
        interpreter.defineBuiltin(NativeFunction.of("sort", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "sort", List.of(), span)).named("items"));
        interpreter.defineBuiltin(NativeFunction.of("reverse", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "reverse", List.of(), span)).named("items"));
        interpreter.defineBuiltin(NativeFunction.of("unique", 1, (host, args, span) ->
                invokeMethod(host, args.get(0), "unique", List.of(), span)).named("items"));
        interpreter.defineBuiltin(NativeFunction.variadic("set", 0, 1, (host, args, span) -> {
            if (args.isEmpty()) return new OqSet();
            if (args.get(0) instanceof OqSet set) return set;
            Set<Object> result = new OqSet();
            for (Object item : Values.iterable(args.get(0), span)) result.add(item);
            return result;
        }).named("items"));
        interpreter.defineBuiltin(NativeFunction.variadic("frozenset", 0, 1, (host, args, span) -> {
            if (args.isEmpty()) return new OqFrozenSet();
            if (args.get(0) instanceof OqFrozenSet frozen) return frozen;
            List<?> items = Values.iterableList(args.get(0), span);
            return new OqFrozenSet(items);
        }).named("items"));
        interpreter.defineBuiltin(NativeFunction.of("list", 1, (host, args, span) -> {
            if (args.get(0) instanceof List<?> list) return list;
            List<Object> result = new ArrayList<>();
            for (Object item : Values.iterable(args.get(0), span)) result.add(item);
            return result;
        }).named("items"));
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
        }).named("items"));
        interpreter.defineBuiltin(NativeFunction.of("zip", 2, (host, args, span) -> {
            List<?> a = Values.iterableList(args.get(0), span);
            List<?> b = Values.iterableList(args.get(1), span);
            List<Object> result = new ArrayList<>();
            int n = Math.min(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                result.add(new ArrayList<Object>(List.of(a.get(i), b.get(i))));
            }
            return result;
        }).named("first", "second"));
        interpreter.defineBuiltin(NativeFunction.of("range_list", 2, (host, args, span) ->
                new OqRange(Values.asLong(args.get(0)), Values.asLong(args.get(1)), false).toList())
                        .named("start", "stop"));
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
        }).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("floor", 1, (host, args, span) ->
                whole(Math.floor(asNumber(args.get(0), "floor", span)), "floor", span)).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("ceil", 1, (host, args, span) ->
                whole(Math.ceil(asNumber(args.get(0), "ceil", span)), "ceil", span)).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("round", 1, (host, args, span) -> {
            double value = asNumber(args.get(0), "round", span);
            requireWhole(value, "round", span);
            // Beyond the long range the double is already integral (spacing > 1), so the
            // value itself is the rounded result. Math.round would saturate at Long.MAX_VALUE.
            return Values.fitsInLong(value) ? (Object) Math.round(value) : (Object) value;
        }).named("value"));
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
        }).named("value", "places"));
        interpreter.defineBuiltin(NativeFunction.of("sqrt", 1, (host, args, span) ->
                Math.sqrt(asNumber(args.get(0), "sqrt", span))).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("pow", 2, (host, args, span) ->
                Values.power(args.get(0), args.get(1), span)).named("base", "exponent"));
        // One argument means "the extreme of this collection"; two or more mean "among these
        // values". The arity decides, so `min([3, 1])` is 1 and `min(3, 1)` is 1 as well.
        interpreter.defineBuiltin(NativeFunction.variadic("min", 1, (host, args, span) ->
                extremeOf(args, span, "min", -1)).named("values"));
        interpreter.defineBuiltin(NativeFunction.variadic("max", 1, (host, args, span) ->
                extremeOf(args, span, "max", 1)).named("values"));
        interpreter.defineBuiltin(NativeFunction.of("sorted", 1, (host, args, span) ->
                sortedCopy(Values.iterableList(args.get(0), span), span, 1)).named("items"));
        interpreter.defineBuiltin(NativeFunction.of("clamp", 3, (host, args, span) -> {
            Object value = args.get(0);
            if (Values.compare(value, args.get(1), span) < 0) return args.get(1);
            if (Values.compare(value, args.get(2), span) > 0) return args.get(2);
            return value;
        }).named("value", "low", "high"));
        interpreter.defineBuiltin(NativeFunction.of("random", 0, (host, args, span) ->
                ThreadLocalRandom.current().nextDouble()));
        interpreter.defineBuiltin(NativeFunction.of("random", 2, (host, args, span) -> {
            long from = Values.asLong(args.get(0));
            long to = Values.asLong(args.get(1));
            if (to <= from) return from;
            return ThreadLocalRandom.current().nextLong(from, to);
        }).named("from", "to"));
        interpreter.defineBuiltin(NativeFunction.of("random_item", 1, (host, args, span) -> {
            List<?> items = Values.iterableList(args.get(0), span);
            if (items.isEmpty()) return null;
            return items.get(ThreadLocalRandom.current().nextInt(items.size()));
        }).named("items"));
    }

    private static void installFiles(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("read", 1, (host, args, span) -> {
            Path path = resolve(host, args.get(0));
            return readBounded(host, path, span, "the file");
        }).named("path"));
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
        }).named("path"));
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
        }).named("path", "text"));
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
        }).named("path", "text"));
        interpreter.defineBuiltin(NativeFunction.of("exists", 1, (host, args, span) ->
                Files.exists(resolve(host, args.get(0)))).named("path"));
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
        }).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("delete", 1, (host, args, span) -> {
            try {
                return Files.deleteIfExists(resolve(host, args.get(0)));
            } catch (IOException e) {
                throw new OqError("cannot delete '" + args.get(0) + "'", span, e.getMessage());
            }
        }).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("make_dir", 1, (host, args, span) -> {
            try {
                Files.createDirectories(resolve(host, args.get(0)));
                return null;
            } catch (IOException e) {
                throw new OqError("cannot create directory '" + args.get(0) + "'", span, e.getMessage());
            }
        }).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("file_size", 1, (host, args, span) -> {
            try {
                return Files.size(resolve(host, args.get(0)));
            } catch (IOException e) {
                throw new OqError("cannot read size of '" + args.get(0) + "'", span, e.getMessage());
            }
        }).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("path_join", 2, (host, args, span) ->
                Path.of(Values.display(args.get(0))).resolve(Values.display(args.get(1))).toString())
                        .named("base", "name"));
        interpreter.defineBuiltin(NativeFunction.of("path_name", 1, (host, args, span) -> {
            Path path = Path.of(Values.display(args.get(0)));
            return path.getFileName() == null ? "" : path.getFileName().toString();
        }).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("path_parent", 1, (host, args, span) -> {
            Path parent = Path.of(Values.display(args.get(0))).getParent();
            return parent == null ? "" : parent.toString();
        }).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("cwd", 0, (host, args, span) ->
                host.workingDirectory().toAbsolutePath().toString()));
    }

    /**
     * The file operations §17.3 asked for that the older module lacked: copying, moving,
     * binary content, traversal, and the two predicates {@code exists} cannot express.
     *
     * <p>Binary content is lowercase hex text rather than a bytes type — see ADR-0036. A copy
     * or move is charged against the transfer budget, so neither is a way to move unlimited
     * bytes for free, and neither overwrites an existing target: silently replacing a file a
     * program meant to keep is the failure mode worth refusing.
     */
    private static void installFileOps(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("is_file", 1, (host, args, span) ->
                Files.isRegularFile(resolve(host, args.get(0)))).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("is_dir", 1, (host, args, span) ->
                Files.isDirectory(resolve(host, args.get(0)))).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("read_bytes", 1, (host, args, span) -> {
            Path path = resolve(host, args.get(0));
            try (java.io.InputStream stream = Files.newInputStream(path)) {
                byte[] bytes = stream.readNBytes(MAX_TEXT_BYTES + 1);
                if (bytes.length > MAX_TEXT_BYTES) {
                    throw new OqError("file is too large to read: " + path, span,
                            "It is more than the " + MAX_TEXT_BYTES + " byte limit.");
                }
                chargeTransfer(host, bytes.length);
                return hexOf(bytes);
            } catch (OqError error) {
                throw error;
            } catch (IOException e) {
                throw new OqError("cannot read file '" + path + "'", span, e.getMessage());
            }
        }).named("path"));
        interpreter.defineBuiltin(NativeFunction.of("write_bytes", 2, (host, args, span) -> {
            Path path = resolve(host, args.get(0));
            byte[] bytes = bytesOfHex(Values.display(args.get(1)), span);
            chargeTransfer(host, bytes.length);
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.write(path, bytes);
                return null;
            } catch (IOException e) {
                throw new OqError("cannot write file '" + path + "'", span, e.getMessage());
            }
        }).named("path", "hex"));
        interpreter.defineBuiltin(NativeFunction.of("copy_file", 2, (host, args, span) -> {
            Path source = resolve(host, args.get(0));
            Path target = resolve(host, args.get(1));
            return copyCharged(host, source, target, span);
        }).named("source", "target"));
        interpreter.defineBuiltin(NativeFunction.of("move_file", 2, (host, args, span) -> {
            Path source = resolve(host, args.get(0));
            Path target = resolve(host, args.get(1));
            // Copy, then delete, rather than Files.move: a cross-filesystem move that fails
            // halfway leaves a partial target, and Files.move does not promise otherwise.
            // Deleting only after the copy succeeded keeps the failure recoverable.
            copyCharged(host, source, target, span);
            try {
                Files.delete(source);
                return null;
            } catch (IOException e) {
                throw new OqError("copied '" + target + "' but could not remove '" + source + "'", span,
                        "Both files now exist. (" + e.getMessage() + ")");
            }
        }).named("source", "target"));
        interpreter.defineBuiltin(NativeFunction.of("walk", 1, (host, args, span) -> {
            Path root = resolve(host, args.get(0));
            if (!Files.isDirectory(root)) {
                throw new OqError("not a directory: " + root, span,
                        "walk() lists the files under a directory. Use list_files for one level.");
            }
            List<Object> found = new ArrayList<>();
            // Files.walk without FOLLOW_LINKS: following a link can revisit a directory already
            // walked, and the entry cap alone would not stop a short cycle from repeating until
            // it was hit for the wrong reason.
            try (var stream = Files.walk(root)) {
                Iterator<Path> entries = stream.sorted().iterator();
                while (entries.hasNext()) {
                    Path entry = entries.next();
                    if (entry.equals(root)) continue;
                    if (found.size() > MAX_LIST_ENTRIES) {
                        throw new OqError("directory tree has too many entries: " + root, span,
                                "It has more than " + MAX_LIST_ENTRIES + " entries.");
                    }
                    found.add(root.relativize(entry).toString().replace(java.io.File.separatorChar, '/'));
                }
            } catch (OqError error) {
                throw error;
            } catch (IOException e) {
                throw new OqError("cannot walk '" + root + "'", span, e.getMessage());
            }
            chargeTransfer(host, found.size());
            return found;
        }).named("path"));
    }

    /** Copies one file, refusing an existing target and billing the bytes moved. */
    private static Object copyCharged(OqHost host, Path source, Path target, Span span) {
        try (java.io.InputStream in = Files.newInputStream(source)) {
            if (Files.exists(target)) {
                throw new OqError("target already exists: " + target, span,
                        "copy_file and move_file never overwrite. Delete it first, or pick another name.");
            }
            long size = Files.size(source);
            if (size > MAX_TEXT_BYTES) {
                throw new OqError("file is too large to copy: " + source, span,
                        "It is more than the " + MAX_TEXT_BYTES + " byte limit.");
            }
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            java.nio.file.Files.copy(in, target);
            chargeTransfer(host, size);
            return null;
        } catch (OqError error) {
            throw error;
        } catch (IOException e) {
            throw new OqError("cannot copy '" + source + "' to '" + target + "'", span, e.getMessage());
        }
    }

    private static String hexOf(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] bytesOfHex(String hex, Span span) {
        if (hex.length() % 2 != 0) {
            throw new OqError("hex text must have an even number of digits", span,
                    "write_bytes() takes lowercase hex, the same shape read_bytes() returns.");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new OqError("not hex: '" + hex + "'", span,
                        "write_bytes() takes lowercase hex, the same shape read_bytes() returns.");
            }
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return bytes;
    }

    private static void installJson(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("json_parse", 1, (host, args, span) ->
                Json.parse(Values.display(args.get(0)), span)).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("json", 1, (host, args, span) -> Json.write(args.get(0)))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("json_write", 1, (host, args, span) -> Json.write(args.get(0)))
                .named("value"));
        interpreter.defineBuiltin(NativeFunction.of("json_pretty", 1, (host, args, span) ->
                Json.writePretty(args.get(0))).named("value"));
    }

    /**
     * Encoding and hashing, the two §17 domains that were absent.
     *
     * <p>There is no bytes type, so binary data is spelled as text: lowercase hex or standard
     * base64. Both are ASCII, both round-trip, and both survive a file, a JSON document and a
     * terminal. Every primitive is a JVM provider -- nothing here implements a hash or a
     * generator.
     */
    private static void installEncoding(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("hex_encode", 1, (host, args, span) ->
                HexFormat.of().formatHex(utf8(args.get(0), "hex_encode", span))).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("hex_decode", 1, (host, args, span) ->
                decodeHex(text(args.get(0), "hex_decode", span), span)).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("base64_encode", 1, (host, args, span) ->
                Base64.getEncoder().encodeToString(utf8(args.get(0), "base64_encode", span))).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("base64_decode", 1, (host, args, span) ->
                decodeBase64(text(args.get(0), "base64_decode", span), span)).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("sha256", 1, (host, args, span) ->
                digest("SHA-256", args.get(0), "sha256", span)).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("sha1", 1, (host, args, span) ->
                digest("SHA-1", args.get(0), "sha1", span)).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("md5", 1, (host, args, span) ->
                digest("MD5", args.get(0), "md5", span)).named("value"));
        interpreter.defineBuiltin(NativeFunction.of("hmac_sha256", 2, (host, args, span) ->
                hmac(args.get(0), args.get(1), span)).named("key", "message"));
        interpreter.defineBuiltin(NativeFunction.of("secure_random_hex", 1, (host, args, span) -> {
            long count = wholeNumber(args.get(0), "secure_random_hex", span);
            if (count < 0 || count > 4096) {
                throw new OqError("secure_random_hex() needs a byte count from 0 to 4096, got "
                        + count, span);
            }
            byte[] bytes = new byte[(int) count];
            RANDOM.nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        }).named("bytes"));
        interpreter.defineBuiltin(NativeFunction.of("secure_equal", 2, (host, args, span) -> {
            // MessageDigest.isEqual is the JVM's constant-time comparison. Writing the loop here
            // was rejected: a hand-written xor accumulator is only correct if the compiler does
            // not reintroduce a branch (ADR-0038, Decision 1).
            byte[] left = utf8(args.get(0), "secure_equal", span);
            byte[] right = utf8(args.get(1), "secure_equal", span);
            return MessageDigest.isEqual(left, right);
        }).named("left", "right"));
        interpreter.defineBuiltin(NativeFunction.of("pbkdf2_sha256", 3, (host, args, span) ->
                pbkdf2(args.get(0), args.get(1), args.get(2), span)).named("password", "salt", "iterations"));
    }

    /** The largest iteration count {@code pbkdf2_sha256} accepts, so a call cannot look hung. */
    static final long MAX_PBKDF2_ITERATIONS = 10_000_000L;

    /**
     * A salted password hash, deliberately separate from {@code sha256} (ADR-0038, Decision 4).
     *
     * <p>The salt is a parameter because a generated one would have to be returned alongside the
     * iteration count for the stored value to be verifiable, and this library has no record type
     * for that. Generating it is the caller's job with {@code secure_random_hex}.
     */
    private static Object pbkdf2(Object passwordValue, Object saltValue, Object iterationsValue,
            Span span) {
        String password = text(passwordValue, "pbkdf2_sha256", span);
        String salt = text(saltValue, "pbkdf2_sha256", span);
        long iterations = wholeNumber(iterationsValue, "pbkdf2_sha256", span);
        if (iterations < 1 || iterations > MAX_PBKDF2_ITERATIONS) {
            throw new OqError("pbkdf2_sha256() needs an iteration count from 1 to "
                    + MAX_PBKDF2_ITERATIONS + ", got " + iterations, span,
                    "A count near zero is not a password hash and a count above the maximum "
                            + "runs long enough to look like a hang. 600000 is a common choice.");
        }
        try {
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    password.toCharArray(), utf8(salt, "pbkdf2_sha256", span), (int) iterations,
                    256);
            javax.crypto.SecretKeyFactory factory =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return HexFormat.of().formatHex(factory.generateSecret(spec).getEncoded());
        } catch (java.security.GeneralSecurityException e) {
            throw new OqError("cannot derive the password hash", span, safeMessage(e));
        }
    }

    /**
     * The threshold above which a log call writes. Read once from {@code OQ_LOG_LEVEL} so a
     * deployment setting cannot change halfway through a run, then resettable by the program.
     */
    private static volatile Level level;

    /** Log levels, ordered so a threshold is a comparison rather than a set membership test. */
    private enum Level {
        DEBUG(10, "DEBUG"), INFO(20, "INFO "), WARN(30, "WARN "), ERROR(40, "ERROR"),
        NONE(100, "NONE ");

        final int rank;
        final String label;

        Level(int rank, String label) {
            this.rank = rank;
            this.label = label;
        }

        static Level named(String name) {
            String normalized = name.trim().toUpperCase(Locale.ROOT);
            if (normalized.equals("WARNING")) normalized = "WARN";
            for (Level candidate : values()) {
                if (candidate.name().equals(normalized)) return candidate;
            }
            return null;
        }
    }

    private static Level level() {
        Level current = level;
        if (current != null) return current;
        synchronized (Stdlib.class) {
            if (level == null) {
                // An unrecognised value is ignored rather than fatal: a typo in a deployment
                // variable must not turn a working program into a failing one.
                Level fromEnvironment = Level.named(System.getenv().getOrDefault("OQ_LOG_LEVEL", "info"));
                level = fromEnvironment == null ? Level.INFO : fromEnvironment;
            }
            return level;
        }
    }

    private static void installLogging(Interpreter interpreter) {
        // Registered by literal name rather than in a loop over Level: the documentation
        // generator finds builtins by matching the literal in this file, so a computed name
        // would make the four functions exist and be undocumented at the same time.
        interpreter.defineBuiltin(NativeFunction.of("log_debug", 1, (host, args, span) ->
                writeLog(host, Level.DEBUG, args.get(0))).named("message"));
        interpreter.defineBuiltin(NativeFunction.of("log_info", 1, (host, args, span) ->
                writeLog(host, Level.INFO, args.get(0))).named("message"));
        interpreter.defineBuiltin(NativeFunction.of("log_warn", 1, (host, args, span) ->
                writeLog(host, Level.WARN, args.get(0))).named("message"));
        interpreter.defineBuiltin(NativeFunction.of("log_error", 1, (host, args, span) ->
                writeLog(host, Level.ERROR, args.get(0))).named("message"));
        interpreter.defineBuiltin(NativeFunction.of("log_level", 1, (host, args, span) -> {
            String requested = text(args.get(0), "log_level", span);
            Level chosen = Level.named(requested);
            if (chosen == null) {
                throw new OqError("log_level() got \"" + requested + "\"", span,
                        "Use debug, info, warn, error or none.");
            }
            level = chosen;
            return null;
        }).named("level"));
        interpreter.defineBuiltin(NativeFunction.of("log_enabled", 1, (host, args, span) -> {
            String requested = text(args.get(0), "log_enabled", span);
            Level chosen = Level.named(requested);
            if (chosen == null) {
                throw new OqError("log_enabled() got \"" + requested + "\"", span,
                        "Use debug, info, warn, error or none.");
            }
            return chosen.rank >= level().rank;
        }).named("level"));
    }

    /**
     * Writes one line to the error stream, never the output stream.
     *
     * The stream split is the whole point: a program's output is its data, a log line is a
     * diagnostic about the program, and `oq run p.oq > out` must not capture the second into
     * the first. The message is converted the way `print` converts, so a string is not quoted
     * and `log_info("done")` writes `INFO  done`.
     */
    private static Object writeLog(OqHost host, Level at, Object message) {
        if (at.rank < level().rank) return null;
        host.err().println(at.label + " " + Values.display(message));
        return null;
    }

    private static void installArguments(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.variadic("arg_parse", 2, (host, args, span) -> {
            List<String> arguments = new ArrayList<>();
            for (Object entry : argumentList(args.get(0), "arg_parse", span)) {
                arguments.add(text(entry, "arg_parse", span));
            }
            List<Option> options = new ArrayList<>();
            for (Object entry : argumentList(args.get(1), "arg_parse", span)) {
                options.add(Option.parse(text(entry, "arg_parse", span), span));
            }
            return parseArguments(arguments, options, span);
        }).named("arguments", "options"));
    }

    /** One declared option: a flag, or a value of a named type that may be optional. */
    private record Option(String name, String type, boolean flag, boolean optional) {

        static Option parse(String specification, Span span) {
            String trimmed = specification.trim();
            if (trimmed.isEmpty()) {
                throw new OqError("arg_parse() was given an empty option", span,
                        "An option is \"name\" for a flag or \"name:type\" for a value.");
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) return new Option(trimmed, "flag", true, false);
            String name = trimmed.substring(0, colon).trim();
            String type = trimmed.substring(colon + 1).trim();
            boolean optional = type.endsWith("?");
            if (optional) type = type.substring(0, type.length() - 1);
            if (name.isEmpty()) {
                throw new OqError("arg_parse() was given \"" + specification + "\" with no name",
                        span, "Write the name before the colon: \"port:int\".");
            }
            if (!type.equals("text") && !type.equals("number") && !type.equals("int")) {
                throw new OqError("arg_parse() got the unknown option type \"" + type + "\"", span,
                        "The types are text, number and int.");
            }
            return new Option(name, type, false, optional);
        }

        /** The spelling a caller uses, with the leading dashes the parser accepts. */
        String spelling() {
            return "--" + name;
        }
    }

    /**
     * Walks the words once. Every failure names the token, because the caller's next action
     * depends on which word was wrong rather than on the fact that something was.
     */
    private static OqMap parseArguments(List<String> words, List<Option> options, Span span) {
        Map<String, Option> bySpelling = new LinkedHashMap<>();
        for (Option option : options) {
            if (bySpelling.put(option.spelling(), option) != null) {
                throw new OqError("arg_parse() declared " + option.spelling() + " twice", span,
                        "Each option name appears once.");
            }
        }
        List<Object> flags = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        List<Object> rest = new ArrayList<>();
        boolean positionalOnly = false;
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            // `--` terminates option parsing. The CLI already hands `--` through to a program,
            // so a parser that ignored it would make the process argument list incoherent.
            if (!positionalOnly && word.equals("--")) {
                positionalOnly = true;
                continue;
            }
            if (positionalOnly || !word.startsWith("-") || word.equals("-")) {
                rest.add(word);
                continue;
            }
            Option option = bySpelling.get(word);
            if (option == null) {
                throw new OqError("arg_parse() got the unknown option " + word, span,
                        "Declared options: " + describe(options) + ". Use -- to end option parsing.");
            }
            if (option.flag) {
                if (!flags.contains(option.name)) flags.add(option.name);
                continue;
            }
            if (i + 1 >= words.size()) {
                throw new OqError("arg_parse() got " + word + " with no value", span,
                        "Write " + word + " followed by a " + option.type + ".");
            }
            String raw = words.get(++i);
            values.put(option.name, convert(raw, option, word, span));
        }
        for (Option option : options) {
            if (option.flag || option.optional) continue;
            if (!values.containsKey(option.name)) {
                throw new OqError("arg_parse() is missing the required option " + option.spelling(),
                        span, "Declared options: " + describe(options) + ".");
            }
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("flags", flags);
        result.put("values", new OqMap(values));
        result.put("rest", rest);
        return new OqMap(result);
    }

    private static Object convert(String raw, Option option, String spelling, Span span) {
        switch (option.type) {
            case "text":
                return raw;
            case "int":
                try {
                    return Long.parseLong(raw);
                } catch (NumberFormatException e) {
                    throw optionTypeError(raw, option, spelling, "a whole number", span);
                }
            default:
                try {
                    double parsed = Double.parseDouble(raw);
                    if (!Double.isFinite(parsed)) {
                        throw optionTypeError(raw, option, spelling, "a finite number", span);
                    }
                    return parsed;
                } catch (NumberFormatException e) {
                    throw optionTypeError(raw, option, spelling, "a number", span);
                }
        }
    }

    private static OqError optionTypeError(String raw, Option option, String spelling,
            String expected, Span span) {
        return new OqError(spelling + " needs " + expected + ", got \"" + raw + "\"", span);
    }

    private static String describe(List<Option> options) {
        if (options.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (Option option : options) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(option.spelling());
            if (!option.flag) sb.append(':').append(option.type);
        }
        return sb.toString();
    }

    /**
     * CSV per RFC 4180.
     *
     * <p>Quoting is the whole of CSV and it is what a splitting implementation gets wrong:
     * {@code a,"b,c"} has two fields, and so does {@code a,",b"}. The parser below walks the
     * text once and never splits.
     */
    private static void installCsv(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("csv_parse", 1, (host, args, span) ->
                csvParse(text(args.get(0), "csv_parse", span), ',', false, span)).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("csv_parse", 2, (host, args, span) ->
                csvParse(text(args.get(0), "csv_parse", span),
                        csvDelimiter(args.get(1), span), false, span)).named("text", "delimiter"));
        interpreter.defineBuiltin(NativeFunction.of("csv_parse", 3, (host, args, span) ->
                csvParse(text(args.get(0), "csv_parse", span),
                        csvDelimiter(args.get(1), span), Values.truthy(args.get(2)), span))
                                .named("text", "delimiter", "comments"));
        interpreter.defineBuiltin(NativeFunction.of("csv_write", 1, (host, args, span) ->
                csvWrite(args.get(0), ',', span)).named("rows"));
        interpreter.defineBuiltin(NativeFunction.of("csv_write", 2, (host, args, span) ->
                csvWrite(args.get(0), csvDelimiter(args.get(1), span), span)).named("rows", "delimiter"));
    }

    /**
     * The delimiter is one character.
     *
     * <p>A multi-character delimiter does not exist in any CSV dialect, and accepting one turns
     * {@code csv_parse} into a general splitter that callers will reach for when CSV is not what
     * they have.
     */
    private static char csvDelimiter(Object value, Span span) {
        String delimiter = text(value, "csv_parse", span);
        if (delimiter.length() != 1) {
            throw new OqError("a delimiter must be exactly one character, got \""
                    + delimiter + "\"", span,
                    "Use , or ; or \t. A longer delimiter is not a CSV dialect.");
        }
        if (delimiter.charAt(0) == '"' || delimiter.charAt(0) == '\n'
                || delimiter.charAt(0) == '\r') {
            throw new OqError("a delimiter cannot be a quote or a line break", span);
        }
        return delimiter.charAt(0);
    }

    /**
     * One pass, one quote state.
     *
     * <p>Malformed input raises rather than being repaired: a repaired table has the wrong shape
     * and no one is told, which is worse than a program that stops.
     */
    private static Object csvParse(String source, char delimiter, boolean comment, Span span) {
        List<Object> rows = new ArrayList<>();
        List<Object> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        // Whether the row's FIRST field was quoted. A quoted "#id" is data, not a comment, and
        // the per-field flag is cleared by the delimiter long before the row ends.
        boolean firstFieldQuoted = false;
        int line = 1;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        // Text after a closing quote would be silently glued onto the field by a
                        // lenient parser, so it is refused instead.
                        if (i + 1 < source.length() && source.charAt(i + 1) != delimiter
                                && source.charAt(i + 1) != '\n' && source.charAt(i + 1) != '\r') {
                            throw new OqError("csv_parse() found text after a closing quote on line "
                                    + line, span,
                                    "A quoted field must be followed by the delimiter or the end "
                                            + "of the line.");
                        }
                    }
                } else {
                    if (c == '\n') line++;
                    field.append(c);
                }
                continue;
            }
            if (c == delimiter) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                if (!(comment && !firstFieldQuoted && !row.isEmpty()
                        && String.valueOf(row.get(0)).startsWith("#"))) {
                    rows.add(row);
                }
                row = new ArrayList<>();
                firstFieldQuoted = false;
                line++;
            } else if (c == '"') {
                if (field.length() > 0) {
                    throw new OqError("csv_parse() found a quote inside an unquoted field on line "
                            + line, span,
                            "Quote the whole field, or double the quote if one is meant literally.");
                }
                quoted = true;
                if (row.isEmpty()) firstFieldQuoted = true;
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new OqError("csv_parse() reached the end of the text inside a quoted field "
                    + "that began on line " + line, span, "A quoted field needs a closing quote.");
        }
        // A trailing newline ends the last row; anything else is still a row.
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            if (!(comment && !firstFieldQuoted && !row.isEmpty()
                    && String.valueOf(row.get(0)).startsWith("#"))) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** Quote exactly the fields that need it, and escape a quote by doubling it. */
    private static String csvWrite(Object value, char delimiter, Span span) {
        if (!(value instanceof List<?> rows)) {
            throw new OqError("csv_write() needs a list of rows, got " + Values.typeName(value),
                    span);
        }
        StringBuilder sb = new StringBuilder();
        for (Object rowValue : rows) {
            if (!(rowValue instanceof List<?> row)) {
                throw new OqError("csv_write() needs each row to be a list, got "
                        + Values.typeName(rowValue), span);
            }
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append(delimiter);
                sb.append(csvField(Values.display(row.get(i)), delimiter));
            }
            sb.append('\n');
        }
        String result = sb.toString();
        if (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new OqError("csv_write() produced more than " + MAX_TEXT_BYTES + " bytes", span);
        }
        return result;
    }

    private static String csvField(String value, char delimiter) {
        boolean needsQuotes = value.indexOf(delimiter) >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * Dates in the shapes the time functions already produce.
     *
     * <p>{@code today()} returns {@code YYYY-MM-DD} and {@code now()} returns an ISO-8601
     * instant ending in {@code Z}, so parsing returns exactly those shapes and a parsed value is
     * interchangeable with a generated one.
     */
    private static void installDates(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("date_parse", 1, (host, args, span) ->
                dateParse(text(args.get(0), "date_parse", span), span)).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("instant_parse", 1, (host, args, span) ->
                instantParse(text(args.get(0), "instant_parse", span), span)).named("text"));
    }

    private static String dateParse(String source, Span span) {
        try {
            return LocalDate.parse(source, DateTimeFormatter.ISO_LOCAL_DATE).toString();
        } catch (DateTimeParseException e) {
            throw new OqError("date_parse() could not read \"" + source + "\"", span,
                    "Use YYYY-MM-DD. A date with no zone is not given one, because that would "
                            + "make the result depend on the machine.");
        }
    }

    private static String instantParse(String source, Span span) {
        try {
            return java.time.Instant.parse(source).toString();
        } catch (DateTimeParseException e) {
            throw new OqError("instant_parse() could not read \"" + source + "\"", span,
                    "Use an ISO-8601 instant with an explicit offset, such as "
                            + "2026-11-10T13:58:06Z.");
        }
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
        interpreter.defineBuiltin(NativeFunction.of("tz_convert", 2, (host, args, span) -> {
            String instantText = Values.display(args.get(0));
            java.time.ZoneId zone;
            try {
                zone = java.time.ZoneId.of(Values.display(args.get(1)));
            } catch (java.time.DateTimeException e) {
                throw new OqError("unknown time zone: '" + Values.display(args.get(1)) + "'", span,
                        "Use an IANA name such as 'Asia/Tokyo' or 'Europe/Lisbon', not an "
                                + "abbreviation like 'JST'.");
            }
            java.time.Instant instant;
            try {
                instant = java.time.Instant.parse(instantText);
            } catch (java.time.format.DateTimeParseException e) {
                throw new OqError("not an instant: '" + instantText + "'", span,
                        "tz_convert() takes an instant like '2026-11-10T14:04:24Z' or "
                                + "'2026-11-10T14:04:24+02:00'. Parse a bare date first.");
            }
            // An offset, not a zone name: the output has to be unambiguous on its own, and a
            // reader that has to look up a zone to read a timestamp is not receiving a timestamp.
            return instant.atZone(zone).toOffsetDateTime().toString();
        }).named("instant", "zone"));
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
        }).named("seconds"));
    }

    /**
     * URL parsing and encoding (ADR-0037).
     *
     * <p>Parsing is {@link java.net.URI}, the same class the HTTP client uses, so a URL that
     * {@code http_get} would reject is rejected here too rather than parsed into something that
     * cannot be requested.
     */
    private static void installUrls(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("url_parse", 1, (host, args, span) -> {
            String text = Values.display(args.get(0));
            URI uri;
            try {
                uri = URI.create(text);
            } catch (IllegalArgumentException e) {
                throw new OqError("invalid URL '" + text + "'", span,
                        e.getMessage() + ". A URL must escape spaces and other reserved "
                                + "characters; url_encode() escapes a value, url_join() escapes "
                                + "a reference.");
            }
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            fields.put("scheme", uri.getScheme());
            fields.put("host", uri.getHost());
            // Never a scheme default: 443 would make https://h and https://h:443 indistinguishable,
            // and they are different strings (ADR-0037, Decision 1).
            fields.put("port", uri.getPort() < 0 ? null : (long) uri.getPort());
            // An opaque URI such as mailto: has no path. Reporting "" would claim it has an empty
            // one, which is a different statement.
            fields.put("path", uri.isOpaque() ? null : uri.getPath());
            fields.put("query", uri.getRawQuery());
            fields.put("fragment", uri.getRawFragment());
            return new OqData("Url", fields);
        }).named("url"));
        interpreter.defineBuiltin(NativeFunction.of("url_encode", 1, (host, args, span) -> {
            String text = Values.display(args.get(0));
            return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
        }).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("url_decode", 1, (host, args, span) -> {
            String text = Values.display(args.get(0));
            try {
                return java.net.URLDecoder.decode(text, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new OqError("invalid percent-encoding in '" + text + "'", span,
                        e.getMessage() + ". A literal % must be written %25.");
            }
        }).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("query_parse", 1, (host, args, span) -> {
            String text = Values.display(args.get(0));
            // A leading ? is what a caller most often has in hand, from url_parse or from a line
            // they pasted. Refusing it would be a puzzle with no purpose, so it is stripped.
            String body = text.startsWith("?") ? text.substring(1) : text;
            List<Object> pairs = new ArrayList<>();
            if (body.isEmpty()) return pairs;
            for (String part : body.split("&", -1)) {
                if (part.isEmpty()) continue;
                int equals = part.indexOf('=');
                String name = equals < 0 ? part : part.substring(0, equals);
                String value = equals < 0 ? "" : part.substring(equals + 1);
                LinkedHashMap<String, Object> pair = new LinkedHashMap<>();
                pair.put("name", decodeQueryComponent(name, text, span));
                pair.put("value", decodeQueryComponent(value, text, span));
                pairs.add(new OqData("QueryPair", pair));
            }
            return pairs;
        }).named("query"));
        interpreter.defineBuiltin(NativeFunction.of("query_encode", 1, (host, args, span) -> {
            List<?> pairs = Values.iterableList(args.get(0), span);
            StringBuilder out = new StringBuilder();
            for (Object entry : pairs) {
                String name;
                String value;
                if (entry instanceof OqData pair) {
                    if (!pair.has("name") || !pair.has("value")) {
                        throw new OqError(
                                "query_encode() needs every pair to have a name and a value", span,
                                "query_parse() returns that shape. A pair can also be written as "
                                        + "a map: {\"name\": \"q\", \"value\": \"x\"}.");
                    }
                    name = Values.display(pair.get("name"));
                    value = Values.display(pair.get("value"));
                } else if (entry instanceof Map<?, ?> map) {
                    // A map literal is the only pair a caller can write by hand; OqData cannot be
                    // constructed from source. Accepting only the parsed shape would make the
                    // inverse of query_parse unusable, which is the defect this branch removes.
                    if (!map.containsKey("name") || !map.containsKey("value")) {
                        throw new OqError(
                                "query_encode() needs every pair to have a name and a value", span,
                                "Write one as {\"name\": \"q\", \"value\": \"x\"}, or build the "
                                        + "list with query_parse().");
                    }
                    name = Values.display(map.get("name"));
                    value = Values.display(map.get("value"));
                } else {
                    throw new OqError("query_encode() needs a list of {name, value} pairs, found "
                            + Values.typeName(entry), span,
                            "query_parse() returns exactly that shape, so a parsed query can be "
                                    + "encoded again unchanged.");
                }
                if (!out.isEmpty()) out.append('&');
                out.append(java.net.URLEncoder.encode(name, StandardCharsets.UTF_8));
                out.append('=');
                out.append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
            return out.toString();
        }).named("pairs"));
        interpreter.defineBuiltin(NativeFunction.of("url_join", 2, (host, args, span) ->
                urlJoin(Values.display(args.get(0)), Values.display(args.get(1)), span)).named("base", "reference"));
    }

    /** Decodes one query component, naming the whole query when the component is malformed. */
    private static String decodeQueryComponent(String component, String whole, Span span) {
        try {
            return java.net.URLDecoder.decode(component, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new OqError("invalid percent-encoding in query '" + whole + "'", span,
                    e.getMessage() + ". A literal % must be written %25.");
        }
    }

    /**
     * Resolves {@code reference} against {@code base}, escaping it per segment.
     *
     * <p>{@code URI.resolve} does the joining, but it refuses a reference containing a space, and
     * it would also treat a leading {@code /} as replacing the base path. Both behaviours are
     * correct HTTP; the first is inconvenient and the second is surprising. The reference is
     * therefore escaped first, segment by segment, so {@code url_join(base, "a b/c")} means
     * {@code a%20b/c} and not a single path component with an escaped slash.
     */
    private static String urlJoin(String base, String reference, Span span) {
        URI baseUri;
        try {
            baseUri = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new OqError("invalid base URL '" + base + "'", span, e.getMessage());
        }
        // Absolute references replace the base entirely; escaping a full URL would destroy its
        // own scheme separator.
        if (reference.contains("://")) {
            try {
                return URI.create(reference).toString();
            } catch (IllegalArgumentException e) {
                throw new OqError("invalid reference URL '" + reference + "'", span, e.getMessage());
            }
        }
        StringBuilder escaped = new StringBuilder();
        String[] segments = reference.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) escaped.append('/');
            String segment = segments[i];
            // "." and ".." are path syntax, not characters to encode, so they stay literal and
            // URI.resolve still gets to apply them.
            if (segment.equals(".") || segment.equals("..")) {
                escaped.append(segment);
            } else {
                escaped.append(encodePathSegment(segment));
            }
        }
        try {
            return baseUri.resolve(escaped.toString()).toString();
        } catch (IllegalArgumentException e) {
            throw new OqError("cannot join '" + reference + "' to '" + base + "'", span,
                    e.getMessage());
        }
    }

    /**
     * Percent-encodes one path segment as UTF-8.
     *
     * <p>{@code URLEncoder} cannot be used: it escapes {@code /} to {@code %2F}, which would turn
     * one segment into an encoded separator, and it maps a space to {@code +}, which is a literal
     * plus in a path. Only unreserved characters survive, matching RFC 3986 §2.3.
     */
    private static String encodePathSegment(String segment) {
        StringBuilder out = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int value = b & 0xFF;
            boolean unreserved = (value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '-' || value == '_' || value == '.' || value == '~';
            if (unreserved) {
                out.append((char) value);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((value >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(value & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static void installHttp(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("http_get", 1, (host, args, span) ->
                http(host, "GET", Values.display(args.get(0)), null, span)).named("url"));
        interpreter.defineBuiltin(NativeFunction.of("http_post", 2, (host, args, span) ->
                http(host, "POST", Values.display(args.get(0)), Values.display(args.get(1)), span))
                        .named("url", "body"));
    }

    private static Object http(OqHost host, String method, String url, String body, Span span) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(HTTP_CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_HEADER_TIMEOUT)
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
            throw new OqError(describeNetworkFailure(url, e), span,
                    "Check the URL and your network connection.");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new OqError(describeNetworkFailure(url, e), span,
                    "Check the URL and your network connection.");
        }
    }

    /**
     * Names a transport failure in words instead of appending its message.
     *
     * <p>Every exception in these chains carries a null message, which is why the error used to
     * read {@code HTTP request failed: null}. Measured, the chains are two levels deep and the
     * informative part is not always at the leaf:
     *
     * <pre>
     * http://127.0.0.1:1/           ConnectException -> ConnectException -> ClosedChannelException
     * http://no-such-host.invalid/  ConnectException -> ConnectException -> UnresolvedAddressException
     * </pre>
     *
     * <p>Both wrap a {@code ConnectException}, so the outer type alone cannot tell a refused
     * connection from a name that did not resolve, and the leaf alone cannot tell either because
     * {@code ClosedChannelException} says nothing by itself. The whole chain is therefore scanned
     * for the most specific recognised type, and the classification is by type rather than by
     * message, because the message is exactly what is missing (ADR-0037, Decision 8).
     */
    private static String describeNetworkFailure(String url, Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = error;
                cause != null && chain.size() < 8 && !chain.contains(cause);
                cause = cause.getCause()) {
            chain.add(cause);
        }
        // Most specific first: these are subclasses of the types checked after them.
        for (String detail : new String[] {
                nameResolutionDetail(chain),
                routeDetail(chain),
                timeoutDetail(chain),
                tlsDetail(chain),
                refusalDetail(chain)}) {
            if (detail != null) {
                return "HTTP request failed for '" + url + "': " + detail;
            }
        }
        Throwable leaf = chain.get(chain.size() - 1);
        return "HTTP request failed for '" + url + "': the request failed: " + safeMessage(leaf);
    }

    /** A name that did not resolve, which is distinct from a host that resolved and refused. */
    private static String nameResolutionDetail(List<Throwable> chain) {
        for (Throwable cause : chain) {
            if (cause instanceof java.nio.channels.UnresolvedAddressException) {
                return "cannot resolve the host: the name did not resolve to an address";
            }
            if (cause instanceof java.net.UnknownHostException) {
                return "cannot resolve the host: " + safeMessage(cause);
            }
        }
        return null;
    }

    private static String routeDetail(List<Throwable> chain) {
        for (Throwable cause : chain) {
            if (cause instanceof java.net.NoRouteToHostException) {
                return "cannot reach the host: no route to it";
            }
        }
        return null;
    }

    private static String timeoutDetail(List<Throwable> chain) {
        for (Throwable cause : chain) {
            if (cause instanceof java.net.http.HttpTimeoutException) {
                return "no response headers within " + HTTP_HEADER_TIMEOUT.toSeconds()
                        + " seconds";
            }
            if (cause instanceof java.net.SocketTimeoutException) {
                return "the connection timed out after " + HTTP_CONNECT_TIMEOUT.toSeconds()
                        + " seconds";
            }
        }
        return null;
    }

    /** A rejected certificate is not a network problem, so it gets its own wording and no hint. */
    private static String tlsDetail(List<Throwable> chain) {
        for (Throwable cause : chain) {
            if (cause instanceof javax.net.ssl.SSLException) {
                return "the TLS handshake failed: " + safeMessage(cause);
            }
        }
        return null;
    }

    private static String refusalDetail(List<Throwable> chain) {
        for (Throwable cause : chain) {
            if (cause instanceof java.net.ConnectException) {
                return "cannot reach the host: connection refused";
            }
        }
        return null;
    }

    /** A cause's message, or its class name when the message is absent or blank. */
    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static void installConcurrency(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("spawn", 1, (host, args, span) -> {
            Object callback = args.get(0);
            java.util.concurrent.CompletableFuture<Object> future =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> host.call(callback, List.of(), span),
                            TaskRunner.EXECUTOR);
            return new dev.oqthara.runtime.OqTask(future);
        }).named("function"));
        interpreter.defineBuiltin(NativeFunction.of("await", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof dev.oqthara.runtime.OqTask task)) {
                throw new OqError("await() needs a task from spawn(), got "
                        + Values.typeName(args.get(0)), span);
            }
            return task.await(span);
        }).named("task"));
        interpreter.defineBuiltin(NativeFunction.of("await", 2, (host, args, span) -> {
            if (!(args.get(0) instanceof dev.oqthara.runtime.OqTask task)) {
                throw new OqError("await() needs a task from spawn(), got "
                        + Values.typeName(args.get(0)), span);
            }
            long millis = (long) (Values.asDouble(args.get(1)) * 1000);
            if (millis > MAX_SLEEP_MILLIS) {
                throw new OqError("cannot await for more than "
                        + MAX_SLEEP_MILLIS / 1000 + " seconds", span,
                        "A long wait would block the program; the limit is "
                                + MAX_SLEEP_MILLIS / 1000 + " seconds per call.");
            }
            return task.awaitWithin(millis, span);
        }).named("task", "seconds"));
        interpreter.defineBuiltin(NativeFunction.of("done", 1, (host, args, span) -> {
            if (!(args.get(0) instanceof dev.oqthara.runtime.OqTask task)) {
                throw new OqError("done() needs a task from spawn(), got "
                        + Values.typeName(args.get(0)), span);
            }
            return task.isDone();
        }).named("task"));
        interpreter.defineBuiltin(NativeFunction.of("mutex", 0, (host, args, span) ->
                new dev.oqthara.runtime.OqMutex()));
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
        }).named("items", "function"));
    }

    private static void installProcess(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("env", 1, (host, args, span) ->
                System.getenv(Values.display(args.get(0)))).named("name"));
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
        }).named("command"));
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
    static void chargeTransfer(OqHost host, long bytes) {
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
     * Drains a response body on its own thread and gives up at {@link #HTTP_BODY_TIMEOUT}.
     *
     * <p>{@link java.net.http.HttpRequest#timeout} stops at the headers, so it does not cover a
     * body that trickles or stops arriving. The reader thread is abandoned rather than joined:
     * a blocking socket read cannot be cancelled, and it ends when the connection is closed.
     */
    private static byte[] readCappedWithinDeadline(java.io.InputStream body, Span span) {
        var reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> readCapped(body));
        try {
            return reader.get(HTTP_BODY_TIMEOUT.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            reader.cancel(true);
            throw new OqError("the response body did not finish in "
                    + HTTP_BODY_TIMEOUT.toSeconds() + " seconds", span,
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

    /** A single, deliberately shared generator: {@code SecureRandom} is thread-safe. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The argument of an encoding builtin, which must be text.
     *
     * <p>{@link Values#display} would accept a number and encode its printing, which is a
     * different string from what the caller meant; the functions are about bytes, so the type
     * is checked.
     */
    private static List<?> argumentList(Object value, String builtin, Span span) {
        if (!(value instanceof List<?> list)) {
            throw new OqError(builtin + "() needs a list, got " + Values.typeName(value), span);
        }
        return list;
    }

    private static String text(Object value, String builtin, Span span) {
        if (!(value instanceof String)) {
            throw new OqError(builtin + "() needs text, got " + Values.typeName(value), span);
        }
        return (String) value;
    }

    private static byte[] utf8(Object value, String builtin, Span span) {
        return text(value, builtin, span).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Hex back to text, reporting the offset of the first character that is not a hex digit.
     *
     * <p>{@link HexFormat#parseHex} throws {@code NumberFormatException} and
     * {@code IllegalArgumentException}, and both reached the CLI as "This is a bug in Oqthara"
     * -- a message about the interpreter for what is a mistake in the caller's string. The
     * offset matters because the usual source is a digest pasted from a log.
     */
    private static String decodeHex(String text, Span span) {
        if (text.length() % 2 != 0) {
            throw new OqError("hex_decode() needs an even number of digits, got " + text.length(),
                    span, "Each byte is two digits; the last one is incomplete.");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean digit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!digit) {
                throw new OqError("hex_decode() found '" + c + "' at offset " + i, span,
                        "Expected a lowercase hex digit 0-9 or a-f.");
            }
        }
        return new String(HexFormat.of().parseHex(text), StandardCharsets.UTF_8);
    }

    /**
     * Base64 back to text, reporting the offset of the first character outside the alphabet.
     * The standard decoder also refuses bad padding; that message is kept as the detail.
     */
    private static String decodeBase64(String text, Span span) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean accepted = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!accepted) {
                throw new OqError("base64_decode() found '" + c + "' at offset " + i, span,
                        "Expected the standard base64 alphabet with padding.");
            }
        }
        if (text.length() % 4 != 0) {
            // The platform decoder tolerates missing padding and so would accept "YWJ", which
            // is a truncated value rather than a shorter one. Every encoder writes padding, so
            // requiring it keeps a corrupt input from decoding to something plausible.
            throw new OqError("base64_decode() needs a length divisible by 4, got "
                    + text.length(), span, "Standard base64 pads with '=' to a multiple of four.");
        }
        try {
            return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new OqError("base64_decode() could not decode the value", span, e.getMessage());
        }
    }

    private static String digest(String algorithm, Object value, String builtin, Span span) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return HexFormat.of().formatHex(md.digest(utf8(value, builtin, span)));
        } catch (NoSuchAlgorithmException e) {
            // Every algorithm named here is required by the Java platform, so this is a broken
            // runtime rather than a program mistake.
            throw new OqError(builtin + "() is unavailable in this runtime", span, e.getMessage());
        }
    }

    private static String hmac(Object key, Object message, Span span) {
        byte[] keyBytes = utf8(key, "hmac_sha256", span);
        if (keyBytes.length == 0) {
            throw new OqError("hmac_sha256() needs a non-empty key", span,
                    "An empty key makes the result depend only on the message.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(utf8(message, "hmac_sha256", span)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new OqError("hmac_sha256() is unavailable in this runtime", span, e.getMessage());
        }
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
            return new OqData(data.typeName(), fields, data.immutable());
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
