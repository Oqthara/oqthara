package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Java method that wants a {@link java.nio.file.Path} is given one through
 * {@code JavaBridge.convert}, and that conversion used to call {@code Path.of} unguarded.
 *
 * <p>The failure is not at the call site. {@code callMethod} reads every overload, converts the
 * arguments, and only then invokes inside a {@code try}; an {@code InvalidPathException} thrown
 * during conversion escapes that {@code catch}, escapes the interpreter, and is reported as
 * "internal error ... This is a bug in Oqthara" with exit 70. The stdlib was guarded for this
 * exact input by {@code OqPaths}; interop was not, which made the PR's claim that the defect
 * class was closed false. These tests pin the interop half.
 */
class JavaBridgePathArgumentTest {
    @Test
    void aNulInAPathArgumentIsAUserErrorNotACrash() {
        OqError error = assertThrows(OqError.class, () -> JavaBridge.callStatic(
                Files.class, "exists", List.of("a\u0000b"), Span.synthetic(), null));
        assertTrue(error.getMessage().contains("is not one"), error.getMessage());
        assertTrue(!error.getMessage().contains("internal error"), error.getMessage());
    }

    @Test
    void theRefusalSaysWhatAPathIsAndWhyThisTextIsNot() {
        OqError error = assertThrows(OqError.class, () -> JavaBridge.callStatic(
                Files.class, "exists", List.of("a\u0000b"), Span.synthetic(), null));
        assertTrue(error.hint() != null && error.hint().contains("NUL"), String.valueOf(error.hint()));
    }

    @Test
    void anOrdinaryPathStillReachesTheMethod() {
        assertDoesNotThrow(() -> JavaBridge.callStatic(
                Files.class, "exists", List.of("/tmp"), Span.synthetic(), null));
    }
}
