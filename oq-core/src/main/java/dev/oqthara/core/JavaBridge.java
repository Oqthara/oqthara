package dev.oqthara.core;

import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.OqTuple;
import dev.oqthara.runtime.Values;
import dev.oqthara.runtime.Span;
import dev.oqthara.runtime.Values;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class JavaBridge {
    public static final Object NOT_FOUND = new Object();

    private JavaBridge() {
    }

    public static Object construct(Class<?> type, List<Object> args, Span span, Path cwd) {
        return construct(type, args, span, cwd, null);
    }

    /**
     * Creates an instance, allowing a function argument to become a functional interface.
     *
     * <p>{@code host} is what runs the function when Java calls the interface back, so a null
     * host means the caller cannot support a callback and such an argument does not convert.
     */
    public static Object construct(Class<?> type, List<Object> args, Span span, Path cwd,
                                   OqHost host) {
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (args.size() == 1 && String.valueOf(constant).equals(Values.display(args.get(0)))) {
                    return constant;
                }
            }
        }
        if (type == java.time.LocalDate.class && args.size() == 3) {
            return java.time.LocalDate.of((int) Values.asLong(args.get(0)),
                    (int) Values.asLong(args.get(1)), (int) Values.asLong(args.get(2)));
        }
        if (type == java.time.LocalDateTime.class && args.size() >= 5) {
            return java.time.LocalDateTime.of(
                    (int) Values.asLong(args.get(0)), (int) Values.asLong(args.get(1)),
                    (int) Values.asLong(args.get(2)), (int) Values.asLong(args.get(3)),
                    (int) Values.asLong(args.get(4)));
        }
        Constructor<?> best = null;
        Object[] bestArgs = null;
        int bestScore = Integer.MIN_VALUE;
        for (Constructor<?> ctor : type.getConstructors()) {
            Object[] converted = tryConvert(ctor.getParameterTypes(), args, cwd,
                    ctor.isVarArgs(), host);
            if (converted == null) continue;
            int score = score(ctor.getParameterTypes(), args, ctor.isVarArgs());
            if (score > bestScore) {
                best = ctor;
                bestArgs = converted;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new OqError("cannot create " + type.getSimpleName() + " with "
                    + args.size() + " argument" + (args.size() == 1 ? "" : "s"), span,
                    "Available constructors: " + describeConstructors(type));
        }
        try {
            return best.newInstance(bestArgs);
        } catch (InvocationTargetException e) {
            throw wrap(e.getTargetException(), span);
        } catch (ReflectiveOperationException e) {
            throw new OqError("cannot create " + type.getSimpleName() + ": " + e.getMessage(), span);
        }
    }

    public static Object callMethod(Object receiver, String name, List<Object> args, Span span,
                                    Path cwd) {
        return callMethod(receiver, name, args, span, cwd, null);
    }

    /** Calls an instance method, allowing a function argument to become a functional interface. */
    public static Object callMethod(Object receiver, String name, List<Object> args, Span span,
                                    Path cwd, OqHost host) {
        Method best = null;
        Object[] bestArgs = null;
        int bestScore = Integer.MIN_VALUE;
        Method[] candidates = receiver.getClass().getMethods();
        for (Method method : candidates) {
            if (!method.getName().equals(name)) continue;
            Object[] converted = tryConvert(method.getParameterTypes(), args, cwd,
                    method.isVarArgs(), host);
            if (converted == null) continue;
            int score = score(method.getParameterTypes(), args, method.isVarArgs());
            if (score > bestScore) {
                best = method;
                bestArgs = converted;
                bestScore = score;
            }
        }
        if (best == null) {
            String mismatch = callbackMismatch(receiver.getClass().getMethods(), name, args);
            if (mismatch != null) throw new OqError(mismatch, span);
            return NOT_FOUND;
        }
        try {
            return normalize(best.invoke(receiver, bestArgs));
        } catch (InvocationTargetException e) {
            throw wrap(e.getTargetException(), span);
        } catch (IllegalAccessException e) {
            throw new OqError("cannot call " + name + ": " + e.getMessage(), span);
        }
    }

    public static Object callStatic(Class<?> type, String name, List<Object> args, Span span,
                                    Path cwd) {
        return callStatic(type, name, args, span, cwd, null);
    }

    /** Calls a static method, allowing a function argument to become a functional interface. */
    public static Object callStatic(Class<?> type, String name, List<Object> args, Span span,
                                    Path cwd, OqHost host) {
        Method best = null;
        Object[] bestArgs = null;
        int bestScore = Integer.MIN_VALUE;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || !Modifier.isStatic(method.getModifiers())) continue;
            Object[] converted = tryConvert(method.getParameterTypes(), args, cwd,
                    method.isVarArgs(), host);
            if (converted == null) continue;
            int score = score(method.getParameterTypes(), args, method.isVarArgs());
            if (score > bestScore) {
                best = method;
                bestArgs = converted;
                bestScore = score;
            }
        }
        if (best == null) {
            String mismatch = callbackMismatch(type.getMethods(), name, args);
            if (mismatch != null) throw new OqError(mismatch, span);
            return NOT_FOUND;
        }
        try {
            return normalize(best.invoke(null, bestArgs));
        } catch (InvocationTargetException e) {
            throw wrap(e.getTargetException(), span);
        } catch (IllegalAccessException e) {
            throw new OqError("cannot call " + name + ": " + e.getMessage(), span);
        }
    }

    public static Object readField(Object receiver, String name, Span span) {
        if (receiver == null || receiver instanceof Long || receiver instanceof Double
                || receiver instanceof Boolean || receiver instanceof String) {
            return NOT_FOUND;
        }
        // A Class<?> receiver is a static reference: `Math.PI`, `Integer.MAX_VALUE`. Field.get
        // takes the instance whose field is read, and for a static field that instance is null -
        // passing the Class object asked for the field of a Class and found nothing.
        if (receiver instanceof Class<?> type) {
            try {
                Field field = type.getField(name);
                if (!Modifier.isStatic(field.getModifiers())) return NOT_FOUND;
                return normalize(field.get(null));
            } catch (NoSuchFieldException e) {
                return NOT_FOUND;
            } catch (IllegalAccessException e) {
                throw new OqError("cannot read field " + name + ": " + e.getMessage(), span);
            }
        }
        try {
            Field field = receiver.getClass().getField(name);
            return normalize(field.get(receiver));
        } catch (NoSuchFieldException e) {
            return NOT_FOUND;
        } catch (IllegalAccessException e) {
            throw new OqError("cannot read field " + name + ": " + e.getMessage(), span);
        }
    }

    public static Object normalize(Object value) {
        if (value instanceof Integer i) return (long) i;
        if (value instanceof Short s) return (long) s;
        if (value instanceof Byte b) return (long) b;
        if (value instanceof java.math.BigInteger big) {
            try {
                return big.longValueExact();
            } catch (ArithmeticException e) {
                return big.doubleValue();
            }
        }
        if (value instanceof java.math.BigDecimal dec) return dec.doubleValue();
        if (value instanceof Float f) return f.doubleValue();
        if (value instanceof Character c) return String.valueOf(c);
        return value;
    }

    private static Object[] tryConvert(Class<?>[] types, List<Object> args, Path cwd) {
        return tryConvert(types, args, cwd, false, null);
    }

    /**
     * Converts arguments to a method's parameter types, or null when they do not fit.
     *
     * <p>A {@code T...} parameter arrives as a trailing {@code T[]}, so an exact arity check
     * refused every varargs call: {@code String.format("%s-%s", "a", "b")} reports that no such
     * method exists even though it does. With {@code varargs} true the tail is packed into a
     * fresh array, which is what the compiler would have emitted at the call site.
     */
    private static Object[] tryConvert(Class<?>[] types, List<Object> args, Path cwd,
                                       boolean varargs, OqHost host) {
        if (!varargs) {
            if (types.length != args.size()) return null;
            Object[] converted = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                Object value = convert(args.get(i), types[i], cwd, host);
                if (value == NOT_FOUND) return null;
                converted[i] = value;
            }
            return converted;
        }
        int fixed = types.length - 1;
        if (args.size() < fixed) return null;
        Object[] converted = new Object[types.length];
        for (int i = 0; i < fixed; i++) {
            Object value = convert(args.get(i), types[i], cwd, host);
            if (value == NOT_FOUND) return null;
            converted[i] = value;
        }
        Class<?> component = types[fixed].getComponentType();
        Object packed = java.lang.reflect.Array.newInstance(component, args.size() - fixed);
        for (int i = fixed; i < args.size(); i++) {
            Object value = convert(args.get(i), component, cwd, host);
            if (value == NOT_FOUND) return null;
            java.lang.reflect.Array.set(packed, i - fixed, value);
        }
        converted[fixed] = packed;
        return converted;
    }

    private static Object convert(Object value, Class<?> type, Path cwd, OqHost host) {
        if (value == null) {
            return type.isPrimitive() ? NOT_FOUND : null;
        }
        if (type.isInstance(value)) return value;
        if (type == String.class) return Values.display(value);
        if (type == long.class || type == Long.class) {
            if (value instanceof Number n) return n.longValue();
            return NOT_FOUND;
        }
        if (type == int.class || type == Integer.class) {
            if (value instanceof Number n) return n.intValue();
            return NOT_FOUND;
        }
        if (type == double.class || type == Double.class) {
            if (value instanceof Number n) return n.doubleValue();
            return NOT_FOUND;
        }
        if (type == float.class || type == Float.class) {
            if (value instanceof Number n) return n.floatValue();
            return NOT_FOUND;
        }
        if (type == boolean.class || type == Boolean.class) {
            return value instanceof Boolean b ? b : NOT_FOUND;
        }
        if (type == char.class || type == Character.class) {
            return value instanceof String s && s.length() == 1 ? s.charAt(0) : NOT_FOUND;
        }
        if (type == Path.class) return Path.of(Values.display(value));
        if (type == java.io.File.class) return Path.of(Values.display(value)).toFile();
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (String.valueOf(constant).equals(Values.display(value))) return constant;
            }
            return NOT_FOUND;
        }
        if (List.class.isAssignableFrom(type) && value instanceof List<?>) return value;
        if (java.util.Map.class.isAssignableFrom(type) && value instanceof java.util.Map<?, ?>) return value;
        if (OqData.class.isAssignableFrom(type)) return NOT_FOUND;
        if (value instanceof OqCallable function && type.isInterface()
                && functionalArity(type) >= 0) {
            return functionalInterface(type, function, host, cwd);
        }
        return NOT_FOUND;
    }

    /**
     * The parameter count of {@code type}'s single abstract method, or -1 when it is not a
     * functional interface.
     *
     * <p>Counted from the declared methods rather than trusting {@code @FunctionalInterface}:
     * the annotation is documentation and is not retained for {@code Comparator} and friends,
     * which the JDK's own classes would then fail to provide. Interface methods that are
     * {@code static}, {@code default} or an override of {@code Object} do not count as abstract,
     * which is the same rule the Java compiler applies.
     */
    private static int functionalArity(Class<?> type) {
        int found = -1;
        for (Method method : type.getMethods()) {
            if (method.isDefault() || Modifier.isStatic(method.getModifiers())) continue;
            if (!Modifier.isAbstract(method.getModifiers())) continue;
            if (overridesObject(method)) continue;
            if (found >= 0 && found != method.getParameterCount()) return -1;
            found = method.getParameterCount();
        }
        return found;
    }

    private static boolean overridesObject(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Wraps an Oqthara function as an instance of a Java functional interface.
     *
     * <p>A {@link java.lang.reflect.Proxy} is the only way to hand Java an object whose type it
     * already understands without generating and loading a class per interface. The proxy is
     * called on whichever thread Java invokes it from, so it runs the function through
     * {@code host}: the interpreter resolves a closure in the environment it was captured in,
     * and a compiled program receives an ordinary {@code OqCallable}. Arguments and the return
     * value cross the same conversion the other direction does, so a callback taking or
     * returning an {@code int} sees an Oqthara number rather than a Java {@code Integer}.
     */
    private static Object functionalInterface(Class<?> type, OqCallable function, OqHost host,
                                              Path cwd) {
        int arity = functionalArity(type);
        if (host == null) return NOT_FOUND;
        if (function.arity() != OqCallable.VARIADIC && function.arity() != arity) return NOT_FOUND;
        return java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    // Proxy forwards Object's own methods here too, and Java calls them from
                    // lists, maps and assertions. Answering them keeps the wrapper a well-behaved
                    // object instead of running the Oqthara function with the wrong arguments.
                    if (overridesObject(method)) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "oq function " + function.name();
                            default -> null;
                        };
                    }
                    if (method.isDefault()) {
                        throw new OqError("'" + function.name() + "' cannot stand in for the "
                                + "default method " + method.getName() + " of "
                                + type.getSimpleName(), Span.synthetic());
                    }
                    List<Object> passed = new ArrayList<>();
                    if (arguments != null) {
                        for (Object argument : arguments) passed.add(normalize(argument));
                    }
                    Object result = function.call(host, passed, Span.synthetic());
                    Class<?> wanted = method.getReturnType();
                    if (wanted == void.class) return null;
                    Object back = convert(result, wanted, cwd, host);
                    // A value the interface cannot hold is a fault in the program, not a reason
                    // for Java to see a half-converted object, so it is refused rather than null.
                    if (back == NOT_FOUND) {
                        throw new OqError("'" + function.name() + "' returned "
                                + Values.typeName(result) + ", which "
                                + wanted.getSimpleName() + " cannot hold", Span.synthetic());
                    }
                    return back;
                });
    }

    /**
     * Explains an argument that is a function but fits no overload's functional interface.
     *
     * <p>Without this the call reports the method as missing, which is false: it exists and
     * took the function, but the function's arity does not fit the interface it would become.
     * Naming the expected interface and its parameter count is the whole diagnosis.
     */
    private static String callbackMismatch(Method[] methods, String name, List<Object> args) {
        for (Method method : methods) {
            if (!method.getName().equals(name)) continue;
            if (method.getParameterCount() != args.size()) continue;
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (!(args.get(i) instanceof OqCallable function)) continue;
                int arity = functionalArity(types[i]);
                if (arity < 0) continue;
                if (function.arity() == OqCallable.VARIADIC || function.arity() == arity) continue;
                return "'" + name + "' wants a " + types[i].getSimpleName() + ", which takes "
                        + arity + " argument" + (arity == 1 ? "" : "s") + ", but '"
                        + function.name() + "' takes " + function.arity();
            }
        }
        return null;
    }

    /**
     * Prefers the overload whose parameter types fit the arguments most exactly.
     *
     * <p>An overload that survived {@link #tryConvert} always has a parameter for every
     * argument, except a varargs one, whose trailing array stands for zero or more. Those
     * arguments are scored against the array's component type, so a varargs call is ranked by
     * the same rules as a fixed one instead of reading past the argument list.
     */
    private static int score(Class<?>[] types, List<Object> args, boolean varargs) {
        int score = 0;
        int fixed = varargs ? types.length - 1 : types.length;
        for (int i = 0; i < fixed; i++) {
            Object value = args.get(i);
            Class<?> type = types[i];
            if (value == null) {
                score += 1;
            } else if (type == value.getClass()) {
                score += 100;
            } else if (value instanceof Long) {
                if (type == long.class || type == Long.class || type == int.class || type == Integer.class) {
                    score += 40;
                } else if (type == double.class || type == Double.class) {
                    score += 20;
                } else if (type == float.class || type == Float.class) {
                    score += 10;
                } else if (type.isInstance(value)) {
                    score += 50;
                } else {
                    score += 2;
                }
            } else if (value instanceof Double) {
                if (type == double.class || type == Double.class) {
                    score += 40;
                } else if (type == float.class || type == Float.class) {
                    score += 10;
                } else if (type.isInstance(value)) {
                    score += 50;
                } else {
                    score += 2;
                }
            } else if (type.isInstance(value)) {
                score += 50;
            } else {
                score += 2;
            }
        }
        if (varargs) {
            Class<?> component = types[fixed].getComponentType();
            // A varargs call is a slightly worse fit than an exact one, so it never wins a tie
            // against a fixed-arity overload that accepts the same arguments.
            score -= 1;
            for (int i = fixed; i < args.size(); i++) {
                Object value = args.get(i);
                if (value != null && component.isInstance(value)) score += 40;
                else if (component == String.class) score += 40;
                else score += 2;
            }
        }
        return score;
    }

    private static String describeConstructors(Class<?> type) {
        List<String> signatures = new ArrayList<>();
        for (Constructor<?> ctor : type.getConstructors()) {
            signatures.add(type.getSimpleName() + "("
                    + String.join(", ", Arrays.stream(ctor.getParameterTypes()).map(Class::getSimpleName).toList())
                    + ")");
        }
        return signatures.isEmpty() ? "(none)" : String.join("; ", signatures);
    }

    /**
     * Delegates to the runtime's shared classifier, so a host-interop refusal and a compiled
     * method call report the same thing. See {@link dev.oqthara.runtime.Errors#immutableRefusal}.
     */
    static OqError immutableRefusal(UnsupportedOperationException error, Span span) {
        return dev.oqthara.runtime.Errors.immutableRefusal(error, span);
    }

    private static OqError wrap(Throwable error, Span span) {
        if (error instanceof OqError oq) return oq;
        if (error instanceof UnsupportedOperationException refusal) {
            return immutableRefusal(refusal, span);
        }
        return new OqError(error.getClass().getSimpleName() + ": " + error.getMessage(), span);
    }
}
