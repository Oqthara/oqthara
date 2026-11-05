package dev.oqthara.bench;

import dev.oqthara.core.Ast;
import dev.oqthara.core.Interpreter;
import dev.oqthara.core.Parser;
import dev.oqthara.stdlib.MethodResolverImpl;
import dev.oqthara.stdlib.Stdlib;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds an interpreter the same way the CLI does and captures its output.
 *
 * <p>Output goes to an in-memory stream, never the console, so the measurement
 * covers interpretation and not terminal I/O.
 */
public final class Runner {

    public static Ast.Program parse(Workloads.Workload workload) {
        return Parser.parse(workload.name() + ".oq", workload.source());
    }

    /**
     * Runs the workload once on a fresh interpreter. A fresh instance per call is
     * required: globals and function definitions would otherwise leak between
     * invocations and change what is being measured.
     */
    public static String run(Workloads.Workload workload, Ast.Program program) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream(256);
        PrintStream out = new PrintStream(sink, false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(sink, false, StandardCharsets.UTF_8);
        Interpreter interpreter = new Interpreter(
                out,
                err,
                new BufferedReader(new StringReader("")),
                List.of(),
                Path.of("."),
                new MethodResolverImpl());
        Stdlib.install(interpreter);
        interpreter.run(program);
        out.flush();
        err.flush();
        // `print` goes through PrintStream.println, which uses the platform line separator.
        // A workload declares its expected output once, so normalize CRLF here instead of
        // giving every workload a platform-specific expectation.
        return sink.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /**
     * Checks the workload once and fails loudly if the program errored or printed
     * something unexpected. The message names the workload so a benchmark failure
     * points at the program, not at JMH.
     */
    public static void verify(Workloads.Workload workload, Ast.Program program) {
        String actual = run(workload, program);
        if (!workload.expected().equals(actual)) {
            throw new IllegalStateException(
                    "benchmark program '" + workload.name() + "' produced unexpected output\n"
                            + "expected: " + escape(workload.expected()) + "\n"
                            + "actual:   " + escape(actual));
        }
    }

    private static String escape(String text) {
        return text.replace("\n", "\\n");
    }

    private Runner() {}
}
