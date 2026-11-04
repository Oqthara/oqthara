package dev.oqthara.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRunnerTest {
    @Test
    void discoversTestPrefixedFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("test_one.oq"), "assert 1 == 1");
        Files.writeString(dir.resolve("main.oq"), "print \"not a test\"");
        List<Path> files = TestRunner.discover(dir);
        assertEquals(1, files.size());
        assertTrue(files.get(0).getFileName().toString().equals("test_one.oq"));
    }

    @Test
    void discoversTestsDirectory(@TempDir Path dir) throws Exception {
        Path tests = dir.resolve("tests");
        Files.createDirectories(tests);
        Files.writeString(tests.resolve("anything.oq"), "assert true");
        assertEquals(1, TestRunner.discover(dir).size());
    }

    @Test
    void ignoresBuildOutput(@TempDir Path dir) throws Exception {
        Path build = dir.resolve("build").resolve("tests");
        Files.createDirectories(build);
        Files.writeString(build.resolve("test_generated.oq"), "assert true");
        assertEquals(0, TestRunner.discover(dir).size());
    }

    @Test
    void reportsPassCount(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("test_ok.oq"), "assert 1 == 1");
        Path outFile = dir.resolve("out.txt");
        try (var out = new java.io.PrintStream(Files.newOutputStream(outFile))) {
            int code = TestRunner.run(out, out, new String[]{dir.toString()});
            assertEquals(0, code);
        }
        String output = Files.readString(outFile);
        assertTrue(output.contains("1 passed, 0 failed, 1 total"), output);
    }

    @Test
    void reportsFailureAndExitCode(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("test_bad.oq"), "assert 1 == 2, \"nope\"");
        Path outFile = dir.resolve("out.txt");
        int code;
        try (var out = new java.io.PrintStream(Files.newOutputStream(outFile))) {
            code = TestRunner.run(out, out, new String[]{dir.toString()});
        }
        assertEquals(1, code);
        String output = Files.readString(outFile);
        assertTrue(output.contains("0 passed, 1 failed"), output);
        assertTrue(output.contains("nope"), output);
    }

    @Test
    void emptyDirectoryIsNotAFailure(@TempDir Path dir) throws Exception {
        Path outFile = dir.resolve("out.txt");
        int code;
        try (var out = new java.io.PrintStream(Files.newOutputStream(outFile))) {
            code = TestRunner.run(out, out, new String[]{dir.toString()});
        }
        assertEquals(0, code);
        assertTrue(Files.readString(outFile).contains("No Oqthara tests found"));
    }
}
