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
