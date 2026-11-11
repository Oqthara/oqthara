package dev.oqthara.cli;

import dev.oqthara.core.Modules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModulesTest {
    @Test
    void importsFunctionsFromAnotherFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mathx.oq"), """
                fun double(x)
                  return x * 2
                end
                """);
        Files.writeString(dir.resolve("main.oq"), """
                use "oq:mathx"
                print double(21)
                """);
        OqRunner.Result result = run(dir, "main.oq");
        assertEquals(0, result.code(), result.stderr());
        assertEquals("42\n", result.stdout());
    }

    @Test
    void importsDataTypes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("shapes.oq"), """
                data Point(x, y)
                """);
        Files.writeString(dir.resolve("main.oq"), """
                use "oq:shapes"
                p = Point(1, 2)
                print p.x + p.y
                """);
        OqRunner.Result result = run(dir, "main.oq");
        assertEquals(0, result.code(), result.stderr());
        assertEquals("3\n", result.stdout());
    }

    @Test
    void modulesCanImportEachOther(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("base.oq"), """
                fun base()
                  return "base"
                end
                """);
        Files.writeString(dir.resolve("layer.oq"), """
                use "oq:base"
                fun layer()
                  return base() + "-layer"
                end
                """);
        Files.writeString(dir.resolve("main.oq"), """
                use "oq:layer"
                print layer()
                """);
        OqRunner.Result result = run(dir, "main.oq");
        assertEquals(0, result.code(), result.stderr());
        assertEquals("base-layer\n", result.stdout());
    }

    @Test
    void cyclesDoNotLoopForever(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.oq"), "use \"oq:b\"\nfun af()\n  return 1\nend\n");
        Files.writeString(dir.resolve("b.oq"), "use \"oq:a\"\nfun bf()\n  return 2\nend\n");
        Files.writeString(dir.resolve("main.oq"), "use \"oq:a\"\nprint af()\nprint bf()\n");
        OqRunner.Result result = run(dir, "main.oq");
        assertEquals(0, result.code(), result.stderr());
        assertEquals("1\n2\n", result.stdout());
    }

    @Test
    void moduleIsLoadedOnce(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("counter.oq"), """
                print "loading counter"
                fun n()
                  return 1
                end
                """);
        Files.writeString(dir.resolve("main.oq"), """
                use "oq:counter"
                use "oq:counter"
                print n()
                """);
        OqRunner.Result result = run(dir, "main.oq");
        assertEquals(0, result.code(), result.stderr());
        assertEquals("loading counter\n1\n", result.stdout());
    }

    @Test
    void missingModuleExplainsWhereItLooked(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("main.oq"), "use \"oq:nope\"\nprint 1\n");
        dev.oqthara.runtime.OqError error = org.junit.jupiter.api.Assertions.assertThrows(
                dev.oqthara.runtime.OqError.class, () -> run(dir, "main.oq"));
        assertTrue(error.getMessage().contains("cannot find module 'nope'"), error.getMessage());
    }

    @Test
    void brokenModuleNamesTheModuleFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("broken.oq"), "fun f(\n");
        Files.writeString(dir.resolve("main.oq"), "use \"oq:broken\"\nprint 1\n");
        dev.oqthara.runtime.OqError error = org.junit.jupiter.api.Assertions.assertThrows(
                dev.oqthara.runtime.OqError.class, () -> run(dir, "main.oq"));
        assertTrue(error.getMessage().contains("broken.oq"), error.getMessage());
    }

    @Test
    void modulesDirectoryIsSearched(@TempDir Path dir) throws Exception {
        Path modules = dir.resolve("modules");
        Files.createDirectories(modules);
        Files.writeString(modules.resolve("util.oq"), "fun answer()\n  return 42\nend\n");
        Files.writeString(dir.resolve("main.oq"), "use \"oq:util\"\nprint answer()\n");
        OqRunner.Result result = run(dir, "main.oq");
        assertEquals(0, result.code(), result.stderr());
        assertEquals("42\n", result.stdout());
    }

    private OqRunner.Result run(Path dir, String file) throws Exception {
        String source = Files.readString(dir.resolve(file));
        var program = dev.oqthara.core.Parser.parse(file, source);
        program = Modules.resolve(dir.resolve(file), program);
        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        var interpreter = new dev.oqthara.core.Interpreter(
                new java.io.PrintStream(out, true),
                new java.io.PrintStream(err, true),
                new java.io.BufferedReader(new java.io.StringReader("")),
                java.util.List.of(), dir, new dev.oqthara.stdlib.MethodResolverImpl());
        dev.oqthara.stdlib.Stdlib.install(interpreter);
        try {
            interpreter.run(program);
            return new OqRunner.Result(0, normalize(out), "");
        } catch (dev.oqthara.runtime.OqError error) {
            return new OqRunner.Result(1, normalize(out), error.getMessage());
        }
    }

    private static String normalize(java.io.ByteArrayOutputStream bytes) {
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
