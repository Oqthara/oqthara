package dev.oqthara.runtime;

public interface MethodResolver {
    OqCallable resolve(Object receiver, String name, Span span);
}
