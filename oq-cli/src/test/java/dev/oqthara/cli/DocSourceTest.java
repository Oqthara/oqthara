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

class DocSourceTest {

    private record Result(int code, String out, String err) {
    }

    private static Result doc(Path path) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = DocGenerator.run(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                new String[] {"--source", path.toString()});
        return new Result(code, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aDocumentedFunctionAppearsWithItsSummaryAndParameters(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("maths.oq");
        Files.writeString(file, """
                \"\"\"Adds two numbers.

                @param a the first addend
                @param b the second addend
                @return the sum
                \"\"\"
                fun add(a, b)
                  return a + b
                end
                """);
        Result result = doc(file);
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("### fun add(a, b)"), result.out());
        assertTrue(result.out().contains("Adds two numbers."), result.out());
        assertTrue(result.out().contains("`a` the first addend"), result.out());
        assertTrue(result.out().contains("Returns: the sum"), result.out());
    }

    @Test
    void anUndocumentedDeclarationIsLabelledRatherThanOmitted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("plain.oq");
        Files.writeString(file, "fun f(n)\n  return n\nend\n");
        Result result = doc(file);
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("### fun f(n)"), result.out());
        assertTrue(result.out().contains("_Undocumented._"), result.out());
        assertTrue(result.out().contains("0 documented, 1 undocumented"), result.out());
    }

    @Test
    void aDataDeclarationIsRenderedWithItsModifierAndFieldDocs(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pt.oq");
        Files.writeString(file, """
                \"\"\"A point.

                @param x the horizontal coordinate
                \"\"\"
                data Point(x, y) immutable
                """);
        Result result = doc(file);
        assertTrue(result.out().contains("### data Point(x, y) immutable"), result.out());
        assertTrue(result.out().contains("`x` the horizontal coordinate"), result.out());
    }

    @Test
    void aClassRendersItsTags(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("c.oq");
        Files.writeString(file, """
                \"\"\"A circle.

                @since 0.1.0
                @deprecated use Ellipse instead
                \"\"\"
                class Circle(r)
                  fun area()
                    return r
                  end
                end
                """);
        Result result = doc(file);
        assertTrue(result.out().contains("Since: 0.1.0"), result.out());
        assertTrue(result.out().contains("Deprecated: use Ellipse instead"), result.out());
    }

    @Test
    void aDirectoryIsWalkedAndEveryFileIsHeaded(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.oq"), "\"\"\"First.\"\"\"\nfun a()\n  return 1\nend\n");
        Files.writeString(dir.resolve("b.oq"), "\"\"\"Second.\"\"\"\nfun b()\n  return 2\nend\n");
        Result result = doc(dir);
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("## a.oq"), result.out());
        assertTrue(result.out().contains("## b.oq"), result.out());
        assertTrue(result.out().contains("First."), result.out());
        assertTrue(result.out().contains("Second."), result.out());
    }

    @Test
    void aMissingPathIsAnErrorRatherThanAnEmptyPage(@TempDir Path dir) {
        Result result = doc(dir.resolve("absent.oq"));
        assertEquals(2, result.code());
        assertTrue(result.err().contains("Cannot find"), result.err());
        assertFalse(result.out().contains("# API reference"));
    }

    @Test
    void aDirectoryWithNoSourceSaysSoRatherThanPrintingAHeader(@TempDir Path dir) throws Exception {
        Result result = doc(dir);
        assertEquals(2, result.code());
        assertTrue(result.err().contains("No .oq files"), result.err());
    }

    @Test
    void aFileThatCannotParseIsReportedWithItsName(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.oq");
        Files.writeString(file, "fun f(\n");
        Result result = doc(file);
        assertEquals(1, result.code());
        assertTrue(result.err().contains("broken.oq"), result.err());
    }

    @Test
    void anUnknownTagIsRenderedUnderItsOwnName(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("x.oq");
        Files.writeString(file, "\"\"\"Does.\n@example add(1, 2)\n\"\"\"\nfun add(a, b)\n  return a\nend\n");
        Result result = doc(file);
        assertTrue(result.out().contains("@example: add(1, 2)"), result.out());
    }

    @Test
    void aModuleReachedOnlyByUseIsDocumentedToo(@TempDir Path dir) throws Exception {
        Path modules = dir.resolve("lib");
        Files.createDirectories(modules);
        Files.writeString(modules.resolve("helper.oq"), """
                \"""Doubles.\"""
                fun doubleIt(n)
                  return n * 2
                end
                """);
        Path main = dir.resolve("main.oq");
        Files.writeString(main, """
                use "oq:helper"
                print doubleIt(2)
                """);
        Result result = doc(main);
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("## lib/helper.oq"), result.out());
        assertTrue(result.out().contains("### fun doubleIt(n)"), result.out());
        assertTrue(result.out().contains("Doubles."), result.out());
    }

    @Test
    void aModuleUnderTheWalkedDirectoryIsDocumentedOnce(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("lib"));
        Files.writeString(dir.resolve("lib/helper.oq"), """
                \"""Doubles.\"""
                fun doubleIt(n)
                  return n * 2
                end
                """);
        Files.writeString(dir.resolve("main.oq"), """
                use "oq:helper"
                print doubleIt(2)
                """);
        Result result = doc(dir);
        assertEquals(0, result.code(), result.err());
        String out = result.out();
        assertEquals(out.indexOf("## lib/helper.oq"), out.lastIndexOf("## lib/helper.oq"),
                "the walked file and the imported file are the same file: " + out);
        assertEquals(1, out.split("### fun doubleIt", -1).length - 1, out);
        assertTrue(out.contains("1 documented, 0 undocumented."), out);
    }

    @Test
    void anImportCycleDocumentsEachModuleOnceAndTerminates(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.oq"), "use \"oq:b\"\nfun af()\n  return 1\nend\n");
        Files.writeString(dir.resolve("b.oq"), "use \"oq:a\"\nfun bf()\n  return 2\nend\n");
        Path main = dir.resolve("main.oq");
        Files.writeString(main, "use \"oq:a\"\nprint af()\n");
        Result result = doc(main);
        assertEquals(0, result.code(), result.err());
        assertEquals(1, result.out().split("## a.oq", -1).length - 1, result.out());
        assertEquals(1, result.out().split("## b.oq", -1).length - 1, result.out());
    }

    @Test
    void anUnresolvableImportIsReportedAndTheRestIsStillDocumented(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("main.oq");
        Files.writeString(file, """
                use "oq:absent"
                \"""Kept.\"""
                fun f()
                  return 1
                end
                """);
        Result result = doc(file);
        assertEquals(0, result.code(), result.err());
        assertTrue(result.err().contains("absent"), result.err());
        assertTrue(result.out().contains("### fun f()"), result.out());
        assertTrue(result.out().contains("1 documented, 0 undocumented."), result.out());
    }

    @Test
    void twoModulesWithTheSameFileNameGetDistinctHeadings(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("a"));
        Files.createDirectories(dir.resolve("b"));
        Files.writeString(dir.resolve("a/util.oq"), "fun one()\n  return 1\nend\n");
        Files.writeString(dir.resolve("b/util.oq"), "fun two()\n  return 2\nend\n");
        Result result = doc(dir);
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("## a/util.oq"), result.out());
        assertTrue(result.out().contains("## b/util.oq"), result.out());
    }

    @Test
    void thePageStatesThatItIsGenerated(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("g.oq");
        Files.writeString(file, "\"\"\"G.\"\"\"\nfun g()\n  return 1\nend\n");
        Result result = doc(file);
        assertTrue(result.out().contains("Do not edit by hand"), result.out());
    }
}
