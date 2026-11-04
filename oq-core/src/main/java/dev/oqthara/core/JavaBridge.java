package dev.oqthara.core;

import dev.oqthara.runtime.OqData;
import dev.oqthara.runtime.OqError;
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
            Object[] converted = tryConvert(ctor.getParameterTypes(), args, cwd);
            if (converted == null) continue;
            int score = score(ctor.getParameterTypes(), args);
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

    public static Object callMethod(Object receiver, String name, List<Object> args, Span span, Path cwd) {
        Method best = null;
        Object[] bestArgs = null;
        int bestScore = Integer.MIN_VALUE;
        for (Method method : receiver.getClass().getMethods()) {
            if (!method.getName().equals(name)) continue;
            Object[] converted = tryConvert(method.getParameterTypes(), args, cwd);
            if (converted == null) continue;
            int score = score(method.getParameterTypes(), args);
            if (score > bestScore) {
                best = method;
                bestArgs = converted;
                bestScore = score;
            }
        }
        if (best == null) {
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

    public static Object callStatic(Class<?> type, String name, List<Object> args, Span span, Path cwd) {
        Method best = null;
        Object[] bestArgs = null;
        int bestScore = Integer.MIN_VALUE;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || !Modifier.isStatic(method.getModifiers())) continue;
            Object[] converted = tryConvert(method.getParameterTypes(), args, cwd);
            if (converted == null) continue;
            int score = score(method.getParameterTypes(), args);
            if (score > bestScore) {
                best = method;
                bestArgs = converted;
                bestScore = score;
            }
        }
        if (best == null) return NOT_FOUND;
        try {
            return normalize(best.invoke(null, bestArgs));
        } catch (InvocationTargetException e) {
            throw wrap(e.getTargetException(), span);
        } catch (IllegalAccessException e) {
            throw new OqError("cannot call " + name + ": " + e.getMessage(), span);
        }
    }

    public static Object readField(Object receiver, String name, Span span) {
        if (receiver instanceof Long || receiver instanceof Double || receiver instanceof Boolean
                || receiver instanceof String) {
            return NOT_FOUND;
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
        if (types.length != args.size()) return null;
        Object[] converted = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Object value = convert(args.get(i), types[i], cwd);
            if (value == NOT_FOUND) return null;
            converted[i] = value;
        }
        return converted;
    }

    private static Object convert(Object value, Class<?> type, Path cwd) {
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
        return NOT_FOUND;
    }

    private static int score(Class<?>[] types, List<Object> args) {
        int score = 0;
        for (int i = 0; i < types.length; i++) {
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

    private static OqError wrap(Throwable error, Span span) {
        if (error instanceof OqError oq) return oq;
        return new OqError(error.getClass().getSimpleName() + ": " + error.getMessage(), span);
    }
}
