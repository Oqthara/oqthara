package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public final class Diagnostics {
    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    private static final String RED = "\u001b[31m";
    private static final String BLUE = "\u001b[36m";
    private static final String DIM = "\u001b[2m";
    private static final String YELLOW = "\u001b[33m";

    private final boolean color;

    public Diagnostics(boolean color) {
        this.color = color;
    }

    public static void print(PrintStream err, OqError error, List<String> sourceLines, boolean color) {
        Diagnostics diagnostics = new Diagnostics(color);
        diagnostics.render(err, error, sourceLines);
    }

    public void render(PrintStream err, OqError error, List<String> sourceLines) {
        Span span = error.span();
        String source = span.source();
        err.println();
        err.println(red("error") + bold(": ") + error.getMessage());
        if (span.line() > 0) {
            err.println(dim("  --> " + source + ":" + span.line() + ":" + span.column()));
        }
        if (sourceLines != null && span.line() >= 1 && span.line() <= sourceLines.size()) {
            String lineText = sourceLines.get(span.line() - 1);
            String number = String.format(java.util.Locale.ROOT, "%4d", span.line());
            err.println(dim(number + " | ") + lineText);
            int start = Math.max(0, span.column() - 1);
            int width = Math.max(1, span.width());
            if (start < lineText.length()) {
                width = Math.min(width, lineText.length() - start);
            }
            String caret = " ".repeat(number.length() + 3 + start) + "^".repeat(Math.max(1, width));
            err.println(red(caret));
        }
        if (error.hint() != null && !error.hint().isBlank()) {
            err.println();
            err.println(blue("  help: ") + error.hint());
        }
        err.println();
    }

    private String red(String text) {
        return color ? RED + BOLD + text + RESET : text;
    }

    private String bold(String text) {
        return color ? BOLD + text + RESET : text;
    }

    private String blue(String text) {
        return color ? BLUE + text + RESET : text;
    }

    private String dim(String text) {
        return color ? DIM + text + RESET : text;
    }

    public static List<String> lines(String source) {
        List<String> lines = new ArrayList<>();
        String[] split = source.split("\n", -1);
        for (String line : split) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return lines;
    }

    public static String colorize(String text, boolean color, String code) {
        return color ? code + text + RESET : text;
    }

    public static String yellow(String text, boolean color) {
        return color ? YELLOW + text + RESET : text;
    }
}
