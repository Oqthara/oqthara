package dev.oqthara.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a hover follows {@code use "oq:name"} to the module that declares the name.
 *
 * <p>These cases need real files, because the whole point is the search order: the directory
 * of the importing file, then {@code modules/}, then {@code lib/}. A test against in-memory
 * source cannot prove the resolver was consulted at all.
 */
class HoverImportsTest {

    /** Locates a cursor by needle so reformatting a case cannot silently move it. */
    private static int[] at(String source, String needle) {
        int offset = source.indexOf(needle);
        assertTrue(offset >= 0, "needle not found: " + needle);
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, offset - lineStart};
    }

    private static Path write(Path file, String source) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    private static String uriOf(Path file) {
        return file.toUri().toString();
    }

    @Test
    void aNameDeclaredOnlyInAnImportedModuleIsDescribed(@TempDir Path dir) throws Exception {
        write(dir.resolve("helper.oq"), """
                \"\"\"Doubles a number.

                @param n the value to double
                @return twice n
                \"\"\"
                fun twice(n)
                  return n * 2
                end
                """);
        Path main = write(dir.resolve("main.oq"), """
                use "oq:helper"

                print twice(21)
                """);

        int[] position = at(Files.readString(main), "twice(21)");
        String hover = Hover.hover(uriOf(main), Files.readString(main), position[0], position[1]);

        assertNotNull(hover);
        assertTrue(hover.contains("fun twice(n)"), hover);
        assertTrue(hover.contains("Doubles a number."), hover);
        assertTrue(hover.contains("**n** the value to double"), hover);
        assertTrue(hover.contains("Returns: twice n"), hover);
    }

    @Test
    void aModuleFoundUnderModulesIsFollowedToo(@TempDir Path dir) throws Exception {
        write(dir.resolve("modules/util.oq"), """
                \"\"\"Removes trailing blanks.\"\"\"
                fun trim_end(text)
                  return text
                end
                """);
        Path main = write(dir.resolve("main.oq"), """
                use "oq:util"

                print trim_end("a ")
                """);

        int[] position = at(Files.readString(main), "trim_end(");
        String hover = Hover.hover(uriOf(main), Files.readString(main), position[0], position[1]);

        assertNotNull(hover);
        assertTrue(hover.contains("fun trim_end(text)"), hover);
        assertTrue(hover.contains("Removes trailing blanks."), hover);
    }

    @Test
    void anImportIsFollowedTransitively(@TempDir Path dir) throws Exception {
        write(dir.resolve("inner.oq"), """
                \"\"\"The innermost declaration.\"\"\"
                fun deepest(x)
                  return x
                end
                """);
        write(dir.resolve("middle.oq"), """
                use "oq:inner"
                """);
        Path main = write(dir.resolve("main.oq"), """
                use "oq:middle"

                print deepest(1)
                """);

        int[] position = at(Files.readString(main), "deepest(1)");
        String hover = Hover.hover(uriOf(main), Files.readString(main), position[0], position[1]);

        assertNotNull(hover, "an import of a module that imports another must be followed");
        assertTrue(hover.contains("fun deepest(x)"), hover);
    }

    @Test
    void aLocalDeclarationWinsOverAnImportedOne(@TempDir Path dir) throws Exception {
        write(dir.resolve("helper.oq"), """
                \"\"\"The imported one.\"\"\"
                fun pick(x)
                  return x
                end
                """);
        Path main = write(dir.resolve("main.oq"), """
                use "oq:helper"

                \"\"\"The local one.\"\"\"
                fun pick(x)
                  return x + 1
                end

                print pick(1)
                """);

        int[] position = at(Files.readString(main), "pick(1)");
        String hover = Hover.hover(uriOf(main), Files.readString(main), position[0], position[1]);

        assertNotNull(hover);
        assertTrue(hover.contains("The local one."), hover);
        assertTrue(!hover.contains("The imported one."), hover);
    }

    @Test
    void anImportCycleTerminatesAndStillResolves(@TempDir Path dir) throws Exception {
        write(dir.resolve("a.oq"), """
                use "oq:b"

                \"\"\"Declared in a.\"\"\"
                fun from_a(x)
                  return x
                end
                """);
        write(dir.resolve("b.oq"), """
                use "oq:a"

                \"\"\"Declared in b.\"\"\"
                fun from_b(x)
                  return x
                end
                """);
        Path main = write(dir.resolve("main.oq"), """
                use "oq:a"

                print from_b(1)
                """);

        int[] position = at(Files.readString(main), "from_b(1)");
        String hover = Hover.hover(uriOf(main), Files.readString(main), position[0], position[1]);

        assertNotNull(hover);
        assertTrue(hover.contains("fun from_b(x)"), hover);
    }

    @Test
    void aChainDeeperThanTheLoadersLimitIsNotFollowed(@TempDir Path dir) throws Exception {
        // The interpreter refuses more than fifty levels. A hover that walked further would
        // describe a name the program cannot actually run, so it stops at the same depth.
        for (int i = 0; i < 55; i++) {
            write(dir.resolve("m" + i + ".oq"), i == 54
                    ? "\"\"\"Too deep to reach.\"\"\"\nfun unreachable(x)\n  return x\nend\n"
                    : "use \"oq:m" + (i + 1) + "\"\n");
        }
        Path main = write(dir.resolve("main.oq"), """
                use "oq:m0"

                print unreachable(1)
                """);

        int[] position = at(Files.readString(main), "unreachable(1)");
        String hover = Hover.hover(uriOf(main), Files.readString(main), position[0], position[1]);

        assertNull(hover, "a chain the interpreter would refuse must not resolve either");
    }

    @Test
    void aChainWithinTheLimitIsFollowedToTheEnd(@TempDir Path dir) throws Exception {
        // The mirror of the case above: depth is a limit, not a reason to give up early.
        for (int i = 0; i < 40; i++) {
            write(dir.resolve("m" + i + ".oq"), i == 39
                    ? "\"\"\"Reachable.\"\"\"\nfun reachable(x)\n  return x\nend\n"
                    : "use \"oq:m" + (i + 1) + "\"\n");
        }
        Path main = write(dir.resolve("main.oq"), """
                use "oq:m0"

                print reachable(1)
                """);

        int[] position = at(Files.readString(main), "reachable(1)");
        String hover = Hover.hover(uriOf(main), Files.readString(main), position[0], position[1]);

        assertNotNull(hover, "forty levels is within the limit and must resolve");
        assertTrue(hover.contains("fun reachable(x)"), hover);
    }

    @Test
    void aMissingModuleLeavesTheHoverEmptyRatherThanFailing(@TempDir Path dir) throws Exception {
        Path main = write(dir.resolve("main.oq"), """
                use "oq:absent"

                print mystery(1)
                """);

        int[] position = at(Files.readString(main), "mystery(1)");
        String hover = Hover.hover(uriOf(main), Files.readString(main), position[0], position[1]);

        assertNull(hover, "a name nothing declares must return null, not an error");
    }

    @Test
    void anUntitledBufferDoesNotAttemptToResolveImports() {
        String source = """
                use "oq:helper"

                print twice(1)
                """;
        int[] position = at(source, "twice(1)");

        String hover = Hover.hover("untitled:Untitled-1", source, position[0], position[1]);

        assertNull(hover, "a buffer with no file has no directory to resolve against");
    }

    @Test
    void aModuleThatDoesNotParseIsSkippedRatherThanThrown(@TempDir Path dir) throws Exception {
        write(dir.resolve("broken.oq"), "fun broken(\n");
        Path main = write(dir.resolve("main.oq"), """
                use "oq:broken"

                print broken(1)
                """);

        int[] position = at(Files.readString(main), "broken(1)");
        String hover = Hover.hover(uriOf(main), Files.readString(main), position[0], position[1]);

        assertNull(hover);
    }

    @Test
    void aBuiltinStillResolvesWhenTheFileImportsNothing(@TempDir Path dir) throws Exception {
        Path main = write(dir.resolve("main.oq"), """
                print range(3)
                """);

        int[] position = at(Files.readString(main), "range(3)");
        String hover = Hover.hover(uriOf(main), Files.readString(main), position[0], position[1]);

        assertNotNull(hover, "import following must not shadow the builtin table");
        assertEquals(true, hover.contains("range"), hover);
    }
}
