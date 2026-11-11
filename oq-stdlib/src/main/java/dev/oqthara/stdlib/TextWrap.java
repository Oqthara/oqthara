package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.util.ArrayList;
import java.util.List;

/**
 * Word wrapping and text diffs: {@code wrap_text}, {@code fill_text}, {@code dedent_text},
 * {@code indent_text}, {@code shorten_text} and {@code unified_diff}.
 *
 * <p>Every one of these is text-in and value-out, so none needs a handle and none is blocked by
 * ADR-0036. The wrapping rule is deliberately simpler than Python's: a hyphen is an ordinary
 * character inside a word rather than a break opportunity, because the hyphenated rule needs an
 * opinion about syllable boundaries that neither the language nor ADR-0043 has. That decision
 * is written down and pinned by a test rather than left to be discovered.
 */
final class TextWrap {
    private TextWrap() {
    }

    static void install(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("wrap_text", 2, (host, args, span) -> {
            String text = text(args.get(0), "wrap_text", span);
            int width = width(args.get(1), "wrap_text", span);
            return new ArrayList<Object>(wrap(text, width));
        }));
        interpreter.defineBuiltin(NativeFunction.of("fill_text", 2, (host, args, span) -> {
            String text = text(args.get(0), "fill_text", span);
            int width = width(args.get(1), "fill_text", span);
            return String.join("\n", wrap(text, width));
        }));
        interpreter.defineBuiltin(NativeFunction.of("dedent_text", 1, (host, args, span) ->
                dedent(text(args.get(0), "dedent_text", span))));
        interpreter.defineBuiltin(NativeFunction.of("indent_text", 2, (host, args, span) -> {
            String text = text(args.get(0), "indent_text", span);
            String prefix = text(args.get(1), "indent_text", span);
            return indent(text, prefix);
        }));
        interpreter.defineBuiltin(NativeFunction.of("shorten_text", 2, (host, args, span) -> {
            String text = text(args.get(0), "shorten_text", span);
            int width = width(args.get(1), "shorten_text", span);
            return shorten(text, width, span);
        }));
        interpreter.defineBuiltin(NativeFunction.variadic("unified_diff", 2, 4,
                (host, args, span) -> {
                    List<String> before = lines(args.get(0), "unified_diff", span);
                    List<String> after = lines(args.get(1), "unified_diff", span);
                    String beforeLabel = args.size() > 2 ? text(args.get(2), "unified_diff", span) : "";
                    String afterLabel = args.size() > 3 ? text(args.get(3), "unified_diff", span) : "";
                    return new ArrayList<Object>(diff(before, after, beforeLabel, afterLabel));
                }));
    }

    /** The placeholder appended to a shortened string, chosen to be unmistakable in output. */
    private static final String ELLIPSIS = " [...]";

    /** Lines of context shown before and after each change in a hunk. */
    private static final int CONTEXT = 3;

    /**
     * Wraps {@code text} to {@code width} columns.
     *
     * <p>Greedy rather than optimal: each line takes as many whole words as fit, and a word is
     * only split when it cannot fit on a line of its own. Every output line is at least one
     * character of the input, so the list cannot exceed the input in any proportional sense.
     *
     * <p>Leading whitespace is carried into the first line because a caller wrapping an already
     * indented string means the indent to survive. If that indent leaves no room for the first
     * word, the indent is dropped rather than emitting a line that holds nothing but spaces.
     */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        int i = 0;
        StringBuilder indent = new StringBuilder();
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            indent.append(text.charAt(i++));
        }
        String current = indent.toString();
        boolean carrying = false;
        while (i < text.length()) {
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
            String word = text.substring(start, i);
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;

            if (carrying && current.length() + 1 + word.length() <= width) {
                current = current + " " + word;
                continue;
            }
            if (carrying) {
                lines.add(current);
                current = "";
                carrying = false;
            } else if (!current.isEmpty() && current.length() + word.length() > width) {
                // Only the indent is on this line and the first word does not fit beside it, so
                // the indent is dropped rather than emitting a line of nothing but spaces.
                current = "";
            }
            if (word.length() <= width) {
                current = current + word;
                carrying = true;
                continue;
            }
            while (word.length() > width) {
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            current = word;
            carrying = true;
        }
        if (carrying || !current.isEmpty()) lines.add(current);
        return lines;
    }

    private static String dedent(String text) {
        String[] lines = text.split("\n", -1);
        int shared = Integer.MAX_VALUE;
        boolean any = false;
        for (String line : lines) {
            if (line.isBlank()) continue;
            int indent = 0;
            while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) indent++;
            shared = Math.min(shared, indent);
            any = true;
        }
        if (!any) return "";
        if (shared == 0) return text;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            String line = lines[i];
            if (line.isBlank()) continue;
            out.append(line.substring(Math.min(shared, line.length())));
        }
        return out.toString();
    }

    private static String indent(String text, String prefix) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            if (lines[i].isBlank()) continue;
            out.append(prefix).append(lines[i]);
        }
        return out.toString();
    }

    private static String shorten(String text, int width, Span span) {
        String collapsed = String.join(" ", words(text));
        if (collapsed.length() <= width) return collapsed;
        if (width <= ELLIPSIS.length()) {
            throw new OqError("shorten_text() cannot fit " + ELLIPSIS.length()
                    + " characters of placeholder in a width of " + width, span,
                    "Use a width of at least " + (ELLIPSIS.length() + 1) + ".");
        }
        int room = width - ELLIPSIS.length();
        List<String> parts = words(text);
        StringBuilder out = new StringBuilder();
        for (String word : parts) {
            int needed = out.isEmpty() ? word.length() : out.length() + 1 + word.length();
            if (needed > room) break;
            if (!out.isEmpty()) out.append(' ');
            out.append(word);
        }
        return out.isEmpty() ? ELLIPSIS.strip() : out + ELLIPSIS;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
            if (i > start) words.add(text.substring(start, i));
        }
        return words;
    }

    /**
     * A unified diff between two lists of lines, with {@value #CONTEXT} lines of context.
     *
     * <p>Longest-common-subsequence over whole lines rather than a character diff: every
     * consumer of a diff here compares generated text against a committed copy, which is a
     * line-oriented question.
     */
    static List<String> diff(List<String> before, List<String> after,
                             String beforeLabel, String afterLabel) {
        List<String> out = new ArrayList<>();
        int n = before.size();
        int m = after.size();
        // length[i][j] is the LCS length of before[i..] and after[j..].
        int[][] length = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                length[i][j] = before.get(i).equals(after.get(j))
                        ? length[i + 1][j + 1] + 1
                        : Math.max(length[i + 1][j], length[i][j + 1]);
            }
        }
        List<String[]> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (before.get(i).equals(after.get(j))) {
                ops.add(new String[] {" ", before.get(i), String.valueOf(i), String.valueOf(j)});
                i++;
                j++;
            } else if (length[i + 1][j] >= length[i][j + 1]) {
                ops.add(new String[] {"-", before.get(i), String.valueOf(i), String.valueOf(j)});
                i++;
            } else {
                ops.add(new String[] {"+", after.get(j), String.valueOf(i), String.valueOf(j)});
                j++;
            }
        }
        while (i < n) {
            ops.add(new String[] {"-", before.get(i), String.valueOf(i), String.valueOf(j)});
            i++;
        }
        while (j < m) {
            ops.add(new String[] {"+", after.get(j), String.valueOf(i), String.valueOf(j)});
            j++;
        }
        boolean changed = ops.stream().anyMatch(op -> !op[0].equals(" "));
        if (!changed) return out;
        out.add("--- " + beforeLabel);
        out.add("+++ " + afterLabel);
        for (int[] hunk : hunks(ops)) {
            int from = hunk[0];
            int to = hunk[1];
            int beforeStart = Integer.parseInt(ops.get(from)[2]);
            int afterStart = Integer.parseInt(ops.get(from)[3]);
            int beforeCount = 0;
            int afterCount = 0;
            for (int k = from; k <= to; k++) {
                if (!ops.get(k)[0].equals("+")) beforeCount++;
                if (!ops.get(k)[0].equals("-")) afterCount++;
            }
            out.add("@@ " + range('-', beforeStart, beforeCount) + " "
                    + range('+', afterStart, afterCount) + " @@");
            for (int k = from; k <= to; k++) {
                out.add(ops.get(k)[0] + ops.get(k)[1]);
            }
        }
        return out;
    }

    /**
     * The index ranges of {@code ops} that a hunk covers: each run of changes plus
     * {@value #CONTEXT} unchanged lines on either side, with runs closer than twice the context
     * merged into one hunk.
     */
    private static List<int[]> hunks(List<String[]> ops) {
        List<int[]> groups = new ArrayList<>();
        int k = 0;
        while (k < ops.size()) {
            if (ops.get(k)[0].equals(" ")) {
                k++;
                continue;
            }
            int start = Math.max(0, k - CONTEXT);
            int end = k;
            int trailing = 0;
            while (end < ops.size()) {
                if (!ops.get(end)[0].equals(" ")) {
                    trailing = 0;
                } else {
                    if (trailing == CONTEXT * 2) break;
                    trailing++;
                }
                end++;
            }
            int stop = Math.min(ops.size() - 1, end - 1 - Math.max(0, trailing - CONTEXT));
            groups.add(new int[] {start, Math.max(start, stop)});
            k = stop + 1;
        }
        return groups;
    }

    private static String range(char sign, int start, int count) {
        // A zero-length range names the line before it, which is what the unified format does:
        // an empty side of the hunk header is "-0,0" for a file with no lines.
        int first = count == 0 ? start : start + 1;
        return count == 1 ? sign + "" + first : sign + "" + first + "," + count;
    }

    private static String text(Object value, String builtin, Span span) {
        if (!(value instanceof String)) {
            throw new OqError(builtin + "() needs text, got " + Values.typeName(value), span);
        }
        return (String) value;
    }

    private static int width(Object value, String builtin, Span span) {
        if (!Values.isNumber(value)) {
            throw new OqError(builtin + "() needs a whole number for width, got "
                    + Values.typeName(value), span);
        }
        double raw = Values.asDouble(value);
        if (raw != Math.rint(raw)) {
            throw new OqError(builtin + "() needs a whole number for width, got "
                    + Values.display(value), span);
        }
        if (raw < 1) {
            throw new OqError(builtin + "() needs a width of at least 1, got "
                    + Values.display(value), span);
        }
        if (raw > Integer.MAX_VALUE) {
            throw new OqError(builtin + "() width is too large, got "
                    + Values.display(value), span);
        }
        return (int) raw;
    }

    private static List<String> lines(Object value, String builtin, Span span) {
        // A string is iterable as characters, which would silently diff characters instead of
        // lines; a caller who means one line should pass a one-item list.
        if (value instanceof String) {
            throw new OqError(builtin + "() needs a list of lines, got text", span,
                    "Pass a list of lines, such as [\"one line\"] or text.lines(), "
                            + "rather than a string of text.");
        }
        List<?> items = Values.iterableList(value, span);
        List<String> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (!(item instanceof String)) {
                throw new OqError(builtin + "() needs lines of text, but item " + i + " is "
                        + Values.typeName(item), span);
            }
            out.add((String) item);
        }
        return out;
    }
}
