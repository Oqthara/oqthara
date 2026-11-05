package dev.oqthara.stdlib;

import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.Errors;
import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OverloadedFunction;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry point for builtin calls emitted by the bytecode compiler.
 *
 * <p>A compiled class cannot construct a {@code NativeFunction} value, so a call such as
 * {@code clamp(5, 1, 3)} is emitted as a static call here. This class does **not** implement
 * any builtin: it installs the real {@link Stdlib} into one headless interpreter and calls the
 * same {@code NativeFunction} the interpreter calls. There is therefore exactly one
 * implementation of every builtin, and the two backends cannot drift.
 *
 * <p>Only builtins whose result is independent of the host are allowed. {@code args} would
 * need the program arguments threaded through the generated class, {@code input} would block
 * on a stream the jar does not own, and the clock, randomness, network, filesystem, process
 * and concurrency helpers are not reproducible. Those stay on the interpreter and the compiler
 * refuses them by name, so a compiled jar never silently behaves differently from
 * {@code oq run}. {@link Builtins#supports} is the single source of truth for that split,
 * which is why the compiler consults it instead of keeping a parallel list.
 */
public final class Builtins {
    /**
     * Builtins that read or mutate state outside the program. {@code spawn}, {@code await},
     * {@code par_map}, {@code sort_by}, {@code map} and {@code filter} also need callable
     * values, which a compiled class cannot create yet.
     */
    private static final Set<String> HOST_DEPENDENT = Set.of(
            "args", "input", "exit",
            "read", "read_lines", "save", "append", "exists", "list_files", "delete",
            "make_dir", "file_size", "path_join", "path_name", "path_parent", "cwd",
            "now", "today", "datetime", "timestamp", "sleep",
            "random", "random_item",
            "http_get", "http_post",
            "env", "run_command", "cpu_count", "memory_mb",
            "spawn", "await", "done", "par_map",
            "sort_by", "map", "filter");

    /** Most arguments a generated call site passes; keep in step with the emitter. */
    private static final int MAX_ARGS = 4;

    private static volatile Map<String, OqCallable> table;
    private static volatile Interpreter host;

    private Builtins() {
    }

    /**
     * True when a generated class can call this builtin with this many arguments. False for
     * unknown names, for host-dependent builtins, and for an arity the builtin rejects.
     */
    public static boolean supports(String name, int argc) {
        if (name == null || argc < 0 || argc > MAX_ARGS) return false;
        if (HOST_DEPENDENT.contains(name)) return false;
        OqCallable callable = table().get(name);
        if (callable instanceof OverloadedFunction overload) {
            for (OqCallable candidate : overload.candidates()) {
                if (candidate instanceof NativeFunction function && function.accepts(argc)) {
                    return true;
                }
            }
            return false;
        }
        return callable instanceof NativeFunction function && function.accepts(argc);
    }

    public static Object invoke0(Object name) {
        return invoke((String) name, List.of());
    }

    public static Object invoke1(Object a, Object name) {
        return invoke((String) name, list(a));
    }

    public static Object invoke2(Object a, Object b, Object name) {
        return invoke((String) name, list(a, b));
    }

    public static Object invoke3(Object a, Object b, Object c, Object name) {
        return invoke((String) name, list(a, b, c));
    }

    public static Object invoke4(Object a, Object b, Object c, Object d, Object name) {
        return invoke((String) name, list(a, b, c, d));
    }

    /**
     * One mutable list of exactly these values, in order. {@code List.of} is not usable here
     * because {@code nothing} is {@code null} and Oqthara passes it as a real argument.
     */
    private static List<Object> list(Object... values) {
        List<Object> args = new ArrayList<>(values.length);
        for (Object value : values) args.add(value);
        return args;
    }

    /** {@code print a, b, c}: the interpreter's own print, fed the same argument list. */
    public static void printList(Object list) {
        invoke("print", items(list));
        host().out().flush();
    }

    /** {@code write a, b}: no separator, exactly as the interpreter's variadic write. */
    public static void writeList(Object list) {
        invoke("write", items(list));
        // `oq run` flushes when the program ends; flush here so a jar whose last statement is
        // `write` is byte-identical even when its stdout is a pipe or a redirect.
        host().out().flush();
    }

    private static List<Object> items(Object list) {
        List<Object> values = new ArrayList<>();
        for (Object item : Values.iterable(list, Errors.current())) values.add(item);
        return values;
    }

    private static Object invoke(String name, List<Object> args) {
        Span span = Errors.current();
        OqCallable callable = table().get(name);
        if (callable == null || HOST_DEPENDENT.contains(name)) {
            throw new OqError("the builtin '" + name + "' is not available in compiled code",
                    span, "This is a compiler bug: it should have refused the call.");
        }
        return callable.call(host(), args, span);
    }

    private static Map<String, OqCallable> table() {
        host();
        return table;
    }

    private static Interpreter host() {
        Interpreter current = host;
        if (current != null) return current;
        synchronized (Builtins.class) {
            if (host == null) {
                // The same UTF-8 output and working directory a generated jar reports: this
                // helper makes compiled output identical to `oq run` on every platform.
                PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(new StringReader(""));
                Interpreter created = new Interpreter(out, err, in, List.of(), Path.of("."),
                        new MethodResolverImpl());
                Stdlib.install(created);
                table = created.builtins();
                host = created;
            }
            return host;
        }
    }
}
