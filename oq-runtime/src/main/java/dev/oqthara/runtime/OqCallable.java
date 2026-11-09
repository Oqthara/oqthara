package dev.oqthara.runtime;

import java.util.List;

public interface OqCallable {
    String name();

    Object call(OqHost host, List<Object> args, Span span);

    /**
     * The parameter count this callable accepts, or {@link #VARIADIC} when its accepted count
     * is not a single value. Overload resolution compares this number, so a callable that
     * cannot state one cannot silently win a resolution it does not fit.
     */
    default int arity() {
        return VARIADIC;
    }

    /** Marks an accepted parameter count that is not one exact number. */
    int VARIADIC = -1;
}
