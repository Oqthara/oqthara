package dev.oqthara.runtime;

import java.util.Objects;

public final class OqData {
    private final String typeName;
    private final java.util.LinkedHashMap<String, Object> fields;

    public OqData(String typeName, java.util.LinkedHashMap<String, Object> fields) {
        this.typeName = Objects.requireNonNull(typeName);
        this.fields = new java.util.LinkedHashMap<>(fields);
    }

    public String typeName() {
        return typeName;
    }

    public java.util.LinkedHashMap<String, Object> fields() {
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
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OqData data)) return false;
        return typeName.equals(data.typeName) && fields.equals(data.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeName, fields);
    }

    @Override
    public String toString() {
        return Values.repr(this);
    }
}
