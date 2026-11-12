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
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers ADR-0048 D7: rollback is retention, and retention never costs a working runtime. */
class RuntimeRetentionTest {

    /** A JDK on Windows has {@code bin/java.exe} and no {@code bin/java}. */
    private static final String JAVA = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "java.exe" : "java";

    private static RuntimeResolver.VersionProbe probeAnswering(int major) {
        return executable -> OptionalInt.of(major);
    }

    private static Path fakeRuntime(Path dir, String name) throws IOException {
        Path archive = dir.resolve(name + ".zip");
        try (OutputStream out = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : new String[] {"bin/", "bin/" + JAVA, "release", "lib/"}) {
                zip.putNextEntry(new ZipEntry(name + "/" + entry));
                if (entry.endsWith(JAVA)) {
                    zip.write("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
                } else if (entry.equals("release")) {
                    zip.write("JAVA_VERSION=\"21\"\n".getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return archive;
    }

    @Test
    void firstInstallRetainsNothing(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RuntimeRetention retention = new RuntimeRetention(home);

        assertFalse(retention.hasPrevious());
        new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21))
                .install(fakeRuntime(temp, "jdk-21"), 21);

        assertTrue(Files.isDirectory(retention.active().resolve("bin")));
        assertFalse(retention.hasPrevious(), "a first install has no earlier runtime to retain");
    }

    @Test
    void upgradeRetainsThePreviousRuntime(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RuntimeRetention retention = new RuntimeRetention(home);
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21));

        installer.install(fakeRuntime(temp, "jdk-21.0.1"), 21);
        Files.writeString(retention.active().resolve("marker"), "first");

        installer.upgrade(fakeRuntime(temp, "jdk-21.0.2"), 21);

        assertTrue(Files.isDirectory(retention.active().resolve("bin")));
        assertTrue(retention.hasPrevious(), "the replaced runtime should be retained");
        assertEquals("first", Files.readString(retention.previous().resolve("marker")),
                "the retained runtime should be the one that was active");
        assertFalse(Files.exists(retention.active().resolve("marker")),
                "the active runtime should be the new one");
    }

    @Test
    void aRefusedUpgradeLeavesTheWorkingRuntimeActive(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RuntimeRetention retention = new RuntimeRetention(home);
        new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21))
                .install(fakeRuntime(temp, "jdk-21.0.1"), 21);
        Files.writeString(retention.active().resolve("marker"), "working");

        // Below the floor: validation must refuse before rotation, so the working runtime is
        // still active and nothing has been displaced.
        RuntimeInstaller refusing =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(17));
        assertThrows(RuntimeInstaller.InstallRefusedException.class,
                () -> refusing.upgrade(fakeRuntime(temp, "jdk-17"), 21));

        assertEquals("working", Files.readString(retention.active().resolve("marker")),
                "a refused upgrade must not displace the working runtime");
        assertFalse(retention.hasPrevious(),
                "a refused upgrade must not rotate, since nothing was activated");
    }

    @Test
    void rollbackSelectsTheRetainedRuntime(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RuntimeRetention retention = new RuntimeRetention(home);
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21));

        installer.install(fakeRuntime(temp, "jdk-21.0.1"), 21);
        Files.writeString(retention.active().resolve("marker"), "old");
        installer.upgrade(fakeRuntime(temp, "jdk-21.0.2"), 21);
        Files.writeString(retention.active().resolve("marker"), "new");

        Path restored = retention.rollback();

        assertEquals(retention.active(), restored);
        assertEquals("old", Files.readString(retention.active().resolve("marker")),
                "rollback should make the retained runtime active again");
    }

    @Test
    void rollbackIsSelectingAndIsItselfUndoable(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RuntimeRetention retention = new RuntimeRetention(home);
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21));

        installer.install(fakeRuntime(temp, "jdk-21.0.1"), 21);
        Files.writeString(retention.active().resolve("marker"), "old");
        installer.upgrade(fakeRuntime(temp, "jdk-21.0.2"), 21);
        Files.writeString(retention.active().resolve("marker"), "new");

        retention.rollback();
        // The runtime that was active is now retained rather than deleted, so a rollback that
        // turns out to be the wrong move is undone the same way.
        assertEquals("new", Files.readString(retention.previous().resolve("marker")));

        retention.rollback();
        assertEquals("new", Files.readString(retention.active().resolve("marker")),
                "a second rollback should return to the runtime rolled back from");
    }

    @Test
    void rollbackWithoutARetainedRuntimeIsRefused(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RuntimeRetention retention = new RuntimeRetention(home);
        new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21))
                .install(fakeRuntime(temp, "jdk-21"), 21);

        IOException refused = assertThrows(IOException.class, retention::rollback);
        assertTrue(refused.getMessage().contains("nothing to fall back to"), refused.getMessage());
    }

    @Test
    void atMostTwoRuntimesAreKept(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RuntimeRetention retention = new RuntimeRetention(home);
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21));

        installer.install(fakeRuntime(temp, "jdk-21.0.1"), 21);
        installer.upgrade(fakeRuntime(temp, "jdk-21.0.2"), 21);
        // A third generation replaces the retained one rather than accumulating.
        installer.upgrade(fakeRuntime(temp, "jdk-21.0.3"), 21);

        long runtimeDirs;
        try (var stream = Files.list(home.resolve("runtime"))) {
            runtimeDirs = stream.filter(Files::isDirectory).count();
        }
        assertEquals(2, runtimeDirs, "exactly one active and one retained runtime should remain");
        assertTrue(retention.hasPrevious());
    }

    @Test
    void discardingThePreviousRuntimeIsExplicit(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RuntimeRetention retention = new RuntimeRetention(home);
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21));

        installer.install(fakeRuntime(temp, "jdk-21.0.1"), 21);
        installer.upgrade(fakeRuntime(temp, "jdk-21.0.2"), 21);
        assertTrue(retention.hasPrevious());

        retention.discardPrevious();

        assertFalse(retention.hasPrevious(), "discarding should remove only the retained one");
        assertTrue(Files.isDirectory(retention.active().resolve("bin")),
                "the active runtime must survive discarding the previous one");
    }

    @Test
    void anUpgradeThatCannotRotateDoesNotHalfInstall(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RuntimeRetention retention = new RuntimeRetention(home);
        new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21))
                .install(fakeRuntime(temp, "jdk-21.0.1"), 21);
        Files.writeString(retention.active().resolve("marker"), "working");

        // An upgrade of an archive with no bin/java must fail at validation, which happens
        // before rotation, and must leave no staging directory for the next run to clean.
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21));
        Path broken = temp.resolve("broken.zip");
        try (OutputStream out = Files.newOutputStream(broken);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("x".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThrows(RuntimeInstaller.InstallRefusedException.class,
                () -> installer.upgrade(broken, 21));

        assertEquals("working", Files.readString(retention.active().resolve("marker")));
        assertEquals(0, installer.discardAbandonedStaging(),
                "a failed upgrade must not leave staging behind");
    }

    @Test
    void aRuntimeThatCannotRunIsNotTreatedAsWorking(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        // A directory with a bin/java that is not executable: the shape D7 asks about, where
        // the runtime is present but cannot run a program.
        Path broken = home.resolve("runtime").resolve("java");
        Files.createDirectories(broken.resolve("bin"));
        Files.writeString(broken.resolve("bin").resolve(JAVA), "not a program");

        assertFalse(RuntimeRetention.runsAProgram(broken, Duration.ofSeconds(5)),
                "a runtime that cannot run a program must not count as working");
    }

    @Test
    void aRealRuntimeRunsAProgram(@TempDir Path temp) {
        Path javaHome = Path.of(System.getProperty("java.home"));
        assertTrue(RuntimeRetention.runsAProgram(javaHome, Duration.ofSeconds(30)),
                "the JVM running the tests should be reported as able to run a program");
    }

    @Test
    void theRetainedVersionsMajorIsReported(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RuntimeRetention retention = new RuntimeRetention(home);
        RuntimeInstaller installer =
                new RuntimeInstaller(home, Duration.ofSeconds(5), probeAnswering(21));

        installer.install(fakeRuntime(temp, "jdk-21.0.1"), 21);
        assertTrue(retention.previousMajor(probeAnswering(21)).isEmpty(),
                "nothing is retained yet");

        installer.upgrade(fakeRuntime(temp, "jdk-21.0.2"), 21);
        assertEquals(21, retention.previousMajor(probeAnswering(21)).orElseThrow());
    }
}
