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

    public NativeFunction(String name, int minArity, int maxArity, Body body) {
        this.name = name;
        this.minArity = minArity;
        this.maxArity = maxArity;
        this.body = body;
    }

    public static NativeFunction of(String name, int arity, Body body) {
        return new NativeFunction(name, arity, arity, body);
    }

    public static NativeFunction variadic(String name, int minArity, Body body) {
        return new NativeFunction(name, minArity, Integer.MAX_VALUE, body);
    }

    @Override
    public String name() {
        return name;
    }

    public boolean accepts(int arity) {
        return arity >= minArity && arity <= maxArity;
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
