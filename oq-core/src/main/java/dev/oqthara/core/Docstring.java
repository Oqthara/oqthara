package dev.oqthara.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A documentation comment and the JavaDoc-style tags it carries (ADR-0030).
 *
 * <p>Tags start a line with {@code @name} and run until the next tag. {@code @param},
 * {@code @return}, {@code @throws}, {@code @since}, {@code @author}, {@code @deprecated}
 * and {@code @see} are recognised; another tag is kept as written under its own name rather
 * than refused, so a docstring written for a future tag does not break the build.
 */
public record Docstring(String summary, Map<String, List<String>> tags) {

    private static final List<String> KNOWN = List.of(
            "param", "return", "throws", "since", "author", "deprecated", "see");

    /** Tags a reader should expect, in the order the reference presents them. */
    public static List<String> knownTags() {
        return KNOWN;
    }

    /** Parses a raw multi-line literal. Never null, never throws. */
    public static Docstring parse(String raw) {
        List<String> summaryLines = new ArrayList<>();
        Map<String, List<String>> tags = new LinkedHashMap<>();
        List<String> current = summaryLines;
        for (String line : raw.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("@") && trimmed.length() > 1
                    && Character.isLetter(trimmed.charAt(1))) {
                int space = trimmed.indexOf(' ');
                String name = space < 0 ? trimmed.substring(1) : trimmed.substring(1, space);
                String rest = space < 0 ? "" : trimmed.substring(space + 1).strip();
                current = tags.computeIfAbsent(name, k -> new ArrayList<>());
                current.add(rest);
            } else {
                current.add(trimmed);
            }
        }
        return new Docstring(joinTrimmed(summaryLines), freeze(tags));
    }

    /** The first non-blank summary line, for a hover or a one-line index entry. */
    public String firstLine() {
        for (String line : summary.split("\n", -1)) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    /**
     * The text of a {@code @param name ...} tag, or null when the docstring documents no such
     * parameter. A parameter documented twice keeps both lines.
     */
    public String param(String name) {
        List<String> lines = tags.get("param");
        if (lines == null) {
            return null;
        }
        StringBuilder found = new StringBuilder();
        for (String line : lines) {
            int space = line.indexOf(' ');
            String first = space < 0 ? line : line.substring(0, space);
            if (first.equals(name)) {
                if (!found.isEmpty()) {
                    found.append(' ');
                }
                found.append(space < 0 ? "" : line.substring(space + 1).strip());
            }
        }
        return found.isEmpty() ? null : found.toString();
    }

    /** The single-line text of a tag such as {@code @return}, or null when absent or empty. */
    public String tag(String name) {
        List<String> lines = tags.get(name);
        if (lines == null) {
            return null;
        }
        String text = joinTrimmed(lines);
        return text.isBlank() ? null : text;
    }

    /** Every tag name present, in source order. */
    public List<String> tagNames() {
        return List.copyOf(tags.keySet());
    }

    private static String joinTrimmed(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line.isBlank()) {
                if (!out.isEmpty() && !out.toString().endsWith("\n\n")) {
                    out.append("\n\n");
                }
                continue;
            }
            if (!out.isEmpty() && !out.toString().endsWith("\n\n")) {
                out.append('\n');
            }
            out.append(line);
        }
        String text = out.toString();
        return text.endsWith("\n\n") ? text.substring(0, text.length() - 2) : text;
    }

    private static Map<String, List<String>> freeze(Map<String, List<String>> tags) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        tags.forEach((name, lines) -> copy.put(name, List.copyOf(lines)));
        return Collections.unmodifiableMap(copy);
    }
}
