package dev.oqthara.stdlib;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cases for defects found by fuzzing the JSON reader.
 */
class JsonFuzzRegressionTest {
    private static Object parse(String text) {
        return Json.parse(text, Span.synthetic());
    }

    private static void reject(String text) {
        OqError error = assertThrows(OqError.class, () -> parse(text),
                "should reject: " + text);
        assertTrue(error.getMessage().startsWith("invalid JSON"), error.getMessage());
    }

    @Test
    void bareDotIsRejectedNotACrash() {
        // Found by Jazzer: "." reached Double.parseDouble and escaped as a raw
        // NumberFormatException, which the CLI would report as an internal error.
        reject(".");
        reject("-.");
        reject("+1");
        reject(".5");
    }

    @Test
    void incompleteFractionsAndExponentsAreRejected() {
        reject("1.");
        reject("1.e5");
        reject("1e");
        reject("1e+");
        reject("-");
    }

    @Test
    void validNumbersStillParse() {
        assertEquals(1L, parse("1"));
        assertEquals(-1L, parse("-1"));
        assertEquals(1.5, parse("1.5"));
        assertEquals(1000.0, parse("1e3"));
        assertEquals(0L, parse("0"));
        assertEquals(0.5, parse("0.5"));
    }

    @Test
    void hugeIntegersBecomeDoublesInsteadOfCrashing() {
        Object value = parse("99999999999999999999999999999999999999");
        assertTrue(value instanceof Double, "expected a double, got " + value.getClass());
    }

    @Test
    void deeplyNestedArraysAreRejectedWithADiagnostic() {
        // Found by Jazzer on CI (where the test JVM stack is smaller): 1884 levels
        // overflowed the reader. The limit makes it deterministic on every JVM.
        for (int depth : List.of(300, 1884, 5000)) {
            String text = "[".repeat(depth) + "]".repeat(depth);
            OqError error = assertThrows(OqError.class, () -> parse(text),
                    "depth " + depth + " must be rejected, not overflow");
            assertTrue(error.getMessage().contains("nested too deeply"), error.getMessage());
        }
        // Realistic documents still parse.
        Object value = parse("[".repeat(100) + "]".repeat(100));
        assertTrue(value instanceof List);
    }

    @Test
    void writerSurvivesEveryParsedValue() {
        for (String text : List.of("1", "1.5", "\"x\"", "[]", "{}", "[[1,2],[3]]",
                "{\"a\":{\"b\":[1,2,3]}}", "true", "null")) {
            Object value = parse(text);
            Json.write(value);
            Json.writePretty(value);
        }
    }

    // --- Duplicate member names (#161) -------------------------------------
    //
    // RFC 8259 leaves the result of a repeated name undefined, and the implementations
    // disagree: some keep the first, some the last, some reject. Emitting or accepting one
    // is therefore the single class of JSON that cannot be interoperable, so both the
    // reader and the writer refuse it and name the offending member.

    @Test
    void repeatedMemberNameIsRejected() {
        OqError error = assertThrows(OqError.class, () -> parse("{\"x\":1,\"x\":2}"),
                "a repeated name must not silently keep one value");
        assertTrue(error.getMessage().contains("duplicate object key 'x'"), error.getMessage());
    }

    @Test
    void duplicateIsDetectedAfterEscapesAreDecoded() {
        // "\u0061" and "a" are the same member name; a byte-wise comparison would miss it.
        OqError error = assertThrows(OqError.class, () -> parse("{\"a\":1,\"\\u0061\":2}"),
                "escaped names must be compared after decoding");
        assertTrue(error.getMessage().contains("duplicate object key 'a'"), error.getMessage());
    }

    @Test
    void distinctNamesAreStillAccepted() {
        Object value = parse("{\"a\":1,\"b\":2,\"A\":3}");
        assertEquals(3, ((java.util.Map<?, ?>) value).size());
    }

    @Test
    void writerRefusesKeysThatCollideAsMemberNames() {
        // The number 1 and the string "1" are distinct map keys but the same member name.
        java.util.Map<Object, Object> map = new java.util.LinkedHashMap<>();
        map.put(1L, "int");
        map.put("1", "text");
        OqError error = assertThrows(OqError.class, () -> Json.write(map),
                "two map keys rendering to one member name would emit duplicate JSON");
        assertTrue(error.getMessage().contains("'1'"), error.getMessage());
    }

    // --- Control characters, whitespace and surrogates (#162) -------------

    @Test
    void rawControlCharactersInTextAreRejected() {
        for (char c = 0; c < 0x20; c++) {
            String text = "[\"" + c + "\"]";
            reject(text);
        }
        // The escaped form of the same characters is valid and still round-trips.
        assertEquals(List.of("\t"), parse("[\"\\t\"]"));
    }

    @Test
    void onlyTheFourJsonWhitespaceCharactersAreSkipped() {
        for (char c : new char[] {' ', '\t', '\n', '\r'}) {
            assertEquals(List.of(1L, 2L), parse("[" + c + "1," + c + "2" + c + "]"));
        }
        // Character.isWhitespace would also accept these; RFC 8259 §2 does not.
        for (char c : new char[] {'\u000B', '\f', '\u00A0', '\u2028', '\u3000'}) {
            reject("[" + c + "1]");
        }
    }

    @Test
    void surrogateEscapesMustFormAPair() {
        OqError lone = assertThrows(OqError.class, () -> parse("[\"\\ud800\"]"));
        assertTrue(lone.getMessage().contains("unpaired high surrogate"), lone.getMessage());
        OqError reverse = assertThrows(OqError.class, () -> parse("[\"\\ude00\"]"));
        assertTrue(reverse.getMessage().contains("unpaired low surrogate"), reverse.getMessage());
        OqError wrongHalf = assertThrows(OqError.class, () -> parse("[\"\\ud83d\\ud83d\"]"));
        assertTrue(wrongHalf.getMessage().contains("not followed by a low surrogate"),
                wrongHalf.getMessage());
        // Two escapes for one astral code point is the valid spelling and decodes to it.
        assertEquals(List.of("\uD83D\uDE00"), parse("[\"\\ud83d\\ude00\"]"));
    }

    @Test
    void rawSurrogatesMustFormAPair() {
        reject("[\"\uD800\"]");
        reject("[\"\uDC00\"]");
        assertEquals(List.of("\uD83D\uDE00"), parse("[\"\uD83D\uDE00\"]"));
    }

    // --- Number grammar (#170) --------------------------------------------

    @Test
    void leadingZeroIsRejected() {
        for (String text : List.of("01", "-01", "{\"n\":01}", "[00]", "01.5", "0e1x")) {
            reject(text);
        }
        // A lone zero is valid, with or without a fraction or exponent.
        assertEquals(0L, parse("0"));
        assertEquals(-0L, parse("-0"));
        assertEquals(0.5, parse("0.5"));
        assertEquals(0.0, parse("0e0"));
    }

    @Test
    void nonAsciiDigitsAreNotNumberDigits() {
        // Character.isDigit accepts these; Long.parseLong then threw a raw
        // NumberFormatException that escaped as an internal error.
        for (String text : List.of("\u0661", "\uFF11", "1\u0662")) {
            reject(text);
        }
    }
}
