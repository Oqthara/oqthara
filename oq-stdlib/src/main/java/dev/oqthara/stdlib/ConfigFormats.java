package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqMap;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Configuration text formats: INI, Java properties, and a narrow reader for TOML.
 *
 * <p>These three sit in their own file because each is a grammar: it has error positions, a
 * rule for malformed input, and edge cases that only make sense next to each other. A grammar
 * is easier to check when its rules are in one place, and none of it shares state with the rest
 * of the library.
 *
 * <p>All three follow the rule {@code csv_parse} already follows: input that cannot be read
 * raises with its line number instead of being skipped. A skipped line yields a map that is
 * missing a key and says nothing about it, so the caller finds out at the point of use, far
 * from the line that was wrong.
 *
 * <p>{@code properties_parse} implements the escaping in {@link java.util.Properties#load}
 * rather than delegating to it, for two measured reasons. First, {@code Properties.load}
 * silently drops a trailing backslash at the end of the text: {@code k=v\} reads back as
 * {@code k=v} with no complaint about the truncated escape, so the one case where the input is
 * truncated is the one case that passes. This reader raises. Second, {@code Properties} reports
 * a bad {@code \\uXXXX} as a bare {@code IllegalArgumentException} naming neither the file nor
 * the line; here the line number is in the message.
 *
 * <p>{@code toml_get} reads one value at a dotted path and refuses the rest of TOML by name.
 * The scope is deliberate. TOML's tables-of-tables, inline tables and date-times each need a
 * representation decision that the language has not made (a date is not a string and there is
 * no date type), and a reader that guessed would return a value that looks right and is not.
 * A named refusal is a program that stops at the line; a guess is a program that runs and is
 * wrong. What is supported is what a configuration file usually holds: strings, integers,
 * floats, booleans, and arrays of those, under {@code [table]} headers.
 */
final class ConfigFormats {

    private ConfigFormats() {
    }

    static void install(Interpreter interpreter) {
        interpreter.defineBuiltin(NativeFunction.of("ini_parse", 1, (host, args, span) ->
                iniParse(text(args.get(0), "ini_parse", span), span)).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("ini_write", 1, (host, args, span) ->
                iniWrite(args.get(0), span)).named("map"));
        interpreter.defineBuiltin(NativeFunction.of("properties_parse", 1, (host, args, span) ->
                propertiesParse(text(args.get(0), "properties_parse", span), span)).named("text"));
        interpreter.defineBuiltin(NativeFunction.of("toml_get", 2, (host, args, span) ->
                tomlGet(text(args.get(0), "toml_get", span),
                        text(args.get(1), "toml_get", span), span)).named("text", "dotted_key"));
    }

    // ---------------------------------------------------------------- INI

    /**
     * Section name to key to value, with a section-less prefix under {@code ""}.
     *
     * <p>Only {@code =} separates a key from its value. {@code :} is not accepted, because a
     * value is allowed to contain one (a URL, a time) and treating it as a separator would split
     * those values at the first colon. Keys and values are trimmed, so a trailing space in the
     * file cannot become part of a value by accident.
     *
     * <p>A comment is a whole line starting with {@code ;} or {@code #} after trimming. There
     * are no inline comments: {@code #} inside a value is data, which is what a colour or a URL
     * fragment needs.
     *
     * <p>Both duplicates resolve the same way, last wins, and a second {@code [section]} header
     * continues the first section rather than replacing it. Silently dropping the earlier value
     * would make the file's meaning depend on which copy a reader happens to process first;
     * last-wins is the rule every INI reader in common use applies, so a file that works
     * elsewhere works here.
     */
    private static OqMap iniParse(String source, Span span) {
        OqMap sections = new OqMap();
        OqMap current = null;
        String[] lines = source.split("\r\n|\r|\n", -1);
        for (int index = 0; index < lines.length; index++) {
            int line = index + 1;
            String trimmed = lines[index].strip();
            if (trimmed.isEmpty()) continue;
            char first = trimmed.charAt(0);
            if (first == ';' || first == '#') continue;
            if (first == '[') {
                if (trimmed.length() < 2 || trimmed.charAt(trimmed.length() - 1) != ']') {
                    throw new OqError("ini_parse() found a malformed section header on line "
                            + line + ": " + trimmed, span,
                            "A section header is [name] on its own line, with nothing after "
                                    + "the closing bracket. To write a bracket as text, start "
                                    + "the line with ; or # instead.");
                }
                String name = trimmed.substring(1, trimmed.length() - 1).strip();
                if (name.isEmpty()) {
                    throw new OqError("ini_parse() found a section with no name on line " + line,
                            span, "A section header needs a name between the brackets.");
                }
                Object existing = sections.get(name);
                if (existing instanceof OqMap map) {
                    current = map;
                } else {
                    current = new OqMap();
                    sections.put(name, current);
                }
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                throw new OqError("ini_parse() found a line with no '=' on line " + line + ": "
                        + trimmed, span,
                        "Every line inside a section is key = value. Start the line with ; or # "
                                + "to make it a comment.");
            }
            String key = trimmed.substring(0, separator).strip();
            if (key.isEmpty()) {
                throw new OqError("ini_parse() found a value with no key on line " + line + ": "
                        + trimmed, span, "Write key = value, with a name before the '='.");
            }
            // Only the first '=' separates: everything after it, including more '=' characters,
            // belongs to the value.
            String value = trimmed.substring(separator + 1).strip();
            if (current == null) {
                Object existing = sections.get("");
                if (existing instanceof OqMap map) {
                    current = map;
                } else {
                    current = new OqMap();
                    sections.put("", current);
                }
            }
            current.put(key, value);
        }
        return sections;
    }

    /**
     * The inverse of {@code ini_parse}, with a fixed order so the same map always produces the
     * same bytes.
     *
     * <p>Sections are sorted by name and keys within each section are sorted, which puts the
     * section-less prefix first because the empty string sorts before every other name. Two runs
     * of the same program produce the same file, so a configuration file can be generated into a
     * repository without a spurious diff on every run.
     *
     * <p>A key, value or section name that would not survive the round trip is refused rather
     * than written and misread. A line break would become two lines; leading or trailing
     * whitespace would be trimmed away on the way back in; a key containing {@code =} would be
     * split at the wrong place; a key starting with {@code ;}, {@code #} or {@code [} would be
     * read as a comment or a header. Writing any of those produces a file that reads back as
     * something else, so it is an error here.
     */
    private static String iniWrite(Object value, Span span) {
        if (!(value instanceof Map<?, ?> sections)) {
            throw new OqError("ini_write() needs a map of sections, got "
                    + Values.typeName(value), span,
                    "The shape is {\"section\": {\"key\": \"value\"}}, with the keys that belong "
                            + "to no section under \"\".");
        }
        TreeMap<String, Map<?, ?>> ordered = new TreeMap<>();
        for (Map.Entry<?, ?> entry : sections.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new OqError("ini_write() needs section names to be text, got "
                        + Values.typeName(entry.getKey()), span);
            }
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                throw new OqError("ini_write() needs each section to be a map, got "
                        + Values.typeName(entry.getValue()), span,
                        "The section '" + name + "' is not a map of keys to values.");
            }
            ordered.put(name, body);
        }

        StringBuilder out = new StringBuilder();
        boolean firstBlock = true;
        for (Map.Entry<String, Map<?, ?>> section : ordered.entrySet()) {
            String name = section.getKey();
            if (!name.isEmpty()) refuseSectionName(name, span);
            TreeMap<String, String> keys = sortedKeys(name, section.getValue(), span);
            if (name.isEmpty() && keys.isEmpty()) continue;
            if (!firstBlock) out.append('\n');
            firstBlock = false;
            if (!name.isEmpty()) out.append('[').append(name).append("]\n");
            for (Map.Entry<String, String> key : keys.entrySet()) {
                out.append(key.getKey()).append(" = ").append(key.getValue()).append('\n');
            }
        }
        return bounded("ini_write", out, span);
    }

    private static void refuseSectionName(String name, Span span) {
        if (!name.equals(name.strip())) {
            throw new OqError("ini_write() cannot write the section name \"" + name + "\"", span,
                    "A section name is trimmed when it is read back, so leading or trailing "
                            + "whitespace would be lost. Remove it.");
        }
        refuseLineBreak("ini_write() cannot write the section name \"" + name + "\"", name, span);
        if (name.indexOf(']') >= 0) {
            throw new OqError("ini_write() cannot write the section name \"" + name + "\"", span,
                    "A ']' in the name would end the header early. Rename the section.");
        }
    }

    private static TreeMap<String, String> sortedKeys(String section, Map<?, ?> body, Span span) {
        TreeMap<String, String> keys = new TreeMap<>();
        for (Map.Entry<?, ?> entry : body.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new OqError("ini_write() needs keys to be text, got "
                        + Values.typeName(entry.getKey()), span);
            }
            String value = Values.display(entry.getValue());
            refuseKey(section, key, span);
            refuseLineBreak("ini_write() cannot write the value of '" + key + "'", value, span);
            if (!value.equals(value.strip())) {
                throw new OqError("ini_write() cannot write the value of '" + key
                        + "' in \"" + section + "\": it starts or ends with whitespace", span,
                        "A value is trimmed when it is read back, so the whitespace would be "
                                + "lost. Trim it before writing.");
            }
            keys.put(key, value);
        }
        return keys;
    }

    private static void refuseKey(String section, String key, Span span) {
        String where = "\"" + section + "\"";
        if (key.isEmpty()) {
            throw new OqError("ini_write() cannot write an empty key in " + where, span,
                    "Every line needs a name before the '='.");
        }
        refuseLineBreak("ini_write() cannot write the key '" + key + "' in " + where, key, span);
        if (!key.equals(key.strip())) {
            throw new OqError("ini_write() cannot write the key '" + key + "' in " + where, span,
                    "A key is trimmed when it is read back, so the whitespace would be lost.");
        }
        if (key.indexOf('=') >= 0) {
            throw new OqError("ini_write() cannot write the key '" + key + "' in " + where, span,
                    "The first '=' on the line separates the key from the value, so a key "
                            + "containing one would be split in the wrong place.");
        }
        char first = key.charAt(0);
        if (first == ';' || first == '#' || first == '[') {
            throw new OqError("ini_write() cannot write the key '" + key + "' in " + where, span,
                    "A line starting with '" + first + "' is read back as a comment or a section "
                            + "header. Rename the key.");
        }
    }

    private static void refuseLineBreak(String message, String value, Span span) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new OqError(message + ": it contains a line break", span,
                    "An INI file has one key per line, and this format has no escape for a "
                            + "line break. Write \\n and unescape it after reading.");
        }
    }

    // ---------------------------------------------------- Java properties

    /**
     * A flat map from the {@code .properties} grammar, read in full.
     *
     * <p>The grammar is small and entirely mechanical, which is why it is implemented rather
     * than approximated: leading whitespace is ignored, {@code #} or {@code !} starts a comment,
     * a key ends at the first unescaped {@code =}, {@code :} or whitespace, and an odd number of
     * backslashes at the end of a line continues it onto the next with that line's leading
     * whitespace removed. A line whose only content is a backslash is therefore a real way to
     * join two long lines, and a reader that did not implement it would produce a truncated
     * value.
     *
     * <p>Escapes are {@code \t}, {@code \n}, {@code \r}, {@code \f}, {@code \\uXXXX}, and a
     * backslash before any other character, which yields that character as itself. That last
     * rule is not an oversight in the format: it is how {@code \=}, {@code \:} and {@code \#}
     * are written in a key or value.
     *
     * <p>Two behaviours differ from {@link java.util.Properties#load} on purpose, both because
     * that call loses information rather than reporting it. A trailing backslash with nothing
     * after it raises here instead of being dropped, and a bad {@code \\uXXXX} names the line it
     * is on. Duplicate keys are last-wins, as they are there.
     */
    private static OqMap propertiesParse(String source, Span span) {
        String[] lines = source.split("\r\n|\r|\n", -1);
        OqMap result = new OqMap();
        for (int index = 0; index < lines.length; index++) {
            int line = index + 1;
            StringBuilder joined = new StringBuilder();
            // Continuations are joined before anything else looks at the line, so a comment can
            // itself be continued and a continued line is never mistaken for two statements.
            while (true) {
                String natural = lines[index];
                if (!isContinued(natural)) {
                    joined.append(natural);
                    break;
                }
                joined.append(natural, 0, natural.length() - 1);
                index++;
                if (index >= lines.length) {
                    throw new OqError("properties_parse() found a line continuation at the end of "
                            + "the text on line " + line, span,
                            "A trailing backslash continues onto the next line, so something must "
                                    + "follow it. Remove it, or write \\\\ for a literal backslash.");
                }
                // The format drops the next line's leading whitespace, which is what makes a
                // wrapped value read as one value.
                lines[index] = stripLeading(lines[index]);
            }

            String logical = stripLeading(joined.toString());
            if (logical.isEmpty()) continue;
            char first = logical.charAt(0);
            if (first == '#' || first == '!') continue;

            int end = keyEnd(logical);
            String key = unescape(logical.substring(0, end), line, span);
            // One separator, wherever it sits: directly after the key, or after whitespace that
            // follows the key. Both spellings mean the same thing in this format.
            int start = skipWhitespace(logical, end);
            if (start < logical.length()
                    && (logical.charAt(start) == '=' || logical.charAt(start) == ':')) {
                start = skipWhitespace(logical, start + 1);
            }
            String value = start >= logical.length()
                    ? ""
                    : unescape(logical.substring(start), line, span);
            result.put(key, value);
        }
        return result;
    }

    /** Whether the natural line ends with an odd number of backslashes. */
    private static boolean isContinued(String line) {
        int count = 0;
        for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) {
            count++;
        }
        return count % 2 == 1;
    }

    private static String stripLeading(String line) {
        int start = 0;
        while (start < line.length() && Character.isWhitespace(line.charAt(start))) start++;
        return line.substring(start);
    }

    private static int skipWhitespace(String line, int from) {
        int index = from;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) index++;
        return index;
    }

    /**
     * Where the key ends: the first unescaped {@code =}, {@code :} or whitespace, or the end of
     * the line. An escaped pair is stepped over as a unit so {@code a\:b} is one key.
     */
    private static int keyEnd(String line) {
        int index = 0;
        while (index < line.length()) {
            char c = line.charAt(index);
            if (c == '\\') {
                index += 2;
                continue;
            }
            if (c == '=' || c == ':' || Character.isWhitespace(c)) return index;
            index++;
        }
        return line.length();
    }

    private static String unescape(String chunk, int line, Span span) {
        if (chunk.indexOf('\\') < 0) return chunk;
        StringBuilder out = new StringBuilder(chunk.length());
        for (int index = 0; index < chunk.length(); index++) {
            char c = chunk.charAt(index);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (index + 1 >= chunk.length()) {
                // Joining continuations strips one backslash per continuation, so a chunk cannot
                // end in an odd run. This is a backstop, not a case the format allows.
                throw new OqError("properties_parse() found a backslash with nothing after it on "
                        + "line " + line, span,
                        "A backslash escapes the character that follows it. Write \\\\ for a "
                                + "literal backslash.");
            }
            char escape = chunk.charAt(index + 1);
            index++;
            switch (escape) {
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (index + 4 >= chunk.length()) {
                        throw new OqError("properties_parse() found a \\u escape with fewer than "
                                + "four hex digits on line " + line, span,
                                "The form is \\uXXXX with exactly four hexadecimal digits.");
                    }
                    int code = 0;
                    for (int digit = 1; digit <= 4; digit++) {
                        int value = Character.digit(chunk.charAt(index + digit), 16);
                        if (value < 0) {
                            throw new OqError("properties_parse() found a \\u escape with a "
                                    + "non-hexadecimal digit on line " + line, span,
                                    "The form is \\uXXXX with exactly four hexadecimal digits, "
                                            + "so only 0-9 and a-f are allowed.");
                        }
                        code = code * 16 + value;
                    }
                    out.append((char) code);
                    index += 4;
                }
                default -> out.append(escape);
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- TOML

    /** Nested arrays beyond this depth are refused rather than recursed into. */
    static final int MAX_NESTING = 64;

    private static final Pattern TOML_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}([Tt ].*)?");
    private static final Pattern TOML_TIME = Pattern.compile("\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");
    private static final Pattern TOML_DECIMAL = Pattern.compile("[+-]?(0|[1-9](_?\\d)*)");
    private static final Pattern TOML_HEX = Pattern.compile("0x[0-9A-Fa-f](_?[0-9A-Fa-f])*");
    private static final Pattern TOML_OCTAL = Pattern.compile("0o[0-7](_?[0-7])*");
    private static final Pattern TOML_BINARY = Pattern.compile("0b[01](_?[01])*");
    private static final Pattern TOML_FLOAT = Pattern.compile(
            "[+-]?(0|[1-9](_?\\d)*)"
                    + "((\\.\\d(_?\\d)*)([eE][+-]?\\d(_?\\d)*)?|([eE][+-]?\\d(_?\\d)*))");

    /**
     * One value at a dotted path, out of a document that is read in full first.
     *
     * <p>The whole document is parsed rather than scanned up to the key, because a value cannot
     * be found without knowing which table it is in, and a table's name is only known once the
     * headers before it have been read. Reading the whole file also means a syntax error
     * anywhere is reported, not just the part in front of the requested key.
     *
     * <p>What is supported: {@code [table]} headers with dotted and quoted names, keys that are
     * bare or quoted, basic strings with the standard escapes, literal strings, integers in
     * decimal, hexadecimal, octal and binary with underscores, floats, {@code inf}/{@code nan},
     * booleans, and arrays of those including nested arrays up to {@link #MAX_NESTING} levels.
     *
     * <p>What is refused by name: tables of tables ({@code [[x]]}), inline tables
     * ({@code x = {y = 1}}), dates and times, and multi-line strings. None of these can be
     * returned as the language's existing types without a decision the language has not made:
     * a date would have to become text that no longer compares as a date or a number that
     * silently drops the time zone, and an inline table would have to become a map that is
     * indistinguishable from a table header. Refusing names the construct and the line, so the
     * file can be changed or read another way.
     *
     * <p>A path that names a table rather than a value returns that table as a map of values
     * already converted the same way, which is what a caller walking a structure needs and is
     * not a guess about the format.
     */
    private static Object tomlGet(String source, String dottedKey, Span span) {
        // Line breaks are normalised before scanning so that CRLF and a lone CR count as one
        // line, and so every reported line number matches an editor on any platform.
        String normalised = source.replace("\r\n", "\n").replace('\r', '\n');
        OqMap document = new Toml(normalised, span, false).parse();
        List<String> path = Toml.pathOf(dottedKey, span);
        Object node = document;
        for (int index = 0; index < path.size(); index++) {
            if (!(node instanceof OqMap map)) {
                throw new OqError("toml_get() found no key '" + dottedKey + "'", span,
                        "'" + String.join(".", path.subList(0, index)) + "' is a value, so it "
                                + "has no keys under it. Check the dotted key.");
            }
            if (!map.containsKey(path.get(index))) {
                throw new OqError("toml_get() found no key '" + dottedKey + "'", span,
                        "The document has no '" + String.join(".", path.subList(0, index + 1))
                                + "'. Check the spelling, including case.");
            }
            node = map.get(path.get(index));
        }
        return node;
    }

    /**
     * A scanning reader for the supported subset of TOML.
     *
     * <p>Values are built as the scan goes rather than through an intermediate tree, because
     * every value here converts straight into a language value and there is nothing to gain from
     * a second representation.
     */
    private static final class Toml {
        private final String source;
        private final Span span;
        private final boolean keyArgument;
        private final OqMap root = new OqMap();
        private final Map<List<String>, Boolean> headers = new LinkedHashMap<>();
        private OqMap current;
        private int index;
        private int line = 1;

        Toml(String source, Span span, boolean keyArgument) {
            this.source = source;
            this.span = span;
            this.keyArgument = keyArgument;
            this.current = root;
        }

        /** Parses a dotted key on its own, refusing anything after the last name. */
        static List<String> pathOf(String text, Span span) {
            Toml parser = new Toml(text, span, true);
            List<String> path = parser.keyPath("a key");
            parser.skipSpaces();
            if (parser.index < text.length()) {
                throw new OqError("not a dotted key: '" + text + "'", span,
                        "A dotted key is names separated by '.', such as tool.poetry.version. "
                                + "Quote a name that contains a '.' of its own.");
            }
            return path;
        }

        OqMap parse() {
            while (true) {
                skipBlank();
                if (index >= source.length()) return root;
                if (source.charAt(index) == '[') {
                    header();
                } else {
                    assignment();
                    endOfLine("value");
                }
            }
        }

        private void header() {
            int at = line;
            index++;
            if (index < source.length() && source.charAt(index) == '[') {
                throw unsupported("tables of tables ([[...]])", at);
            }
            List<String> path = keyPath("a table name");
            if (index >= source.length() || source.charAt(index) != ']') {
                throw new OqError("toml_get() expected ']' after a table name on line " + at,
                        span, "A table header is [name] on one line.");
            }
            index++;
            endOfLine("table header");
            if (headers.put(List.copyOf(path), Boolean.TRUE) != null) {
                throw new OqError("toml_get() found the table [" + String.join(".", path)
                        + "] twice, the second time on line " + at, span,
                        "Merging a repeated header is not implemented, so its keys would be "
                                + "ambiguous. Give each table one header.");
            }
            current = descend(root, path.subList(0, path.size() - 1), at);
            String last = path.get(path.size() - 1);
            Object existing = current.get(last);
            if (existing instanceof OqMap) {
                throw new OqError("toml_get() does not merge tables (line " + at + ": ["
                        + String.join(".", path) + "])", span,
                        "This table already exists, either from its own header or because it "
                                + "was created by a header under it. Merging the two is not "
                                + "implemented, so its keys would be ambiguous.");
            }
            if (current.containsKey(last)) {
                throw new OqError("toml_get() found '" + last + "' used as a value and as a "
                        + "table on line " + at, span,
                        "A name is either a value or a table, never both.");
            }
            OqMap table = new OqMap();
            current.put(last, table);
            current = table;
        }

        private void assignment() {
            int at = line;
            List<String> path = keyPath("a key");
            if (index >= source.length() || source.charAt(index) != '=') {
                throw new OqError("toml_get() expected '=' after a key on line " + at, span,
                        "Every line outside a table header is key = value.");
            }
            index++;
            skipSpaces();
            Object value = value(0);
            OqMap target = descend(current, path.subList(0, path.size() - 1), at);
            String last = path.get(path.size() - 1);
            if (target.containsKey(last)) {
                throw new OqError("toml_get() found the key '" + last + "' twice on line " + at,
                        span, "A TOML key appears once. Remove one of the copies.");
            }
            target.put(last, value);
        }

        private OqMap descend(OqMap from, List<String> path, int at) {
            OqMap node = from;
            for (int position = 0; position < path.size(); position++) {
                String segment = path.get(position);
                Object existing = node.get(segment);
                if (existing == null && !node.containsKey(segment)) {
                    OqMap child = new OqMap();
                    node.put(segment, child);
                    node = child;
                } else if (existing instanceof OqMap child) {
                    node = child;
                } else {
                    throw new OqError("toml_get() found '" + segment + "' used as a value and as "
                            + "a table on line " + at, span,
                            "A name is either a value or a table, never both.");
                }
            }
            return node;
        }

        private List<String> keyPath(String what) {
            List<String> segments = new ArrayList<>();
            while (true) {
                skipSpaces();
                if (index >= source.length()) {
                    throw new OqError("toml_get() expected " + what + position() + " and reached "
                            + "the end of the text", span,
                            "A key needs a name. A table header also needs its closing ']'.");
                }
                char c = source.charAt(index);
                if (c == '"') {
                    if (source.startsWith("\"\"\"", index)) {
                        throw unsupported("multi-line strings", line);
                    }
                    segments.add(basicString());
                } else if (c == '\'') {
                    if (source.startsWith("'''", index)) {
                        throw unsupported("multi-line strings", line);
                    }
                    segments.add(literalString());
                } else if (isBareKey(c)) {
                    int start = index;
                    while (index < source.length() && isBareKey(source.charAt(index))) index++;
                    segments.add(source.substring(start, index));
                } else {
                    throw new OqError("toml_get() expected " + what + position() + ", found '"
                            + c + "'", span,
                            "Bare keys use letters, digits, '_' and '-'. Quote anything else.");
                }
                skipSpaces();
                if (index < source.length() && source.charAt(index) == '.') {
                    index++;
                    continue;
                }
                return segments;
            }
        }

        private Object value(int depth) {
            if (index >= source.length()) {
                throw new OqError("toml_get() expected a value" + position() + " and reached the "
                        + "end of the text", span, "A key needs a value after the '='.");
            }
            char c = source.charAt(index);
            if (c == '"') {
                if (source.startsWith("\"\"\"", index)) {
                    throw unsupported("multi-line strings", line);
                }
                return basicString();
            }
            if (c == '\'') {
                if (source.startsWith("'''", index)) {
                    throw unsupported("multi-line strings", line);
                }
                return literalString();
            }
            if (c == '[') return array(depth + 1);
            if (c == '{') throw unsupported("inline tables ({...})", line);
            return scalar();
        }

        private List<Object> array(int depth) {
            int at = line;
            if (depth > MAX_NESTING) {
                throw new OqError("toml_get() will not read an array nested more than "
                        + MAX_NESTING + " deep (line " + at + ")", span,
                        "The limit exists so a corrupt or hostile file cannot exhaust the stack. "
                                + "Flatten the array, or read it another way.");
            }
            index++;
            List<Object> items = new ArrayList<>();
            while (true) {
                skipBlank();
                if (index >= source.length()) {
                    throw new OqError("toml_get() reached the end of the text inside the array "
                            + "that began on line " + at, span,
                            "An array needs its closing ']'. A missing bracket swallows the rest "
                                    + "of the file otherwise.");
                }
                if (source.charAt(index) == ']') {
                    index++;
                    return items;
                }
                items.add(value(depth));
                skipBlank();
                if (index < source.length() && source.charAt(index) == ',') {
                    index++;
                    continue;
                }
                if (index < source.length() && source.charAt(index) == ']') {
                    index++;
                    return items;
                }
                throw new OqError("toml_get() expected ',' or ']' in the array that began on line "
                        + at + ", found " + (index >= source.length() ? "the end of the text"
                        : "'" + source.charAt(index) + "'"), span,
                        "Array items are separated by commas, and a trailing comma is allowed.");
            }
        }

        private Object scalar() {
            int at = line;
            int start = index;
            while (index < source.length() && isScalar(source.charAt(index))) index++;
            String token = source.substring(start, index);
            if (token.isEmpty()) {
                throw new OqError("toml_get() expected a value" + position() + ", found '"
                        + source.charAt(index) + "'", span,
                        "Strings need quotes. Bare words are only true, false, inf and nan.");
            }
            if (token.equals("true")) return Boolean.TRUE;
            if (token.equals("false")) return Boolean.FALSE;
            if (token.equals("inf") || token.equals("+inf")) return Double.POSITIVE_INFINITY;
            if (token.equals("-inf")) return Double.NEGATIVE_INFINITY;
            // A double has no signed NaN, so all three spellings come back as NaN.
            if (token.equals("nan") || token.equals("+nan") || token.equals("-nan")) {
                return Double.NaN;
            }
            if (TOML_DATE.matcher(token).matches() || TOML_TIME.matcher(token).matches()) {
                throw unsupported("dates and times ('" + token + "')", at);
            }
            if (TOML_HEX.matcher(token).matches()) return integer(token, 16, at);
            if (TOML_OCTAL.matcher(token).matches()) return integer(token, 8, at);
            if (TOML_BINARY.matcher(token).matches()) return integer(token, 2, at);
            if (TOML_DECIMAL.matcher(token).matches()) return integer(token, 10, at);
            if (TOML_FLOAT.matcher(token).matches()) {
                try {
                    return Double.parseDouble(token.replace("_", ""));
                } catch (NumberFormatException e) {
                    throw new OqError("toml_get() cannot read the number '" + token + "' on line "
                            + at, span, "The value is outside the range a decimal can hold.");
                }
            }
            throw new OqError("toml_get() found a value it does not support on line " + at
                    + ": " + token, span,
                    "Supported values are quoted strings, integers, floats, booleans and arrays "
                            + "of those. Quote the value if it is meant as text.");
        }

        private Long integer(String token, int radix, int at) {
            String body = token.replace("_", "");
            boolean negative = body.startsWith("-");
            boolean signed = negative || body.startsWith("+");
            String digits = signed ? body.substring(1) : body;
            if (radix != 10) digits = digits.substring(2);
            BigInteger value = new BigInteger(digits, radix);
            if (negative) value = value.negate();
            if (value.bitLength() > 63) {
                throw new OqError("toml_get() found an integer that does not fit in 64 bits on "
                        + "line " + at + ": " + token, span,
                        "Numbers are 64-bit signed integers here. Write it as a float, or keep "
                                + "the value as quoted text.");
            }
            return value.longValue();
        }

        private String basicString() {
            int at = line;
            index++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (index >= source.length()) {
                    throw new OqError("toml_get() reached the end of the text inside the string "
                            + "that began on line " + at, span,
                            "A basic string needs its closing double quote.");
                }
                char c = source.charAt(index);
                if (c == '"') {
                    index++;
                    return out.toString();
                }
                if (c == '\n') {
                    throw new OqError("toml_get() found a line break inside the string that began "
                            + "on line " + at, span,
                            "Basic strings are one line. Write \\n for a line break; multi-line "
                                    + "strings are not supported.");
                }
                if (c != '\\') {
                    out.append(c);
                    index++;
                    continue;
                }
                int escapeLine = line;
                index++;
                if (index >= source.length()) {
                    throw new OqError("toml_get() found a backslash at the end of the text on "
                            + "line " + escapeLine, span,
                            "A backslash escapes the character after it. Write \\\\ for a "
                                    + "literal backslash.");
                }
                char escape = source.charAt(index);
                index++;
                switch (escape) {
                    case 'b' -> out.append('\b');
                    case 't' -> out.append('\t');
                    case 'n' -> out.append('\n');
                    case 'f' -> out.append('\f');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'u' -> out.appendCodePoint(codePoint(4, escapeLine));
                    case 'U' -> out.appendCodePoint(codePoint(8, escapeLine));
                    default -> throw new OqError("toml_get() found an unsupported escape '\\"
                            + escape + "' on line " + escapeLine, span,
                            "Basic strings allow \\b \\t \\n \\f \\r \\\" \\\\ \\uXXXX and "
                                    + "\\UXXXXXXXX. Write a literal backslash as \\\\.");
                }
            }
        }

        private String literalString() {
            int at = line;
            index++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (index >= source.length()) {
                    throw new OqError("toml_get() reached the end of the text inside the literal "
                            + "string that began on line " + at, span,
                            "A literal string needs its closing single quote.");
                }
                char c = source.charAt(index);
                if (c == '\'') {
                    index++;
                    return out.toString();
                }
                if (c == '\n') {
                    throw new OqError("toml_get() found a line break inside the literal string "
                            + "that began on line " + at, span,
                            "Literal strings are one line. Use a basic string with \\n, or read "
                                    + "the text another way.");
                }
                out.append(c);
                index++;
            }
        }

        private int codePoint(int digits, int at) {
            if (index + digits > source.length()) {
                throw new OqError("toml_get() found a \\u escape with fewer than " + digits
                        + " hex digits on line " + at, span,
                        "The forms are \\uXXXX and \\UXXXXXXXX, with exactly that many hex digits.");
            }
            int value = 0;
            for (int position = 0; position < digits; position++) {
                int digit = Character.digit(source.charAt(index + position), 16);
                if (digit < 0) {
                    throw new OqError("toml_get() found a \\u escape with a non-hexadecimal digit "
                            + "on line " + at, span,
                            "The forms are \\uXXXX and \\UXXXXXXXX, so only 0-9 and a-f are "
                                    + "allowed.");
                }
                value = value * 16 + digit;
            }
            index += digits;
            if (!Character.isValidCodePoint(value) || (value >= 0xD800 && value <= 0xDFFF)) {
                throw new OqError("toml_get() found a \\u escape that is not a character on line "
                        + at, span, "Surrogates and values above 10FFFF are not characters.");
            }
            return value;
        }

        private void endOfLine(String after) {
            skipSpaces();
            if (index < source.length() && source.charAt(index) == '#') {
                while (index < source.length() && source.charAt(index) != '\n') index++;
            }
            if (index >= source.length()) return;
            if (source.charAt(index) != '\n') {
                throw new OqError("toml_get() found text after the " + after + position() + ": '"
                        + source.charAt(index) + "'", span,
                        "One statement per line. A '#' starts a comment.");
            }
            index++;
            line++;
        }

        private void skipSpaces() {
            while (index < source.length()
                    && (source.charAt(index) == ' ' || source.charAt(index) == '\t')) {
                index++;
            }
        }

        /** Skips spaces, line breaks and comments, which is what an array and a statement allow. */
        private void skipBlank() {
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c == ' ' || c == '\t') {
                    index++;
                } else if (c == '\n') {
                    index++;
                    line++;
                } else if (c == '#') {
                    while (index < source.length() && source.charAt(index) != '\n') index++;
                } else {
                    return;
                }
            }
        }

        private String position() {
            return keyArgument ? "" : " on line " + line;
        }

        private OqError unsupported(String what, int at) {
            return new OqError("toml_get() does not support " + what + " (line " + at + ")", span,
                    "This reader covers quoted strings, integers, floats, booleans and arrays of "
                            + "those, under [table] headers. Read the value another way rather "
                            + "than receiving one that is not what the file says.");
        }

        private static boolean isBareKey(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
        }

        private static boolean isScalar(char c) {
            return isBareKey(c) || c == '+' || c == '.' || c == ':';
        }
    }

    // -------------------------------------------------------------- shared

    private static String bounded(String builtin, StringBuilder out, Span span) {
        String result = out.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > Stdlib.MAX_TEXT_BYTES) {
            throw new OqError(builtin + "() produced more than " + Stdlib.MAX_TEXT_BYTES
                    + " bytes", span,
                    "Write the file in pieces instead of making one string this large.");
        }
        return result;
    }

    private static String text(Object value, String builtin, Span span) {
        if (value instanceof String s) return s;
        throw new OqError(builtin + "() needs text, got " + Values.typeName(value), span,
                "Quote the value, or convert it to text first.");
    }
}
