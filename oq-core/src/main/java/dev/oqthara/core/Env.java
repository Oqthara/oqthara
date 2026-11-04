package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.util.HashMap;
import java.util.Map;

public final class Env {
    private record Binding(Object value, boolean mutable, boolean shadowable) {
    }

    private final Env parent;
    private final Map<String, Binding> values = new HashMap<>();

    public Env() {
        this.parent = null;
    }

    public Env(Env parent) {
        this.parent = parent;
    }

    public Env parent() {
        return parent;
    }

    public void define(String name, Object value, boolean mutable) {
        values.put(name, new Binding(value, mutable, false));
    }

    public void defineConst(String name, Object value) {
        define(name, value, false);
    }

    public void defineBuiltin(String name, Object value) {
        values.put(name, new Binding(value, false, true));
    }

    public boolean hasLocal(String name) {
        return values.containsKey(name);
    }

    public Object get(String name, Span span) {
        Binding binding = values.get(name);
        if (binding != null) return binding.value();
        if (parent != null) return parent.get(name, span);
        throw new OqError("'" + name + "' is not defined", span,
                "Check the spelling, or define it before using it: " + name + " = value");
    }

    public boolean isMutable(String name, Span span) {
        Binding binding = values.get(name);
        if (binding != null) return binding.mutable();
        if (parent != null) return parent.isMutable(name, span);
        throw new OqError("'" + name + "' is not defined", span, null);
    }

    private Binding find(String name) {
        Binding binding = values.get(name);
        if (binding != null) return binding;
        return parent == null ? null : parent.find(name);
    }

    public void set(String name, Object value, Span span) {
        Binding binding = values.get(name);
        if (binding != null) {
            if (!binding.mutable()) {
                throw new OqError("'" + name + "' cannot be changed", span,
                        "It is a fixed value in this scope.");
            }
            values.put(name, new Binding(value, true, false));
            return;
        }
        if (parent != null) {
            parent.set(name, value, span);
            return;
        }
        throw new OqError("'" + name + "' is not defined", span,
                "Create it first with " + name + " = value");
    }

    public boolean isDefined(String name) {
        if (values.containsKey(name)) return true;
        return parent != null && parent.isDefined(name);
    }

    public void assignOrDefine(String name, Object value, Span span) {
        Binding existing = find(name);
        if (existing == null || existing.shadowable()) {
            values.put(name, new Binding(value, true, false));
            return;
        }
        set(name, value, span);
    }

    public void assignLocal(String name, Object value) {
        values.put(name, new Binding(value, true, false));
    }
}
