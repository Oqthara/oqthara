package dev.oqthara.runtime;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

public interface OqHost {
    Object call(Object callee, List<Object> args, Span span);

    PrintStream out();

    PrintStream err();

    BufferedReader in();

    List<String> programArgs();

    Path workingDirectory();
}
