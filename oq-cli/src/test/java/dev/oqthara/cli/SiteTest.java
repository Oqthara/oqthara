package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the website sources. Node is not required: these checks read the build script's
 * inputs and the generated output when it exists, so a broken site fails the normal build.
 */
class SiteTest {
    private static Path root() {
        return Path.of(System.getProperty("oq.root", "."));
    }

    @Test
    void buildScriptDefinesEveryNavigationTarget() throws IOException {
        String build = Files.readString(root().resolve("site/build.mjs"));
        for (String page : List.of("index.html", "tour.html", "docs.html", "examples.html",
                "stdlib.html", "status.html")) {
            assertTrue(build.contains("\"" + page + "\""),
                    "navigation is missing " + page);
        }
    }

    @Test
    void stylesheetHasDesignTokensAndAccessibilityRules() throws IOException {
        String css = Files.readString(root().resolve("site/assets/site.css"));
        for (String token : List.of("--bg:", "--fg:", "--accent:", "--radius:",
                "--mono:", "prefers-color-scheme", "prefers-reduced-motion",
                ":focus-visible", "@media (max-width: 600px)", ".skip-link")) {
                assertTrue(css.contains(token), "stylesheet is missing " + token);
        }
        // Reduced motion must actually disable animation and transitions.
        assertTrue(css.contains("animation: none !important"),
                "reduced motion must disable animations");
        assertTrue(css.contains("transition: none !important"),
                "reduced motion must disable transitions");
    }

    @Test
    void generatedPagesAreStructurallyValid() throws IOException {
        Path output = root().resolve("site/_site");
        if (!Files.isDirectory(output)) return; // built by CI; skip when absent
        List<String> problems = new ArrayList<>();
        List<Path> pages;
        try (Stream<Path> stream = Files.list(output)) {
            pages = stream.filter(p -> p.toString().endsWith(".html")).toList();
        }
        assertTrue(pages.size() >= 6, "expected at least 6 pages, found " + pages.size());
        for (Path page : pages) {
            String html = Files.readString(page);
            for (String required : List.of("<!DOCTYPE html>", "lang=\"en\"", "<title>",
                    "id=\"main\"", "assets/site.css", "skip-link", "aria-label=\"Main\"",
                    "viewport")) {
                if (!html.contains(required)) {
                    problems.add(page.getFileName() + " missing " + required);
                }
            }
            if (html.contains("${")) {
                problems.add(page.getFileName() + " leaked a template literal");
            }
            Matcher links = Pattern.compile("href=\"([^\"#][^\"]*)\"").matcher(html);
            while (links.find()) {
                String target = links.group(1);
                if (target.startsWith("http") || target.startsWith("mailto")) continue;
                if (!Files.exists(output.resolve(target))) {
                    problems.add(page.getFileName() + " broken link " + target);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void playgroundOnlyAdvertisesProgramsItCanShow() throws IOException {
        String js = Files.readString(root().resolve("site/playground.js"));
        for (String example : List.of("hello", "fizzbuzz", "records", "wordcount",
                "interop", "parallel")) {
            assertTrue(js.contains(example + ":"), "playground is missing " + example);
        }
        // It must not claim to execute, because it cannot.
        assertTrue(js.contains("Run this locally"),
                "playground must tell the visitor to run locally");
    }

    @Test
    void pagesWorkflowBuildsAndVerifiesBeforePublishing() throws IOException {
        String workflow = Files.readString(
                root().resolve(".github/workflows/pages.yml"));
        assertTrue(workflow.contains("node site/build.mjs"), "workflow must build the site");
        assertTrue(workflow.contains("node site/verify.mjs"), "workflow must verify the site");
        assertTrue(workflow.contains("html-validate"), "workflow must parse the HTML");
        assertTrue(workflow.contains("actions/deploy-pages"), "workflow must deploy");
        assertTrue(workflow.contains("curl"), "workflow must smoke test the published URL");
        assertTrue(workflow.contains("pages: write"), "permissions must allow Pages");
    }
}
