package dev.oqthara.cli;

import dev.oqthara.compiler.ClassFileWriter;
import dev.oqthara.compiler.JarBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-0038 acceptance: {@code oq build} produces a real, executable jar and the jar reproduces
 * the interpreter's output.
 */
class BuildJarTest {
    private static Path root() {
        return Path.of(System.getProperty("oq.root", "."));
    }

    @Test
    void generatedClassIsAValidClassFile() {
        byte[] bytes = ClassFileWriter.programClass("oq.generated.Program", "print 1");
        // magic
        assertEquals((byte) 0xCA, bytes[0]);
        assertEquals((byte) 0xFE, bytes[1]);
        assertEquals((byte) 0xBA, bytes[2]);
        assertEquals((byte) 0xBE, bytes[3]);
        // major version 65 = Java 21
        assertEquals(0, bytes[6]);
        assertEquals(65, bytes[7]);
    }

    @Test
    void jarContainsManifestProgramAndSource(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("app.jar");
        JarBuilder.Result result = JarBuilder.build(jar, "oq.generated.Program",
                "print \"hi\"", "app.oq");
        assertTrue(Files.exists(jar));
        assertTrue(result.classes() > 10, "expected runtime classes in the jar");
        try (JarFile file = new JarFile(jar.toFile())) {
            assertNotNull(file.getEntry("META-INF/MANIFEST.MF"));
            assertNotNull(file.getEntry("oq/generated/Program.class"));
            assertNotNull(file.getEntry("oq/main.oq"));
            assertNotNull(file.getEntry("oq/source-name.txt"));
            assertEquals("oq.generated.Program",
                    file.getManifest().getMainAttributes().getValue("Main-Class"));
            assertEquals("classfile-launcher",
                    file.getManifest().getMainAttributes().getValue("Oqthara-Backend"));
            assertEquals("app.oq", new String(file.getInputStream(
                    file.getEntry("oq/source-name.txt")).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void generatedJarRunsAndMatchesTheInterpreter(@TempDir Path dir) throws Exception {
        String source = "print \"Hello, World!\"";
        Path jar = dir.resolve("hello.jar");
        JarBuilder.build(jar, "oq.generated.Program", source, "hello.oq");
        assertEquals("Hello, World!",
                runJar(jar).stdout().strip());
    }

    @Test
    void jarHandlesDataFunctionsAndCollections(@TempDir Path dir) throws Exception {
        String source = """
                data Point(x, y)
                fun length(p)
                  return sqrt(p.x * p.x + p.y * p.y)
                end
                p = Point(3, 4)
                print "length: {length(p)}"
                print [1, 2, 3].map(x -> x * 2)
                print {"a": 1}["a"]
                """;
        Path jar = dir.resolve("features.jar");
        JarBuilder.build(jar, "oq.generated.Program", source, "features.oq");
        String output = runJar(jar).stdout();
        assertTrue(output.contains("length: 5"), output);
        assertTrue(output.contains("[2, 4, 6]"), output);
        assertTrue(output.contains("1"), output);
    }

    @Test
    void jarErrorsUseTheOriginalFileName(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("bad.jar");
        JarBuilder.build(jar, "oq.generated.Program", "print nope", "myprogram.oq");
        ProcessResult result = runJar(jar);
        assertEquals(1, result.exit());
        assertTrue(result.stderr().contains("myprogram.oq"), result.stderr());
        assertTrue(result.stderr().contains("'nope' is not defined"), result.stderr());
    }

    @Test
    void jarEmbedsModulesSoItWorksFromAnyDirectory(@TempDir Path dir) throws Exception {
        // Regression for the QA finding: the jar used to look up modules relative to the
        // process CWD, so it only worked when started next to the module file.
        Path app = dir.resolve("app");
        Path elsewhere = dir.resolve("elsewhere");
        Files.createDirectories(app);
        Files.createDirectories(elsewhere);
        Files.writeString(app.resolve("helper.oq"), "fun twice(x)\n  return x * 2\nend\n");
        Path entry = app.resolve("mod.oq");
        Files.writeString(entry, "use \"oq:helper\"\nprint twice(21)");

        String flattened = Modules.flatten(entry, Files.readString(entry));
        Path jar = app.resolve("mod.jar");
        JarBuilder.build(jar, "oq.generated.Program", flattened, "mod.oq");

        ProcessResult result = runJar(jar, elsewhere);
        assertEquals(0, result.exit(), result.stderr());
        assertEquals("42", result.stdout().strip());
    }

    @Test
    void aModuleReachedByUseKeepsTheDriver(@TempDir Path dir) throws Exception {
        // A module is reached with a `use "..."` line, and the predicate's USE_KEYWORD matches
        // any `use`, so a program whose database access lives in a module is already classified
        // as needing the driver. That is worth pinning: it is the reason `oq build` does not
        // have to flatten the entry before deciding, and it would stop being true if module
        // loading ever moved to a different keyword.
        Path helper = dir.resolve("dbhelper.oq");
        Files.writeString(helper, "use java.sql.DriverManager\nfun stored()\n  return 1\nend\n");
        Path entry = dir.resolve("app.oq");
        Files.writeString(entry, "use \"oq:dbhelper\"\nprint stored()");

        String raw = Files.readString(entry);
        assertFalse(raw.contains("java.sql"), "the entry must not name the driver itself");
        assertTrue(JarBuilder.needsDatabaseDriver(raw),
                "the `use` that loads the module must already count as reaching the driver");

        Path jar = dir.resolve("app.jar");
        JarBuilder.build(jar, "oq.generated.Program", Modules.flatten(entry, raw), "app.oq", null,
                JarBuilder.needsDatabaseDriver(raw));
        assertTrue(carriesDriver(jar), "the module's Java use must still bring the driver along");
    }

    @Test
    void nonBmpTextSurvivesTheConstantPool(@TempDir Path dir) throws Exception {
        // Reviewer finding A: the constant pool needs modified UTF-8, not plain UTF-8.
        String source = "print \"party: \uD83C\uDF89\"\nprint \"dash: \u2014\"";
        Path jar = dir.resolve("unicode.jar");
        JarBuilder.build(jar, "oq.generated.Program", source, "unicode.oq");
        ProcessResult result = runJar(jar);
        assertEquals(0, result.exit(), result.stderr());
        assertTrue(result.stdout().contains("\uD83C\uDF89"), result.stdout());
        assertTrue(result.stdout().contains("\u2014"), result.stdout());
    }

    @Test
    void programArgumentsReachTheProgram(@TempDir Path dir) throws Exception {
        // Reviewer finding D: generated main dropped argv.
        Path jar = dir.resolve("args.jar");
        JarBuilder.build(jar, "oq.generated.Program", "print args()", "args.oq");
        ProcessResult result = runJar(jar, dir, "one", "two");
        assertEquals(0, result.exit(), result.stderr());
        assertEquals("[\"one\", \"two\"]", result.stdout().strip());
    }

    /**
     * Building a jar must read each archive on the classpath once, not once per copied class.
     * Reading a single zip entry means inflating every entry before it, so the earlier
     * entry-at-a-time API re-inflated the SQLite driver hundreds of times and took ~19 s per
     * jar. This pins the cost of a jar that includes the driver to a sane bound.
     */
    @Test
    void buildingAJarWithTheDatabaseDriverStaysFast() throws Exception {
        Path work = root().resolve("build/jar-cost");
        Files.createDirectories(work);
        Path jar = work.resolve("cost.jar");
        long start = System.nanoTime();
        JarBuilder.build(jar, "oq.generated.Program", "print \"hi\"", "cost.oq");
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(Files.size(jar) > 10_000_000, "the jar should carry the driver");
        // The quadratic version needed ~19 s here; the single-pass one needs ~1.4 s.
        assertTrue(millis < 8_000, "building a jar took " + millis + " ms");
    }

    @Test
    void onlyProgramsThatCanReachTheDriverCarryIt() throws Exception {
        // A program that names no database builtin and has no `use` statement cannot reach
        // java.sql.DriverManager: `use` is the interpreter's only route to an arbitrary JVM
        // class. Such a jar must therefore leave out 13.5 MB of native libraries it cannot call.
        Path work = root().resolve("build/jar-cost");
        Files.createDirectories(work);

        Path plain = work.resolve("no-driver.jar");
        long start = System.nanoTime();
        JarBuilder.build(plain, "oq.generated.Program", "print 2 + 3", "plain.oq", null, false);
        long plainMillis = (System.nanoTime() - start) / 1_000_000;
        assertFalse(carriesDriver(plain), "a program that cannot reach the driver must not carry it");
        assertTrue(Files.size(plain) < 2_000_000,
                "a driver-free jar should be small, was " + Files.size(plain) + " B");
        assertEquals("5\n", runJar(plain, work).stdout());

        Path withDriver = work.resolve("with-driver.jar");
        long driverStart = System.nanoTime();
        JarBuilder.build(withDriver, "oq.generated.Program", "print 2 + 3", "plain.oq", null, true);
        long driverMillis = (System.nanoTime() - driverStart) / 1_000_000;
        assertTrue(carriesDriver(withDriver), "an explicit request must bundle the driver");

        // The whole point: not copying the driver has to be the cheap path by a wide margin.
        // Compare the two builds in this same run rather than against a wall-clock constant:
        // an absolute bound measures the machine, and a loaded CI worker would fail the test
        // for being slow rather than for being quadratic.
        assertTrue(driverMillis > plainMillis * 2, "copying the driver took only " + driverMillis
                + " ms against " + plainMillis + " ms without it; the comparison cannot tell"
                + " the two paths apart, so this test proves nothing");
        assertTrue(plainMillis < 3_000, "a driver-free jar took " + plainMillis + " ms");
    }

    private static boolean carriesDriver(Path jar) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.stream().anyMatch(entry -> entry.getName().startsWith("org/sqlite/"));
        }
    }

    @Test
    void theDatabasePredicateIsConservativeAboutUseStatements() {
        // Soundness argument, in the form of the cases that would break a naive
        // "does it call db_query" test.
        assertTrue(JarBuilder.needsDatabaseDriver("rows = db_query(\"select 1\")"));
        assertTrue(JarBuilder.needsDatabaseDriver("db_execute(\"create table t (a)\")\n"));
        assertTrue(JarBuilder.needsDatabaseDriver("db_transaction(f)\n"));
        // `use` loads a class by name, so it reaches the driver without naming a builtin.
        assertTrue(JarBuilder.needsDatabaseDriver("use java.sql.DriverManager\nprint 1\n"));
        assertTrue(JarBuilder.needsDatabaseDriver("  use java.time.LocalDate\nprint 1\n"));
        // A statement can share a line, so the keyword is not necessarily at line start.
        assertTrue(JarBuilder.needsDatabaseDriver("print 1; use java.sql.DriverManager\n"));
        // And the programs that make up the bulk of the corpus get the small jar.
        assertFalse(JarBuilder.needsDatabaseDriver("print 2 + 3\n"));
        assertFalse(JarBuilder.needsDatabaseDriver("let unusable = 1\nprint unusable\n"));
    }

    @Test
    void unsafeJarEntryNamesAreRejected() {
        // Reviewer finding B: zip-slip protection.
        assertTrue(JarBuilder.isSafeEntryName("dev/oqthara/core/Interpreter.class"));
        for (String bad : List.of("../evil.class", "dev/../../evil.class",
                "/abs/evil.class", "C:/evil.class", "..\\evil.class", "")) {
            assertTrue(!JarBuilder.isSafeEntryName(bad), "should reject: " + bad);
        }
    }

    @Test
    void relativeReadsResolveAgainstTheProgramDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("data.txt"), "payload");
        Files.writeString(dir.resolve("reader.oq"), "print read(\"data.txt\")");
        // Run from a directory that does not contain data.txt: the interpreter must still
        // resolve the path relative to the program, not the process CWD.
        OqRunner.Result result = OqRunner.run("<t>",
                Files.readString(dir.resolve("reader.oq")), List.of(), "", dir);
        assertEquals(0, result.code(), result.stderr());
        assertEquals("payload\n", result.stdout());
    }

    @Test
    void aBuiltJarCarriesTheDatabaseDriver() throws Exception {
        // Found by examples/database.oq: JarBuilder copied only dev.oqthara classes, so a jar
        // built from a database program ran and then failed with "the SQLite driver is missing
        // from this build". The driver and its SLF4J binding have to travel with it.
        Path work = root().resolve("build/jar-conformance");
        Files.createDirectories(work);
        Path jar = work.resolve("database-standalone.jar");
        String source = Files.readString(root().resolve("examples/database.oq"));
        JarBuilder.build(jar, "oq.generated.Program", source, "database.oq");
        ProcessResult result = runJar(jar, root());
        assertEquals(0, result.exit(), "the standalone jar failed:\n" + result.stderr());
        assertEquals("3 items stored\n  bolt x 2\n  nut x 2\n  washer x 2\ntotal: 6\n"
                        + "after an injection attempt: 4 rows, table intact\n"
                        + "rolled back: the batch was rejected\nrows after rollback: 4\n",
                result.stdout());
    }

    @Test
    void everyExampleBuildsAndMatchesTheInterpreter() throws Exception {
        Path examples = root().resolve("examples");
        List<Path> programs;
        try (var stream = Files.list(examples)) {
            programs = stream.filter(p -> p.toString().endsWith(".oq"))
                    .filter(p -> !p.getFileName().toString().equals("guess.oq"))
                    .filter(p -> !p.getFileName().toString().equals("grep.oq"))
                    .filter(p -> !p.getFileName().toString().equals("parallel.oq"))
                    .sorted()
                    .toList();
        }
        assertTrue(programs.size() >= 25, "expected the example corpus to exist");

        Path work = root().resolve("build/jar-conformance");
        Files.createDirectories(work);
        List<String> mismatches = new java.util.ArrayList<>();
        for (Path program : programs) {
            String source = Files.readString(program);
            Path jar = work.resolve(program.getFileName().toString().replace(".oq", ".jar"));
            JarBuilder.build(jar, "oq.generated.Program", source, program.getFileName().toString(),
                    null, JarBuilder.needsDatabaseDriver(source));
            ProcessResult viaJar = runJar(jar, root());
            OqRunner.Result viaInterpreter = OqRunner.run(program.getFileName().toString(),
                    source, List.of(), "", root());
            if (viaInterpreter.code() != viaJar.exit()
                    || !viaInterpreter.stdout().equals(viaJar.stdout())) {
                mismatches.add(program.getFileName() + "\n--- interpreter (exit "
                        + viaInterpreter.code() + ") ---\n" + viaInterpreter.stdout()
                        + "\n--- jar (exit " + viaJar.exit() + ") ---\n" + viaJar.stdout());
            }
        }
        if (!mismatches.isEmpty()) {
            throw new AssertionError("jar and interpreter disagree:\n\n"
                    + String.join("\n\n", mismatches));
        }
    }

    /**
     * A compiled jar has no step budget, so a runaway allocation is only stopped by the heap.
     * It must be reported the way the interpreter reports it, not dumped as a JVM stack trace.
     */
    @Test
    void aCompiledJarReportsRunningOutOfMemoryAsAProgramError(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("oom.jar");
        Path source = dir.resolve("oom.oq");
        Files.writeString(source, """
                l = [1]
                while true
                  l = l + l
                end
                """);
        int code = build(source, jar);
        assertEquals(0, code, "the program must compile to real bytecode for this test to mean"
                + " anything");
        ProcessResult result = runJar(jar, dir, "-Xmx32m");
        assertNotEquals(0, result.exit());
        assertFalse(result.stderr().contains("Exception in thread"), result.stderr());
        assertFalse(result.stderr().contains("OutOfMemoryError"), result.stderr());
        assertTrue(result.stderr().contains("error: the program ran out of memory"),
                result.stderr());
    }

    /**
     * An error raised inside Oqthara itself must not look like a program error once compiled.
     *
     * <p>{@code oq run} classifies a {@code RuntimeException} that is not an {@code OqError} as
     * "Oqthara itself failed" and exits 70. The compiled entry point hardcoded 1, so the same
     * source exited 70 interpreted and 1 from the jar, with byte-identical text. A script
     * could not tell an Oqthara bug from a program that legitimately failed.
     *
     * <p>{@code 1 to true} is used because it is the smallest program that both reaches real
     * bytecode and raises a non-{@code OqError}: the compiler emits the range loop and the
     * bound check throws {@code IllegalArgumentException}. That the jar really runs compiled
     * bytecode, rather than falling back to the launcher, was confirmed by hand with
     * {@code oq run} printing {@code codepath: compiled bytecode} for the same source; this
     * test does not re-assert it.
     */
    @Test
    void aCompiledJarReportsAnInternalErrorWithExitCode70(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("internal.oq");
        Files.writeString(source, "print 1 to true");
        Path jar = dir.resolve("internal.jar");
        assertEquals(0, build(source, jar));

        ProcessResult result = runJar(jar, dir);
        assertEquals(70, result.exit(),
                "a jar must report an Oqthara bug as 70, not as a program error; stderr was:\n"
                        + result.stderr());
        assertTrue(result.stderr().contains("internal error"), result.stderr());
        assertTrue(result.stderr().contains("This is a bug in Oqthara"), result.stderr());
        // The 70 path must not be reached by mislabelling an ordinary program error.
        assertFalse(result.stderr().contains("error: division by zero"), result.stderr());
        assertFalse(result.stderr().contains("Exception in thread"), result.stderr());
    }

    /**
     * The ordinary program-error path is unchanged by the classification fix: an {@code OqError}
     * that escapes compiled bytecode still exits 1 and keeps its source location.
     */
    @Test
    void aCompiledJarStillReportsAProgramErrorWithExitCode1(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("program.oq");
        Files.writeString(source, """
                fun boom(n)
                  return n / 0
                end
                print boom(1)
                """);
        Path jar = dir.resolve("program.jar");
        assertEquals(0, build(source, jar));

        ProcessResult result = runJar(jar, dir);
        assertEquals(1, result.exit(), "an OqError must stay a program error; stderr was:\n"
                + result.stderr());
        assertTrue(result.stderr().contains("error: division by zero"), result.stderr());
        // The location line proves the OqError was not rerouted through the internal path,
        // which renders a message but no span.
        assertTrue(result.stderr().contains("--> program.oq:2"), result.stderr());
        assertFalse(result.stderr().contains("internal error"), result.stderr());
    }

    /** A program that ends normally must still do so once the handler catches Throwable. */
    @Test
    void aCompiledJarReportsAnUnhashableMemberAtTheSameCallAsTheInterpreter(@TempDir Path dir)
            throws Exception {
        // ADR-0025: the interpreter used to answer `<unknown>:1:1` here. Both backends now
        // report the call expression, and this test pins them to each other so a future change
        // to either one's marking cannot silently diverge.
        for (String stmt : new String[] {
                "print(set([[1]]))",
                "print(frozenset([[1]]))",
                "print(unique([[1]]))",
                "print([[1]].to_set())",
                "print(set([1]).union([[1]]))",
                "print(set([1]).add([[1]]))"}) {
            Path source = dir.resolve("member.oq");
            Files.writeString(source, "a = 1\nb = 2\n" + stmt + "\n");
            Path jar = dir.resolve("member.jar");
            assertEquals(0, build(source, jar), stmt);

            ProcessResult fromJar = runJar(jar, dir);
            assertEquals(1, fromJar.exit(), stmt + "\n" + fromJar.stderr());
            assertTrue(fromJar.stderr().contains("member.oq:3:"),
                    stmt + "\n" + fromJar.stderr());
            assertFalse(fromJar.stderr().contains("<unknown>"),
                    stmt + "\n" + fromJar.stderr());
        }
    }

    @Test
    void aCatchAllHandlerDoesNotDisturbNormalPrograms(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("ok.jar");
        Path source = dir.resolve("ok.oq");
        Files.writeString(source, "print \"Hello, World!\"");
        assertEquals(0, build(source, jar));
        ProcessResult result = runJar(jar, dir);
        assertEquals(0, result.exit());
        assertEquals("Hello, World!", result.stdout().strip());
        assertEquals("", result.stderr().strip());
    }

    /** Builds `source` into `jar` the way `oq build` does, and returns the CLI's exit code. */
    private int build(Path source, Path jar) {
        java.io.PrintStream quiet =
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
        return new Main.Runner(quiet, quiet, new java.io.BufferedReader(new java.io.StringReader("")))
                .dispatch(new String[]{"build", source.toString(), "-o", jar.toString()});
    }

    private record ProcessResult(int exit, String stdout, String stderr) {
    }

    private ProcessResult runJar(Path jar) throws Exception {
        return runJar(jar, jar.getParent());
    }

    private ProcessResult runJar(Path jar, Path workingDirectory, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // Flags such as -Xmx arrive as leading arguments; the jar path always follows -jar.
        List<String> jvmFlags = new java.util.ArrayList<>();
        List<String> programArgs = new java.util.ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("-X") || arg.startsWith("-D")) jvmFlags.add(arg);
            else programArgs.add(arg);
        }
        command.addAll(jvmFlags);
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.addAll(programArgs);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.environment().put("OQ_WORKDIR", workingDirectory.toAbsolutePath().toString());
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("jar did not finish: " + jar);
        }
        return new ProcessResult(process.exitValue(),
                stdout.replace("\r\n", "\n"), stderr.replace("\r\n", "\n"));
    }
}
