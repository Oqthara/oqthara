package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code random} is deliberately not reproducible, and there is no way to make it so.
 *
 * <p>The property is a security one rather than a testing convenience: a seeded generator is
 * the standard way a token or a session id ends up predictable, so the absence of {@code seed}
 * is asserted here rather than left to prose. If someone adds one, this fails and the addition
 * has to argue for itself.
 *
 * <p>The "two processes differ" claim in the standard library inventory is checked in the
 * weaker but non-flaky form the same guarantee supports: a fixed number of draws from one
 * generator are not all the same value. A generator that returned a constant would pass a
 * "is not equal to the previous one" check by alternating two values, so the set of draws is
 * compared instead of adjacent pairs.
 */
class RandomIsUnseededTest {

    @TempDir
    Path dir;

    private Interpreter host;

    private Interpreter host() {
        if (host == null) {
            PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            host = new Interpreter(sink, sink,
                    new java.io.BufferedReader(new java.io.StringReader("")),
                    List.of(), dir, new MethodResolverImpl());
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

    @Test
    void thereIsNoSeedFunctionToMakeItReproducible() {
        Set<String> builtins = host().builtins().keySet();
        assertFalse(builtins.contains("seed"), "a seed function would make random() predictable");
        assertFalse(builtins.contains("srand"), "a seed function would make random() predictable");
        assertFalse(builtins.contains("set_seed"), "a seed function would make random() predictable");
    }

    /**
     * A hundred draws that are not all identical. Sixteen distinct values is far below the
     * expected count for a uniform draw and far above what a broken generator returns, so the
     * threshold separates the two without depending on any particular value appearing.
     */
    @Test
    void repeatedDrawsAreNotAllTheSameValue() {
        Set<Double> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) seen.add(((Number) call("random")).doubleValue());
        assertTrue(seen.size() > 16, "100 draws produced only " + seen.size() + " distinct values");
    }

    @Test
    void everyDrawIsInTheUnitInterval() {
        for (int i = 0; i < 100; i++) {
            double value = ((Number) call("random")).doubleValue();
            assertTrue(value >= 0.0 && value < 1.0, "random() returned " + value);
        }
    }

    /**
     * The two-argument form is inclusive of {@code from} and exclusive of {@code to}, so a
     * bounded draw never reaches the upper bound. Equal bounds collapse to the lower one
     * rather than raising, which is what the implementation does.
     */
    @Test
    void aBoundedDrawStaysInsideItsHalfOpenRange() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            long value = ((Number) call("random", 3L, 7L)).longValue();
            assertTrue(value >= 3 && value < 7, "random(3, 7) returned " + value);
            seen.add(value);
        }
        assertEquals(Set.of(3L, 4L, 5L, 6L), seen);
        assertEquals(5L, ((Number) call("random", 5L, 5L)).longValue());
    }
}
