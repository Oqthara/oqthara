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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the React website. Node is not required: these checks read the source tree and the
 * prerendered output when it exists, so a broken site fails the normal Gradle build.
 */
class SiteTest {
    private static final List<String> PAGES = List.of(
            "index.html", "tour.html", "docs.html", "examples.html", "stdlib.html", "status.html");

    private static Path root() {
        return Path.of(System.getProperty("oq.root", "."));
    }

    @Test
    void theSiteIsAReactApplication() throws IOException {
        Path site = root().resolve("site");
        String packageJson = Files.readString(site.resolve("package.json"));
        assertTrue(packageJson.contains("\"react\""), "site must depend on React");
        assertTrue(packageJson.contains("\"react-dom\""), "site must depend on React DOM");
        assertTrue(packageJson.contains("\"vite\""), "site must use a real production bundler");
        assertTrue(packageJson.contains("prerender"), "site must prerender to static HTML");

        // The entry point must mount React, not assemble strings.
        String main = Files.readString(site.resolve("src/main.jsx"));
        assertTrue(main.contains("@vitejs/plugin-react") || main.contains("react-dom/client"),
                "the entry point must use React DOM");
        assertTrue(Files.exists(site.resolve("src/App.jsx")), "site must define a React App");
    }

    @Test
    void componentsAreSplitIntoReusablePieces() throws IOException {
        Path components = root().resolve("site/src/components");
        assertTrue(Files.isDirectory(components), "site must have a components directory");
        for (String component : List.of("Layout.jsx", "Code.jsx", "Playground.jsx")) {
            assertTrue(Files.exists(components.resolve(component)),
                    "missing shared component " + component);
        }
        Path pages = root().resolve("site/src/pages");
        for (String page : List.of("HomePage.jsx", "TourPage.jsx", "DocsPage.jsx",
                "ExamplesPage.jsx", "StdlibPage.jsx", "StatusPage.jsx")) {
            assertTrue(Files.exists(pages.resolve(page)), "missing page component " + page);
        }
        // No page may be a monolithic dump: each one is a component with its own content.
        for (String page : List.of("HomePage.jsx", "TourPage.jsx", "DocsPage.jsx",
                "ExamplesPage.jsx", "StdlibPage.jsx", "StatusPage.jsx")) {
            int lines = Files.readAllLines(pages.resolve(page)).size();
            assertTrue(lines < 400, page + " is too large to be maintainable (" + lines + " lines)");
        }
    }

    @Test
    void routesCoverEveryPublishedUrl() throws IOException {
        String app = Files.readString(root().resolve("site/src/App.jsx"));
        for (String page : PAGES) {
            assertTrue(app.contains("\"" + page + "\""), "routes are missing " + page);
        }
    }

    @Test
    void prerenderedPagesAreValidAndSelfContained() throws IOException {
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
                    "id=\"main\"", "skip-link", "aria-label=\"Main\"", "viewport",
                    "<div id=\"root\">", "/oqthara/")) {
                if (!html.contains(required)) {
                    problems.add(page.getFileName() + " missing " + required);
                }
            }
            if (html.contains("${")) {
                problems.add(page.getFileName() + " leaked a template literal");
            }
            if (html.contains("__REFERENCE__")) {
                problems.add(page.getFileName() + " leaked a placeholder");
            }
            Matcher links = Pattern.compile("href=\"(/oqthara/[^\"]*)\"").matcher(html);
            while (links.find()) {
                String relative = links.group(1).substring("/oqthara/".length());
                if (relative.isEmpty()) continue;
                if (!Files.exists(output.resolve(relative))) {
                    problems.add(page.getFileName() + " broken link " + links.group(1));
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void stylesheetHasDesignTokensAndAccessibilityRules() throws IOException {
        String css = Files.readString(root().resolve("site/assets/site.css"));
        for (String token : List.of("--bg:", "--fg:", "--accent:", "--radius:",
                "--mono:", "prefers-color-scheme", "prefers-reduced-motion",
                ":focus-visible", "@media (max-width: 600px)", ".skip-link")) {
            assertTrue(css.contains(token), "stylesheet is missing " + token);
        }
        assertTrue(css.contains("animation: none !important"),
                "reduced motion must disable animations");
        assertTrue(css.contains("transition: none !important"),
                "reduced motion must disable transitions");
    }

    @Test
    void theBasePathMatchesGitHubPages() throws IOException {
        String config = Files.readString(root().resolve("site/vite.config.js"));
        assertTrue(config.contains("/oqthara/"),
                "the Vite base must match the GitHub Pages project path");
    }

    @Test
    void playgroundDoesNotClaimToExecute() throws IOException {
        String source = Files.readString(root().resolve("site/src/components/Playground.jsx"));
        for (String example : List.of("hello", "fizzbuzz", "records", "wordcount",
                "interop", "parallel")) {
            assertTrue(source.contains(example + ":"), "playground is missing " + example);
        }
        assertTrue(source.contains("does not execute") || source.contains("needs the JVM"),
                "the playground must state that it does not run programs");
    }

    @Test
    void pagesWorkflowBuildsAndVerifiesBeforePublishing() throws IOException {
        String workflow = Files.readString(root().resolve(".github/workflows/pages.yml"));
        assertTrue(workflow.contains("npm ci") || workflow.contains("npm install"),
                "workflow must install the locked dependencies");
        assertTrue(workflow.contains("npm run build"), "workflow must run the React build");
        assertTrue(workflow.contains("npm test"), "workflow must execute the component tests");
        assertTrue(workflow.contains("verify.mjs"), "workflow must verify the site");
        assertTrue(workflow.contains("html-validate"), "workflow must parse the HTML");
        assertTrue(workflow.contains("actions/deploy-pages"), "workflow must deploy");
        assertTrue(workflow.contains("curl"), "workflow must smoke test the published URL");
        assertTrue(workflow.contains("pages: write"), "permissions must allow Pages");
    }
}
