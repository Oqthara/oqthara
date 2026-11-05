package dev.oqthara.lsp;

import java.util.ArrayList;
import java.util.List;

/**
 * A conservative, idempotent formatter mirroring {@code oq fmt} (the CLI keeps its own
 * package-private copy): re-indent blocks, normalise spacing around operators and after
 * commas, and leave text literals byte-for-byte intact. The server applies it only after
 * the document parsed and re-parses the result before sending edits.
 */
public final class Formatter {
    private static final int INDENT = 2;

    private Formatter() {
    }

    public static String format(String source) {
        if (source.isEmpty()) return source;
        List<String> output = new ArrayList<>();
        int depth = 0;
        boolean inTriple = false;
        for (String rawLine : source.replace("\r\n", "\n").split("\n", -1)) {
            String line = rawLine.stripTrailing();
            String stripped = line.strip();

            if (inTriple) {
                output.add(line);
                if (stripped.contains("\"\"\"")) inTriple = false;
                continue;
            }

            if (stripped.isEmpty()) {
                if (!output.isEmpty() && !output.get(output.size() - 1).isEmpty()) {
                    output.add("");
                }
                continue;
            }

            if (stripped.startsWith("#")) {
                output.add(" ".repeat(depth * INDENT) + stripped);
                continue;
            }

            String firstWord = firstWord(stripped);
            boolean closes = firstWord.equals("end") || firstWord.equals("else")
                    || firstWord.equals("catch");
            if (closes) {
                depth = Math.max(0, depth - 1);
            }

            String normalised = normalise(stripped);
            output.add(" ".repeat(depth * INDENT) + normalised);

            if (opensBlock(normalised) && isBlockOpener(normalised)) {
                depth++;
            } else if (firstWord.equals("else") || firstWord.equals("catch")) {
                depth++;
            }

            long tripleQuotes = countOccurrences(normalised, "\"\"\"");
            if (tripleQuotes % 2 == 1) inTriple = true;
        }
        while (!output.isEmpty() && output.get(output.size() - 1).isEmpty()) {
            output.remove(output.size() - 1);
        }
        return String.join("\n", output) + "\n";
    }

    private static String firstWord(String line) {
        int i = 0;
        while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static boolean isBlockOpener(String line) {
        if (firstWord(line).equals("data")) {
            // data declarations are one line and never open a block.
            return false;
        }
        return true;
    }

    private static boolean opensBlock(String line) {
        String first = firstWord(line);
        return switch (first) {
            case "if", "while", "for", "fun", "data", "try" -> true;
            default -> false;
        };
    }

    /** Normalises operator and comma spacing outside text literals and comments. */
    static String normalise(String line) {
        StringBuilder out = new StringBuilder();
        List<Boolean> maps = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'') {
                if (c == '"' && i + 2 < line.length()
                        && line.charAt(i + 1) == '"' && line.charAt(i + 2) == '"') {
                    int end = line.indexOf("\"\"\"", i + 3);
                    if (end < 0) {
                        out.append(line.substring(i));
                        return out.toString().stripTrailing();
                    }
                    out.append(line, i, end + 3);
                    i = end + 3;
                    continue;
                }
                int end = i + 1;
                while (end < line.length()) {
                    char inner = line.charAt(end);
                    if (inner == '\\') {
                        end += 2;
                        continue;
                    }
                    if (inner == c) {
                        end++;
                        break;
                    }
                    end++;
                }
                out.append(line, i, Math.min(end, line.length()));
                i = Math.min(end, line.length());
                continue;
            }
            if (c == '#') {
                out.append(line.substring(i));
                break;
            }
            if (isOperatorChar(c)) {
                String operator = longestOperatorAt(line, i);
                boolean prefix = isPrefixPosition(out);
                if (prefix && isUnary(operator)) {
                    out.append(operator);
                    i += operator.length();
                    while (i < line.length() && line.charAt(i) == ' ') i++;
                    continue;
                }
                trimTrailingSpaces(out);
                if (!out.isEmpty() && !isOpenDelimiter(out.charAt(out.length() - 1))) {
                    out.append(' ');
                }
                out.append(operator);
                i += operator.length();
                while (i < line.length() && line.charAt(i) == ' ') i++;
                if (i < line.length() && !isCloseDelimiter(line.charAt(i))) {
                    out.append(' ');
                }
                continue;
            }
            if (c == ',') {
                trimTrailingSpaces(out);
                out.append(',');
                i++;
                while (i < line.length() && line.charAt(i) == ' ') i++;
                if (i < line.length() && !isCloseDelimiter(line.charAt(i))) out.append(' ');
                continue;
            }
            if (c == ':') {
                trimTrailingSpaces(out);
                out.append(':');
                i++;
                while (i < line.length() && line.charAt(i) == ' ') i++;
                if (i < line.length() && !isCloseDelimiter(line.charAt(i))) out.append(' ');
                continue;
            }
            if (c == '{') {
                boolean map = hasMapColon(line, i);
                maps.add(map);
                out.append('{');
                i++;
                if (map) {
                    while (i < line.length() && line.charAt(i) == ' ') i++;
                    if (i < line.length() && line.charAt(i) != '}') out.append(' ');
                }
                continue;
            }
            if (c == '}') {
                boolean map = !maps.isEmpty() && maps.remove(maps.size() - 1);
                if (map) {
                    trimTrailingSpaces(out);
                    if (!out.isEmpty() && out.charAt(out.length() - 1) != '{') out.append(' ');
                }
                out.append('}');
                i++;
                continue;
            }
            if (c == ' ') {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') out.append(' ');
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        String result = out.toString();
        result = result.replaceAll("\\s+$", "");
        result = result.replaceAll("\\(\\s+", "(");
        result = result.replaceAll("\\s+\\)", ")");
        result = result.replaceAll("\\[\\s+", "[");
        result = result.replaceAll("\\s+\\]", "]");
        return result.stripLeading();
    }

    private static boolean isUnary(String operator) {
        return operator.equals("-") || operator.equals("+") || operator.equals("!");
    }

    private static boolean isPrefixPosition(StringBuilder out) {
        int i = out.length() - 1;
        while (i >= 0 && out.charAt(i) == ' ') i--;
        if (i < 0) return true;
        char last = out.charAt(i);
        return last == '(' || last == '[' || last == '{' || last == ',' || last == ':'
                || last == '=' || isOperatorChar(last);
    }

    /**
     * True when the brace at {@code open} starts a map literal: a colon appears among its
     * top-level entries on this line. Nested braces, brackets, parentheses and text literals
     * are skipped, so a set of maps stays a set.
     */
    private static boolean hasMapColon(String line, int open) {
        int depth = 0;
        int i = open + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'') {
                i++;
                while (i < line.length()) {
                    char inner = line.charAt(i);
                    if (inner == '\\') {
                        i += 2;
                        continue;
                    }
                    if (inner == c) {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '#') return false;
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) return false;
                depth--;
            } else if (c == ':' && depth == 0) {
                return true;
            }
            i++;
        }
        return false;
    }

    private static boolean isOperatorChar(char c) {
        return "+-*/%<>=!".indexOf(c) >= 0;
    }

    private static boolean isOpenDelimiter(char c) {
        return c == '(' || c == '[' || c == '{';
    }

    private static boolean isCloseDelimiter(char c) {
        return c == ')' || c == ']' || c == '}' || c == ',';
    }

    private static String longestOperatorAt(String line, int index) {
        String[] multi = {"**=", "**", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "->"};
        for (String operator : multi) {
            if (line.startsWith(operator, index)) return operator;
        }
        return String.valueOf(line.charAt(index));
    }

    private static void trimTrailingSpaces(StringBuilder out) {
        while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
        }
    }

    private static long countOccurrences(String text, String needle) {
        long count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
