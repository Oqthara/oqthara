package dev.oqthara.cli;

import dev.oqthara.core.Env;
import dev.oqthara.core.Interpreter;
import dev.oqthara.core.Parser;
import dev.oqthara.runtime.MethodResolver;
import dev.oqthara.runtime.OqError;
import dev.oqthara.stdlib.Stdlib;
import dev.oqthara.stdlib.Stdlib.ExitSignal;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

final class Repl {
    private final PrintStream out;
    private final PrintStream err;
    private final BufferedReader in;
    private final MethodResolver methods;

    Repl(PrintStream out, PrintStream err, BufferedReader in, MethodResolver methods) {
        this.out = out;
        this.err = err;
        this.in = in;
        this.methods = methods;
    }

    int start() {
        Interpreter interpreter = new Interpreter(out, err, in, List.of(), Path.of("."), methods);
        Stdlib.install(interpreter);
        Env env = new Env();
        for (var entry : interpreter.builtins().entrySet()) {
            env.defineBuiltin(entry.getKey(), entry.getValue());
        }
        while (true) {
            out.print("oq> ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (java.io.IOException e) {
                return 1;
            }
            if (line == null) {
                out.println();
                return 0;
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (trimmed.equals("exit") || trimmed.equals("exit()") || trimmed.equals("quit")) return 0;
            try {
                // One command is one unit of work, so it gets a fresh budget. The REPL never calls
                // Interpreter.run(), which is where the counters are normally reset, so without
                // this a single large command would exhaust the session permanently.
                interpreter.resetSteps();
                var program = Parser.parse("<repl>", line);
                if (program.statements().size() == 1
                        && program.statements().get(0) instanceof dev.oqthara.core.Ast.ExprStmt exprStmt) {
                    Object value = interpreter.evaluate(exprStmt.expr(), env);
                    if (value != null) out.println(dev.oqthara.runtime.Values.repr(value));
                } else {
                    for (var stmt : program.statements()) {
                        interpreter.execute(stmt, env);
                    }
                }
            } catch (ExitSignal exit) {
                return exit.code();
            } catch (OqError error) {
                Details.render(err, error, line, "<repl>", System.console() != null);
            } catch (RuntimeException e) {
                err.println("error: " + e.getMessage());
            }
        }
    }
}
