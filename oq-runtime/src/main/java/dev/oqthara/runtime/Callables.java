package dev.oqthara.runtime;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
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
        if (callee instanceof Class<?> type) {
            return construct(type, args, span, Methods.host());
        }
        // A Java value whose class has one abstract method is a function in everything but
        // syntax, exactly as `Interpreter.invoke` decides before it gives up: a Comparator
        // from `Collections` is callable as `c(1, 2)`. Testing `instanceof OqCallable` alone
        // compiled a program that refused what the interpreter ran.
        Object fromJava = callFunctional(callee, args, span, Methods.host());
        if (!isNotFound(fromJava)) return fromJava;
        // A null callee means the name was not a function at runtime, which is the same
        // situation the interpreter reports when a variable shadows a function.
        throw new OqError("'" + name + "' is not a function", span,
                Values.notCallableHelp(callee));
    }

    /**
     * Runs a class value's constructor: {@code ArrayList()} in compiled code means what it means
     * interpreted, where {@code Interpreter.invoke} hands a {@code Class} to
     * {@code JavaBridge.construct}.
     *
     * <p>The bridge is reached reflectively for the same reason {@code Methods} reaches it that
     * way: this module sits below the interpreter, so there is one implementation of constructor
     * selection and argument conversion and nothing can drift.
     */
    static Object construct(Class<?> type, List<Object> args, Span span, OqHost host) {
        try {
            Class<?> bridge = JavaBridgeHolder.type();
            return bridge.getMethod("construct", Class.class, List.class, Span.class, Path.class,
                            OqHost.class)
                    .invoke(null, type, args, span, Path.of("."), host);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new OqError(String.valueOf(cause), span);
        } catch (ReflectiveOperationException e) {
            throw new OqError("class construction needs the Oqthara runtime on the classpath",
                    span, e.toString());
        }
    }

    /**
     * Calls a Java value through its single abstract method, or returns the bridge's
     * not-found marker when the value is not a functional interface.
     *
     * <p>Reflective for the same reason {@link #construct} is: the bridge lives above this
     * module, so there is one implementation of the rule and no second copy to drift.
     */
    static Object callFunctional(Object callee, List<Object> args, Span span, OqHost host) {
        try {
            return JavaBridgeHolder.type()
                    .getMethod("callFunctional", Object.class, List.class, Span.class, Path.class,
                            OqHost.class)
                    .invoke(null, callee, args, span, Path.of("."), host);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new OqError(String.valueOf(cause), span);
        } catch (ReflectiveOperationException e) {
            throw new OqError("calling a Java function needs the Oqthara runtime on the classpath",
                    span, e.toString());
        }
    }

    static boolean isNotFound(Object value) {
        try {
            return value == JavaBridgeHolder.type().getField("NOT_FOUND").get(null);
        } catch (ReflectiveOperationException e) {
            throw new OqError("calling a Java function needs the Oqthara runtime on the classpath",
                    Errors.current(), e.toString());
        }
    }

    /** Holds the reflectively loaded bridge, so the lookup happens once per program. */
    private static final class JavaBridgeHolder {
        private static Class<?> cached;

        static Class<?> type() throws ClassNotFoundException {
            if (cached == null) cached = Class.forName("dev.oqthara.core.JavaBridge");
            return cached;
        }
    }

    /** One mutable list, because {@code nothing} is {@code null} and is a real argument. */
    private static List<Object> list(Object... values) {
        List<Object> args = new ArrayList<>(values.length);
        for (Object value : values) args.add(value);
        return args;
    }
}
