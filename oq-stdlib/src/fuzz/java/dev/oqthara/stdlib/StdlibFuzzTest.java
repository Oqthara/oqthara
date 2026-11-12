package dev.oqthara.stdlib;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fuzzes the pure stdlib builtins: text, math and conversion helpers plus the JSON writers.
 * A call must either return a value or raise {@link OqError}; any other {@link Throwable} is a
 * defect, because a raw Java exception reaches the CLI as "internal error ... this is a bug
 * in Oqthara".
 *
 * <p>Only side-effect-free builtins are called. {@code read}, {@code save}, {@code http_get},
 * {@code run_command}, {@code sleep}, {@code random}, {@code now} and their neighbours are
 * deliberately absent: they touch the filesystem, network, clock, process state or a shared
 * random source, so a fuzz run could not be reproduced and could block.
 *
 * <p>Every argument is bounded: text is at most {@value #MAX_TEXT} characters, lists hold at
 * most {@value #MAX_LIST_ITEMS} items and nest at most {@value #MAX_LIST_DEPTH} levels, and the
 * numeric arguments are a single {@code long} or {@code double}. No seed can therefore make one
 * call allocate unbounded memory or loop for long.
 */
class StdlibFuzzTest {
    /** Same cap as the JSON target, so a single call stays cheap. */
    private static final int MAX_TEXT = 4096;
    private static final int MAX_LIST_ITEMS = 8;
    private static final int MAX_LIST_DEPTH = 2;

    /** Builtins that must only ever return a value or raise {@code OqError}. */
    private static final String[] BUILTINS = {
            "upper", "lower", "trim", "split", "replace", "contains", "format",
            "abs", "floor", "ceil", "round", "sqrt", "pow", "min", "max", "clamp",
            "number", "text", "type_of", "json", "json_pretty",
            "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "hypot",
            "ln", "log10", "log2", "exp", "degrees", "radians", "pi", "e", "tau",
    };

    private static final Interpreter INTERPRETER = createInterpreter();

    @FuzzTest(maxDuration = "30s")
    void pureBuiltinsNeverThrowUnexpectedExceptions(FuzzedDataProvider data) {
        String name = data.pickValue(BUILTINS);
        List<Object> args = argumentsFor(name, data);
        Object result;
        try {
            result = call(name, args);
        } catch (OqError expected) {
            // A type error, a bad range or non-finite JSON is a normal, reported outcome.
            return;
        } catch (StackOverflowError e) {
            throw new AssertionError(name + "() overflowed on " + describe(name, args), e);
        } catch (RuntimeException e) {
            throw new AssertionError(name + "() crashed on " + describe(name, args)
                    + ": " + e, e);
        }
        checkResult(name, args, result);
    }

    /**
     * Builds only the arguments the selected builtin takes, so an arity mismatch cannot hide a
     * real failure. In every slot the fuzzer picks the kind of value as well as the value, which
     * keeps the "wrong type gives OqError" paths reachable.
     */
    private static List<Object> argumentsFor(String name, FuzzedDataProvider data) {
        List<Object> args = new ArrayList<>();
        switch (name) {
            case "split", "contains", "pow", "min", "max", "atan2", "hypot" -> {
                args.add(anyValue(data, 0));
                args.add(anyValue(data, 0));
            }
            case "round" -> {
                args.add(anyValue(data, 0));
                // Both arities exist; -2..20 also covers the bounds check.
                if (data.consumeBoolean()) args.add((long) data.consumeInt(-2, 20));
            }
            case "replace" -> {
                args.add(anyValue(data, 0));
                args.add(anyValue(data, 0));
                args.add(anyValue(data, 0));
            }
            case "clamp" -> {
                args.add(anyValue(data, 0));
                args.add(anyValue(data, 0));
                args.add(anyValue(data, 0));
            }
            default -> args.add(anyValue(data, 0));
        }
        return args;
    }

    /** One value: text, whole number, decimal, boolean, nothing or a small nested list. */
    private static Object anyValue(FuzzedDataProvider data, int depth) {
        return switch (data.consumeInt(0, depth < MAX_LIST_DEPTH ? 6 : 5)) {
            case 0 -> data.consumeString(MAX_TEXT);
            case 1 -> data.consumeLong();
            case 2 -> data.consumeDouble();
            case 3 -> data.consumeBoolean();
            case 4 -> null;
            case 5 -> List.of();
            default -> {
                int size = data.consumeInt(0, MAX_LIST_ITEMS);
                List<Object> items = new ArrayList<>(size);
                for (int i = 0; i < size; i++) items.add(anyValue(data, depth + 1));
                yield items;
            }
        };
    }

    private static Object call(String name, List<Object> args) {
        OqCallable callable = INTERPRETER.builtins().get(name);
        if (callable == null) throw new AssertionError("stdlib does not define '" + name + "'");
        return callable.call(INTERPRETER, args, Span.synthetic());
    }

    /**
     * A returned value must have the documented shape. This catches silent corruption such as
     * a text builtin returning a number, which the "no exception" check alone would miss.
     */
    private static void checkResult(String name, List<Object> args, Object result) {
        boolean ok = switch (name) {
            case "upper", "lower", "trim", "format", "replace", "text", "type_of",
                    "json", "json_pretty" -> result instanceof String;
            case "split" -> result instanceof List<?>;
            case "contains" -> result instanceof Boolean;
            case "number" -> Values.isNumber(result);
            // min/max/clamp compare and return one of their own arguments, so the result is
            // one of the inputs verbatim: max("", "") is text, max(true, false) is a boolean.
            case "min", "max" -> matchesOneArgument(result, args);
            case "clamp" -> matchesOneArgument(result, args);
            default -> Values.isNumber(result);
        };
        if (!ok) {
            throw new AssertionError(name + "() returned " + Values.typeName(result)
                    + " for " + describe(name, args) + " (value " + Values.repr(result) + ")");
        }
        if ((name.equals("json") || name.equals("json_pretty")) && result instanceof String text) {
            // If the writer returns text at all, that text has to be valid JSON. Rejecting a
            // value is allowed (it raises OqError above), silently writing invalid JSON is not.
            try {
                Json.parse(text, Span.synthetic());
            } catch (OqError e) {
                throw new AssertionError(name + "() wrote text the reader rejects for "
                        + describe(name, args) + ": " + repr(text), e);
            }
        }
    }

    /** min/max/clamp select an argument rather than building a new value. */
    private static boolean matchesOneArgument(Object result, List<Object> args) {
        for (Object arg : args) {
            if (Objects.equals(arg, result)) return true;
        }
        return false;
    }

    private static Interpreter createInterpreter() {
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream(), true,
                StandardCharsets.UTF_8);
        Interpreter interpreter = new Interpreter(sink, sink,
                new BufferedReader(new StringReader("")), List.of(), Path.of("."),
                new MethodResolverImpl());
        Stdlib.install(interpreter);
        return interpreter;
    }

    private static String describe(String name, List<Object> args) {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(shortRepr(args.get(i)));
        }
        return sb.append(')').toString();
    }

    private static String shortRepr(Object value) {
        String text = Values.repr(value);
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    private static String repr(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
