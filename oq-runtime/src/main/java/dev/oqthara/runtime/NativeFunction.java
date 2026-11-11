package dev.oqthara.runtime;

import java.util.List;

public final class NativeFunction implements OqCallable {
    @FunctionalInterface
    public interface Body {
        Object apply(OqHost host, List<Object> args, Span span);
    }

    private final String name;
    private final int minArity;
    private final int maxArity;
    private final Body body;
    private final List<String> parameterNames;

    public NativeFunction(String name, int minArity, int maxArity, Body body) {
        this(name, minArity, maxArity, body, List.of());
    }

    private NativeFunction(String name, int minArity, int maxArity, Body body,
                           List<String> parameterNames) {
        this.name = name;
        this.minArity = minArity;
        this.maxArity = maxArity;
        this.body = body;
        this.parameterNames = List.copyOf(parameterNames);
    }

    public static NativeFunction of(String name, int arity, Body body) {
        return new NativeFunction(name, arity, arity, body);
    }

    public static NativeFunction variadic(String name, int minArity, Body body) {
        return new NativeFunction(name, minArity, Integer.MAX_VALUE, body);
    }

    /** A builtin that takes between {@code minArity} and {@code maxArity} arguments. */
    public static NativeFunction variadic(String name, int minArity, int maxArity, Body body) {
        return new NativeFunction(name, minArity, maxArity, body);
    }

    /**
     * The same function, also publishing the names of its leading parameters.
     *
     * <p>The names live beside the body that reads the arguments rather than in a separate
     * table, so a builtin cannot carry a name list that its own code contradicts. They are
     * displayed by signature help and never affect a call: a caller passes values by
     * position, and a mismatch between a name here and the body would be a documentation
     * defect, not a behavioural one.
     *
     * @param names one name per parameter, in call order; a trailing variadic parameter is
     *              named once and repeated as needed
     */
    public NativeFunction named(String... names) {
        return new NativeFunction(name, minArity, maxArity, body, List.of(names));
    }

    @Override
    public String name() {
        return name;
    }

    public boolean accepts(int arity) {
        return arity >= minArity && arity <= maxArity;
    }

    /**
     * The published parameter names, in call order. Empty when this builtin does not publish
     * any, which signature help renders as numbered placeholders.
     */
    public List<String> parameterNames() {
        return parameterNames;
    }

    /** The smallest number of arguments this function accepts. */
    public int minArity() {
        return minArity;
    }

    /** The largest number of arguments this function accepts, possibly {@link Integer#MAX_VALUE}. */
    public int maxArity() {
        return maxArity;
    }

    @Override
    public int arity() {
        return minArity == maxArity ? minArity : VARIADIC;
    }

    @Override
    public Object call(OqHost host, List<Object> args, Span span) {
        if (args.size() < minArity || args.size() > maxArity) {
            String expected = minArity == maxArity
                    ? String.valueOf(minArity)
                    : (maxArity == Integer.MAX_VALUE ? minArity + " or more" : minArity + " to " + maxArity);
            throw new OqError(
                    name + "() expects " + expected + " argument" + (minArity == 1 && maxArity == 1 ? "" : "s")
                            + ", got " + args.size(),
                    span);
        }
        return body.apply(host, args, span);
    }
}
