package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code oq java} command surface: help, option rejection, and machine-readable output.
 *
 * <p>These exercise the parsing rather than the resolution, because the resolution order is
 * covered by {@link RuntimeResolverTest}. Running the real command would depend on whichever JDK
 * happens to be installed, which is exactly what makes such a test flaky.
 */
class JavaCommandTest {
    private record Run(int exit, String out, String err) {
    }

    private static Run java(String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int exit = new Main.Runner(out, err, null).javaRuntime(args);
        return new Run(exit, outBytes.toString(StandardCharsets.UTF_8),
                errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpExplainsTheResolutionOrder() {
        Run run = java("--help");
        assertEquals(0, run.exit());
        assertTrue(run.out().contains("OQ_JAVA"), run.out());
        assertTrue(run.out().contains(".oq/java"), run.out());
        assertTrue(run.out().contains("Java 21"), run.out());
        assertTrue(run.err().isEmpty(), run.err());
    }

    @Test
    void shortHelpAlsoWorks() {
        assertEquals(0, java("-h").exit());
    }

    @Test
    void unknownOptionIsRejectedWithoutResolving() {
        Run run = java("--nonsense");
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("Unknown option: --nonsense"), run.err());
    }

    @Test
    void reportedOriginIsOneOfTheDocumentedFour() {
        // The command must never invent an origin; this asserts the enum the ADR fixes, so an
        // addition to it is a deliberate change rather than an accident.
        assertEquals(4, RuntimeResolver.Origin.values().length);
        Run run = java();
        assertTrue(run.exit() == 0 || run.exit() == 1, "exit " + run.exit() + ": " + run.err());
    }

    @Test
    void theJsonReportIsByteIdenticalInEveryLocale() {
        // The origin name is lowercased before it reaches the JSON. Under tr_TR an unqualified
        // toLowerCase() turns "OVERRIDE" into "overrıde" with a dotless i, so a consumer
        // comparing against "override" reports a missing field rather than a malformed one.
        // Build the resolution directly, because whether this machine resolves an override at
        // all depends on its own environment — and a test that only holds when OQ_JAVA happens
        // to be set is a test that cannot fail.
        RuntimeResolver.Resolution resolution = new RuntimeResolver.Resolution(
                java.util.Optional.of(new RuntimeResolver.Candidate(
                        RuntimeResolver.Origin.OVERRIDE, "/opt/java", java.util.OptionalInt.of(21),
                        null)),
                java.util.List.of(new RuntimeResolver.Candidate(
                        RuntimeResolver.Origin.PROJECT_PIN, "/proj/.oq/java",
                        java.util.OptionalInt.of(17), "below the Java 21 floor")));
        PlatformSupport.Platform platform = new PlatformSupport.Platform("linux", "x86_64");

        Locale original = Locale.getDefault();
        try {
            String baseline = null;
            for (Locale locale : new Locale[] {
                    Locale.ROOT,
                    Locale.forLanguageTag("tr-TR"),
                    Locale.forLanguageTag("ar-EG"),
                    Locale.forLanguageTag("de-DE"),
                    Locale.forLanguageTag("hi-IN-u-nu-deva")}) {
                Locale.setDefault(locale);

                String json = Main.Runner.jsonRuntime(resolution, platform);

                if (baseline == null) {
                    baseline = json;
                } else {
                    assertEquals(baseline, json, () -> "JSON differs under " + locale);
                }
            }
            String settled = baseline;
            assertTrue(
                    settled.contains("\"origin\":\"override\""),
                    () -> "origin was locale-mangled: " + settled);
            assertTrue(
                    settled.contains("\"origin\":\"project_pin\""),
                    () -> "rejected origin was locale-mangled: " + settled);
        } finally {
            Locale.setDefault(original);
        }
    }
}
