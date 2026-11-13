package dev.oqthara.stdlib;

import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Json {
    /**
     * Maximum container nesting. JSON from an untrusted source can be astronomically deep,
     * and the reader is recursive, so a limit turns a stack overflow into a clear error.
     * Real documents are far below this.
     */
    private static final int MAX_DEPTH = 256;

    private final String src;
    private final Span span;
    private int pos = 0;
    private int depth = 0;

    private Json(String src, Span span) {
        this.src = src;
        this.span = span;
    }

    public static Object parse(String text, Span span) {
        Json parser = new Json(text, span);
        parser.skipWhitespace();
        Object value = parser.value();
        parser.skipWhitespace();
        if (parser.pos < parser.src.length()) {
            throw parser.fail("unexpected text after JSON value");
        }
        return value;
    }

    private Object value() {
        if (pos >= src.length()) throw fail("unexpected end of JSON");
        if (++depth > MAX_DEPTH) {
            depth--;
            throw fail("JSON is nested too deeply (limit " + MAX_DEPTH + ")");
        }
        try {
            return valueInner();
        } finally {
            depth--;
        }
    }

    private Object valueInner() {
        if (pos >= src.length()) throw fail("unexpected end of JSON");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", true);
            case 'f' -> literal("false", false);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<Object, Object> object() {
        pos++;
        Map<Object, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw fail("object keys must be text");
            String key = string();
            skipWhitespace();
            if (peek() != ':') throw fail("expected ':' after object key");
            pos++;
            skipWhitespace();
            // ADR-0035 records duplicate member names as invalid input. Silently keeping the
            // last value is also the one behaviour that cannot be interoperable, because
            // implementations disagree on which member wins. Checked before the value is
            // read so the reported position is the duplicate key, not the end of its value.
            if (map.containsKey(key)) {
                throw fail("duplicate object key '" + key + "'");
            }
            map.put(key, value());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw fail("expected ',' or '}' in object");
        }
    }

    private List<Object> array() {
        pos++;
        List<Object> items = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return items;
        }
        while (true) {
            skipWhitespace();
            items.add(value());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return items;
            }
            throw fail("expected ',' or ']' in array");
        }
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw fail("unfinished escape in text");
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> appendUnicodeEscape(sb);
                    default -> throw fail("unknown escape '\\" + esc + "'");
                }
                continue;
            }
            // RFC 8259 §7: unescaped control characters (U+0000 through U+001F) are not
            // permitted inside a string. Accepting them yields values that the writer
            // re-escapes, so a parse/print round-trip would silently rewrite the input.
            if (c < 0x20) {
                throw fail("unescaped control character U+" + String.format("%04X", (int) c)
                        + " in text; use an escape such as '\\u" + String.format("%04X", (int) c) + "'");
            }
            // A lone surrogate cannot be encoded as UTF-8 on the way out, so it is a
            // corrupt document rather than a character.
            if (Character.isSurrogate(c)) {
                char next = pos < src.length() ? src.charAt(pos) : '\0';
                if (!Character.isSurrogatePair(c, next)) {
                    throw fail("unpaired surrogate U+" + String.format("%04X", (int) c)
                            + " in text; use a \\u escape for both halves of the pair");
                }
                sb.append(c).append(next);
                pos++;
                continue;
            }
            sb.append(c);
        }
        throw fail("unterminated text in JSON");
    }

    /**
     * Reads the body of a {@code \\u} escape, which {@link #string()} has already located at
     * {@code pos}. JSON permits a surrogate pair to be written as two consecutive escapes; a
     * lone half is rejected because it is not a character and cannot survive a UTF-8 encode.
     */
    private void appendUnicodeEscape(StringBuilder sb) {
        char unit = readHex4();
        if (Character.isLowSurrogate(unit)) {
            throw fail("unpaired low surrogate U+" + String.format("%04X", (int) unit) + " in text");
        }
        if (!Character.isHighSurrogate(unit)) {
            sb.append(unit);
            return;
        }
        if (pos + 1 >= src.length() || src.charAt(pos) != '\\' || src.charAt(pos + 1) != 'u') {
            throw fail("unpaired high surrogate U+" + String.format("%04X", (int) unit)
                    + " in text; escape the low surrogate as a second \\u");
        }
        pos += 2;
        char low = readHex4();
        if (!Character.isLowSurrogate(low)) {
            throw fail("high surrogate U+" + String.format("%04X", (int) unit)
                    + " is not followed by a low surrogate");
        }
        sb.append(unit).append(low);
    }

    private char readHex4() {
        if (pos + 4 > src.length()) throw fail("invalid unicode escape");
        String hex = src.substring(pos, pos + 4);
        char unit;
        try {
            unit = (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw fail("invalid unicode escape \\u" + hex);
        }
        pos += 4;
        return unit;
    }

    private Object number() {
        int start = pos;
        if (peek() == '-') pos++;
        // JSON requires at least one integer digit before any fraction or exponent, and the
        // digits are the ASCII ten: Character.isDigit would also accept Arabic-Indic and
        // fullwidth digits, which Long.parseLong then rejects as a crash rather than a
        // diagnostic.
        if (pos >= src.length() || !isJsonDigit(src.charAt(pos))) {
            throw fail("invalid number");
        }
        if (src.charAt(pos) == '0') {
            pos++;
            // RFC 8259 §6: an integer part is either a lone 0 or a nonzero digit followed by
            // any digits. "01" is therefore invalid, not one.
            if (pos < src.length() && isJsonDigit(src.charAt(pos))) {
                throw fail("invalid number: leading zero in '" + src.substring(start, pos + 1) + "'");
            }
        } else {
            while (pos < src.length() && isJsonDigit(src.charAt(pos))) pos++;
        }
        boolean decimal = false;
        if (pos < src.length() && src.charAt(pos) == '.') {
            decimal = true;
            pos++;
            if (pos >= src.length() || !isJsonDigit(src.charAt(pos))) {
                throw fail("invalid number: expected a digit after '.'");
            }
            while (pos < src.length() && isJsonDigit(src.charAt(pos))) pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            decimal = true;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            if (pos >= src.length() || !isJsonDigit(src.charAt(pos))) {
                throw fail("invalid number: expected a digit in the exponent");
            }
            while (pos < src.length() && isJsonDigit(src.charAt(pos))) pos++;
        }
        String text = src.substring(start, pos);
        try {
            if (decimal) return finite(Double.parseDouble(text), text);
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            // Out-of-range integers fall back to a double, which never throws for valid
            // numeric syntax; guard anyway so a malformed value cannot escape as a crash.
            try {
                return finite(Double.parseDouble(text), text);
            } catch (NumberFormatException again) {
                throw fail("invalid number '" + text + "'");
            }
        }
    }

    /**
     * A parsed number that overflows a double ({@code 1e400}) or has no finite value is not
     * usable JSON: the writer cannot represent it and every round-trip would change it. Reject
     * it with the same diagnostic instead of returning {@code Infinity}/{@code NaN}.
     */
    private Object finite(double value, String text) {
        if (!Double.isFinite(value)) {
            throw fail("number '" + text + "' is out of range");
        }
        return value;
    }

    private Object literal(String text, Object value) {
        if (src.startsWith(text, pos)) {
            pos += text.length();
            return value;
        }
        throw fail("invalid value");
    }

    private char peek() {
        if (pos >= src.length()) throw fail("unexpected end of JSON");
        return src.charAt(pos);
    }

    /**
     * JSON defines exactly four insignificant whitespace characters (RFC 8259 §2). Using
     * {@link Character#isWhitespace} here would also accept a vertical tab, a form feed, a
     * no-break space and the whole Unicode space category, none of which a strict reader
     * may skip.
     */
    private void skipWhitespace() {
        while (pos < src.length() && isJsonWhitespace(src.charAt(pos))) pos++;
    }

    private static boolean isJsonWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isJsonDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private OqError fail(String message) {
        return new OqError("invalid JSON: " + message, span,
                "Check the text passed to json_parse(). Position: character " + (pos + 1) + ".");
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, -1);
        return sb.toString();
    }

    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String s) {
            writeString(sb, s);
            return;
        }
        if (value instanceof Boolean || value instanceof Long) {
            sb.append(value);
            return;
        }
        if (value instanceof Double d) {
            if (!Double.isFinite(d)) {
                // The writer has no source position of its own; the caller's span is not
                // threaded through every recursive call just for this message.
                throw new OqError("cannot write " + Values.display(d) + " as JSON",
                        Span.synthetic());
            }
            if (d == Math.rint(d) && Values.fitsInLong(d)) {
                sb.append(d.longValue());
            } else {
                sb.append(d);
            }
            return;
        }
        if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                if (indent >= 0) sb.append('\n').append("  ".repeat(indent + 1));
                writeValue(sb, list.get(i), indent < 0 ? -1 : indent + 1);
            }
            if (!list.isEmpty() && indent >= 0) sb.append('\n').append("  ".repeat(indent));
            sb.append(']');
            return;
        }
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            // Two distinct map keys can render to the same member name: the number 1 and the
            // string "1" both display as 1. Emitting both would produce JSON that this very
            // reader now rejects (and that no other parser agrees on), so refuse instead.
            Set<String> emitted = new LinkedHashSet<>();
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = Values.display(entry.getKey());
                if (!emitted.add(name)) {
                    throw new OqError("cannot write two map keys as the JSON member '" + name + "'",
                            Span.synthetic());
                }
                if (i++ > 0) sb.append(',');
                if (indent >= 0) sb.append('\n').append("  ".repeat(indent + 1));
                writeString(sb, name);
                sb.append(':');
                if (indent >= 0) sb.append(' ');
                writeValue(sb, entry.getValue(), indent < 0 ? -1 : indent + 1);
            }
            if (!map.isEmpty() && indent >= 0) sb.append('\n').append("  ".repeat(indent));
            sb.append('}');
            return;
        }
        if (value instanceof OqData data) {
            writeValue(sb, data.fields(), indent);
            return;
        }
        if (value instanceof Set<?> set) {
            writeValue(sb, new ArrayList<>(set), indent);
            return;
        }
        writeString(sb, Values.display(value));
    }

    private static void writeString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
