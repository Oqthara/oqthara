package dev.oqthara.cli;

import dev.oqthara.core.Lexer;
import dev.oqthara.core.Parser;
import dev.oqthara.runtime.Span;
import dev.oqthara.stdlib.Json;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the editor extension honest: every keyword the lexer knows must be highlighted,
 * every snippet must parse, and every file referenced by package.json must exist.
 *
 * <p>Deliberately uses Oqthara's own JSON parser rather than a test dependency, so the
 * test also exercises the stdlib on files that are not Oqthara programs.
 */
class EditorSupportTest {
    private static final Path EXTENSION =
            Path.of(System.getProperty("oq.root", ".")).resolve("editors/vscode");

    private static Object json(String relative) throws Exception {
        return Json.parse(Files.readString(EXTENSION.resolve(relative)), Span.synthetic());
    }

    @SuppressWarnings("unchecked")
    private static Object at(Object node, String... path) {
        Object current = node;
        for (String key : path) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(key);
            } else if (current instanceof List<?> list) {
                current = list.get(Integer.parseInt(key));
            } else {
                throw new AssertionError("cannot descend into " + current + " with " + key);
            }
            if (current == null) throw new AssertionError("missing path: " + String.join("/", path));
        }
        return current;
    }

    @Test
    void grammarCoversEveryKeyword() throws Exception {
        Object grammar = json("syntaxes/oq.tmLanguage.json");
        String keywords = (String) at(grammar, "repository", "keywords", "patterns", "0", "match");
        String literals = (String) at(grammar, "repository", "constants", "patterns", "0", "match");
        String wordOperators = (String) at(grammar, "repository", "operators", "patterns", "0", "match");
        for (String keyword : Lexer.KEYWORDS) {
            // true/nothing/false are highlighted as constants, not control keywords.
            boolean covered = keywords.contains(keyword) || literals.contains(keyword)
                    || wordOperators.contains(keyword);
            assertTrue(covered, "grammar is missing keyword: " + keyword);
        }
    }

    @Test
    void grammarHasTheExpectedScopes() throws Exception {
        Object grammar = json("syntaxes/oq.tmLanguage.json");
        assertEquals("source.oq", at(grammar, "scopeName"));
        assertEquals("Oqthara", at(grammar, "name"));
        assertTrue(at(grammar, "fileTypes").toString().contains("oq"));
        assertTrue(((List<?>) at(grammar, "repository", "strings", "patterns")).size() >= 3,
                "expected single, double and multi-line strings");
        assertTrue(((String) at(grammar, "repository", "comments", "patterns", "0", "match")).contains("#"));
    }

    @Test
    void everySnippetParses() throws Exception {
        Object snippets = json("snippets/oq.json");
        List<String> failures = new ArrayList<>();
        for (var entry : ((Map<?, ?>) snippets).entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object body = at(entry.getValue(), "body");
            StringBuilder source = new StringBuilder();
            for (Object line : (List<?>) body) {
                String text = stripPlaceholders(String.valueOf(line));
                source.append(text).append('\n');
            }
            try {
                Parser.parse(name, source.toString());
            } catch (RuntimeException e) {
                failures.add(name + ": " + e.getMessage() + "\n" + source);
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("snippets that do not parse:\n" + String.join("\n\n", failures));
        }
    }

    private static String stripPlaceholders(String line) {
        String text = line;
        while (text.contains("${")) {
            int start = text.indexOf("${");
            int end = text.indexOf('}', start);
            if (end < 0) break;
            String inner = text.substring(start + 2, end);
            int colon = inner.indexOf(':');
            String replacement = colon >= 0 ? inner.substring(colon + 1) : "x";
            replacement = replacement.replaceAll("\\$\\d+", "x");
            text = text.substring(0, start) + replacement + text.substring(end + 1);
        }
        return text.replaceAll("\\$\\d+", "x");
    }

    @Test
    void languageConfigurationMatchesTheSyntax() throws Exception {
        Object config = json("language-configuration.json");
        assertEquals("#", at(config, "comments", "lineComment"));
        assertTrue(((String) at(config, "indentationRules", "decreaseIndentPattern")).contains("end"));
        assertTrue(((String) at(config, "indentationRules", "increaseIndentPattern")).contains("fun"));
    }

    @Test
    void packageJsonPointsAtExistingFiles() throws Exception {
        Object pkg = json("package.json");
        assertEquals("oq", at(pkg, "contributes", "languages", "0", "id"));
        assertEquals(".oq", at(pkg, "contributes", "languages", "0", "extensions", "0"));
        for (String relative : List.of(
                (String) at(pkg, "contributes", "grammars", "0", "path"),
                (String) at(pkg, "contributes", "snippets", "0", "path"),
                (String) at(pkg, "contributes", "languages", "0", "configuration"))) {
            Path resolved = EXTENSION.resolve(relative.replace("./", "")).normalize();
            assertTrue(Files.exists(resolved), "missing file referenced by package.json: " + relative);
        }
    }

    @Test
    void everyExampleParsesAndIsNotEmpty() throws Exception {
        Path examples = Path.of(System.getProperty("oq.root", ".")).resolve("examples");
        try (var stream = Files.list(examples)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".oq")).toList()) {
                String source = Files.readString(file);
                assertFalse(source.isBlank(), "empty example: " + file);
                Parser.parse(file.getFileName().toString(), source);
            }
        }
    }
}
