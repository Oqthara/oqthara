package dev.oqthara.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * A function value created by compiled bytecode.
 *
 * <p>Every lambda becomes one extra static method on the generated class. Captured variables
 * are that method's leading parameters, so a closure is its captured values plus the name of
 * the method to run. Resolving the method once through {@link Class#getDeclaredMethods()} keeps
 * the generated class free of any table, registry or name-to-index scheme, and it is why a
 * lambda body is ordinary emitted bytecode rather than a second code path.
 */
public final class CompiledFunction implements OqCallable {
    /** The interpreter's display name for a lambda, so {@code print} renders identically. */
    private static final String LAMBDA_NAME = "<lambda>";

    private final Class<?> owner;
    private final String methodName;
    private final Object[] captured;
    private final int arity;
    private volatile Method method;

    public CompiledFunction(Class<?> owner, String methodName, int arity, Object[] captured) {
        this.owner = owner;
        this.methodName = methodName;
        this.arity = arity;
        this.captured = captured;
    }

    @Override
    public String name() {
        return LAMBDA_NAME;
    }

    @Override
    public Object call(OqHost host, List<Object> args, Span span) {
        if (args.size() != arity) {
            throw new OqError(LAMBDA_NAME + "() expects " + arity + " argument"
                    + (arity == 1 ? "" : "s") + ", got " + args.size(), span);
        }
        Object[] all = new Object[captured.length + args.size()];
        System.arraycopy(captured, 0, all, 0, captured.length);
        for (int i = 0; i < args.size(); i++) {
            all[captured.length + i] = args.get(i);
        }
        try {
            return resolve().invoke(null, all);
        } catch (InvocationTargetException error) {
            // The lambda body's own OqError carries the span the program can observe; a
            // reflective wrapper would hide it behind InvocationTargetException.
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw new OqError(String.valueOf(error.getCause()), span);
        } catch (IllegalAccessException error) {
            throw new OqError("cannot call a compiled function", span);
        }
    }

    private Method resolve() {
        Method resolved = method;
        if (resolved != null) return resolved;
        synchronized (this) {
            if (method == null) {
                for (Method candidate : owner.getDeclaredMethods()) {
                    if (candidate.getName().equals(methodName)) {
                        method = candidate;
                        break;
                    }
                }
                if (method == null) {
                    throw new OqError("compiled function '" + methodName + "' is missing from "
                            + owner.getName(), Span.synthetic(),
                            "This is a compiler bug: the generated class is incomplete.");
                }
            }
            return method;
        }
    }
}
