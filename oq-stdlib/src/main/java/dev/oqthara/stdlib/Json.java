package dev.oqthara.stdlib;

import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Json {
    private final String src;
    private final Span span;
    private int pos = 0;

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
                    case 'u' -> {
                        if (pos + 4 > src.length()) throw fail("invalid unicode escape");
                        String hex = src.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw fail("invalid unicode escape \\u" + hex);
                        }
                    }
                    default -> throw fail("unknown escape '\\" + esc + "'");
                }
                continue;
            }
            sb.append(c);
        }
        throw fail("unterminated text in JSON");
    }

    private Object number() {
        int start = pos;
        if (peek() == '-') pos++;
        // JSON requires at least one integer digit before any fraction or exponent.
        if (pos >= src.length() || !Character.isDigit(src.charAt(pos))) {
            throw fail("invalid number");
        }
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        boolean decimal = false;
        if (pos < src.length() && src.charAt(pos) == '.') {
            decimal = true;
            pos++;
            if (pos >= src.length() || !Character.isDigit(src.charAt(pos))) {
                throw fail("invalid number: expected a digit after '.'");
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            decimal = true;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            if (pos >= src.length() || !Character.isDigit(src.charAt(pos))) {
                throw fail("invalid number: expected a digit in the exponent");
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        }
        String text = src.substring(start, pos);
        try {
            if (decimal) return Double.parseDouble(text);
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            // Out-of-range integers fall back to a double, which never throws for valid
            // numeric syntax; guard anyway so a malformed value cannot escape as a crash.
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException again) {
                throw fail("invalid number '" + text + "'");
            }
        }
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

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
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
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
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
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (i++ > 0) sb.append(',');
                if (indent >= 0) sb.append('\n').append("  ".repeat(indent + 1));
                writeString(sb, Values.display(entry.getKey()));
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
