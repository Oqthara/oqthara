package dev.oqthara.core;

import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code getClass()} hands back a class object, and the spec says a returned Java value's
 * methods are called like any other receiver's.
 *
 * <p>They were not. Dispatch treated a {@code Class<?>} receiver as a class <em>reference</em>
 * and asked only for a static method, so {@code String("x").getClass().getName()} reported
 * "Java class String has no static method 'getName'" — a diagnostic about the wrong question.
 * The repair tries statics first, so every call that already worked keeps its meaning, and
 * then asks the class object about itself.
 *
 * <p>The narrowness is the point. {@code Class} is also the doorway to reflection, and enabling
 * its full surface would make {@code getDeclaredMethod} and {@code setAccessible} reachable,
 * which nothing else in the bridge allows. {@link #declaredMemberLookupStaysRefused()} pins
 * that the door is still shut, and {@link #aRefusedLookupSaysItIsReflection()} pins that the
 * refusal explains itself rather than repeating the static-method message.
 */
class JavaBridgeClassObjectTest {
    private final Span span = Span.synthetic();

    @Test
    void aClassNameIsReadableFromTheClassObject() {
        Object value = "x";
        assertEquals("java.lang.String", JavaBridge.callOnClass(value.getClass(), "getName",
                List.of(), span, Path.of("."), null));
    }

    @Test
    void aClassObjectDescribesItself() {
        assertEquals("String", JavaBridge.callOnClass(String.class, "getSimpleName", List.of(),
                span, Path.of("."), null));
        assertEquals(Boolean.FALSE, JavaBridge.callOnClass(String.class, "isInterface",
                List.of(), span, Path.of("."), null));
        assertEquals(Boolean.TRUE, JavaBridge.callOnClass(List.class, "isInterface", List.of(),
                span, Path.of("."), null));
    }

    /**
     * The value is a class object, so a method it does not override is answered by
     * {@code Object}; that path is ordinary instance dispatch and needs nothing special.
     */
    @Test
    void aClassObjectsOwnObjectMethodsWork() {
        assertEquals("class java.util.ArrayList",
                JavaBridge.callOnClass(ArrayList.class, "toString", List.of(), span,
                        Path.of("."), null));
        assertEquals(Boolean.TRUE, JavaBridge.callOnClass(Object.class, "isAssignableFrom",
                List.of(ArrayList.class), span, Path.of("."), null));
    }

    @Test
    void declaredMemberLookupStaysRefused() {
        for (String name : List.of("getDeclaredMethod", "getDeclaredField", "getMethod",
                "getField", "getConstructor", "newInstance", "cast")) {
            assertSame(JavaBridge.NOT_FOUND,
                    JavaBridge.callOnClass(String.class, name, List.of(), span, Path.of("."),
                            null),
                    name + " must not be reachable through a class object");
        }
    }

    @Test
    void aRefusedLookupSaysItIsReflection() {
        String hint = JavaBridge.classMethodHint(String.class, "getDeclaredMethod");
        assertEquals("'getDeclaredMethod' is reflection, which is not exposed. A class object "
                + "answers questions about itself — name, kind, hierarchy — but not lookup of "
                + "its declared members.", hint);
    }

    /** An ordinary name gets no special help, so the generic diagnostic is unchanged. */
    @Test
    void anOrdinaryMissingNameGetsNoReflectionHint() {
        assertNull(JavaBridge.classMethodHint(String.class, "noSuchMethod"));
    }

    /**
     * The static path is unaffected: {@code String.valueOf} is still a static call, and it is
     * answered before the class object is consulted, so the two cannot disagree.
     */
    @Test
    void staticsAreStillReachable() {
        assertEquals("7", JavaBridge.callStatic(String.class, "valueOf", List.of(7L), span,
                Path.of(".")));
    }

    /** A functional value's class is a package-private JDK class; naming it must still work. */
    @Test
    void aPackagePrivateImplementationClassNameIsReadable() {
        Comparator<Integer> natural = Comparator.naturalOrder();
        assertEquals("java.util.Comparators$NaturalOrderComparator",
                JavaBridge.callOnClass(natural.getClass(), "getName", List.of(), span,
                        Path.of("."), null));
    }
}
