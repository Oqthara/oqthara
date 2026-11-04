package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                if (!actual.contains(expected)) {
                    failures.add(name + ": wrong error message\n  expected to contain: " + expected
                            + "\n  actual: " + actual.replace("\n", "\n          "));
                }
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
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " conformance failure(s):\n\n" + String.join("\n\n", failures));
        }
        assertEquals(programs.size(), programs.size());
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
