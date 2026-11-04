package dev.oqthara.cli;

import dev.oqthara.core.Lexer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocGeneratorTest {
    private static Path root() {
        return Path.of(System.getProperty("oq.root", "."));
    }

    @Test
    void generatedReferenceListsRealFunctions() throws Exception {
        String markdown = DocGenerator.generate(
                root().resolve("oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java"),
                root().resolve("oq-stdlib/src/main/java/dev/oqthara/stdlib/MethodResolverImpl.java"));
        for (String expected : List.of("`print`", "`json_parse`", "`spawn`", "`read`", "`round`")) {
            assertTrue(markdown.contains(expected), "reference is missing " + expected);
        }
    }

    @Test
    void keywordSectionMatchesTheLexer() throws Exception {
        String markdown = DocGenerator.generate(
                root().resolve("oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java"),
                root().resolve("oq-stdlib/src/main/java/dev/oqthara/stdlib/MethodResolverImpl.java"));
        for (String keyword : Lexer.KEYWORDS) {
            assertTrue(markdown.contains("`" + keyword + "`"), "missing keyword " + keyword);
        }
        assertTrue(markdown.contains(Lexer.KEYWORDS.size() + " keywords"),
                "keyword count must be stated and correct");
    }

    @Test
    void methodSectionsAreGrouped() throws Exception {
        String markdown = DocGenerator.generate(
                root().resolve("oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java"),
                root().resolve("oq-stdlib/src/main/java/dev/oqthara/stdlib/MethodResolverImpl.java"));
        assertTrue(markdown.contains("### list"));
        assertTrue(markdown.contains("### map"));
        assertTrue(markdown.contains("### text"));
        assertTrue(markdown.contains("`.map()`"));
    }

    @Test
    void noDuplicateSources() throws Exception {
        String markdown = DocGenerator.generate(
                root().resolve("oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java"),
                root().resolve("oq-stdlib/src/main/java/dev/oqthara/stdlib/MethodResolverImpl.java"));
        assertFalse(markdown.contains("built-in, built-in"));
    }

    @Test
    void checkedInReferenceIsUpToDate() throws Exception {
        Path reference = root().resolve("docs/stdlib/reference.md");
        if (!Files.exists(reference)) return;
        String current = Files.readString(reference);
        String generated = DocGenerator.generate(
                root().resolve("oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java"),
                root().resolve("oq-stdlib/src/main/java/dev/oqthara/stdlib/MethodResolverImpl.java"));
        assertEquals(generated, current,
                "docs/stdlib/reference.md is stale; run: oq doc .");
    }
}
