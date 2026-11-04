package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
