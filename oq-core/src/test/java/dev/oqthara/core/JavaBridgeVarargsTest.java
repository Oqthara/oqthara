package dev.oqthara.core;

import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A Java varargs parameter arrives at reflection as a trailing array, so an overload table that
 * demanded one argument per declared parameter could never match {@code String.format("%s", "a")}
 * — the call the bridge exists to make possible. These cases pin both halves of the repair: the
 * tail is packed into an array, and a call that declares no tail still matches.
 */
class JavaBridgeVarargsTest {
    private final Span span = Span.synthetic();
    private final Path cwd = Path.of(".");

    @Test
    void aStaticVarargsMethodPacksItsTailIntoAnArray() {
        assertEquals("a-b", JavaBridge.callStatic(String.class, "format", List.of("%s-%s", "a", "b"), span, cwd));
    }

    @Test
    void aStaticVarargsMethodWithAnEmptyTailStillMatches() {
        // The arguments fit, so the packed array is empty. Scoring must not read a component
        // type for an argument that was never passed - that used to be an IndexOutOfBounds.
        assertEquals("no args here", JavaBridge.callStatic(String.class, "format", List.of("no args here"), span, cwd));
    }

    @Test
    void aVarargsMethodTakingObjectsAcceptsHeterogeneousArguments() {
        assertEquals("1-2.5-true", JavaBridge.callStatic(String.class, "format", List.of("%s-%s-%s", 1L, 2.5, true), span, cwd));
    }

    @Test
    void anInstanceVarargsMethodPacksItsTailToo() {
        // Formatter.format(String, Object...) is the clearest instance varargs case in the JDK,
        // and it exercises the method path rather than the static one.
        var formatter = new java.util.Formatter();
        try {
            // Formatter.format returns the formatter, so the packed tail is proved by the text
            // it accumulated rather than by the return value.
            JavaBridge.callMethod(formatter, "format", List.of("%s-%s", "x", "y"), span, cwd);
            assertEquals("x-y", formatter.toString());
        } finally {
            formatter.close();
        }
    }

    @Test
    void aConstructorWithoutAVarargsTailIsStillConstructed() {
        Object value = JavaBridge.construct(java.util.ArrayList.class, List.of(), span, cwd);
        assertInstanceOf(java.util.ArrayList.class, value);
    }

    @Test
    void anOverloadIsChosenByFitRatherThanByDeclarationOrder() {
        // Integer.valueOf has both valueOf(int) and valueOf(String). Passing a Long must pick
        // the numeric one, and passing text must pick the other; a scoring bug shows up here as
        // a NumberFormatException from the wrong overload.
        assertEquals(5L, JavaBridge.callStatic(Integer.class, "valueOf", List.of(5L), span, cwd));
        assertEquals(5L, JavaBridge.callStatic(Integer.class, "valueOf", List.of("5"), span, cwd));
    }

    @Test
    void aFixedArityCallIsUnaffectedByTheVarargsPath() {
        assertEquals("ABC", JavaBridge.callMethod("abc", "toUpperCase", List.of(), span, cwd));
        assertEquals(3L, JavaBridge.callMethod("abc", "length", List.of(), span, cwd));
        assertEquals(List.of("a", "b"), JavaBridge.callStatic(Arrays.class, "asList", List.of("a", "b"), span, cwd));
    }

    @Test
    void aMethodThatAcceptsNothingLikeTheseArgumentsIsStillNotFound() {
        assertSame(JavaBridge.NOT_FOUND,
                JavaBridge.callStatic(String.class, "format", List.of(), span, cwd));
    }
}
