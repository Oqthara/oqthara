package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encoding and hashing builtins, checked against published values rather than against a second
 * implementation of the same algorithm.
 *
 * <p>The digests below are the standard test vectors for the ASCII text {@code "abc"}, which is
 * also what {@code sha256sum}, {@code sha1sum} and {@code md5sum} print for a file holding it.
 * Comparing the implementation to itself would prove only that it is consistent.
 */
class EncodingAndHashingTest {
    private static Interpreter interpreter;

    private static synchronized Interpreter host() {
        if (interpreter == null) {
            PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            Interpreter created = new Interpreter(sink, sink,
                    new java.io.BufferedReader(new java.io.StringReader("")),
                    List.of(), Path.of("."), new MethodResolverImpl());
            Stdlib.install(created);
            interpreter = created;
        }
        return interpreter;
    }

    private static Object call(String name, Object... args) {
        OqCallable callable = host().builtins().get(name);
        // A plain ArrayList, not List.of: `nothing` is null and List.of rejects it.
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(host(), values, Span.synthetic());
    }

    @Test
    void sha256MatchesThePublishedVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                call("sha256", "abc"));
    }

    @Test
    void sha256OfEmptyTextIsThePublishedEmptyVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                call("sha256", ""));
    }

    @Test
    void sha1AndMd5MatchTheirPublishedVectors() {
        // Kept because protocols and file formats still name them, not because they are safe
        // for a secret; the reference says so.
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", call("sha1", "abc"));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", call("md5", "abc"));
    }

    @Test
    void hmacSha256MatchesThePublishedRfc4231Vector() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?".
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                call("hmac_sha256", "Jefe", "what do ya want for nothing?"));
    }

    @Test
    void hashingBytesTheUtf8EncodingOfTheText() {
        // "é" is two UTF-8 bytes, c3 a9. A platform-default charset would give a different
        // answer on a machine that is not UTF-8, and this value is what sha256sum reports for
        // a UTF-8 file holding it.
        assertEquals("4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c",
                call("sha256", "é"));
    }

    @Test
    void hexRoundTripsAndIsLowercase() {
        assertEquals("616263", call("hex_encode", "abc"));
        assertEquals("abc", call("hex_decode", "616263"));
    }

    @Test
    void base64RoundTripsAtEveryLength() {
        for (int n = 0; n <= 12; n++) {
            String text = "x".repeat(n);
            assertEquals(text, call("base64_decode", call("base64_encode", text)),
                    "round trip failed at length " + n);
        }
    }

    @Test
    void encodingAndDecodingPreserveAstralCharacters() {
        // One astral character is four UTF-8 bytes and two Java chars, so a mistake in either
        // direction shows up here and not at length one.
        String text = "é😀";
        assertEquals(text, call("hex_decode", call("hex_encode", text)));
        assertEquals(text, call("base64_decode", call("base64_encode", text)));
    }

    @Test
    void aNonHexDigitIsReportedWithItsOffset() {
        OqError error = assertThrows(OqError.class, () -> call("hex_decode", "61zz"));
        assertTrue(error.getMessage().contains("offset 2"), error.getMessage());
    }

    @Test
    void anOddLengthHexValueIsRefusedRatherThanTruncated() {
        OqError error = assertThrows(OqError.class, () -> call("hex_decode", "abc"));
        assertTrue(error.getMessage().contains("even number of digits"), error.getMessage());
    }

    @Test
    void uppercaseHexIsRefusedSoTheSpellingIsFixed() {
        // Accepting both cases would make two encodings of the same bytes compare unequal
        // unless every caller normalised first.
        OqError error = assertThrows(OqError.class, () -> call("hex_decode", "ABC1"));
        assertTrue(error.getMessage().contains("offset 0"), error.getMessage());
    }

    @Test
    void aNonBase64CharacterIsReportedWithItsOffset() {
        OqError error = assertThrows(OqError.class, () -> call("base64_decode", "YW!j"));
        assertTrue(error.getMessage().contains("offset 2"), error.getMessage());
    }

    @Test
    void unpaddedBase64IsRefusedRatherThanGuessed() {
        // The platform decoder accepts "YWJ" and returns "ab"; every encoder writes padding,
        // so a value without it is truncated rather than shorter.
        OqError error = assertThrows(OqError.class, () -> call("base64_decode", "YWJ"));
        assertTrue(error.getMessage().contains("divisible by 4"), error.getMessage());
    }

    @Test
    void aNumberIsRefusedWhereTextIsNeeded() {
        // Values.display would encode "5" and hide the mistake; these functions are about
        // bytes, so the type is checked.
        for (String name : List.of("sha256", "sha1", "md5", "hex_encode", "base64_encode")) {
            OqError error = assertThrows(OqError.class, () -> call(name, 5L), name);
            assertTrue(error.getMessage().contains("needs text"), name + ": " + error.getMessage());
        }
    }

    @Test
    void anEmptyHmacKeyIsRefused() {
        OqError error = assertThrows(OqError.class, () -> call("hmac_sha256", "", "message"));
        assertTrue(error.getMessage().contains("non-empty key"), error.getMessage());
    }

    @Test
    void secureRandomHexProducesTheRequestedNumberOfBytesInLowerCaseHex() {
        String value = (String) call("secure_random_hex", 8L);
        assertEquals(16, value.length());
        assertTrue(value.matches("[0-9a-f]+"), value);
    }

    @Test
    void twoSecureRandomDrawsDiffer() {
        // A generator seeded identically per call would pass the shape check above.
        assertNotEquals(call("secure_random_hex", 16L), call("secure_random_hex", 16L));
    }

    @Test
    void aByteCountOutsideTheBudgetIsRefused() {
        for (long bad : new long[]{-1L, 4097L}) {
            OqError error = assertThrows(OqError.class, () -> call("secure_random_hex", bad));
            assertTrue(error.getMessage().contains("0 to 4096"), error.getMessage());
        }
    }

    @Test
    void anEmptyDrawIsAllowedAndEmpty() {
        assertEquals("", call("secure_random_hex", 0L));
        assertEquals("", call("hex_encode", ""));
    }
}
