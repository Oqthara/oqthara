package dev.oqthara.core;

import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The JDK implements most of its own function objects in package-private classes: {@code
 * Comparator.naturalOrder()} answers a {@code Comparators$NaturalOrderComparator} and {@code
 * Function.identity()} answers a hidden lambda class. Both are legal values whose interface is
 * public, but reflection reports the <em>declaring</em> class's accessibility, so calling a
 * method on such a value was refused with "cannot access a member of class ... with modifiers
 * public" even though the same call compiles in Java. These tests pin the repair.
 */
class JavaBridgeAccessibilityTest {
    private final Span span = Span.synthetic();

    @Test
    void aMethodOnAPackagePrivateImplementationIsCallable() {
        Comparator<Integer> natural = Comparator.naturalOrder();
        assertEquals(-1L, JavaBridge.callMethod(natural, "compare", List.of(1L, 2L), span,
                Path.of(".")));
    }

    @Test
    void aMethodOnAHiddenLambdaClassIsCallable() {
        Function<Object, Object> identity = Function.identity();
        assertEquals(7L, JavaBridge.callMethod(identity, "apply", List.of(7L), span, Path.of(".")));
    }

    @Test
    void aCallableValueStaysCallableAfterBeingCountedAsAnObject() {
        Comparator<Integer> reversed = Comparator.reverseOrder();
        Object asValue = reversed;
        assertEquals(1L, JavaBridge.callMethod(asValue, "compare", List.of(1L, 2L), span,
                Path.of(".")));
    }

    /**
     * Accessibility is not {@code Modifier.isPublic} alone. {@code BufferedImage.createGraphics}
     * answers a {@code sun.java2d.SunGraphics2D}, which is public but sits in a package
     * {@code java.desktop} does not export, so {@code Method.invoke} refuses it. Checking only
     * the modifier kept returning that {@code Method}, and drawing failed on the very first call
     * with "does not export sun.java2d". The declaration on {@code java.awt.Graphics} is the
     * reachable one and dispatches to the same override.
     */
    @Test
    void aMethodOnAPublicButUnexportedImplementationIsCallable() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            JavaBridge.callMethod(graphics, "setColor", List.of(Color.RED), span, Path.of("."));
            assertEquals(Color.RED, graphics.getColor());
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void aUnexportedImplementationStillAnswersItsOwnSupertypeMethods() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics graphics = image.createGraphics();
        try {
            // A void method answers nothing, which the bridge normalises to null. The assertion
            // is that the call returned at all rather than raising IllegalAccessException.
            assertNull(JavaBridge.callMethod(graphics, "dispose", List.of(), span, Path.of(".")));
        } finally {
            graphics.dispose();
        }
    }
}
