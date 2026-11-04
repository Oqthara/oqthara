package dev.oqthara.core;

import dev.oqthara.runtime.NativeFunction;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.OqHost;
import dev.oqthara.runtime.Span;

import java.util.List;

public final class OqFunction implements OqCallable {
    private final String name;
    private final List<String> params;
    private final List<Ast.Stmt> body;
    private final Env closure;

    public OqFunction(String name, List<String> params, List<Ast.Stmt> body, Env closure) {
        this.name = name;
        this.params = params;
        this.body = body;
        this.closure = closure;
    }

    public List<String> params() {
        return params;
    }

    public List<Ast.Stmt> body() {
        return body;
    }

    public Env closure() {
        return closure;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Object call(OqHost host, List<Object> args, Span span) {
        if (args.size() != params.size()) {
            throw new OqError(
                    name + "() expects " + params.size() + " argument" + (params.size() == 1 ? "" : "s")
                            + ", got " + args.size(),
                    span,
                    params.isEmpty() ? "This function takes no arguments." : "Expected: " + String.join(", ", params));
        }
        Env local = new Env(closure);
        for (int i = 0; i < params.size(); i++) {
            local.define(params.get(i), args.get(i), true);
        }
        Interpreter interpreter = (Interpreter) host;
        try {
            interpreter.executeBlock(body, local);
        } catch (ReturnSignal signal) {
            return signal.value;
        }
        return null;
    }

    @Override
    public String toString() {
        return "<function " + name + ">";
    }

    public static final class ReturnSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient Object value;

        public ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }

        public Object value() {
            return value;
        }
    }

    public static NativeFunction nativeOf(String name, int arity, NativeFunction.Body body) {
        return NativeFunction.of(name, arity, body);
    }
}
