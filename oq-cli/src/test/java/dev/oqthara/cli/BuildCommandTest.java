package dev.oqthara.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Argument handling for {@code oq build}. These are the kinds of mistakes that show up as a
 * confusing "cannot read file" error when someone types something ordinary.
 */
class BuildCommandTest {
    private record Run(int exit, String out, String err) {
    }

    private static Run build(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = new Main.Runner(out, err, null).build(args);
        return new Run(exit, outBytes.toString(StandardCharsets.UTF_8),
                errBytes.toString(StandardCharsets.UTF_8));
    }

    /**
     * A `use` naming a class that does not exist must report like every other program mistake.
     * The class is resolved while lowering, which happens inside `oq build`, so without a
     * handler in that command the error reached the top level as a Java stack trace: the same
     * typo produced a diagnostic from `oq run` and a crash from `oq build`.
     */
    @Test
    void anUnknownClassNameIsADiagnosticNotAStackTrace(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("bad.oq");
        Files.writeString(source, "use java.lang.Mathh\nprint 1");
        Run run = build(source.toString());
        assertEquals(1, run.exit());
        assertTrue(run.err().contains("error: cannot find Java class 'java.lang.Mathh'"), run.err());
        assertFalse(run.err().contains("\tat dev.oqthara"), run.err());
        assertFalse(Files.exists(dir.resolve("bad.jar")), "a refused program writes no jar");
    }

    @Test
    void helpIsNotTreatedAsAFileName() {
        Run run = build("--help");
        assertEquals(0, run.exit());
        assertTrue(run.out().contains("Usage: oq build"), run.out());
        assertTrue(run.out().contains("--emit-ir"), run.out());
        assertTrue(run.err().isEmpty(), run.err());
    }

    @Test
    void shortHelpAlsoWorks() {
        assertEquals(0, build("-h").exit());
    }

    @Test
    void unknownOptionsAreRejectedWithoutTouchingTheFileSystem() {
        Run run = build("--nope");
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("Unknown option: --nope"), run.err());
    }

    @Test
    void missingFileExplainsTheUsage() {
        Run run = build();
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("oq build hello.oq"), run.err());
    }

    @Test
    void missingFileIsReportedAsUnreadable() {
        Run run = build("definitely-not-here.oq");
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("Cannot read"), run.err());
    }

    /**
     * {@code oq build} picks one of two jar shapes, and the docs tell people to read the
     * {@code codepath:} line to know which. A line that says neither, or says both, would make
     * the documented advice unusable.
     */
    @Test
    void buildSaysWhichJarShapeItChose(@TempDir Path dir) throws Exception {
        Path plain = dir.resolve("plain.oq");
        Files.writeString(plain, "print \"hi\"");
        Run compiled = build(plain.toString());
        assertEquals(0, compiled.exit(), compiled.err());
        assertTrue(compiled.out().contains("codepath: compiled bytecode"), compiled.out());

        Path interactive = dir.resolve("interactive.oq");
        Files.writeString(interactive, "x = read()\nprint x");
        Run launcher = build(interactive.toString());
        assertEquals(0, launcher.exit(), launcher.err());
        assertTrue(launcher.out().contains("codepath: interpreter launcher"), launcher.out());
        assertTrue(launcher.out().contains("is not compiled yet"), launcher.out());
    }

    /** Exactly one codepath line, so the documented "read this line" recipe has one answer. */
    @Test
    void buildPrintsOnlyOneCodepathLine(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("one.oq");
        Files.writeString(source, "print 1");
        Run run = build(source.toString());
        assertEquals(0, run.exit(), run.err());
        assertEquals(1, run.out().lines().filter(line -> line.startsWith("codepath:")).count(),
                run.out());
    }
}
