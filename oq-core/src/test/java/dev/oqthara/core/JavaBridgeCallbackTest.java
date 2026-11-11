package dev.oqthara.core;

import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Java method that takes a functional interface had no way to receive an Oqthara function, so
 * {@code list.forEach} and {@code Collections.sort(list, comparator)} both failed. These cases
 * pin the conversion in both directions: the function becomes a proxy the JDK accepts, and the
 * values crossing back into it arrive as Oqthara values rather than Java boxed ones.
 */
class JavaBridgeCallbackTest {
    private final Span span = Span.synthetic();
    private final Path cwd = Path.of(".");

    /** A host that records what a callback was invoked with instead of running a program. */
    private OqHost recordingHost(List<Object> seen) {
        return new OqHost() {
            @Override
            public Object call(Object callee, List<Object> args, Span span) {
                return ((OqCallable) callee).call(this, args, span);
            }

            @Override
            public PrintStream out() {
                return System.out;
            }

            @Override
            public PrintStream err() {
                return System.err;
            }

            @Override
            public BufferedReader in() {
                return new BufferedReader(new StringReader(""));
            }

            @Override
            public List<String> programArgs() {
                return List.of();
            }

            @Override
            public Path workingDirectory() {
                return cwd;
            }
        };
    }

    /** A callable whose behaviour is supplied inline, so no parser is needed here. */
    private static OqCallable function(String name, List<Object> seen, int arity,
                                       java.util.function.BiFunction<List<Object>, Span, Object> body) {
        return new OqCallable() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Object call(OqHost host, List<Object> args, Span span) {
                seen.addAll(args);
                return body.apply(args, span);
            }

            @Override
            public int arity() {
                return arity;
            }
        };
    }

    @Test
    void anIterableMethodAcceptsAFunctionAsAConsumer() {
        List<Object> seen = new ArrayList<>();
        List<Object> xs = new ArrayList<>(List.of(1L, 2L, 3L));
        Object result = JavaBridge.callMethod(xs, "forEach",
                List.of(function("cb", seen, 1, (args, s) -> null)), span, cwd, recordingHost(seen));
        assertNull(result, "forEach returns void, so nothing crosses back");
        assertEquals(List.of(1L, 2L, 3L), seen);
    }

    @Test
    void aSortAcceptsAFunctionAsAComparator() {
        List<Object> xs = new ArrayList<>(List.of(3L, 1L, 2L));
        List<Object> seen = new ArrayList<>();
        JavaBridge.callStatic(java.util.Collections.class, "sort",
                List.of(xs, function("cmp", seen, 2, (args, s) -> ((Long) args.get(0)) - ((Long) args.get(1)))),
                span, cwd, recordingHost(seen));
        assertEquals(List.of(1L, 2L, 3L), xs);
        assertTrue(seen.size() >= 2, "a comparator must actually be consulted");
    }

    @Test
    void theArgumentsAJavaMethodPassesToTheCallbackArriveAsOqtharaValues() {
        // java.util.function.Function is called with the boxed element type, so a list of
        // Integer elements would hand the callback Integer. The proxy normalizes it, which is
        // why Oqthara code never sees an Integer where it expects a number.
        List<Object> seen = new ArrayList<>();
        List<Integer> xs = new ArrayList<>(List.of(7, 8));
        JavaBridge.callMethod(xs, "replaceAll",
                List.of(function("f", seen, 1, (args, s) -> args.get(0))), span, cwd, recordingHost(seen));
        assertEquals(List.of(7L, 8L), seen, "the callback must see numbers, not boxed Integers");
    }

    @Test
    void aCallbackReturningTheWrongTypeIsRefusedRatherThanSilentlyNull() {
        List<Object> seen = new ArrayList<>();
        List<Object> xs = new ArrayList<>(List.of(1L));
        OqError error = assertThrows(OqError.class, () -> JavaBridge.callMethod(xs, "removeIf",
                List.of(function("pred", seen, 1, (args, s) -> "not a boolean")), span, cwd,
                recordingHost(seen)));
        assertTrue(error.getMessage().contains("boolean"),
                "the message must name the type the interface needed: " + error.getMessage());
    }

    @Test
    void aCallbackWithTheWrongArityIsReportedAsAMismatchNotAsAMissingMethod() {
        // The method exists; the function cannot become the interface it wants. Saying the
        // method is missing would send the reader looking in the wrong place.
        List<Object> seen = new ArrayList<>();
        List<Object> xs = new ArrayList<>(List.of(1L));
        OqCallable twoArgs = new OqCallable() {
            @Override
            public String name() {
                return "cb";
            }

            @Override
            public Object call(OqHost host, List<Object> args, Span span) {
                return null;
            }

            @Override
            public int arity() {
                return 2;
            }
        };
        OqError error = assertThrows(OqError.class, () -> JavaBridge.callMethod(xs, "forEach",
                List.of(twoArgs), span, cwd, recordingHost(seen)));
        assertTrue(error.getMessage().contains("Consumer"), error.getMessage());
        assertTrue(error.getMessage().contains("takes 1"), error.getMessage());
    }

    @Test
    void aFunctionPassedWhereNoFunctionalInterfaceIsWantedIsStillNotFound() {
        // add(Object) takes any reference, so a function is already an instance and no proxy
        // is involved. The conversion must not turn every object parameter into a callback.
        List<Object> seen = new ArrayList<>();
        List<Object> xs = new ArrayList<>();
        OqCallable fn = function("cb", seen, 1, (args, s) -> null);
        JavaBridge.callMethod(xs, "add", List.of(fn), span, cwd, recordingHost(seen));
        assertSame(fn, xs.get(0));
        assertTrue(seen.isEmpty(), "a function stored as a value must not be called");
    }

    @Test
    void withoutAHostAFunctionArgumentIsNotConverted() {
        // The bytecode backend's bridge path always supplies a host. A null one means no
        // backend can run the function, so the overload must be declined rather than fail later.
        List<Object> seen = new ArrayList<>();
        List<Object> xs = new ArrayList<>(List.of(1L));
        assertSame(JavaBridge.NOT_FOUND, JavaBridge.callMethod(xs, "forEach",
                List.of(function("cb", seen, 1, (args, s) -> null)), span, cwd));
    }
}
