package dev.oqthara.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Call site and factory for function values in generated code.
 *
 * <p>The interpreter resolves a call in {@code evaluateCall}; a generated class cannot run that
 * method, so this holds the equivalent in one place and reproduces its message. The span comes
 * from the {@code Errors.mark} emitted immediately before the call, as for every other helper.
 *
 * <p>Arguments arrive as separate parameters rather than an array because the emitter builds
 * arrays only for list literals; {@code call0..call4} mirror {@code Builtins.invoke0..4}.
 */
public final class Callables {
    /** Most arguments a generated call site may pass; keep in step with the emitter. */
    public static final int MAX_ARGS = 4;

    private Callables() {
    }

    public static Object call0(Object callee, Object name) {
        return call(callee, List.of(), name);
    }

    public static Object call1(Object callee, Object a, Object name) {
        return call(callee, list(a), name);
    }

    public static Object call2(Object callee, Object a, Object b, Object name) {
        return call(callee, list(a, b), name);
    }

    public static Object call3(Object callee, Object a, Object b, Object c, Object name) {
        return call(callee, list(a, b, c), name);
    }

    public static Object call4(Object callee, Object a, Object b, Object c, Object d, Object name) {
        return call(callee, list(a, b, c, d), name);
    }

    /**
     * Builds the value a lambda expression evaluates to. The generated class passes its own
     * {@code Class}, the static method holding the lambda body, the captured values in
     * declaration order, and the body's arity.
     */
    public static Object closure(Object owner, Object method, Object captured, Object arity) {
        List<Object> values = captured instanceof List<?> list
                ? new ArrayList<>(list)
                : new ArrayList<>();
        return new CompiledFunction((Class<?>) owner, (String) method,
                (int) Values.asLong(arity), values.toArray());
    }

    /**
     * Builds the value a bare {@code fun} name evaluates to: the generated class's own static
     * method, which captures nothing because a compiled function only reads its parameters and
     * locals. The name travels with the value so {@code print} and an arity error report the
     * function, matching the interpreter's {@code OqFunction}.
     */
    public static Object function(Object owner, Object method, Object arity, Object name) {
        return new CompiledFunction((Class<?>) owner, (String) method,
                (int) Values.asLong(arity), new Object[0], (String) name);
    }

    /**
     * Builds the value a bare name with several declared arities evaluates to. The interpreter
     * keeps one value per name that dispatches on argument count, so the compiled backend must
     * present the same single value instead of the first method it happens to find.
     *
     * <p>Every declared arity compiles to a method of the function's own name, so the class and
     * the name are shared and only the counts differ. Each element of {@code arities} is a
     * {@code Long} pushed by the lowering in declaration order.
     */
    public static Object overload(Object owner, Object arities, Object name) {
        List<?> counts = (List<?>) arities;
        List<OqCallable> candidates = new ArrayList<>(counts.size());
        for (Object count : counts) {
            candidates.add(new CompiledFunction((Class<?>) owner, (String) name,
                    (int) Values.asLong(count), new Object[0], (String) name));
        }
        return OverloadedFunction.of((String) name, candidates);
    }

    private static Object call(Object callee, List<Object> args, Object name) {
        Span span = Errors.current();
        if (callee instanceof OqCallable callable) {
            return callable.call(null, args, span);
        }
        // A null callee means the name was not a function at runtime, which is the same
        // situation the interpreter reports when a variable shadows a function.
        throw new OqError("'" + name + "' is not a function", span,
                "It is " + Values.typeName(callee) + ", which cannot be called.");
    }

    /** One mutable list, because {@code nothing} is {@code null} and is a real argument. */
    private static List<Object> list(Object... values) {
        List<Object> args = new ArrayList<>(values.length);
        for (Object value : values) args.add(value);
        return args;
    }
}
