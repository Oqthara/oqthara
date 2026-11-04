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
            assertEquals("classfile",
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
            JarBuilder.build(jar, "oq.generated.Program", source, program.getFileName().toString());
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

    private record ProcessResult(int exit, String stdout, String stderr) {
    }

    private ProcessResult runJar(Path jar) throws Exception {
        return runJar(jar, jar.getParent());
    }

    private ProcessResult runJar(Path jar, Path workingDirectory) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toAbsolutePath().toString());
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
