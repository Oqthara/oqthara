package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ConformanceTest {
    private static final Path CASES = Path.of(System.getProperty("oq.root", ".")).resolve("conformance");

    @Test
    void conformanceSuite() throws Exception {
        assertTrue(Files.isDirectory(CASES), "conformance directory not found at " + CASES.toAbsolutePath());
        List<Path> programs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(CASES)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".oq"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(programs::add);
        }
        assertTrue(programs.size() >= 100, "expected at least 100 conformance cases, found " + programs.size());

        List<String> failures = new ArrayList<>();
        int errorCases = 0;
        int outputCases = 0;
        for (Path program : programs) {
            String name = program.getFileName().toString();
            String base = name.substring(0, name.length() - 3);
            String source = Files.readString(program);
            Path expectedOut = CASES.resolve(base + ".out");
            Path expectedErr = CASES.resolve(base + ".err");
            Path stdin = CASES.resolve(base + ".in");

            String input = Files.exists(stdin) ? Files.readString(stdin) : "";
            OqRunner.Result result = OqRunner.run(name, source, List.of(), input, CASES);

            if (Files.exists(expectedErr)) {
                String expected = Files.readString(expectedErr).strip();
                if (result.code() == 0) {
                    failures.add(name + ": expected an error, but the program succeeded\n" + result.stdout());
                    continue;
                }
                String actual = result.stderr().strip();
                // The first line of every diagnostic is the message itself (`OqRunner` leaves the
                // `error: ` prefix to the CLI); the rest is the position, the source excerpt and
                // the hint. Matching the whole first line
                // exactly means a message that gains a prefix, loses a detail, or is reported
                // for a different reason no longer passes. `contains` would accept all three.
                String firstLine = actual.lines().findFirst().orElse("");
                if (!firstLine.equals(expected)) {
                    failures.add(name + ": wrong error message\n  expected first line: "
                            + expected + "\n  actual first line:   " + firstLine
                            + "\n  full stderr:\n          " + actual.replace("\n", "\n          "));
                    continue;
                }
                // Every diagnostic must say where it happened. Without this, an error that names
                // the right problem in the wrong place still passes.
                if (!actual.contains("--> " + name + ":")) {
                    failures.add(name + ": diagnostic has no position\n  expected to find: --> "
                            + name + ":\n  actual:\n          "
                            + actual.replace("\n", "\n          "));
                    continue;
                }
                errorCases++;
                continue;
            }

            String expected = Files.exists(expectedOut) ? normalize(Files.readString(expectedOut)) : "";
            String actual = normalize(result.stdout());
            if (!expected.equals(actual)) {
                failures.add(name + ": wrong output\n--- expected ---\n" + expected
                        + "\n--- actual ---\n" + actual + "\n--- stderr ---\n" + result.stderr());
            } else if (result.code() != 0) {
                failures.add(name + ": expected exit code 0, got " + result.code()
                        + "\n--- stderr ---\n" + result.stderr());
            } else {
                outputCases++;
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " conformance failure(s):\n\n" + String.join("\n\n", failures));
        }
        // Both kinds of case must actually exist. This can fail: if the .err files were deleted,
        // every error case would silently start being compared as output, and a corpus of
        // purely-output cases would stop exercising the diagnostic path that this suite exists
        // to pin. The count is only reachable once a case passes its own branch to the end, so
        // a case that bails out early is not counted as covered.
        assertEquals(programs.size(), errorCases + outputCases,
                "every discovered program must reach the end of one of the two checks");
        assertTrue(errorCases >= 10, "expected at least 10 error cases, found " + errorCases);
        assertTrue(outputCases >= 100, "expected at least 100 output cases, found " + outputCases);
    }

    /**
     * The name of a case that promises a position must be backed by the harness rule that
     * checks one. This used to be a comment-level promise: case 200 is called
     * `error-message-includes-position` but asserted only a message.
     */
    @Test
    void casesThatPromiseAPositionAreCheckedForOne() throws Exception {
        List<String> promising = new ArrayList<>();
        try (Stream<Path> stream = Files.list(CASES)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".err"))
                    .filter(n -> n.contains("position"))
                    .sorted()
                    .forEach(promising::add);
        }
        assertFalse(promising.isEmpty(),
                "no .err case exercises the position rule; the check would be unreachable");
        // The rule is enforced for every .err case by conformanceSuite, so the promise in the
        // name is now a property of the corpus rather than a comment.
        for (String name : promising) {
            assertTrue(Files.exists(CASES.resolve(name)), name);
        }
    }

    private static String normalize(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line.stripTrailing()).append('\n');
        }
        String result = sb.toString();
        while (result.endsWith("\n\n")) result = result.substring(0, result.length() - 1);
        return result.strip();
    }
}
