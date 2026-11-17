package dev.oqthara.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The classes a running program has brought into scope with {@code use}.
 *
 * <p>The interpreter keeps one {@code javaTypes} map per interpreter and fills it as each
 * {@code use} statement executes (ADR-0064, ADR-0066). A compiled program reaches the same state
 * through this class: {@link #register} is called where the statement runs, so an untaken branch
 * registers nothing, a {@code use} inside a function leaks out exactly as the interpreter's does,
 * and a later registration of the same simple name replaces the earlier one.
 *
 * <p>Generated code emits {@code Errors.mark} before each call, so both methods report their
 * position through {@link Errors#current()} exactly as the other helpers do.
 */
public final class UsedClasses {
    private static final Map<String, Class<?>> TYPES = new LinkedHashMap<>();

    private UsedClasses() {
    }

    /**
     * Resolves {@code path} and binds it to {@code simpleName}, replacing any earlier binding.
     *
     * <p>The candidate order and the diagnostic match {@code Interpreter.loadClass} so a program
     * gets one verdict whichever backend runs it: the path as written, then the path under
     * {@code java.lang.} and {@code java.util.}, the latter two only when it has no dot at all.
     */
    public static void register(Object path, Object simpleName) {
        String name = Values.display(path);
        Class<?> type;
        try {
            type = resolve(name);
        } catch (ClassNotFoundException missing) {
            throw new OqError("cannot find Java class '" + name + "'", Errors.current(),
                    "Check the package name, for example: use java.time.LocalDate");
        }
        TYPES.put(Values.display(simpleName), type);
    }

    /**
     * The class bound to {@code simpleName}, or an {@code OqError} carrying the read's position.
     *
     * <p>A compiled program can reach a read of a name whose {@code use} never executed, because
     * registration is conditional. The interpreter's own wording is used so the two backends do
     * not diverge on the message either.
     */
    public static Object lookup(Object simpleName) {
        String name = Values.display(simpleName);
        Class<?> type = TYPES.get(name);
        if (type == null) {
            throw new OqError("'" + name + "' is not defined", Errors.current(),
                    "Check the spelling, or define it before using it: " + name + " = value");
        }
        return type;
    }

    private static Class<?> resolve(String path) throws ClassNotFoundException {
        try {
            return Class.forName(path);
        } catch (ClassNotFoundException asWritten) {
            if (path.contains(".")) {
                throw asWritten;
            }
        }
        for (String prefix : new String[] {"java.lang.", "java.util."}) {
            try {
                return Class.forName(prefix + path);
            } catch (ClassNotFoundException ignored) {
                // try next
            }
        }
        throw new ClassNotFoundException(path);
    }
}
