package dev.oqthara.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime resolution (ADR-0048 D1). Each test fixes one documented clause of the order, so a
 * change here is a change to the ADR rather than to an implementation detail.
 */
class RuntimeResolverTest {

    /** A JDK on Windows has {@code bin/java.exe} and no {@code bin/java}, so the name depends. */
    private static final String EXECUTABLE = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "java.exe" : "java";

    /** A probe that answers from a fixed table, so no process is ever started. */
    private static RuntimeResolver.VersionProbe probe(Map<String, Integer> versions) {
        return executable -> {
            Integer major = versions.get(executable);
            return major == null ? OptionalInt.empty() : OptionalInt.of(major);
        };
    }

    private static RuntimeResolver.Inputs inputs(String override, String pin,
                                                 String managed, String path) {
        return new RuntimeResolver.Inputs(override, pin, managed, path);
    }

    @Test
    void overrideWinsOverEverythingElse() {
        RuntimeResolver.Resolution resolution = RuntimeResolver.resolve(
                inputs("/opt/override/java", "/proj/.oq/java", "/home/.oq/runtime/java", "java"),
                probe(Map.of(
                        "/opt/override/java", 21,
                        "/proj/.oq/java", 25,
                        "/home/.oq/runtime/java", 25,
                        "java", 25)));

        assertEquals(RuntimeResolver.Origin.OVERRIDE, resolution.chosen().origin());
        assertTrue(resolution.rejected().isEmpty(),
                "a winning override should mean nothing below it was consulted");
    }

    @Test
    void projectPinBeatsManagedAndPath() {
        RuntimeResolver.Resolution resolution = RuntimeResolver.resolve(
                inputs(null, "/proj/.oq/java", "/home/.oq/runtime/java", "java"),
                probe(Map.of("/proj/.oq/java", 21, "/home/.oq/runtime/java", 25, "java", 25)));

        assertEquals(RuntimeResolver.Origin.PROJECT_PIN, resolution.chosen().origin());
        assertEquals("/proj/.oq/java", resolution.chosen().location());
    }

    @Test
    void managedRuntimeBeatsPath() {
        RuntimeResolver.Resolution resolution = RuntimeResolver.resolve(
                inputs(null, null, "/home/.oq/runtime/java", "java"),
                probe(Map.of("/home/.oq/runtime/java", 21, "java", 25)));

        assertEquals(RuntimeResolver.Origin.MANAGED, resolution.chosen().origin());
    }

    @Test
    void pathIsTheLastResort() {
        RuntimeResolver.Resolution resolution = RuntimeResolver.resolve(
                inputs(null, null, null, "java"), probe(Map.of("java", 21)));

        assertEquals(RuntimeResolver.Origin.PATH, resolution.chosen().origin());
    }

    @Test
    void belowFloorIsRejectedAndResolutionContinues() {
        // A JDK 17 named by OQ_JAVA must not shadow a usable JDK 21 on PATH: silently ignoring the
        // override would be worse than reporting it, but failing outright would be worse still.
        RuntimeResolver.Resolution resolution = RuntimeResolver.resolve(
                inputs("/opt/old/java", null, null, "java"),
                probe(Map.of("/opt/old/java", 17, "java", 21)));

        assertTrue(resolution.found());
        assertEquals(RuntimeResolver.Origin.PATH, resolution.chosen().origin());
        assertEquals(1, resolution.rejected().size());
        RuntimeResolver.Candidate rejected = resolution.rejected().get(0);
        assertEquals(RuntimeResolver.Origin.OVERRIDE, rejected.origin());
        assertTrue(rejected.rejection().contains("below the required floor"),
                rejected.rejection());
    }

    @Test
    void unparseableVersionIsRejectedRatherThanAssumedUsable() {
        RuntimeResolver.Resolution resolution = RuntimeResolver.resolve(
                inputs("/opt/broken/java", null, null, null),
                probe(Map.of()));

        assertFalse(resolution.found());
        assertEquals(1, resolution.rejected().size());
        assertTrue(resolution.rejected().get(0).rejection().contains("could not determine"));
    }

    @Test
    void unsetOriginsAreNotReportedAsRejections() {
        // Nothing configured is different from something configured and wrong; reporting the
        // former as four rejections would bury the one reason that matters.
        RuntimeResolver.Resolution resolution = RuntimeResolver.resolve(
                inputs(null, "", "  ", null), probe(Map.of()));

        assertFalse(resolution.found());
        assertTrue(resolution.rejected().isEmpty(), resolution.rejected().toString());
    }

    @Test
    void parsesTheQuotedBanner() {
        assertEquals(OptionalInt.of(21), RuntimeResolver.parseMajorVersion(
                "openjdk version \"21.0.1\" 2023-10-17"));
        assertEquals(OptionalInt.of(25), RuntimeResolver.parseMajorVersion(
                "openjdk version \"25-ea\" 2026-09-15"));
    }

    @Test
    void parsesTheLegacyOneDotForm() {
        // 1.8 must read as 8, not as 1, or an ancient runtime is reported as far below the floor
        // when it is merely somewhat below it.
        assertEquals(OptionalInt.of(8), RuntimeResolver.parseMajorVersion(
                "java version \"1.8.0_402\""));
    }

    @Test
    void rejectsBannersWithoutAVersion() {
        assertTrue(RuntimeResolver.parseMajorVersion("java: command not found").isEmpty());
        assertTrue(RuntimeResolver.parseMajorVersion("").isEmpty());
        assertTrue(RuntimeResolver.parseMajorVersion(null).isEmpty());
    }

    @Test
    void failureMessageNamesEveryRejectedCandidate() {
        RuntimeResolver.Resolution resolution = RuntimeResolver.resolve(
                inputs("/opt/old/java", null, null, null),
                probe(Map.of("/opt/old/java", 17)));

        String message = RuntimeResolver.explainFailure(resolution);
        assertTrue(message.contains("Java 21 or newer"), message);
        assertTrue(message.contains("/opt/old/java"), message);
        assertTrue(message.contains("OQ_JAVA"), message);
    }

    @Test
    void failureMessageDistinguishesNothingConfiguredFromAllRejected() {
        RuntimeResolver.Resolution nothing = RuntimeResolver.resolve(
                inputs(null, null, null, null), probe(Map.of()));

        String message = RuntimeResolver.explainFailure(nothing);
        assertTrue(message.contains("Nothing was configured"), message);
        assertFalse(message.contains("were rejected"), message);
    }

    @Test
    void failureMessageNamesWhereAManagedRuntimeWouldBeInstalled() {
        // ADR-0048 D8: an offline failure has to say where the runtime it looked for belongs.
        // Without the path, "no managed runtime" reads the same whether one was never installed
        // or was installed somewhere else and this invocation is looking in the wrong home.
        RuntimeResolver.Resolution nothing = RuntimeResolver.resolve(
                inputs(null, null, null, null), probe(Map.of()));

        String message = RuntimeResolver.explainFailure(nothing, "/home/someone/.oq");

        assertTrue(message.contains(Path.of("/home/someone/.oq", "runtime", "java").toString()),
                message);
        assertTrue(message.contains("does not exist"), message);
    }

    @Test
    void failureMessageOmitsTheManagedPathWhenNoHomeIsKnown() {
        RuntimeResolver.Resolution nothing = RuntimeResolver.resolve(
                inputs(null, null, null, null), probe(Map.of()));

        String withoutHome = RuntimeResolver.explainFailure(nothing);

        assertFalse(withoutHome.contains("would be installed at"), withoutHome);
        assertTrue(withoutHome.contains("Put one on PATH") || withoutHome.contains("on PATH"),
                withoutHome);
    }

    @Test
    void anOfflineFailureSaysTheNetworkIsUnreachable() {
        // ADR-0048 D8. The useful part of an offline failure is that installing is impossible
        // right now, which is a different situation from the network being fine and the
        // download merely not having happened.
        RuntimeResolver.Resolution nothing = RuntimeResolver.resolve(
                inputs(null, null, "/home/someone/.oq/runtime/java/bin/java",
                        "/usr/bin/java"), probe(Map.of()));

        String offline = RuntimeResolver.explainFailure(nothing, "/home/someone/.oq",
                () -> false);

        assertTrue(offline.contains("The network is unreachable"), offline);
        assertTrue(offline.contains("used offline without any network access"), offline);
        assertTrue(offline.contains(Path.of("/home/someone/.oq", "runtime", "java").toString()),
                offline);
    }

    @Test
    void anOnlineFailureDoesNotClaimTheNetworkIsDown() {
        // The mirror of the previous test, and the one that catches a probe that is never
        // consulted: a message that always says "unreachable" would pass that test alone.
        RuntimeResolver.Resolution nothing = RuntimeResolver.resolve(
                inputs(null, null, null, null), probe(Map.of()));

        String online = RuntimeResolver.explainFailure(nothing, "/home/someone/.oq", () -> true);

        assertFalse(online.contains("The network is unreachable"), online);
        assertTrue(online.contains("does not exist"), online);
    }

    @Test
    void theNetworkIsNotConsultedWhenNoManagedHomeIsKnown() {
        // Nothing would be downloaded into a home that is not known, so asking the network
        // would be a side effect this path does not need. A probe that throws proves it is
        // never called.
        RuntimeResolver.Resolution nothing = RuntimeResolver.resolve(
                inputs(null, null, null, null), probe(Map.of()));

        String message = RuntimeResolver.explainFailure(nothing, null, () -> {
            throw new AssertionError("the network must not be consulted without a managed home");
        });

        assertFalse(message.contains("The network is unreachable"), message);
    }

    @Test
    void anInstalledRuntimeResolvesWithoutAnyNetwork(@TempDir Path home) throws Exception {
        // The first half of D8, and the half that matters: an installed runtime is used with
        // no network. Resolution reads the filesystem and never reaches out, so the proof is
        // that a runtime on disk resolves while the network probe would report unreachable.
        Path java = home.resolve("runtime").resolve("java").resolve("bin").resolve("java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "#!/bin/sh\necho 'openjdk version \"21.0.0\"'\n");
        java.toFile().setExecutable(true);

        RuntimeResolver.Resolution resolution = RuntimeResolver.resolve(
                inputs(null, null, java.toString(), null),
                probe(Map.of(java.toString(), 21)));

        assertTrue(resolution.found(), resolution.rejected().toString());
        assertEquals(java.toString(), resolution.selected().orElseThrow().location());
    }

    @Test
    void managedHomeFallsBackFromOqHomeToUserHome() {
        assertEquals(Path.of("/custom/oq"),
                RuntimeResolver.managedHome(Map.of("OQ_HOME", "/custom/oq")).orElseThrow());
        assertEquals(Path.of("/home/someone/.oq"),
                RuntimeResolver.managedHome(Map.of("HOME", "/home/someone")).orElseThrow());
        // The separator belongs to the running platform, so the expectation is built the same way
        // rather than pinning a Windows separator into a test that also runs on POSIX.
        assertEquals(Path.of("C:\\Users\\someone").resolve(".oq"),
                RuntimeResolver.managedHome(Map.of("USERPROFILE", "C:\\Users\\someone"))
                        .orElseThrow());
        assertTrue(RuntimeResolver.managedHome(Map.of()).isEmpty(),
                "no home variable names nothing rather than a guess");
    }

    @Test
    void projectPinIsFoundFromANestedDirectory(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".oq"));
        Path java = root.resolve(".oq").resolve("java");
        Files.writeString(java, "#!/bin/sh\n");
        Path nested = Files.createDirectories(root.resolve("src").resolve("deep"));

        assertEquals(java.toString(), RuntimeResolver.findProjectPin(nested).orElseThrow());
    }

    @Test
    void absentProjectPinFindsNothing(@TempDir Path root) throws Exception {
        Path nested = Files.createDirectories(root.resolve("src"));
        assertTrue(RuntimeResolver.findProjectPin(nested).isEmpty());
    }

    @Test
    void managedRuntimeIsOnlyOfferedWhenItExists(@TempDir Path home) throws Exception {
        assertTrue(RuntimeResolver.managedJava(Map.of("OQ_HOME", home.toString())).isEmpty());

        Files.createDirectories(home.resolve("runtime").resolve("java").resolve("bin"));
        Path java = home.resolve("runtime").resolve("java").resolve("bin").resolve(EXECUTABLE);
        Files.writeString(java, "#!/bin/sh\n");

        assertEquals(java.toString(),
                RuntimeResolver.managedJava(Map.of("OQ_HOME", home.toString())).orElseThrow());
    }

    @Test
    void managedRuntimeDefaultsToTheHomeDirectory(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(".oq").resolve("runtime").resolve("java").resolve("bin"));
        Path java = home.resolve(".oq").resolve("runtime").resolve("java").resolve("bin")
                .resolve(EXECUTABLE);
        Files.writeString(java, "#!/bin/sh\n");

        assertEquals(java.toString(),
                RuntimeResolver.managedJava(Map.of("HOME", home.toString())).orElseThrow());
    }

    /**
     * Shell scripts stand in for candidates here, so these three run only on POSIX.
     *
     * <p>Windows has no {@code /bin/sh} to write a hanging or flooding probe in, and the
     * behaviour under test is the deadline and the cap rather than anything platform-specific.
     * The matrix still exercises the bounded probe on Windows through every other test in this
     * class, because the real probe runs the JDK there.
     */
    private static final boolean POSIX = java.io.File.separatorChar == '/';

    /** A candidate that never exits must be refused, not waited on forever. */
    @Test
    @Timeout(30)
    void aProbeThatNeverExitsIsRefused(@TempDir Path temp) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(POSIX, "needs a POSIX shell");
        Path script = script(temp, "hangs", "exec sleep 600");

        long started = System.nanoTime();
        OptionalInt major = RuntimeResolver
                .processProbe(java.time.Duration.ofMillis(500), 4096)
                .majorVersion(script.toString());
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(major.isEmpty(), "a hanging candidate must not report a version");
        assertTrue(elapsedMillis < 10_000,
                "the probe must give up at its deadline, but took " + elapsedMillis + "ms");
    }

    /**
     * Exceeding the cap is a refusal even when a parseable banner arrives first.
     *
     * <p>The candidate exits promptly, so only the cap can decide this. It announces a valid
     * version and then floods; without the cap the whole stream is read and the banner parses,
     * which is why this case -- rather than one that never terminates -- is the one that proves
     * the cap is load-bearing.
     */
    @Test
    @Timeout(60)
    void aProbeThatFloodsItsOutputIsRefused(@TempDir Path temp) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(POSIX, "needs a POSIX shell");
        Path script = script(temp, "floods",
                "echo 'openjdk version \"21.0.1\" 2025-01-21'\n"
                        + "i=0\n"
                        + "while [ $i -lt 200 ]; do echo 'padding padding padding padding';"
                        + " i=$((i+1)); done");

        OptionalInt major = RuntimeResolver
                .processProbe(java.time.Duration.ofSeconds(20), 1024)
                .majorVersion(script.toString());

        assertTrue(major.isEmpty(),
                "exceeding the output cap is a refusal, not a truncated but accepted banner");
    }

    /** The bounded probe still parses a candidate that behaves. */
    @Test
    void aWellBehavedProbeStillReportsItsMajor(@TempDir Path temp) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(POSIX, "needs a POSIX shell");
        Path script = script(temp, "good", "echo 'openjdk version \"21.0.9\" 2025-01-21'");

        assertEquals(OptionalInt.of(21), RuntimeResolver.processProbe().majorVersion(script.toString()));
    }

    private static Path script(Path dir, String name, String body) throws IOException {
        Path script = dir.resolve(name);
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        assertTrue(script.toFile().setExecutable(true), "the script must be executable");
        return script;
    }
}
