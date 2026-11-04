package dev.oqthara.cli;

import dev.oqthara.compiler.BytecodeCompiler;
import dev.oqthara.compiler.JarBuilder;
import dev.oqthara.compiler.Lowering;
import dev.oqthara.core.Parser;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-0048: real bytecode for the straight-line arithmetic subset, with an explicit and
 * honest fallback for everything else.
 */
class BytecodeCompilerTest {
    private static dev.oqthara.compiler.Ir.Program ir(String source) {
        return Lowering.lower(Parser.parse("<t>", source));
    }

    private String compileAndRun(String source, Path dir) throws Exception {
        dev.oqthara.compiler.Ir.Program program = ir(source);
        String reason = BytecodeCompiler.whyNot(program);
        assertNull(reason, "expected the program to be compilable, refused because: " + reason);
        byte[] compiled = BytecodeCompiler.compile(program, "oq.generated.Program");
        Path jar = dir.resolve("prog.jar");
        JarBuilder.build(jar, "oq.generated.Program", source, "prog.oq", compiled);
        return runJar(jar, dir);
    }

    @Test
    void compilesIntegerArithmetic(@TempDir Path dir) throws Exception {
        assertEquals("14\n20\n2\n", compileAndRun("print 2 + 3 * 4\nprint (2 + 3) * 4\nprint 10 - 8", dir));
    }

    @Test
    void compilesDivisionAndModulo(@TempDir Path dir) throws Exception {
        assertEquals("2.5\n1\n", compileAndRun("print 10 / 4\nprint 7 % 3", dir));
    }

    @Test
    void compilesDecimals(@TempDir Path dir) throws Exception {
        assertEquals("3.0\n", compileAndRun("print 2.5 + 0.5", dir));
    }

    @Test
    void compilesTextConcatenation(@TempDir Path dir) throws Exception {
        assertEquals("sum: 7\n", compileAndRun("print \"sum: \" + 7", dir));
    }

    @Test
    void compilesUnaryMinus(@TempDir Path dir) throws Exception {
        assertEquals("-3\n5\n", compileAndRun("print -5 + 2\nprint -(-5)", dir));
    }

    @Test
    void divisionByZeroReportsTheSameMessage(@TempDir Path dir) throws Exception {
        dev.oqthara.compiler.Ir.Program program = ir("print 1 / 0");
        byte[] compiled = BytecodeCompiler.compile(program, "oq.generated.Program");
        Path jar = dir.resolve("zero.jar");
        JarBuilder.build(jar, "oq.generated.Program", "print 1 / 0", "zero.oq", compiled);
        ProcessResult result = runJarProcess(jar, dir);
        assertTrue(result.exit() != 0, "expected a non-zero exit");
        assertTrue(result.stderr().contains("division by zero"), result.stderr());
    }

    @Test
    void compiledJarContainsNoEmbeddedSource(@TempDir Path dir) throws Exception {
        // The proof that this is compilation and not packaging.
        dev.oqthara.compiler.Ir.Program program = ir("print 1 + 1");
        byte[] compiled = BytecodeCompiler.compile(program, "oq.generated.Program");
        Path jar = dir.resolve("compiled.jar");
        JarBuilder.build(jar, "oq.generated.Program", "print 1 + 1", "x.oq", compiled);
        try (JarFile file = new JarFile(jar.toFile())) {
            assertNull(file.getEntry("oq/main.oq"), "source must not be embedded");
            assertNull(file.getEntry("oq/source-name.txt"));
            assertNotNull(file.getEntry("oq/generated/Program.class"));
            assertEquals("classfile-compiled",
                    file.getManifest().getMainAttributes().getValue("Oqthara-Backend"));
        }
        assertEquals("2\n", runJar(jar, dir));
    }

    @Test
    void unsupportedConstructsAreRefusedWithAReason() {
        assertFalse(BytecodeCompiler.canCompile(ir("x = 1")));
        assertTrue(BytecodeCompiler.whyNot(ir("x = 1")).contains("Store"));

        assertFalse(BytecodeCompiler.canCompile(ir("print [1, 2]")));
        assertTrue(BytecodeCompiler.whyNot(ir("print [1, 2]")).contains("MakeList"));

        assertFalse(BytecodeCompiler.canCompile(ir("fun f()\n  return 1\nend\nprint f()")));
        assertTrue(BytecodeCompiler.whyNot(ir("fun f()\n  return 1\nend\nprint f()"))
                .contains("function"));

        assertFalse(BytecodeCompiler.canCompile(ir("print 1 == 1")));
    }

    @Test
    void fallbackStillRunsEverything(@TempDir Path dir) throws Exception {
        // A program outside the subset must still produce a working jar via the launcher.
        String source = "xs = [1, 2, 3]\nprint xs.map(x -> x * 2)";
        Path jar = dir.resolve("fallback.jar");
        JarBuilder.build(jar, "oq.generated.Program", source, "fallback.oq");
        assertEquals("[2, 4, 6]\n", runJar(jar, dir));
    }

    @Test
    void compiledOutputMatchesTheInterpreterOnArithmeticPrograms(@TempDir Path dir) throws Exception {
        List<String> programs = List.of(
                "print 1 + 1",
                "print -7 % 3",
                "print 2.5 * 4",
                "print \"a\" + \"b\"",
                "print 1 + 2 + 3 + 4 + 5",
                "print (1 + 2) * (3 + 4)");
        for (String source : programs) {
            String expected = OqRunner.run("<t>", source, List.of(), "", dir).stdout();
            assertEquals(expected, compileAndRun(source, dir),
                    "mismatch for: " + source);
        }
    }

    private record ProcessResult(int exit, String stdout, String stderr) {
    }

    private String runJar(Path jar, Path dir) throws Exception {
        ProcessResult result = runJarProcess(jar, dir);
        assertEquals(0, result.exit(), result.stderr());
        return result.stdout();
    }

    private ProcessResult runJarProcess(Path jar, Path dir) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toAbsolutePath().toString());
        builder.directory(dir.toFile());
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "jar timed out");
        return new ProcessResult(process.exitValue(), out.replace("\r\n", "\n"),
                err.replace("\r\n", "\n"));
    }
}
