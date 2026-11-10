package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every program in {@code examples/}. The README tells readers that CI runs them, so this
 * test is what makes that true; before it existed nothing did, and an example could rot silently.
 */
class ExamplesRunTest {
    /** Needs two arguments, so running it bare is a usage error by design. */
    private static final Set<String> NEEDS_ARGUMENTS = Set.of("grep.oq");
    /** Reads from standard input; exercised by hand instead. */
    private static final Set<String> INTERACTIVE = Set.of("guess.oq");

    @Test
    void everyExampleRunsWithoutAnError() throws Exception {
        Path examples = Path.of(System.getProperty("oq.root", ".")).resolve("examples");
        List<Path> files;
        try (var stream = Files.list(examples)) {
            files = stream.filter(p -> p.toString().endsWith(".oq")).sorted().toList();
        }
        assertTrue(files.size() >= 37, "examples were not found: " + files.size());
        int ran = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (NEEDS_ARGUMENTS.contains(name) || INTERACTIVE.contains(name)) continue;
            // Driven through the real entry point rather than OqRunner: the latter builds an
            // interpreter directly and never wires up module loading, so examples/  modules.oq
            // would fail there for a reason a reader could never reproduce.
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    "dev.oqthara.cli.Main", "run", file.toString())
                    .directory(examples.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), name + " failed:\n" + output);
            ran++;
        }
        assertEquals(files.size() - NEEDS_ARGUMENTS.size() - INTERACTIVE.size(), ran);
    }
}
