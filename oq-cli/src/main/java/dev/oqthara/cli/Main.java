package dev.oqthara.cli;

import dev.oqthara.core.Ast;
import dev.oqthara.core.Diagnostics;
import dev.oqthara.core.Interpreter;
import dev.oqthara.core.Modules;
import dev.oqthara.core.Parser;
import dev.oqthara.compiler.Backends;
import dev.oqthara.compiler.Ir;
import dev.oqthara.compiler.JarBuilder;
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
    public static final String VERSION = "0.1.0-alpha.4";

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
                case "doc" -> DocGenerator.run(out, err, rest(args));
                case "test" -> TestRunner.run(out, err, rest(args));
                case "build" -> build(rest(args));
                case "java" -> javaRuntime(rest(args));
                case "runtime" -> runtime(rest(args));
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
                      oq build <file.oq>         compile a program to a runnable jar
                      oq java [--json]           show which Java runtime Oqthara will use
                      oq runtime install         download and install a managed Java runtime
                      oq runtime status          show the managed runtime, if any
                      oq new <name>              create a new project
                      oq doc [root]              generate the stdlib reference
                      oq doc --source <path>     document your own declarations
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
            return execute(file.getFileName().toString(), source, file.toAbsolutePath().getParent(),
                    programArgs, file.toAbsolutePath());
        }

        int execute(String name, String source, Path directory, List<String> programArgs) {
            return execute(name, source, directory, programArgs, null);
        }

        int execute(String name, String source, Path directory, List<String> programArgs, Path file) {
            Ast.Program program;
            try {
                program = Parser.parse(name, source);
                if (file != null) {
                    program = Modules.resolve(file, program);
                }
            } catch (OqError error) {
                Details.render(err, error, source, name, color);
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
                Details.render(err, error, source, name, color);
                return 1;
            } catch (StackOverflowError overflow) {
                EmbeddedMain.renderFailure(err, overflow);
                return 1;
            } catch (OutOfMemoryError notEnough) {
                EmbeddedMain.renderFailure(err, notEnough);
                return 1;
            } catch (RuntimeException e) {
                // One renderer for both backends, so a jar and `oq run` never disagree about
                // who is to blame for the same failure.
                EmbeddedMain.renderFailure(err, e);
                return EmbeddedMain.exitCodeFor(e);
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
                Details.render(err, error, readSafeString(path), path, color);
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

        /**
         * Reports the runtime that {@code oq} would use, and where it looked.
         *
         * <p>This is the observable face of ADR-0048 D1. Without it, resolution is an invisible
         * decision until something fails, and the failure happens far from the cause.
         */
        int javaRuntime(String[] args) {
            boolean json = false;
            for (String arg : args) {
                switch (arg) {
                    case "--json" -> json = true;
                    case "--help", "-h" -> {
                        out.println("""
                                Usage: oq java [--json]

                                Shows the Java runtime Oqthara would use, and every place it looked.

                                  --json   machine-readable output

                                The order is: OQ_JAVA, then a .oq/java project pin, then a managed
                                runtime under OQ_HOME (or ~/.oq), then java on PATH. The first
                                executable that exists and is Java 21 or newer wins.""");
                        return 0;
                    }
                    default -> {
                        err.println("Unknown option: " + arg);
                        err.println("Try: oq java [--json]");
                        return 2;
                    }
                }
            }
            RuntimeResolver.Inputs inputs = RuntimeResolver.inputsFrom(
                    Path.of("").toAbsolutePath(), System.getenv());
            RuntimeResolver.Resolution resolution =
                    RuntimeResolver.resolve(inputs, RuntimeResolver.processProbe());
            PlatformSupport.Platform platform = currentPlatform();
            if (json) {
                out.println(jsonRuntime(resolution, platform));
                return resolution.found() ? 0 : 1;
            }
            if (!resolution.found()) {
                err.println(RuntimeResolver.explainFailure(resolution,
                        RuntimeResolver.managedHome(System.getenv()).map(Path::toString)
                                .orElse(null)));
                if (!platform.supported()) {
                    err.println();
                    err.println(PlatformSupport.explainUnsupported(
                            System.getProperty("os.name"), System.getProperty("os.arch")));
                }
                return 1;
            }
            out.println(resolution.chosen().describe());
            for (RuntimeResolver.Candidate skipped : resolution.rejected()) {
                out.println("  skipped " + skipped.describe());
            }
            return 0;
        }

        /**
         * Downloads, verifies and installs the managed runtime (ADR-0048 D3, D5, D6, D7).
         *
         * <p>The command exists because D3 had no user-reachable entry point: {@code RuntimeInstaller}
         * could install an archive and nothing could obtain one, so provenance had nothing to
         * protect. Every refusal below is reported rather than thrown, because a command that ends
         * in a stack trace tells the user nothing about what to do next.
         */
        int runtime(String[] args) {
            String action = args.length == 0 ? "status" : args[0];
            switch (action) {
                case "--help", "-h", "help" -> {
                    out.println("""
                            Usage: oq runtime <command>

                            Manages the Java runtime Oqthara installs for itself.

                              status    show whether a managed runtime is installed
                              install   download the pinned runtime, verify it, and install it

                            The download is checked against a SHA-256 digest compiled into oq before
                            any of it is extracted. An existing runtime is kept at
                            $OQ_HOME/runtime/java.prev so an upgrade can be undone.

                            This never replaces a runtime found through OQ_JAVA, a project pin or
                            PATH: those are preferred by resolution and are not managed here.""");
                    return 0;
                }
                case "status" -> {
                    return runtimeStatus();
                }
                case "install" -> {
                    return runtimeInstall(rest(args));
                }
                default -> {
                    err.println("Unknown runtime command: " + action);
                    err.println("Try: oq runtime [status|install]");
                    return 2;
                }
            }
        }

        private int runtimeStatus() {
            PlatformSupport.Platform platform = currentPlatform();
            RuntimeResolver.Resolution resolution =
                    RuntimeResolver.resolve(RuntimeResolver.inputsFrom(
                                    Path.of("").toAbsolutePath(), System.getenv()),
                            RuntimeResolver.processProbe());
            if (!platform.supported()) {
                err.println(PlatformSupport.explainUnsupported(
                        System.getProperty("os.name"), System.getProperty("os.arch")));
                return 1;
            }
            RuntimeRetention retention = RuntimeResolver.managedHome(System.getenv())
                    .map(RuntimeRetention::new)
                    .orElse(null);
            if (retention == null) {
                err.println("No managed home is known: set OQ_HOME or HOME.");
                return 1;
            }
            out.println("platform   " + platform.label());
            out.println("pinned     " + RuntimeProvenance.describePin());
            out.println(retention.describe());
            if (resolution.found()) {
                out.println("in use     " + resolution.chosen().describe());
            }
            return 0;
        }

        private int runtimeInstall(String[] args) {
            for (String arg : args) {
                if (!arg.equals("--force")) {
                    err.println("Unknown option: " + arg);
                    err.println("Try: oq runtime install [--force]");
                    return 2;
                }
            }
            PlatformSupport.Platform platform = currentPlatform();
            if (!platform.supported()) {
                err.println(PlatformSupport.explainUnsupported(
                        System.getProperty("os.name"), System.getProperty("os.arch")));
                return 1;
            }
            java.util.Optional<Path> home = RuntimeResolver.managedHome(System.getenv());
            if (home.isEmpty()) {
                err.println("No managed home is known: set OQ_HOME or HOME.");
                return 1;
            }
            RuntimeInstaller installer = RuntimeInstaller.forCommand(home.get(), runningJar());
            out.println("Downloading " + RuntimeProvenance.describePin() + " for "
                    + platform.label() + "...");
            try {
                Path java = installer.installDownloaded(platform, 21);
                out.println("Installed " + java);
                out.println("Run 'oq java' to confirm which runtime is now used.");
                return 0;
            } catch (RuntimeInstaller.InstallRefusedException e) {
                err.println("Refused: " + e.getMessage());
                err.println("Nothing was installed and any existing runtime is unchanged.");
                return 1;
            } catch (IOException e) {
                err.println("Install failed: " + e.getMessage());
                err.println("Nothing was installed and any existing runtime is unchanged.");
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err.println("Install was interrupted; nothing was activated.");
                return 1;
            }
        }

        /**
         * The jar this process is running from, or {@code null} when it is running from classes.
         *
         * <p>ADR-0048 D9 runs that jar on the candidate JVM, so the check needs a jar. A source
         * checkout on a class path has none, and the installer treats {@code null} as "validate
         * the reported version only" rather than failing — refusing to install for a developer
         * running from {@code build/classes} would be a worse trade than a weaker check.
         */
        private static Path runningJar() {
            try {
                java.security.CodeSource source =
                        Main.class.getProtectionDomain().getCodeSource();
                if (source == null || source.getLocation() == null) {
                    return null;
                }
                Path location = Path.of(source.getLocation().toURI());
                return Files.isRegularFile(location) ? location : null;
            } catch (java.net.URISyntaxException | SecurityException e) {
                return null;
            }
        }

        /**
         * The platform, for the diagnostic that says a managed runtime cannot be installed here.
         *
         * <p>Reported only when nothing resolved. Resolution on {@code PATH} is unaffected by
         * platform support, so mentioning it on success would imply a limit that does not exist.
         */
        private static PlatformSupport.Platform currentPlatform() {
            return PlatformSupport.detect(
                            System.getProperty("os.name"), System.getProperty("os.arch"))
                    .orElse(new PlatformSupport.Platform("unknown", "unknown"));
        }

        static String jsonRuntime(RuntimeResolver.Resolution resolution,
                                          PlatformSupport.Platform platform) {
            StringBuilder json = new StringBuilder("{\"found\":")
                    .append(resolution.found())
                    .append(",\"platform\":\"").append(platform.label())
                    .append("\",\"platformSupported\":").append(platform.supported());
            if (resolution.found()) {
                RuntimeResolver.Candidate chosen = resolution.chosen();
                json.append(",\"origin\":\"").append(chosen.origin().name().toLowerCase(java.util.Locale.ROOT))
                        .append("\",\"location\":\"").append(escapeJson(chosen.location()))
                        .append("\",\"major\":").append(chosen.major().getAsInt());
            }
            json.append(",\"rejected\":[");
            for (int i = 0; i < resolution.rejected().size(); i++) {
                RuntimeResolver.Candidate skipped = resolution.rejected().get(i);
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"origin\":\"").append(skipped.origin().name().toLowerCase(java.util.Locale.ROOT))
                        .append("\",\"location\":\"").append(escapeJson(skipped.location()))
                        .append("\",\"reason\":\"").append(escapeJson(skipped.rejection()))
                        .append("\"}");
            }
            return json.append("]}").toString();
        }

        private static String escapeJson(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        int build(String[] args) {
            if (args.length == 0) {
                err.println("Missing program file. Try: oq build hello.oq");
                return 2;
            }
            // `oq build --help` used to be treated as a file named `--help.oq`, which is a
            // confusing error for a very ordinary thing to type.
            if (args[0].equals("--help") || args[0].equals("-h")) {
                out.println("""
                        Usage: oq build <file.oq> [--emit-ir]

                        Compiles a program into a runnable jar next to the source file.

                          --emit-ir   print the lowered IR before packaging

                        A program compiles to real bytecode when it stays inside the supported
                        subset; otherwise the jar launches the interpreter and the reason is
                        printed. Either way the jar prints what `oq run` prints.""");
                return 0;
            }
            if (args[0].startsWith("-")) {
                err.println("Unknown option: " + args[0]);
                err.println("Try: oq build <file.oq> [--emit-ir]");
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
                Details.render(err, error, source, path, color);
                return 1;
            }
            Lowering.Result lowered = Lowering.lowerWithReport(program);
            Ir.Program ir = lowered.program();
            boolean emitIr = false;
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--emit-ir")) emitIr = true;
            }
            if (emitIr) {
                out.print(Ir.disassemble(ir));
            }
            out.println("compiled " + path + " (" + ir.functions().size() + " functions, "
                    + ir.main().body().size() + " main instructions)");
            for (String divergence : lowered.divergences()) {
                err.println("warning: " + divergence + " — the interpreter is authoritative");
            }
            out.println("backend=classfile (self-contained writer, JDK " + Backends.featureVersion() + ")");
            String stem = path.endsWith(".oq") ? path.substring(0, path.length() - 3) : path;
            Path jar = Path.of(stem + ".jar");
            String className = "oq.generated.Program";
            try {
                String selfContained = Modules.flatten(Path.of(path), source);
                byte[] compiled = null;
                String reason = dev.oqthara.compiler.BytecodeCompiler.whyNot(ir);
                if (reason == null) {
                    compiled = dev.oqthara.compiler.BytecodeCompiler.compile(ir, className);
                    out.println("codepath: compiled bytecode");
                } else {
                    out.println("codepath: interpreter launcher (" + reason + ")");
                }
                JarBuilder.Result built = JarBuilder.build(jar, className, selfContained,
                        Path.of(path).getFileName().toString(), compiled);
                out.println("wrote " + jar + " (" + built.classes() + " classes, "
                        + built.skipped() + " skipped)");
                out.println("run it with: java -jar " + jar);
                return 0;
            } catch (IOException e) {
                err.println("cannot write " + jar + ": " + e.getMessage());
                return 1;
            }
        }

        int repl() {
            out.println("Oqthara " + VERSION + " — type 'exit()' or Ctrl-D to quit");
            Repl repl = new Repl(out, err, in, new MethodResolverImpl());
            return repl.start();
        }
    }
}
