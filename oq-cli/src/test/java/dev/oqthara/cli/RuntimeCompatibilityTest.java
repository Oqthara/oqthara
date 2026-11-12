package dev.oqthara.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the bounded compatibility check (ADR-0048 D9).
 *
 * <p>The central case is a JVM that reports a correct version and still cannot run Oqthara.
 * ADR-0002 records exactly that happening with JDK 25, which is why this check exists at all;
 * a test that only ever runs a real JVM would not distinguish this class from
 * {@code RuntimeInstaller.validate}.
 */
class RuntimeCompatibilityTest {

    /**
     * A fake JVM whose behaviour the test chooses. A shell script on Unix, a batch file on
     * Windows; both are what a real {@code java} looks like to {@link ProcessBuilder}.
     */
    private static Path fakeJava(Path dir, String script) throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows");
        Path java = dir.resolve(windows ? "java.bat" : "java");
        Files.writeString(java, script, StandardCharsets.UTF_8);
        if (!windows) {
            assertTrue(java.toFile().setExecutable(true), "could not make the fake JVM executable");
        }
        return java;
    }

    private static String shellScript(String body) {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows");
        return windows ? "@echo off\r\n" + body.replace("\n", "\r\n") : "#!/bin/sh\n" + body;
    }

    /** A real jar is needed for the check to run at all; its contents are never inspected. */
    private static Path emptyJar(Path dir) throws IOException {
        Path jar = dir.resolve("oq.jar");
        Files.write(jar, new byte[] {'P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0});
        return jar;
    }

    @Test
    void refusesAJvmThatCannotLoadTheJar(@TempDir Path dir) throws Exception {
        // Reports a fine version, then fails every invocation. This is the JDK 25 case: the
        // version is not the problem.
        Path java = fakeJava(dir, shellScript("exit 1\n"));

        RuntimeCompatibility.Verdict verdict =
                new RuntimeCompatibility(emptyJar(dir)).validate(java);

        assertFalse(verdict.compatible(), verdict.summary());
        assertTrue(verdict.summary().contains("--version"), verdict.summary());
    }

    @Test
    void refusesAJvmThatPrintsTheWrongOutput(@TempDir Path dir) throws Exception {
        // Succeeds, but prints nonsense instead of the program's output. A check that only
        // looked at the exit code would accept this.
        Path java = fakeJava(dir, shellScript("echo \"[3,2,1]\"\necho 99\nexit 0\n"));

        RuntimeCompatibility.Verdict verdict =
                new RuntimeCompatibility(emptyJar(dir)).validate(java);

        assertFalse(verdict.compatible(), verdict.summary());
        assertTrue(verdict.summary().contains("language and standard library"),
                verdict.summary());
    }

    @Test
    void refusesAJvmThatHangs(@TempDir Path dir) throws Exception {
        Path java = fakeJava(dir, shellScript("sleep 60\n"));
        RuntimeCompatibility compatibility = new RuntimeCompatibility(emptyJar(dir), 2);

        RuntimeCompatibility.Verdict verdict = compatibility.validate(java);

        assertFalse(verdict.compatible(), verdict.summary());
        assertTrue(verdict.summary().contains("timed out"), verdict.summary());
    }

    @Test
    void aWorkingJvmPassesTheBoundedChecks(@TempDir Path dir) throws Exception {
        // Stands in for a JVM that runs everything successfully. It must reproduce the checked
        // program's output exactly, because the check compares output rather than exit status
        // alone; printing "ok" here would be a wrong-output run and is refused by design.
        Path java = fakeJava(dir, shellScript("echo '[1,2,3]'\necho 10\nexit 0\n"));

        RuntimeCompatibility.Verdict verdict =
                new RuntimeCompatibility(emptyJar(dir)).validate(java);

        assertTrue(verdict.compatible(), verdict.summary());
        assertTrue(verdict.summary().contains("version"), verdict.summary());
        assertTrue(verdict.summary().contains("database check"), verdict.summary());
    }

    @Test
    void aMissingDatabaseLibraryDoesNotRefuseTheJvm(@TempDir Path dir) throws Exception {
        // The database check is reported, not enforced. A machine without the native SQLite
        // library would otherwise be unable to install any runtime at all, even though every
        // other program it runs works.
        Path java = fakeJava(dir, shellScript(
                "if [ \"$1\" = \"-jar\" ] && [ \"$3\" = \"--version\" ]; then echo 'oq 0.1.0'; exit 0; fi\n"
                        + "if grep -q 'db_execute' \"$3\" 2>/dev/null; then\n"
                        + "  echo 'no SQLite library on this platform' >&2\n"
                        + "  exit 1\n"
                        + "fi\n"
                        + "echo '[1,2,3]'\n"
                        + "echo 10\n"
                        + "exit 0\n"));

        RuntimeCompatibility.Verdict verdict =
                new RuntimeCompatibility(emptyJar(dir)).validate(java);

        assertTrue(verdict.compatible(), verdict.summary());
        assertTrue(verdict.summary().contains("did not run"), verdict.summary());
    }

    @Test
    void anAbsentJarIsAnErrorRatherThanAVerdict(@TempDir Path dir) throws Exception {
        // Misconfiguration is not the candidate's fault, so it must not read as "incompatible".
        Path java = fakeJava(dir, shellScript("exit 0\n"));
        RuntimeCompatibility compatibility = new RuntimeCompatibility(dir.resolve("absent.jar"));

        IOException thrown = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> compatibility.validate(java));

        assertTrue(thrown.getMessage().contains("does not exist"), thrown.getMessage());
    }

    @Test
    void aCheckThatCannotStartIsAFailureNotAPass(@TempDir Path dir) throws Exception {
        // A java path that is a directory cannot be executed at all. Reaching the check and
        // finding nothing runnable must not be mistaken for a pass.
        RuntimeCompatibility compatibility = new RuntimeCompatibility(emptyJar(dir));

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> compatibility.validate(dir.resolve("not-an-executable")));
    }

    @Test
    void theCheckedProgramsAreTheThreeThatAreDocumented() {
        // The claim in the ADR and the matrix is specific: version, language/stdlib, database.
        // If a fourth is added or one is dropped, the documents become wrong, so this pins the
        // set rather than letting it drift silently.
        assertEquals(3, RuntimeCompatibility.documentedCheckCount(),
                "ADR-0048 D9 names exactly three checks");
    }
}
