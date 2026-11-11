package dev.oqthara.core;

import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
