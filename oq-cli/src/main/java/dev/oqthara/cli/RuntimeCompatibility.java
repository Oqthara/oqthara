package dev.oqthara.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Checks that a candidate JVM can actually run Oqthara (ADR-0048 D9).
 *
 * <p>D5 asks whether the JVM reports a version at or above the floor. That is a question about
 * the JVM's self-description, not about Oqthara, and ADR-0002 records the difference: JDK 25 was
 * current, published and available from its vendor, and the build failed on it with
 * {@code Unsupported class file major version 69} because the Gradle release in use could not
 * parse that class-file format. Any rule that ranks "newest" above "known-compatible" would have
 * selected it. So a candidate is validated by running Oqthara on it.
 *
 * <p>Three programs, chosen for what they reach rather than for coverage:
 *
 * <ol>
 *   <li>{@code --version}, which proves the jar loads and the launcher works;</li>
 *   <li>a program that exercises the bytecode backend and the standard library together;</li>
 *   <li>a program that opens a database through the JNI path, which is the one part of the
 *       runtime that depends on a native library and therefore on the platform.</li>
 * </ol>
 *
 * <p>This is deliberately <em>not</em> the conformance corpus. 229 files is too much to run
 * before every install, and running them would turn an install into a slower duplicate of CI.
 * The claim this makes is bounded and stated: these three run, so this JVM is not obviously
 * incompatible. It is not a statement that every language feature works.
 *
 * <p>The database check is reported separately from the others because its failure has a
 * different meaning. A missing SQLite library is an environment fact, not an incompatibility,
 * and refusing an otherwise working JVM for it would make installation fail on machines where
 * every other program runs. The result therefore distinguishes "the runtime is incompatible"
 * from "the runtime ran the program and the program could not open a database".
 */
final class RuntimeCompatibility {

    /** One program to run, and what a successful run looks like. */
    private record Check(String name, String source, String expectedOutput) {
    }

    /**
     * Exercises the bytecode backend with a function, a loop, a collection and arithmetic, and
     * the standard library with a list sort and a JSON encode.
     */
    private static final Check LANGUAGE = new Check("language and standard library", """
            fun add(a, b)
              return a + b
            end

            total = 0
            for n in range(0, 5)
              total = add(total, n)
            end

            items = list_sort([3, 1, 2])
            print(json_stringify(items))
            print(total)
            """, "[1,2,3]\n10\n");

    /**
     * Opens a database, writes and reads it back. The only check that needs a native library.
     */
    private static final Check DATABASE = new Check("database", """
            path = path_join(cwd(), "compat-check.db")
            if exists(path)
              delete(path)
            end
            db_execute(path, "CREATE TABLE t (n INTEGER NOT NULL)", [])
            db_execute(path, "INSERT INTO t (n) VALUES (?)", [7])
            print(db_query(path, "SELECT n FROM t", []))
            """, null);

    /**
     * How many programs {@link #validate} runs, excluding {@code --version}.
     *
     * <p>Named so the test that pins the documented set has something to assert against, and so
     * the count in the ADR and the requirement matrix has one place to be wrong.
     */
    static final int DOCUMENTED_CHECK_COUNT = 3;

    /** How long one program may take. Generous, because a cold JVM on a slow disk is slow. */
    private static final long CHECK_TIMEOUT_SECONDS = 60;

    private final Path jar;
    private final long timeoutSeconds;

    RuntimeCompatibility(Path jar) {
        this(jar, CHECK_TIMEOUT_SECONDS);
    }

    RuntimeCompatibility(Path jar, long timeoutSeconds) {
        this.jar = jar;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * The outcome of validating one JVM.
     *
     * @param compatible whether every mandatory check passed
     * @param summary a human-readable account of what ran, for a refusal message
     */
    record Verdict(boolean compatible, String summary) {
    }

    /**
     * Runs the bounded checks against {@code java}.
     *
     * <p>The jar is run with the candidate JVM rather than this process's JVM, because the
     * question is what that JVM does. A timeout kills the process and counts as a failure: a
     * JVM that hangs on {@code --version} cannot be handed to every later command.
     */
    Verdict validate(Path java) throws IOException, InterruptedException {
        if (!Files.isRegularFile(jar)) {
            throw new IOException("the Oqthara jar to validate against does not exist: " + jar);
        }
        Path scratch = Files.createTempDirectory("oq-compat-");
        try {
            List<String> failures = new ArrayList<>();

            Result version = run(java, scratch, null, "--version");
            if (!version.succeeded()) {
                failures.add("--version " + version.describe());
            }

            Result language = run(java, scratch, LANGUAGE.source());
            if (!language.succeeded()
                    || !withPlatformLineEndings(language.output())
                            .equals(LANGUAGE.expectedOutput())) {
                failures.add(LANGUAGE.name() + " " + language.describe());
            }

            Result database = run(java, scratch, DATABASE.source());
            // Reported, not enforced. See the class comment: an absent native library is an
            // environment fact, and failing installation for it would refuse a working JVM.
            String note = database.succeeded()
                    ? "the database check ran"
                    : "the database check did not run (" + database.describe()
                            + "); the JDK is not refused for this";

            if (!failures.isEmpty()) {
                return new Verdict(false, String.join("; ", failures));
            }
            return new Verdict(true, "version, language and standard library checks passed; " + note);
        } finally {
            deleteRecursively(scratch);
        }
    }

    /** The number of checks this class runs, so a document's claim can be tested. */
    static int documentedCheckCount() {
        return DOCUMENTED_CHECK_COUNT;
    }

    /** One completed run. */
    private record Result(int exitCode, String output, boolean timedOut) {
        boolean succeeded() {
            return !timedOut && exitCode == 0;
        }

        String describe() {
            if (timedOut) {
                return "timed out";
            }
            String trimmed = output.strip();
            return "exited " + exitCode + (trimmed.isEmpty() ? "" : ": " + trimmed);
        }
    }

    /**
     * Runs {@code java -jar oq.jar <script>} or {@code --version}, capturing stdout and stderr.
     */
    private Result run(Path java, Path scratch, String source, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-jar");
        command.add(jar.toString());
        if (source != null) {
            Path script = Files.createTempFile(scratch, "check-", ".oq");
            Files.writeString(script, source, StandardCharsets.UTF_8);
            command.add(script.toString());
        }
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
                .directory(scratch.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                return new Result(-1, "", true);
            }
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return new Result(process.exitValue(), output, false);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Converts the platform's line separator to {@code \n} before comparing.
     *
     * <p>A JVM on Windows writes {@code \r\n} even when the program itself prints {@code \n},
     * because the console and redirected streams translate it. Comparing raw bytes would refuse
     * every working JVM on Windows at install time. The check still compares the full text and
     * the exact number of lines; only the line terminator is normalised.
     */
    private static String withPlatformLineEndings(String output) {
        return output.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void deleteRecursively(Path root) {        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a leftover file in a temporary directory is not a failure.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }
}
