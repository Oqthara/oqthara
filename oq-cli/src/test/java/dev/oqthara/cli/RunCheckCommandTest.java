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
 * Argument handling for {@code oq run} and {@code oq check}.
 *
 * <p>{@code oq build --help} was fixed once already, and the same mistake was left in its two
 * siblings: asking either for help produced "Cannot find file '--help.oq'". A help flag is the
 * first thing a person types at an unfamiliar command, so getting it wrong is the most expensive
 * place to get it wrong.
 */
class RunCheckCommandTest {
    private record Run(int exit, String out, String err) {
    }

    @TempDir
    Path dir;

    private static Run run(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = new Main.Runner(out, err, null).run(args);
        return new Run(exit, outBytes.toString(StandardCharsets.UTF_8),
                errBytes.toString(StandardCharsets.UTF_8));
    }

    private static Run check(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = new Main.Runner(out, err, null).check(args);
        return new Run(exit, outBytes.toString(StandardCharsets.UTF_8),
                errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void runHelpIsNotTreatedAsAFileName() {
        Run result = run("--help");

        assertEquals(0, result.exit());
        assertTrue(result.out().contains("Usage: oq"), result.out());
        assertTrue(result.err().isEmpty(), result.err());
        assertFalse(result.err().contains("--help.oq"), result.err());
    }

    @Test
    void runShortHelpAlsoWorks() {
        assertEquals(0, run("-h").exit());
        assertTrue(run("-h").out().contains("Usage: oq"), run("-h").out());
    }

    @Test
    void checkHelpIsNotTreatedAsAFileName() {
        Run result = check("--help");

        assertEquals(0, result.exit());
        assertTrue(result.out().contains("Usage: oq check"), result.out());
        assertTrue(result.err().isEmpty(), result.err());
        assertFalse(result.err().contains("--help.oq"), result.err());
    }

    @Test
    void checkShortHelpAlsoWorks() {
        assertEquals(0, check("-h").exit());
        assertTrue(check("-h").out().contains("Usage: oq check"), check("-h").out());
    }

    @Test
    void checkReportsAMissingFileAsASentence() {
        Run result = check("absent");

        assertEquals(2, result.exit());
        // A raw NoSuchFileException message is just the path, so passing it through produced
        // "Cannot read 'absent.oq': absent.oq" -- the filename twice and no reason.
        assertEquals("Cannot find file 'absent.oq'." + System.lineSeparator(), result.err(),
                result.err());
    }

    @Test
    void checkSaysOkForAFileThatParses() throws Exception {
        Files.writeString(dir.resolve("ok.oq"), "print \"hi\"\n");

        Run result = check(dir.resolve("ok.oq").toString());

        assertEquals(0, result.exit());
        assertTrue(result.out().startsWith("OK "), result.out());
    }
}
