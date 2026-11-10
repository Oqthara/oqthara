package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constant-time comparison and password hashing, per ADR-0038.
 *
 * <p>The PBKDF2 expectations are the well-known vectors for the password "password" and the salt
 * "salt" at 1, 2 and 4096 iterations. They were cross-checked against an independent
 * implementation rather than against this one, because a test that asserts what the code already
 * produces proves only that the code is self-consistent.
 *
 * <p>Nothing here measures timing. A unit test cannot reliably distinguish a constant-time
 * comparison from a fast one, and a flaky timing assertion would be worse than none: the property
 * comes from {@code MessageDigest.isEqual}, and what these tests can establish is that the right
 * function is called with the right arguments and answers correctly.
 */
class ConstantTimeAndHashingTest {

    private Interpreter host;

    private Interpreter host() {
        if (host == null) {
            PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            host = new Interpreter(sink, sink,
                    new java.io.BufferedReader(new java.io.StringReader("")),
                    List.of(), java.nio.file.Path.of("."), new MethodResolverImpl());
            Stdlib.install(host);
        }
        return host;
    }

    private Object call(String name, Object... args) {
        OqCallable callable = host().builtins().get(name);
        List<Object> values = new ArrayList<>(args.length);
        for (Object arg : args) values.add(arg);
        return callable.call(host(), values, Span.synthetic());
    }

    // --- secure_equal ------------------------------------------------------------------------

    @Test
    void identicalTextIsEqual() {
        assertEquals(true, call("secure_equal", "a", "a"));
    }

    @Test
    void textDifferingInTheFirstCharacterIsNotEqual() {
        assertEquals(false, call("secure_equal", "a", "b"));
    }

    @Test
    void textDifferingOnlyInTheLastCharacterIsNotEqual() {
        // The case that a naive early-exit comparison would answer fastest, and the reason this
        // function exists.
        assertEquals(false, call("secure_equal", "aaaaaaab", "aaaaaaac"));
    }

    @Test
    void differentLengthsAreNotEqual() {
        assertEquals(false, call("secure_equal", "abc", "ab"));
        assertEquals(false, call("secure_equal", "", "a"));
    }

    @Test
    void twoEmptyTextsAreEqual() {
        assertEquals(true, call("secure_equal", "", ""));
    }

    @Test
    void aComputedMacComparesEqualToItsPublishedValue() {
        // Probed against the built CLI first, and the value is the same one ADR-0038 records.
        assertEquals(true, call("secure_equal",
                call("hmac_sha256", "k", "m"),
                "b60090e3052297aeb5a080889ce2fc4bca957e756faeb4df7d31800ca1e771ec"));
    }

    @Test
    void comparingDifferentEncodingsOfTheSameBytesIsNotEqual() {
        // Hex and base64 of the same input are different text, and this function compares text.
        // A caller mixing encodings has a bug it should not hide.
        assertEquals(false, call("secure_equal",
                call("hex_encode", "abc"), call("base64_encode", "abc")));
    }

    @Test
    void nonTextIsRefused() {
        OqError error = assertThrows(OqError.class, () -> call("secure_equal", 1, 2));
        assertTrue(error.getMessage().contains("needs text"), error.getMessage());
    }

    // --- pbkdf2_sha256 -----------------------------------------------------------------------

    @Test
    void oneIterationMatchesTheKnownVector() {
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
                call("pbkdf2_sha256", "password", "salt", 1L));
    }

    @Test
    void twoIterationsMatchTheKnownVector() {
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
                call("pbkdf2_sha256", "password", "salt", 2L));
    }

    @Test
    void fourThousandAndNinetySixIterationsMatchTheKnownVector() {
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
                call("pbkdf2_sha256", "password", "salt", 4096L));
    }

    @Test
    void theResultIsThirtyTwoBytesOfLowercaseHex() {
        String derived = (String) call("pbkdf2_sha256", "p", "s", 10L);
        assertEquals(64, derived.length());
        assertEquals(derived.toLowerCase(java.util.Locale.ROOT), derived);
    }

    @Test
    void aDifferentSaltGivesADifferentHash() {
        assertFalse(call("pbkdf2_sha256", "password", "salt", 10L)
                .equals(call("pbkdf2_sha256", "password", "other", 10L)));
    }

    @Test
    void aDifferentIterationCountGivesADifferentHash() {
        assertFalse(call("pbkdf2_sha256", "password", "salt", 10L)
                .equals(call("pbkdf2_sha256", "password", "salt", 11L)));
    }

    @Test
    void theSameInputsGiveTheSameHash() {
        assertEquals(call("pbkdf2_sha256", "password", "salt", 10L),
                call("pbkdf2_sha256", "password", "salt", 10L));
    }

    @Test
    void zeroIterationsIsRefused() {
        OqError error = assertThrows(OqError.class,
                () -> call("pbkdf2_sha256", "p", "s", 0L));
        assertTrue(error.getMessage().contains("from 1 to"), error.getMessage());
    }

    @Test
    void anIterationCountAboveTheCeilingIsRefused() {
        OqError error = assertThrows(OqError.class,
                () -> call("pbkdf2_sha256", "p", "s", Stdlib.MAX_PBKDF2_ITERATIONS + 1));
        assertTrue(error.getMessage().contains("look like a hang") || error.hint().contains("hang"),
                error.getMessage() + " / " + error.hint());
    }

    @Test
    void aNegativeIterationCountIsRefused() {
        OqError error = assertThrows(OqError.class,
                () -> call("pbkdf2_sha256", "p", "s", -5L));
        assertTrue(error.getMessage().contains("-5"), error.getMessage());
    }
}
