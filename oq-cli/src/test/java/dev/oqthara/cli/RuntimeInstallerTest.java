package dev.oqthara.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers ADR-0048 D5: installation is atomic and validation precedes activation. */
class RuntimeInstallerTest {

    /**
     * The launcher's file name on this platform.
     *
     * <p>These tests build archives by hand, so they decide what the archive contains. A JDK on
     * Windows has {@code bin/java.exe} and none of the others, so an archive holding only
     * {@code bin/java} is not a runtime there -- the installer was right to refuse it, and the
     * tests were wrong to expect otherwise. Every path below goes through this constant so the
     * two cannot disagree again.
     */
    private static final String JAVA = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "java.exe" : "java";

    /** Reports whatever major the caller tells it to, so no real JVM is needed. */
    private static RuntimeResolver.VersionProbe probeAnswering(int major) {
        return executable -> OptionalInt.of(major);
    }

    private static RuntimeResolver.VersionProbe probeFailing() {
        return executable -> OptionalInt.empty();
    }

    /** Builds a fake JDK archive: a top-level directory holding bin/java. */
    private static Path fakeRuntime(Path dir, String topLevel, int major) throws IOException {
        Path archive = dir.resolve("runtime-" + major + ".zip");
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(topLevel + "/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(topLevel + "/bin/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(topLevel + "/bin/" + JAVA));
            zip.write("#!/bin/sh\necho 'openjdk version \"21.0.1\"'\n"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(topLevel + "/release"));
            zip.write(("JAVA_VERSION=\"" + major + "\"\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return archive;
    }

    @Test
    void installsAndActivatesAValidRuntime(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path archive = fakeRuntime(temp, "jdk-21.0.1", 21);

        Path java = new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21))
                .install(archive, 21);

        assertEquals(home.resolve("runtime").resolve("java").toAbsolutePath(), java);
        assertTrue(Files.isRegularFile(java.resolve("bin").resolve(JAVA)),
                "the activated runtime should hold bin/" + JAVA);
        assertTrue(Files.isRegularFile(java.resolve("release")));
    }

    @Test
    void theActivatedRuntimeIsWhereTheResolverLooks(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path archive = fakeRuntime(temp, "jdk-21.0.1", 21);
        new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21)).install(archive, 21);

        // This is the coupling that matters: an installer that activates somewhere else would
        // pass its own tests and still leave `oq` unable to find the runtime.
        String managed = RuntimeResolver.managedJava(java.util.Map.of("OQ_HOME", home.toString()))
                .orElseThrow(() -> new AssertionError("the resolver cannot see the runtime"));
        assertTrue(Files.isRegularFile(Path.of(managed)));
    }

    @Test
    void refusesAnArchiveWithNoJavaExecutable(@TempDir Path temp) throws Exception {
        Path archive = temp.resolve("notajdk.zip");
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("docs/readme.txt"));
            zip.write("nothing useful".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        RuntimeInstaller installer =
                new RuntimeInstaller(temp.resolve("home"), Duration.ofSeconds(5), probeAnswering(21));
        RuntimeInstaller.InstallRefusedException refused =
                assertThrows(RuntimeInstaller.InstallRefusedException.class,
                        () -> installer.install(archive, 21));
        assertTrue(refused.getMessage().contains("bin/" + JAVA), refused.getMessage());
        assertFalse(Files.exists(installer.destination()),
                "a refused archive must not activate anything");
    }

    @Test
    void refusesWhenTheExtractedJavaCannotReportAVersion(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path archive = fakeRuntime(temp, "jdk-21.0.1", 21);
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeFailing());

        RuntimeInstaller.InstallRefusedException refused =
                assertThrows(RuntimeInstaller.InstallRefusedException.class,
                        () -> installer.install(archive, 21));
        assertTrue(refused.getMessage().contains("did not report a version"),
                refused.getMessage());
        assertFalse(Files.exists(installer.destination()),
                "a runtime that cannot report its version must not be activated");
    }

    @Test
    void refusesARuntimeBelowTheFloor(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path archive = fakeRuntime(temp, "jdk-17.0.9", 17);
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(17));

        RuntimeInstaller.InstallRefusedException refused =
                assertThrows(RuntimeInstaller.InstallRefusedException.class,
                        () -> installer.install(archive, 21));
        assertTrue(refused.getMessage().contains("Java 17"), refused.getMessage());
        assertTrue(refused.getMessage().contains("21"), refused.getMessage());
        assertFalse(Files.exists(installer.destination()));
    }

    @Test
    void validationHappensBeforeActivation(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path archive = fakeRuntime(temp, "jdk-21.0.1", 21);

        // The probe is the only place validation can run, so this records whether the runtime
        // was visible at its live path at the moment validation was asked about it. If it were
        // activated first, the answer would be yes.
        final boolean[] visibleDuringValidation = {false};
        Path destination = home.resolve("runtime").resolve("java");
        RuntimeResolver.VersionProbe recording = executable -> {
            visibleDuringValidation[0] = Files.exists(destination);
            return OptionalInt.of(21);
        };

        new RuntimeInstaller(home, Duration.ofSeconds(5), recording).install(archive, 21);

        assertFalse(visibleDuringValidation[0],
                "the runtime was already at its live path while it was being validated");
        assertTrue(Files.isRegularFile(destination.resolve("bin").resolve(JAVA)));
    }

    @Test
    void aFailedInstallLeavesNoStagingDirectory(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path archive = fakeRuntime(temp, "jdk-21.0.1", 21);
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeFailing());

        assertThrows(RuntimeInstaller.InstallRefusedException.class,
                () -> installer.install(archive, 21));

        assertTrue(installer.discardAbandonedStaging() == 0,
                "a failed install must not leave staging behind for the next run to clean");
    }

    @Test
    void discardsStagingLeftByAnInterruptedRun(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Files.createDirectories(home.resolve(".staging-abandoned").resolve("jdk/bin"));
        Files.writeString(home.resolve(".staging-abandoned").resolve("jdk/bin/" + JAVA), "x");

        int removed = new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21))
                .discardAbandonedStaging();

        assertEquals(1, removed);
        assertFalse(Files.exists(home.resolve(".staging-abandoned")));
    }

    @Test
    void discardingDoesNotTouchAnActiveRuntime(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path live = home.resolve("runtime").resolve("java").resolve("bin");
        Files.createDirectories(live);
        Files.writeString(live.resolve(JAVA), "x");
        Files.createDirectories(home.resolve(".staging-old"));

        new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21))
                .discardAbandonedStaging();

        assertTrue(Files.isRegularFile(live.resolve(JAVA)),
                "cleanup must never delete the runtime that is in use");
    }

    @Test
    void refusesAnArchiveThatDoesNotExist(@TempDir Path temp) {
        RuntimeInstaller installer =
                new RuntimeInstaller(temp.resolve("home"), Duration.ofSeconds(5), probeAnswering(21));
        RuntimeInstaller.InstallRefusedException refused =
                assertThrows(RuntimeInstaller.InstallRefusedException.class,
                        () -> installer.install(temp.resolve("absent.zip"), 21));
        assertTrue(refused.getMessage().contains("does not exist"), refused.getMessage());
    }

    @Test
    void refusesRatherThanActivatingOverAnArchiveItCannotExtract(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path archive = temp.resolve("evil.zip");
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("jdk/bin/" + JAVA));
            zip.write("x".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            // Traversal: SafeArchive must refuse, and the installer must not activate anything.
            zip.putNextEntry(new ZipEntry("../escaped.txt"));
            zip.write("x".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21));

        assertThrows(IOException.class, () -> installer.install(archive, 21));
        assertFalse(Files.exists(installer.destination()));
        assertFalse(Files.exists(temp.resolve("escaped.txt")));
    }

    @Test
    void removesTheOldRuntimeIsNotAttemptedHere(@TempDir Path temp) throws Exception {
        // Retention is D7, not D5. Pinned so a later change does not silently start deleting the
        // previous runtime as part of an upgrade, which would make rollback impossible.
        Path home = temp.resolve("home");
        Path live = home.resolve("runtime").resolve("java").resolve("bin");
        Files.createDirectories(live);
        Files.writeString(live.resolve(JAVA), "old");

        Path archive = fakeRuntime(temp, "jdk-21.0.2", 21);
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21));

        // The activated path already exists, so activation is refused rather than overwriting:
        // a real upgrade has to move the old runtime aside first, which is D7's job.
        assertThrows(IOException.class, () -> installer.install(archive, 21));
        assertEquals("old", Files.readString(live.resolve(JAVA)),
                "an existing runtime must not be replaced without retention");
    }

    @Test
    @Timeout(120)
    void aHeldLockDelaysAConcurrentInstall(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Files.createDirectories(home);
        Path archive = fakeRuntime(temp, "jdk-21.0.1", 21);

        try (InstallLock held = InstallLock.acquire(home, Duration.ofSeconds(5))) {
            RuntimeInstaller installer =
                    new RuntimeInstaller(home, Duration.ofMillis(200), probeAnswering(21));
            assertThrows(IllegalStateException.class, () -> installer.install(archive, 21));
            assertFalse(Files.exists(installer.destination()));
            assertTrue(Files.exists(held.file()));
        }

        // Once released, the same installer object proceeds.
        Path java = new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21))
                .install(archive, 21);
        assertTrue(Files.isRegularFile(java.resolve("bin").resolve(JAVA)));
    }
}
