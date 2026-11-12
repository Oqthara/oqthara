package dev.oqthara.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
