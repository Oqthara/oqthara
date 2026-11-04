package dev.oqthara.runtime;

import java.io.PrintStream;
import java.util.List;

public interface OqCallable {
    String name();

    Object call(OqHost host, List<Object> args, Span span);
}
