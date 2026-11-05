package dev.oqthara.cli;

import dev.oqthara.core.Parser;
import dev.oqthara.runtime.OqError;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A conservative, idempotent formatter. It re-indents blocks, normalises spacing around
 * operators and after commas, and leaves text literals byte-for-byte intact. It never
 * changes the token stream: {@code format(format(x)) == format(x)} and the formatted file
 * must still parse.
 */
final class Formatter {
    private static final int INDENT = 2;

    private Formatter() {
    }

    static int formatFiles(PrintStream out, PrintStream err, String[] args) {
        if (args.length == 0) {
            err.println("Missing file. Try: oq fmt hello.oq");
            return 2;
        }
        int status = 0;
        for (String name : args) {
            String path = name.endsWith(".oq") ? name : name + ".oq";
            try {
                String source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
                Parser.parse(path, source);
                String formatted = format(source);
                Parser.parse(path, formatted);
                if (!formatted.equals(source)) {
                    Files.writeString(Path.of(path), formatted, StandardCharsets.UTF_8);
                    out.println("formatted " + path);
                } else {
                    out.println("unchanged " + path);
                }
            } catch (java.io.IOException e) {
                err.println("Cannot read '" + path + "': " + e.getMessage());
                status = 2;
            } catch (OqError e) {
                Details.render(err, e, null, false);
                status = 1;
            }
        }
        return status;
    }

    static String format(String source) {
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

            // A single-line declaration or signature opens a block; a continued line
            // (after a comma, or an unclosed bracket) does not.
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
        String first = firstWord(line);
        if (first.equals("data")) {
            // data declarations are one line and never open a block.
            return false;
        }
        if (first.equals("fun")) {
            // fun declarations open a block on the signature line.
            return line.trim().endsWith(")") || line.contains(")");
        }
        if (first.equals("try")) {
            return true;
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
                int before = out.length();
                trimTrailingSpaces(out);
                boolean needSpaceBefore = before > 0 && !out.isEmpty()
                        && !isOpenDelimiter(out.charAt(out.length() - 1))
                        && !isPrefixPosition(out);
                if (needSpaceBefore) out.append(' ');
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

    private static boolean isPrefixPosition(StringBuilder out) {
        if (out.isEmpty()) return true;
        char last = out.charAt(out.length() - 1);
        return last == '(' || last == '[' || last == '{' || last == ',' || last == ':'
                || last == '=';
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
        String[] multi = {"**=", "**", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/="};
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
