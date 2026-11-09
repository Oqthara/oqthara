package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.util.HashMap;
import java.util.Map;

public final class Env {
    private record Binding(Object value, boolean mutable, boolean shadowable, String fixedBy,
            String hint) {
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
        values.put(name, new Binding(value, mutable, false, null, null));
    }

    /**
     * Declares a binding that cannot be reassigned. Loop variables, caught errors and
     * {@code const} all fix a name, but for different reasons, so the reason and the recovery
     * advice are supplied by the caller that knows which one applies.
     */
    public void defineFixed(String name, Object value, String fixedBy, String hint) {
        values.put(name, new Binding(value, false, false, fixedBy, hint));
    }

    public void defineBuiltin(String name, Object value) {
        values.put(name, new Binding(value, false, true, "a built-in",
                "Use a different name."));
    }

    public boolean hasLocal(String name) {
        return values.containsKey(name);
    }

    /** The value bound directly in this scope, or {@code null} when the name is not here. */
    public Object getLocal(String name) {
        Binding binding = values.get(name);
        return binding == null ? null : binding.value();
    }

    /**
     * True when this scope's binding for a name may be replaced by a user definition. Built-ins
     * are shadowable so that a program's own function wins, as the interpreter has always
     * documented; a user definition is not, because redefining it is a duplicate signature.
     */
    public boolean isShadowableLocal(String name) {
        Binding binding = values.get(name);
        return binding != null && binding.shadowable();
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
                String reason = binding.fixedBy() != null
                        ? "It is " + binding.fixedBy() + "."
                        : "It is read-only here.";
                throw new OqError("cannot change '" + name + "'", span,
                        binding.hint() == null ? reason : reason + " " + binding.hint());
            }
            values.put(name, new Binding(value, true, false, null, null));
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
            values.put(name, new Binding(value, true, false, null, null));
            return;
        }
        set(name, value, span);
    }

    public void assignLocal(String name, Object value) {
        values.put(name, new Binding(value, true, false, null, null));
    }
}
