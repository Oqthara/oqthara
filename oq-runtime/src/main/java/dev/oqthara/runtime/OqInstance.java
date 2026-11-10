package dev.oqthara.runtime;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * One object built from a {@code class} declaration (ADR-0027). Unlike {@link OqData}, which is
 * a value type compared by content, an instance is a reference type identified by its own
 * identity: two instances of the same class with equal fields are never {@code ==}. Equality is
 * therefore left as object identity here, and {@code Values.equal} does not reach inside.
 *
 * <p>Fields are kept in declaration order so {@code repr} and {@code type_of} read the same way
 * they do for {@code data}.
 */
public final class OqInstance {
    private final String className;
    private final LinkedHashMap<String, Object> fields;
    // The declaration this instance was built from, kept as an opaque handle because the
    // declaration type lives in oq-core and this module must not depend on it. Carrying the
    // declaration itself, rather than looking it up by name later, is what stops a second class
    // with the same name in an inner scope from silently retargeting instances of the first.
    private Object declaration;

    public OqInstance(String className, LinkedHashMap<String, Object> fields) {
        this.className = Objects.requireNonNull(className, "className");
        this.fields = new LinkedHashMap<>(Objects.requireNonNull(fields, "fields"));
    }

    public String className() {
        return className;
    }

    /** The opaque declaration handle, or {@code null} before the constructor binds one. */
    public Object declaration() {
        return declaration;
    }

    public void bindDeclaration(Object declaration) {
        this.declaration = Objects.requireNonNull(declaration, "declaration");
    }

    public LinkedHashMap<String, Object> fields() {
        return fields;
    }

    public boolean has(String name) {
        return fields.containsKey(name);
    }

    public Object get(String name) {
        return fields.get(name);
    }

    public void set(String name, Object value) {
        fields.put(name, value);
    }

    @Override
    public String toString() {
        return Values.repr(this);
    }
}
