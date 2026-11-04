package dev.oqthara.cli;

import dev.oqthara.core.Ast;
import dev.oqthara.core.Diagnostics;
import dev.oqthara.core.Interpreter;
import dev.oqthara.core.Parser;
import dev.oqthara.compiler.Backends;
import dev.oqthara.compiler.Ir;
import dev.oqthara.compiler.Lowering;
import dev.oqthara.runtime.OqError;
import dev.oqthara.stdlib.MethodResolverImpl;
import dev.oqthara.stdlib.Stdlib;
import dev.oqthara.stdlib.Stdlib.ExitSignal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Main {
    public static final String VERSION = "0.1.0-alpha.1";

    public static void main(String[] args) {
        int code = new Runner(System.out, System.err, new BufferedReader(new InputStreamReader(System.in)))
                .dispatch(args);
        if (code != 0) System.exit(code);
    }

    static final class Runner {
        private final PrintStream out;
        private final PrintStream err;
        private final BufferedReader in;
        private final boolean color;

        Runner(PrintStream out, PrintStream err, BufferedReader in) {
            this.out = out;
            this.err = err;
            this.in = in;
            this.color = System.getenv("NO_COLOR") == null && System.console() != null;
        }

        int dispatch(String[] args) {
            if (args.length == 0) {
                out.println(help());
                return 0;
            }
            String command = args[0];
            return switch (command) {
                case "run" -> run(rest(args));
                case "repl" -> repl();
                case "check" -> check(rest(args));
                case "version", "--version", "-v" -> {
                    out.println("oq " + VERSION);
                    yield 0;
                }
                case "help", "--help", "-h" -> {
                    out.println(help());
                    yield 0;
                }
                case "fmt", "format" -> Formatter.formatFiles(out, err, rest(args));
                case "new" -> Scaffold.create(out, err, rest(args));
                case "test" -> TestRunner.run(out, err, rest(args));
                case "build" -> build(rest(args));
                default -> {
                    if (command.startsWith("-")) {
                        err.println("Unknown option: " + command);
                        err.println("Run 'oq help' to see the available commands.");
                        yield 2;
                    }
                    yield run(args);
                }
            };
        }

        private static String[] rest(String[] args) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            return rest;
        }

        private String help() {
            return """
                    oq — the Oqthara language

                    Usage:
                      oq <file.oq> [args...]     run a program
                      oq run <file.oq> [args...] run a program
                      oq check <file.oq>         check a program without running it
                      oq repl                    start an interactive session
                      oq fmt <file.oq>           format a program
                      oq test [dir]              run test_*.oq files
                      oq build <file.oq>         lower a program to IR (bytecode needs JDK 24+)
                      oq new <name>              create a new project
                      oq version                 show the version
                      oq help                    show this help

                    Learn more at https://oqthara.dev""";
        }

        int run(String[] args) {
            if (args.length == 0) {
                err.println("Missing program file. Try: oq hello.oq");
                return 2;
            }
            String path = args[0];
            if (!path.endsWith(".oq")) {
                path = path + ".oq";
            }
            Path file = Path.of(path);
            if (!Files.exists(file)) {
                err.println("Cannot find file '" + path + "'.");
                return 2;
            }
            String source;
            try {
                source = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                err.println("Cannot read '" + path + "': " + e.getMessage());
                return 2;
            }
            List<String> programArgs = new ArrayList<>();
            for (int i = 1; i < args.length; i++) programArgs.add(args[i]);
            return execute(file.getFileName().toString(), source, file.toAbsolutePath().getParent(), programArgs);
        }

        int execute(String name, String source, Path directory, List<String> programArgs) {
            Ast.Program program;
            try {
                program = Parser.parse(name, source);
                if (!program.statements().isEmpty()) {
                    // parsed successfully
                }
            } catch (OqError error) {
                Details.render(err, error, source, color);
                return 1;
            }
            Interpreter interpreter = new Interpreter(out, err, in, programArgs,
                    directory == null ? Path.of(".") : directory, new MethodResolverImpl());
            Stdlib.install(interpreter);
            try {
                interpreter.run(program);
                return 0;
            } catch (ExitSignal exit) {
                return exit.code();
            } catch (OqError error) {
                Details.render(err, error, source, color);
                return 1;
            } catch (StackOverflowError overflow) {
                err.println();
                err.println("error: the program recursed too deeply");
                err.println("  help: check that a recursive function has a stopping condition.");
                err.println();
                return 1;
            } catch (RuntimeException e) {
                err.println();
                err.println("internal error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                err.println("  This is a bug in Oqthara. Please report it.");
                err.println();
                return 70;
            }
        }

        int check(String[] args) {
            if (args.length == 0) {
                err.println("Missing program file. Try: oq check hello.oq");
                return 2;
            }
            String path = args[0];
            if (!path.endsWith(".oq")) path = path + ".oq";
            try {
                String source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
                Parser.parse(path, source);
                out.println("OK " + path);
                return 0;
            } catch (IOException e) {
                err.println("Cannot read '" + path + "': " + e.getMessage());
                return 2;
            } catch (OqError error) {
                Details.render(err, error, readSafeString(path), color);
                return 1;
            }
        }

        private String readSafeString(String path) {
            try {
                return Files.readString(Path.of(path), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }

        int build(String[] args) {
            if (args.length == 0) {
                err.println("Missing program file. Try: oq build hello.oq");
                return 2;
            }
            String path = args[0].endsWith(".oq") ? args[0] : args[0] + ".oq";
            String source;
            try {
                source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            } catch (IOException e) {
                err.println("Cannot read '" + path + "': " + e.getMessage());
                return 2;
            }
            Ast.Program program;
            try {
                program = Parser.parse(path, source);
            } catch (OqError error) {
                Details.render(err, error, source, color);
                return 1;
            }
            Ir.Program ir = Lowering.lower(program);
            boolean emitIr = false;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--emit-ir")) emitIr = true;
            }
            if (emitIr) {
                out.print(Ir.disassemble(ir));
            }
            Backends.Kind kind = Backends.select();
            out.println("compiled " + path + " (" + ir.functions().size() + " functions, "
                    + ir.main().body().size() + " main instructions)");
            out.println(Backends.describe());
            if (kind == Backends.Kind.DIAGNOSTIC) {
                err.println(Backends.diagnostic(kind, Backends.featureVersion()));
                return 3;
            }
            out.println("classfile emission is not implemented yet; IR lowering only");
            return 0;
        }

        int repl() {
            out.println("Oqthara " + VERSION + " — type 'exit()' or Ctrl-D to quit");
            Repl repl = new Repl(out, err, in, new MethodResolverImpl());
            return repl.start();
        }
    }
}
