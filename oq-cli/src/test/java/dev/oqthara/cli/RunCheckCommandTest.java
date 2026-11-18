package dev.oqthara.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    private static Run dispatch(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = new Main.Runner(out, err, null).dispatch(args);
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

    @ParameterizedTest
    @ValueSource(strings = {"run", "check", "fmt", "format", "new", "doc", "test", "build"})
    void everyFileTakingSubcommandAnswersHelp(String command) throws Exception {
        String[] strays = {"--help", "-x"};
        boolean[] existedBefore = new boolean[strays.length];
        for (int i = 0; i < strays.length; i++) {
            existedBefore[i] = Files.exists(Path.of(strays[i]));
        }
        try {
            Run result = dispatch(command, "--help");

            assertEquals(0, result.exit(), command + " --help should succeed: " + result.err());
            assertTrue(result.out().startsWith("Usage: oq"), result.out());
            assertFalse(result.err().contains(".oq'"), result.err());
            assertFalse(result.err().contains("Cannot"), result.err());
        } finally {
            for (int i = 0; i < strays.length; i++) {
                deleteIfCreated(Path.of(strays[i]), existedBefore[i]);
            }
        }
    }

    @Test
    void newWithHelpDoesNotCreateADirectory() throws Exception {
        Path stray = Path.of("--help");
        boolean existedBefore = Files.exists(stray);
        try {
            Run result = dispatch("new", "--help");

            assertEquals(0, result.exit());
            // `oq new --help` used to scaffold a real project directory called "--help" in the
            // working directory, which is why this checks the working directory, not the temp dir.
            assertEquals(existedBefore, Files.exists(stray), "no directory should be created");
        } finally {
            deleteIfCreated(stray, existedBefore);
        }
    }

    @Test
    void anUnknownOptionIsRejectedByName() throws Exception {
        Path stray = Path.of("-x");
        boolean existedBefore = Files.exists(stray);
        try {
            Run result = dispatch("new", "-x");

            assertEquals(2, result.exit());
            assertTrue(result.err().contains("Unknown option: -x"), result.err());
            assertEquals(existedBefore, Files.exists(stray), "no directory should be created");
        } finally {
            deleteIfCreated(stray, existedBefore);
        }
    }

    private static void deleteIfCreated(Path stray, boolean existedBefore) throws Exception {
        if (existedBefore || !Files.exists(stray)) {
            return;
        }
        try (var paths = Files.walk(stray)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
