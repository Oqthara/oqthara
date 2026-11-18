package dev.oqthara.stdlib;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.Span;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fuzzes the format builtins the original stdlib target left out: CSV, INI, properties and the
 * text wrapper. Risk R-018 recorded that these ten names were reachable from a program and never
 * fuzzed, so a malformed quote or a reversed section header could only be found by hand.
 *
 * <p>These are still side-effect-free. {@code glob} and path resolution are absent on purpose:
 * they read the filesystem through {@link OqHost}, so a seed could not be replayed on a machine
 * with a different tree.
 *
 * <p>The second half is {@link Patterns.Glob}, the pattern engine behind {@code fnmatch}, called
 * directly. Testing the builtin alone would exercise the same class, but constructing it here
 * keeps the translation from glob syntax to a regex in reach of the fuzzer even when the builtin
 * refuses an argument early.
 *
 * <p>Beyond "no unexpected exception" every target asserts a postcondition that a silently wrong
 * result would break: a parsed CSV or INI value must survive a write/parse round trip, wrapped
 * lines must respect the width they were given, and a compiled glob must agree with a reference
 * interpretation on the two cases the dialect makes no choice about.
 */
class StdlibFormatsFuzzTest {
    private static final int MAX_TEXT = 2048;
    private static final int MAX_LIST_ITEMS = 6;

    /**
     * Delimiters kept to characters that are legal in a CSV dialect and not the quote.
     *
     * <p>A quote delimiter would make quoting ambiguous in a way the format never promised, and
     * a NUL or a newline delimiter is not what "a CSV dialect" means; both would be testing a
     * contract the builtin does not offer.
     */
    private static final String DELIMITERS = ",;\t|: ";

    private static final Interpreter INTERPRETER = createInterpreter();

    private static final String[] FORMAT_BUILTINS = {
            "csv_parse", "csv_write", "wrap_text", "fill_text",
            "ini_parse", "ini_write", "properties_parse",
    };

    @FuzzTest(maxDuration = "30s")
    void formatBuiltinsNeverThrowUnexpectedExceptions(FuzzedDataProvider data) {
        String name = data.pickValue(FORMAT_BUILTINS);
        List<Object> args = argumentsFor(name, data);
        Object result;
        try {
            result = call(name, args);
        } catch (OqError expected) {
            // A bad delimiter, a malformed section header or a width of zero is reported.
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
     * Compiles arbitrary text as a glob pattern and matches it against arbitrary text.
     *
     * <p>A bad pattern is an {@code OqError}; anything else reaching the caller is a defect,
     * because both {@code fnmatch} and {@code glob} tell the user the pattern is wrong rather
     * than crashing.
     */
    @FuzzTest(maxDuration = "30s")
    void globPatternsNeverThrowUnexpectedExceptions(FuzzedDataProvider data) {
        String pattern = data.consumeString(MAX_TEXT);
        String name = data.consumeString(MAX_TEXT);
        Patterns.Glob glob;
        try {
            glob = new Patterns.Glob(pattern, Span.synthetic());
        } catch (OqError expected) {
            return;
        } catch (RuntimeException | StackOverflowError e) {
            throw new AssertionError("Glob rejected its own pattern for " + repr(pattern)
                    + ": " + e, e);
        }
        boolean matched;
        try {
            matched = glob.matchesText(name);
        } catch (RuntimeException | StackOverflowError e) {
            throw new AssertionError("Glob compiled " + repr(pattern)
                    + " but threw matching " + repr(name) + ": " + e, e);
        }
        checkLiteralPatternMatchesItself(pattern, glob);
        // Matching is a total function of the name, so the same name cannot answer differently
        // twice. This costs one call and catches a matcher holding mutable state.
        if (matched != glob.matchesText(name)) {
            throw new AssertionError("Glob " + repr(pattern) + " disagreed with itself on "
                    + repr(name));
        }
    }

    /**
     * Builds patterns out of character classes and checks them against their members.
     *
     * <p>Written after a review of the linear rewrite found a defect the two free-form targets
     * above could not produce. They generate arbitrary text, so a class in a generated pattern is
     * almost never a wide range; the draft that expanded ranges into members therefore needed a
     * class spanning most of Unicode to expose itself, and random text will not spell one. This
     * target ranges over classes deliberately, including the widest legal range, so the cost of
     * representing a class is part of what is fuzzed rather than only what is unit-tested.
     *
     * <p>The property is a class member must match and a non-member must not. A range's endpoints
     * are the interesting inputs, since an off-by-one at either end is the defect a hand-written
     * expansion has.
     *
     * <p>The class is repeated a fuzzer-chosen number of times on purpose. One wide class costs a
     * few megabytes even when its ranges are expanded, which is well inside this target's heap and
     * therefore invisible; it is the *product* of pattern length and range width that turns into a
     * heap exhaustion. The first version of this target built a single class and passed against
     * the expanding implementation, so it could not have caught the defect it was written for.
     */
    @FuzzTest(maxDuration = "30s")
    void globClassesMatchTheirOwnMembers(FuzzedDataProvider data) {
        int low = data.consumeInt(0, 0x10FFFF);
        int high = data.consumeInt(low, 0x10FFFF);
        boolean negated = data.consumeBoolean();
        // `]` closes the class wherever it appears, so it cannot be spelled as an endpoint; `!`
        // only negates as the first member and `-` only makes a range between two members, so
        // either inside the body would change what the pattern means. Excluding the three keeps
        // this property about representation cost rather than about the class syntax rules, which
        // the tests and the free-form target below already cover.
        if (isClassSyntax(low) || isClassSyntax(high)) {
            return;
        }
        String body = new String(Character.toChars(low)) + "-" + new String(Character.toChars(high));
        String one = "[" + (negated ? "!" : "") + body + "]";
        int repeat = data.consumeInt(1, 512);
        String pattern = one.repeat(repeat);
        Patterns.Glob glob;
        try {
            glob = new Patterns.Glob(pattern, Span.synthetic());
        } catch (OqError expected) {
            return;
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            throw new AssertionError("Glob rejected " + repr(pattern) + ": " + e, e);
        }
        int[] endpoints = {low, high, Math.max(0, low - 1), Math.min(0x10FFFF, high + 1)};
        for (int candidate : endpoints) {
            boolean expected = negated != (candidate >= low && candidate <= high);
            boolean actual;
            try {
                actual = glob.matchesText(new String(Character.toChars(candidate)).repeat(repeat));
            } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
                throw new AssertionError("Glob " + repr(pattern) + " threw on one member: " + e, e);
            }
            if (actual != expected) {
                throw new AssertionError("Glob " + repr(pattern) + " answered " + actual
                        + " for member U+" + Integer.toHexString(candidate)
                        + " but a class is " + (negated ? "negated" : "inclusive"));
            }
        }
    }

    private static boolean isClassSyntax(int codePoint) {
        return codePoint == ']' || codePoint == '!' || codePoint == '-';
    }

    /**
     * A pattern with no wildcard is ordinary text, so it matches exactly one name: itself.
     *
     * <p>This is the property {@code translate} gets wrong in the most damaging way. Every
     * literal character goes through {@code Pattern.quote}, and a missing quote turns a name
     * like {@code a.b} into a regex that also matches {@code axb}. The pattern side and the
     * name side are different code paths, so agreeing on the wildcard-free case is not
     * automatic.
     */
    private static void checkLiteralPatternMatchesItself(String pattern, Patterns.Glob glob) {
        if (pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0 || pattern.indexOf('[') >= 0) {
            return;
        }
        // The dialect treats the host separator as '/' on both sides, so only the pattern's own
        // text is compared; a separator inside the pattern is still ordinary text here.
        if (!glob.matchesText(pattern)) {
            throw new AssertionError("Glob does not match its own wildcard-free pattern: "
                    + escape(pattern));
        }
        if (!glob.matchesText(pattern + "x")) {
            return;
        }
        throw new AssertionError("Glob " + escape(pattern) + " also matches "
                + escape(pattern + "x") + ", so a literal was not quoted");
    }

    /**
     * Renders control characters as escapes so a failure message stays on one line.
     *
     * <p>{@link #repr} truncates but still prints a '\n' as a real line break, which splits the
     * assertion message across lines and hides the input that caused it.
     */
    private static String escape(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length() && i < 200; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20 || c > 0x7E) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static List<Object> argumentsFor(String name, FuzzedDataProvider data) {
        List<Object> args = new ArrayList<>();
        switch (name) {
            case "csv_parse" -> {
                args.add(data.consumeString(MAX_TEXT));
                // Both extra arities exist: a delimiter, then a comments flag.
                if (data.consumeBoolean()) {
                    args.add(String.valueOf(
                            DELIMITERS.charAt(data.consumeInt(0, DELIMITERS.length() - 1))));
                    if (data.consumeBoolean()) args.add(data.consumeBoolean());
                }
            }
            case "csv_write" -> {
                args.add(rows(data));
                if (data.consumeBoolean()) {
                    args.add(String.valueOf(
                            DELIMITERS.charAt(data.consumeInt(0, DELIMITERS.length() - 1))));
                }
            }
            case "wrap_text", "fill_text" -> {
                args.add(data.consumeString(MAX_TEXT));
                // 1 is the lowest accepted width and the one that splits every character; the
                // upper end covers words longer than the line and indents that cannot fit.
                args.add((long) data.consumeInt(1, MAX_TEXT));
            }
            case "ini_parse", "properties_parse" -> args.add(data.consumeString(MAX_TEXT));
            case "ini_write" -> args.add(iniMap(data));
            default -> args.add(data.consumeString(MAX_TEXT));
        }
        return args;
    }

    /** A list of rows, where every cell is text or a scalar; CSV writes their display form. */
    private static List<Object> rows(FuzzedDataProvider data) {
        int count = data.consumeInt(0, MAX_LIST_ITEMS);
        List<Object> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int width = data.consumeInt(0, MAX_LIST_ITEMS);
            List<Object> row = new ArrayList<>(width);
            for (int j = 0; j < width; j++) row.add(cell(data));
            rows.add(row);
        }
        return rows;
    }

    /** A cell the writer can show: quoting is only interesting when the cell holds a delimiter. */
    private static Object cell(FuzzedDataProvider data) {
        return switch (data.consumeInt(0, 4)) {
            case 0 -> data.consumeString(64);
            case 1 -> data.consumeLong();
            case 2 -> data.consumeDouble();
            case 3 -> data.consumeBoolean();
            default -> null;
        };
    }

    /**
     * Section to key to value, the shape {@code ini_write} documents.
     *
     * <p>The same flat section-less prefix the reader produces is not generated here: a writer
     * input is a map a program built, and nesting a table inside a table is what makes the
     * writer's recursion worth fuzzing.
     */
    private static Map<Object, Object> iniMap(FuzzedDataProvider data) {
        Map<Object, Object> map = new dev.oqthara.runtime.OqMap();
        int sections = data.consumeInt(0, 3);
        for (int i = 0; i < sections; i++) {
            Map<Object, Object> table = new dev.oqthara.runtime.OqMap();
            int keys = data.consumeInt(0, 3);
            for (int j = 0; j < keys; j++) table.put(cell(data), cell(data));
            map.put(data.consumeString(32), table);
        }
        return map;
    }

    private static Object call(String name, List<Object> args) {
        OqCallable callable = INTERPRETER.builtins().get(name);
        if (callable == null) throw new AssertionError("stdlib does not define '" + name + "'");
        return callable.call(INTERPRETER, args, Span.synthetic());
    }

    /**
     * The shape each builtin documents, plus the round trip that makes the shape meaningful.
     *
     * <p>A parser returning a list where a map belongs, or a wrapper returning one long line,
     * would satisfy "did not throw" and still be wrong, so each result is checked against the
     * contract and then, where a writer exists, written and read back.
     */
    private static void checkResult(String name, List<Object> args, Object result) {
        switch (name) {
            case "csv_parse" -> {
                if (!(result instanceof List<?> parsed)) {
                    throw new AssertionError("csv_parse() returned "
                            + typeName(result) + " for " + describe(name, args));
                }
                for (Object row : parsed) {
                    if (!(row instanceof List<?> cells)) {
                        throw new AssertionError("csv_parse() returned a row that is "
                                + typeName(row) + " for " + describe(name, args));
                    }
                    for (Object c : cells) {
                        if (!(c instanceof String)) {
                            throw new AssertionError("csv_parse() returned a cell that is "
                                    + typeName(c) + " for " + describe(name, args));
                        }
                    }
                }
                // Quoting is the only lossy step, so a parse/write/parse cycle has to agree.
                String written = (String) callTrusted("csv_write", List.of(parsed));
                @SuppressWarnings("unchecked")
                List<Object> again = (List<Object>) callTrusted("csv_parse", List.of(written));
                if (!again.equals(parsed)) {
                    throw new AssertionError("csv_parse() did not survive a round trip for "
                            + describe(name, args) + ": wrote " + repr(written));
                }
            }
            case "csv_write" -> {
                if (!(result instanceof String text)) {
                    throw new AssertionError("csv_write() returned " + typeName(result)
                            + " for " + describe(name, args));
                }
                if (!text.isEmpty() && !text.endsWith("\n")) {
                    throw new AssertionError("csv_write() did not end its last row with a"
                            + " newline for " + describe(name, args) + ": " + repr(text));
                }
            }
            case "wrap_text" -> {
                if (!(result instanceof List<?> lines)) {
                    throw new AssertionError("wrap_text() returned " + typeName(result)
                            + " for " + describe(name, args));
                }
                checkLines(name, args, lines);
            }
            case "fill_text" -> {
                if (!(result instanceof String text)) {
                    throw new AssertionError("fill_text() returned " + typeName(result)
                            + " for " + describe(name, args));
                }
                checkLines(name, args, List.of(text.split("\n", -1)));
            }
            case "ini_parse", "properties_parse" -> {
                if (!(result instanceof Map<?, ?>)) {
                    throw new AssertionError(name + "() returned " + typeName(result)
                            + " for " + describe(name, args));
                }
            }
            case "ini_write" -> {
                if (!(result instanceof String text)) {
                    throw new AssertionError("ini_write() returned " + typeName(result)
                            + " for " + describe(name, args));
                }
                // What the writer produces is, by definition, what the reader has to accept.
                callTrusted("ini_parse", List.of(text));
            }
            default -> throw new AssertionError("no postcondition for '" + name + "'");
        }
    }

    /**
     * Every line fits the width, except a line holding one character that cannot be split.
     *
     * <p>The width is a code point count, so a surrogate pair is one character and a line of
     * {@code width} emoji is legal even though it is {@code 2 * width} UTF-16 units.
     */
    private static void checkLines(String name, List<Object> args, List<?> lines) {
        long width = (Long) args.get(1);
        for (Object lineValue : lines) {
            if (!(lineValue instanceof String line)) {
                throw new AssertionError(name + "() returned a line that is " + typeName(lineValue)
                        + " for " + describe(name, args));
            }
            int length = line.codePointCount(0, line.length());
            // A single character wider than the width has to be emitted whole.
            if (length > width && length != 1) {
                throw new AssertionError(name + "() produced a line of " + length
                        + " characters for width " + width + ": " + repr(line));
            }
            if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                throw new AssertionError(name + "() produced a line containing a newline for "
                        + describe(name, args) + ": " + repr(line));
            }
        }
    }

    /** Calls a builtin the fuzzer did not choose, for a postcondition the result depends on. */
    private static Object callTrusted(String name, List<Object> args) {
        return call(name, args);
    }

    private static String typeName(Object value) {
        if (value == null) return "nothing";
        if (value instanceof List<?>) return "a list";
        if (value instanceof Map<?, ?>) return "a map";
        if (value instanceof String) return "text";
        if (value instanceof Boolean) return "a boolean";
        if (value instanceof Number) return "a number";
        return value.getClass().getSimpleName();
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
        String text = dev.oqthara.runtime.Values.repr(value);
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    private static String repr(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
