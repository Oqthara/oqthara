package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqMutex;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The callback position of {@code sort}, {@code reduce}, and {@code m.lock}.
 *
 * <p>These reach {@code MethodResolverImpl.callableArgument} through the resolver a program uses,
 * then call the resolved method with the real interpreter as host, so the arity each builtin
 * requires is pinned rather than merely described. Before this class the method had no unit
 * test: an adversarial review replaced the callback arity with a constant and the whole suite
 * stayed green, because the only thing that noticed was the executable probe harness, which does
 * not run in CI.
 *
 * <p>The refusals assert the message, not just the exception type, so a change that starts
 * denying a value is callable at all fails here distinctly from one reporting the wrong count.
 */
class CallableArgumentTest {

    @TempDir
    Path dir;

    private final MethodResolverImpl resolver = new MethodResolverImpl();
    private final Span span = Span.synthetic();

    private Interpreter host;

    private Interpreter host() {
        if (host == null) {
            PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                    StandardCharsets.UTF_8);
            host = new Interpreter(sink, sink,
                    new java.io.BufferedReader(new java.io.StringReader("")),
                    List.of(), dir, resolver);
            Stdlib.install(host);
        }
        return host;
    }

    private Object invoke(Object receiver, String name, Object... args) {
        OqCallable method = resolver.resolve(receiver, name, span);
        assertTrue(method != null, name + " did not resolve");
        return method.call(host(), new ArrayList<>(Arrays.asList(args)), span);
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static List<Object> asList(Object value) {
        assertTrue(value instanceof List, "expected a list, got " + value);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) value;
        return result;
    }

    // --- values that can be called -----------------------------------------------------------

    @Test
    void sortTakesAJavaUnaryOperatorAsItsKey() {
        // Sorting by negation through a Java value. If callableArgument stopped reading the
        // functional arity, this would be refused as "not a function" rather than sorted.
        Function<Long, Long> negate = n -> -n;
        assertEquals(list(3L, 2L, 1L), asList(invoke(list(1L, 2L, 3L), "sort", negate)));
    }

    @Test
    void reduceTakesAJavaBinaryOperatorAsItsStep() {
        BiFunction<Long, Long, Long> sum = (a, b) -> a + b;
        assertEquals(6L, invoke(list(1L, 2L, 3L), "reduce", sum));
    }

    // --- wrong counts are reported as counts -------------------------------------------------

    @Test
    void sortRefusesATwoArgumentJavaFunctionNamingOneArgument() {
        Comparator<Long> twoArguments = Comparator.naturalOrder();
        OqError error = assertThrows(OqError.class,
                () -> invoke(list(1L, 2L), "sort", twoArguments));
        assertTrue(error.getMessage().contains("taking 1 argument"),
                "unexpected message: " + error.getMessage());
        assertTrue(error.getMessage().contains("argument 1"),
                "the position was not named: " + error.getMessage());
    }

    @Test
    void reduceRefusesAOneArgumentJavaFunctionNamingTwoArguments() {
        Function<Object, Object> oneArgument = Function.identity();
        OqError error = assertThrows(OqError.class,
                () -> invoke(list(1L, 2L), "reduce", oneArgument));
        assertTrue(error.getMessage().contains("taking 2 arguments"),
                "unexpected message: " + error.getMessage());
    }

    @Test
    void lockRefusesAOneArgumentCallbackNamingZeroArguments() {
        // The mutex body is the only callback site that wants no arguments at all, so it is the
        // one that would go unnoticed if the wanted count were hardcoded to 1.
        OqMutex mutex = new OqMutex();
        Function<Object, Object> oneArgument = Function.identity();
        OqError error = assertThrows(OqError.class, () -> invoke(mutex, "lock", oneArgument));
        assertTrue(error.getMessage().contains("taking 0 arguments"),
                "unexpected message: " + error.getMessage());
    }

    // --- values that cannot be called --------------------------------------------------------

    @Test
    void aNonCallableIsRefusedAsNotAFunctionRatherThanAsAWrongCount() {
        // The distinction matters: OqCallable.VARIADIC and "not callable" are both -1, so a
        // check that compared the sign before establishing callability would report this as
        // taking -1 arguments.
        OqError error = assertThrows(OqError.class,
                () -> invoke(list(1L, 2L), "sort", "not a function"));
        assertEquals("'sort' needs a function as argument 1", error.getMessage());
    }

    @Test
    void nullIsRefusedAsNotAFunction() {
        OqError error = assertThrows(OqError.class,
                () -> invoke(list(1L, 2L), "reduce", (Object) null));
        assertEquals("'reduce' needs a function as argument 1", error.getMessage());
    }

    @Test
    void aVariadicOqCallableIsAcceptedWhateverTheCount() {
        // VARIADIC is -1, the value the bridge also uses for "not a function". A variadic
        // callback must still be accepted, so the callability check cannot be a sign test.
        // A NativeFunction reports VARIADIC whenever its bounds differ, so the bounds are what
        // describe "any count"; VARIADIC itself is the sentinel arity(), not a bound.
        OqCallable variadic = new NativeFunction("var", 0, Integer.MAX_VALUE,
                (callHost, args, callSpan) -> (long) args.size());
        assertEquals(OqCallable.VARIADIC, variadic.arity());
        assertEquals(2L, invoke(list(1L, 2L), "reduce", variadic));
    }
}
