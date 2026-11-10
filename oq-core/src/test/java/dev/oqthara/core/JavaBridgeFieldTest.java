package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a field reaches the host through {@link JavaBridge}, so the receiver has to be
 * inspected without assuming it is an object. A program that reads a field off {@code nothing}
 * is an Oqthara mistake and must be told so in Oqthara terms -- an escaping
 * {@link NullPointerException} names a Java class and an internal handler, which tells the
 * programmer nothing about their own error.
 */
class JavaBridgeFieldTest {
    private final Span span = Span.synthetic();

    @Test
    void readingAFieldOffNothingReportsNothingRatherThanLeakingANullPointerException() {
        assertSame(JavaBridge.NOT_FOUND, JavaBridge.readField(null, "x", span));
    }

    @Test
    void readingAFieldOffNothingReachesTheProgramAsAnOqtharaError() {
        var error = assertThrows(OqError.class, () -> {
            Object value = JavaBridge.readField(null, "x", span);
            if (value == JavaBridge.NOT_FOUND) {
                throw new OqError("cannot read field 'x' on " + dev.oqthara.runtime.Values.typeName(null),
                        span);
            }
        });
        assertEquals("cannot read field 'x' on nothing", error.getMessage());
    }

    @Test
    void readingAFieldOffAPrimitiveIsStillNotAFieldRead() {
        assertSame(JavaBridge.NOT_FOUND, JavaBridge.readField(5L, "x", span));
        assertSame(JavaBridge.NOT_FOUND, JavaBridge.readField(5.5, "x", span));
        assertSame(JavaBridge.NOT_FOUND, JavaBridge.readField("text", "x", span));
        assertSame(JavaBridge.NOT_FOUND, JavaBridge.readField(true, "x", span));
    }

    @Test
    void readingARealFieldStillWorks() {
        assertTrue(JavaBridge.readField(new JavaBridgeFieldTest.Holder(), "value", span) instanceof Long);
    }

    /** A public field, which is the only shape {@link JavaBridge#readField} reaches. */
    public static final class Holder {
        public long value = 7L;
    }
}
