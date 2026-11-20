package dev.oqthara.stdlib;

import com.code_intelligence.jazzer.api.BugDetectors;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.api.SilentCloseable;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.oqthara.core.Interpreter;
import dev.oqthara.runtime.OqCallable;
import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Fuzzes path resolution, the half of the stdlib the other targets left out.
 *
 * <p>{@link StdlibFormatsFuzzTest} says path resolution is absent because it reads the filesystem
 * through the interpreter's working directory, so a seed could not be replayed on a machine with
 * a different tree. That is true of the <em>result</em> and not of the <em>resolution</em>: the
 * builtins that only resolve a path never look at what is under it. Pinning the base directory to
 * a fresh empty temporary directory makes every one of them a pure function of its argument, so a
 * seed replays anywhere.
 *
 * <p>The property is that no path text makes a path builtin throw anything but {@code OqError}.
 * This is not hypothetical: {@code Patterns.resolve} and two copies in {@code Stdlib} parsed with
 * {@code Path.of} and let {@code InvalidPathException} escape, so {@code glob} and fourteen other
 * builtins reported "internal error ... This is a bug in Oqthara" and exit 70 for a program that
 * had merely passed a NUL. A fuzzer feeding arbitrary text into a path builtin finds that on its
 * own; these targets exist so the next unguarded copy is found the same way.
 *
 * <p>Deliberately not covered: {@code save}, {@code append}, {@code write_bytes}, {@code delete},
 * {@code make_dir}, {@code copy_file} and {@code move_file} change the tree, so a seed would leave
 * each run with a different base and stop replaying. {@code read} and {@code read_lines} are left
 * out for the same reason in reverse: they succeed only on a file the seed cannot name ahead of
 * time, and the fuzzer would spend its budget on {@code ENOENT} rather than on resolution.
 */
class PathResolutionFuzzTest {
    private static final int MAX_TEXT = 512;

    /**
     * The builtins that resolve their argument and then answer a question about the tree.
     *
     * <p>Every one is read-only, so the base directory stays empty and a seed means the same thing
     * on every machine. The argument list each takes is built by {@link #argumentsFor}.
     */
    private static final String[] RESOLVING_BUILTINS = {
            "exists", "is_file", "is_dir", "file_size", "list_files", "walk", "stat",
            "path_absolute", "path_normalize", "path_name", "path_parent", "path_join",
            "glob", "read", "read_lines", "read_bytes", "read_lines_or_empty",
    };

    @FuzzTest(maxDuration = "30s")
    void resolvedPathsNeverEscapeAsAnInternalError(FuzzedDataProvider data) {
        String name = data.pickValue(RESOLVING_BUILTINS);
        List<Object> args = argumentsFor(name, data);
        try {
            call(name, args);
        } catch (OqError expected) {
        } catch (RuntimeException | StackOverflowError e) {
            throw new AssertionError(name + "() crashed on " + describe(name, args)
                    + " instead of reporting it: " + e, e);
        }
    }

    /**
     * Feeds text that produces a {@link java.nio.file.InvalidPathException} on this platform.
     *
     * <p>Random text almost never contains a NUL, so the free-form target above would have to run
     * for a very long time to reach the exact input that exposed the missing guard. This target
     * puts the offending character where it matters and varies everything around it.
     */
    @FuzzTest(maxDuration = "15s")
    void aNulInAPathIsRefusedByName(FuzzedDataProvider data) {
        String name = data.pickValue(RESOLVING_BUILTINS);
        int at = data.consumeInt(0, 4);
        StringBuilder text = new StringBuilder(data.consumeString(4));
        text.insert(Math.min(at, text.length()), '\u0000');
        List<Object> args = argumentsFor(name, data);
        args.set(0, text.toString());
        try {
            call(name, args);
            // A NUL is not a path on this platform, so reaching here means the function accepted
            // one. That is as wrong as crashing, though it is less visible.
            throw new AssertionError(name + "() accepted a NUL in " + repr(text.toString()));
        } catch (OqError expected) {
            String message = expected.getMessage();
            if (message.contains("internal error")) {
                throw new AssertionError(name + "() blamed Oqthara for " + repr(text.toString())
                        + ": " + message, expected);
            }
        } catch (RuntimeException | StackOverflowError e) {
            throw new AssertionError(name + "() crashed on " + describe(name, args)
                    + " instead of reporting it: " + e, e);
        }
    }

    /**
     * A path that resolves absolutely or relatively must come back absolute either way.
     *
     * <p>{@code path_absolute} is the only builtin that states this in the language, so it is
     * checked directly rather than inferred. A relative answer would make the function's name
     * false and would change meaning with the process's directory.
     */
    @FuzzTest(maxDuration = "15s")
    void pathAbsoluteAlwaysAnswersAnAbsolutePath(FuzzedDataProvider data) {
        String text = data.consumeString(MAX_TEXT);
        Object result;
        try {
            result = call("path_absolute", List.of(text));
        } catch (OqError expected) {
            return;
        } catch (RuntimeException | StackOverflowError e) {
            throw new AssertionError("path_absolute() crashed on " + repr(text) + ": " + e, e);
        }
        if (!(result instanceof String answered)) {
            throw new AssertionError("path_absolute(" + repr(text) + ") returned "
                    + (result == null ? "nothing" : result.getClass().getSimpleName()));
        }
        if (!Path.of(answered).isAbsolute()) {
            throw new AssertionError("path_absolute(" + repr(text) + ") answered "
                    + repr(answered) + ", which is relative");
        }
    }

    private static List<Object> argumentsFor(String name, FuzzedDataProvider data) {
        String text = data.consumeString(MAX_TEXT);
        List<Object> args = new ArrayList<>();
        args.add(text);
        switch (name) {
            case "path_join" -> args.add(data.consumeString(MAX_TEXT));
            case "glob" -> args.add(data.pickValue(new String[] {"*", "**/*.oq", "*.txt", "a*"}));
            default -> {
            }
        }
        return args;
    }

    private static Object call(String name, List<Object> args) {
        OqCallable callable = INTERPRETER.builtins().get(name);
        if (callable == null) throw new AssertionError("stdlib does not define '" + name + "'");
        try (SilentCloseable ignored = withoutTraversalGuidance()) {
            return callable.call(INTERPRETER, args, Span.synthetic());
        }
    }

    /**
     * Turns off Jazzer's path-traversal <em>guidance</em> while a builtin runs.
     *
     * <p>Jazzer steers inputs towards {@code ../jazzer-traversal} and reports a critical finding
     * the moment one arrives there. That is useful for an application that must not escape its
     * directory and wrong for this one: reading {@code ../x} is the program doing what it was
     * written to do, which TH-02 accepts and section 4 of the threat model spells out. Left on,
     * the guidance does the steering itself, so the finding is about Jazzer's own sentinel rather
     * than about Oqthara — CircleCI failed on exactly that, at
     * {@code Files.newInputStream} resolving to {@code <module>/../jazzer-traversal}.
     *
     * <p>Only the guidance is disabled. The sanitizer's {@code checkPath} predicate is untouched
     * and still defaults to allowing everything, so a real finding remains possible for whoever
     * installs a predicate; and the hook is left installed rather than unregistered, because a
     * blanket {@code jazzer.disabled_hooks} would also blind the targets that read archives, where
     * a traversal in a jar entry name is zip slip and is a real vulnerability.
     */
    private static SilentCloseable withoutTraversalGuidance() {
        try {
            return BugDetectors.setFilePathTraversalTarget(() -> null);
        } catch (RuntimeException | LinkageError e) {
            // The sanitizer is absent (unit-test runs) or its internals moved; the property under
            // test does not depend on it, so fall through rather than fail the target.
            return null;
        }
    }

    /**
     * One interpreter for the whole class, based at a directory that is empty and stays empty.
     *
     * <p>Created with {@code Files.createTempDirectory} rather than through JUnit's {@code @TempDir}
     * because a static field cannot be injected, and the base has to be stable across the inputs a
     * single fuzzer run replays.
     */
    private static final Interpreter INTERPRETER = createInterpreter();

    private static Interpreter createInterpreter() {
        PrintStream sink = new PrintStream(OutputStream.nullOutputStream(), true,
                StandardCharsets.UTF_8);
        Path base;
        try {
            base = java.nio.file.Files.createTempDirectory("oq-path-fuzz");
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        Interpreter interpreter = new Interpreter(sink, sink,
                new BufferedReader(new StringReader("")), List.of(), base,
                new MethodResolverImpl());
        Stdlib.install(interpreter);
        return interpreter;
    }

    private static String describe(String name, List<Object> args) {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(shortRepr(args.get(i)));
        }
        return sb.append(')').toString();
    }

    private static String shortRepr(Object value) {
        String text = dev.oqthara.runtime.Values.repr(value);
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    private static String repr(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
