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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tie rule for {@code round}, which the specification states for the whole number and the
 * standard library inventory cites by this class name.
 *
 * <p>The rule is <em>not</em> inferable from the alternatives: {@code round(0.5)} and
 * {@code round(1.5)} are the same under banker's rounding and under half-up, and the three
 * negative cases are where all three candidate rules disagree. So the cases below are chosen to
 * separate them, and every expected value came from the built CLI rather than from this file's
 * reading of {@code Math.round}.
 *
 * <table>
 *   <caption>How the candidate rules differ on the values pinned here</caption>
 *   <tr><th>call</th><th>upward (chosen)</th><th>banker's</th><th>away from zero</th></tr>
 *   <tr><td>{@code round(0.5)}</td><td>1</td><td>0</td><td>1</td></tr>
 *   <tr><td>{@code round(1.5)}</td><td>2</td><td>2</td><td>2</td></tr>
 *   <tr><td>{@code round(2.5)}</td><td>3</td><td>2</td><td>3</td></tr>
 *   <tr><td>{@code round(-0.5)}</td><td>0</td><td>0</td><td>-1</td></tr>
 *   <tr><td>{@code round(-1.5)}</td><td>-1</td><td>-2</td><td>-2</td></tr>
 *   <tr><td>{@code round(-2.5)}</td><td>-2</td><td>-2</td><td>-3</td></tr>
 * </table>
 */
class RoundTieTest {

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

    private static long whole(Object value) {
        return ((Number) value).longValue();
    }

    @Test
    void tiesInThePositiveDirectionGoUp() {
        assertEquals(1L, whole(call("round", 0.5)));
        assertEquals(2L, whole(call("round", 1.5)));
        assertEquals(3L, whole(call("round", 2.5)));
    }

    /**
     * The three cases that rule out every alternative. A negative tie rounds toward positive
     * infinity, so it moves <em>toward zero</em>, which is the direction most readers assume
     * wrongly.
     */
    @Test
    void tiesInTheNegativeDirectionAlsoGoUp() {
        assertEquals(0L, whole(call("round", -0.5)));
        assertEquals(-1L, whole(call("round", -1.5)));
        assertEquals(-2L, whole(call("round", -2.5)));
    }

    @Test
    void aValueThatIsNotATieRoundsToTheNearest() {
        assertEquals(1L, whole(call("round", 0.6)));
        assertEquals(0L, whole(call("round", 0.4)));
        assertEquals(-1L, whole(call("round", -0.6)));
    }

    /**
     * The two-argument form follows the same rule, so the rule does not vary by arity. The
     * second case is the one that makes the rule observable at a place it would otherwise be
     * hidden: a tie reached exactly in the stored double.
     */
    @Test
    void theRuleDoesNotVaryByArity() {
        assertEquals(0.13, ((Number) call("round", 0.125, 2L)).doubleValue(), 1e-12);
        assertEquals(-0.12, ((Number) call("round", -0.125, 2L)).doubleValue(), 1e-12);
    }

    /**
     * A decimal the binary double cannot hold reaches no tie at all, so the result follows the
     * stored value rather than the written one. This is why exact decimal arithmetic exists as
     * a proposed domain and why the specification says so at the point it states the tie rule.
     */
    @Test
    void aDecimalThatABinaryDoubleCannotHoldReachesNoTie() {
        assertEquals(1.0, ((Number) call("round", 1.005, 2L)).doubleValue(), 1e-12);
    }

    @Test
    void aWholeValueIsUnchanged() {
        assertEquals(7L, whole(call("round", 7.0)));
        assertEquals(-7L, whole(call("round", -7.0)));
    }
}
