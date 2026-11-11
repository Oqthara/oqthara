package dev.oqthara.cli;

import dev.oqthara.core.Ast;
import dev.oqthara.core.Interpreter;
import dev.oqthara.core.Parser;
import dev.oqthara.runtime.OqError;
import dev.oqthara.stdlib.MethodResolverImpl;
import dev.oqthara.stdlib.Stdlib;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class OqRunner {
    private OqRunner() {
    }

    public record Result(int code, String stdout, String stderr) {
    }

    public static Result run(String sourceName, String source, List<String> args, String stdin, Path cwd) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new StringReader(stdin == null ? "" : stdin));
        try {
            Ast.Program program = Parser.parse(sourceName, source);
            Interpreter interpreter = new Interpreter(out, err, in, args, cwd, new MethodResolverImpl());
            Stdlib.install(interpreter);
            interpreter.run(program);
            out.flush();
            err.flush();
            return new Result(0, normalize(outBytes), normalize(errBytes));
        } catch (Stdlib.ExitSignal exit) {
            out.flush();
            err.flush();
            return new Result(exit.code(), normalize(outBytes), normalize(errBytes));
        } catch (OqError error) {
            out.flush();
            StringBuilder message = new StringBuilder(error.getMessage());
            if (error.span() != null && error.span().line() > 0) {
                message.append("\n  --> ").append(error.span().source())
                        .append(':').append(error.span().line())
                        .append(':').append(error.span().column());
            }
            if (error.hint() != null) message.append('\n').append(error.hint());
            err.print(message);
            err.flush();
            return new Result(1, normalize(outBytes), normalize(errBytes));
        } catch (StackOverflowError overflow) {
            // `oq run` and a built jar both catch this and report it as a program failure
            // (EmbeddedMain.renderFailure); `oq test` did not, so on a small thread stack a
            // runaway recursion escaped as a raw stack trace and the runner reported success.
            // The depth guard in Interpreter is supposed to fire first, but its ceiling is a
            // count of Oqthara calls and the Java frames per call are what a smaller stack
            // actually limits, so the two do not have to agree on Windows or a 512k stack.
            out.flush();
            err.print("error: the program recursed too deeply\n"
                    + "  help: check that a recursive function has a stopping condition.");
            err.flush();
            return new Result(1, normalize(outBytes), normalize(errBytes));
        } finally {
            out.flush();
            err.flush();
        }
    }

    public static Result run(String source) {
        return run("<test>", source, List.of(), "", Path.of("."));
    }

    private static String normalize(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    public static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(line);
        }
        return lines;
    }
}
