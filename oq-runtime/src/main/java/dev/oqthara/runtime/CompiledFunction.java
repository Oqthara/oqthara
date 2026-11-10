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
    private final String name;
    private volatile Method method;

    public CompiledFunction(Class<?> owner, String methodName, int arity, Object[] captured) {
        this(owner, methodName, arity, captured, LAMBDA_NAME);
    }

    /**
     * A {@code fun} used as a value keeps its declared name, so {@code print f} renders
     * {@code <function double>} exactly as the interpreter's {@code OqFunction} does. A lambda
     * stays {@code <lambda>}. Both are arity-checked with the name they report, which is why
     * an arity error names the function rather than the binding it was stored in.
     */
    public CompiledFunction(Class<?> owner, String methodName, int arity, Object[] captured,
            String name) {
        this.owner = owner;
        this.methodName = methodName;
        this.arity = arity;
        this.captured = captured;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int arity() {
        return arity;
    }

    @Override
    public Object call(OqHost host, List<Object> args, Span span) {
        if (args.size() != arity) {
            throw new OqError(name + "() expects " + arity + " argument"
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
        // An overload set compiles to one method per arity under the same name, so the name
        // alone does not identify a method: the parameter count does. Captured values are
        // leading parameters, exactly as the constructor documents.
        int parameters = captured.length + arity;
        synchronized (this) {
            if (method == null) {
                for (Method candidate : owner.getDeclaredMethods()) {
                    if (candidate.getName().equals(methodName)
                            && candidate.getParameterCount() == parameters) {
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
