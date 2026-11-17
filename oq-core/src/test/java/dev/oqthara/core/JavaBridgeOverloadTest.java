package dev.oqthara.core;

import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A function argument has to prefer the overload that takes it as code over the one that can
 * only render it as text.
 *
 * <p>{@code convert} turns any value into a {@code String}, so {@code Thread(() -> work())}
 * fitted both {@code Thread(Runnable)} and {@code Thread(String)} and the two scored the same.
 * Which one won came down to the order {@code getConstructors()} returned, which the language
 * specification does not fix. On JDK 21 {@code Thread(Runnable)} came first and the program
 * worked; on JDK 25 {@code Thread(String)} did, so the function became the thread's *name* and
 * the body never ran. The program then exited 0 with no output and nothing on stderr, which is
 * the worst possible failure: it looks like a program that simply had nothing to print.
 *
 * <p>This is pinned on the constructed object rather than on thread output, because the order of
 * {@code getConstructors()} cannot be forced and a test that reproduced the JDK 25 order on
 * JDK 21 would have to stub reflection. What the bridge must guarantee is that the choice is
 * decided by the arguments and not by that order.
 */
class JavaBridgeOverloadTest {
    private final Span span = Span.synthetic();
    private final Path cwd = Path.of(".");

    private OqHost host() {
        return new OqHost() {
            @Override
            public Object call(Object callee, List<Object> args, Span where) {
                return ((OqCallable) callee).call(this, args, where);
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

    private static OqCallable function(AtomicBoolean ran) {
        return new OqCallable() {
            @Override
            public String name() {
                return "work";
            }

            @Override
            public Object call(OqHost host, List<Object> args, Span where) {
                ran.set(true);
                return null;
            }

            @Override
            public int arity() {
                return 0;
            }
        };
    }

    /**
     * Declares the text constructor first on purpose.
     *
     * <p>Relying on {@code Thread} itself would make this test depend on the very reflection
     * order it is guarding against: on JDK 21 {@code Thread(Runnable)} happens to come first, so
     * the broken scoring still picks correctly and the test passes. This class fixes the order,
     * so the broken scoring fails here on every JDK instead of only on JDK 25.
     */
    public static final class Widget {
        public final Runnable body;
        public final String label;

        public Widget(String label) {
            this.label = label;
            this.body = null;
        }

        public Widget(Runnable body) {
            this.body = body;
            this.label = null;
        }
    }

    @Test
    void aFunctionArgumentPrefersTheCodeOverloadRegardlessOfDeclarationOrder() {
        AtomicBoolean ran = new AtomicBoolean();
        Widget widget = (Widget) JavaBridge.construct(Widget.class,
                List.of(function(ran)), span, cwd, host());

        assertTrue(widget.body != null,
                "the function bound to Widget(String) instead of Widget(Runnable)");
        widget.body.run();
        assertTrue(ran.get(), "the function was not run as the widget body");
    }

    @Test
    void aFunctionArgumentBecomesTheThreadBodyAndNotItsName() {
        AtomicBoolean ran = new AtomicBoolean();
        Thread thread = (Thread) JavaBridge.construct(Thread.class,
                List.of(function(ran)), span, cwd, host());

        // The name is the visible half of the defect: a named thread reports the function's
        // rendering, which is how the JDK 25 misbinding could be seen at all.
        assertTrue(thread.getName().startsWith("Thread-"),
                "expected a default thread name, got: " + thread.getName());

        thread.run();
        assertTrue(ran.get(), "the function was not run as the thread body");
    }

    @Test
    void aStringArgumentStillReachesTheNameConstructor() {
        // The repair must not take the name overload away; it only makes a function lose to a
        // functional interface. A String argument is still the only thing that fits `name`.
        Thread thread = (Thread) JavaBridge.construct(Thread.class,
                List.of("worker"), span, cwd, host());
        assertEquals("worker", thread.getName());
    }
}
