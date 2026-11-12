package dev.oqthara.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Chooses the JVM that will run Oqthara programs (ADR-0048 D1).
 *
 * <p>The order is fixed and documented because an ambiguous one produces a class of bug that is
 * expensive to diagnose: the same command behaving differently on two machines for a reason
 * nobody wrote down. The first entry that exists <em>and</em> validates to the floor wins, and
 * nothing below it is consulted.
 *
 * <ol>
 *   <li>an explicit {@code OQ_JAVA} override;</li>
 *   <li>a project-local pin;</li>
 *   <li>a managed runtime installed by {@code oq};</li>
 *   <li>{@code java} on {@code PATH}.</li>
 * </ol>
 *
 * <p>Resolution reads the environment and the filesystem and nothing else, so it is a pure
 * function of its inputs and can be tested without installing a JDK. It does not download,
 * extract or modify anything: deciding which runtime to use is separable from acquiring one, and
 * keeping them separate is what makes the decision testable.
 */
final class RuntimeResolver {

    /** The floor is the release target set by {@code options.release = 21} in build.gradle.kts. */
    static final int FLOOR = 21;

    /** Where each origin looked for a runtime, and what it found. */
    enum Origin {
        /** {@code OQ_JAVA} named an executable. */
        OVERRIDE("override"),
        /** A project-local pin named an executable. */
        PROJECT_PIN("project pin"),
        /** A runtime this tool installed and validated. */
        MANAGED("managed runtime"),
        /** Whatever {@code java} resolves to on {@code PATH}. */
        PATH("PATH");

        private final String label;

        Origin(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * One candidate considered, whether or not it was usable.
     *
     * <p>Rejected candidates are kept so a failure can explain every place that was checked
     * instead of reporting only the last one. "No Java found" is unhelpful when the real problem
     * is that {@code OQ_JAVA} points at a JDK 17.
     */
    record Candidate(Origin origin, String location, OptionalInt major, String rejection) {
        boolean usable() {
            return rejection == null;
        }

        String describe() {
            if (usable()) {
                return origin.label() + ": " + location + " (Java " + major.getAsInt() + ")";
            }
            return origin.label() + ": " + location + " — " + rejection;
        }
    }

    /** The outcome of resolution: a winner when one exists, and every candidate examined. */
    record Resolution(Optional<Candidate> selected, List<Candidate> rejected) {
        boolean found() {
            return selected.isPresent();
        }

        Candidate chosen() {
            return selected.orElseThrow();
        }
    }

    /** Everything resolution is allowed to depend on, injected so tests need no real environment. */
    record Inputs(String override, String projectPin, String managedHome, String pathJava) {
    }

    private RuntimeResolver() {
    }

    /**
     * Resolves against a supplied environment.
     *
     * <p>Each origin is examined in order and the first usable one wins. A candidate that exists
     * but is below the floor is recorded as rejected and resolution continues, because a wrong
     * answer from a higher-priority origin must not shadow a correct one below it.
     */
    static Resolution resolve(Inputs inputs, VersionProbe probe) {
        List<Candidate> rejected = new java.util.ArrayList<>();

        Candidate override = examine(Origin.OVERRIDE, inputs.override(), probe);
        if (override != null) {
            if (override.usable()) {
                return new Resolution(Optional.of(override), rejected);
            }
            rejected.add(override);
        }

        Candidate pin = examine(Origin.PROJECT_PIN, inputs.projectPin(), probe);
        if (pin != null) {
            if (pin.usable()) {
                return new Resolution(Optional.of(pin), rejected);
            }
            rejected.add(pin);
        }

        Candidate managed = examine(Origin.MANAGED, inputs.managedHome(), probe);
        if (managed != null) {
            if (managed.usable()) {
                return new Resolution(Optional.of(managed), rejected);
            }
            rejected.add(managed);
        }

        Candidate onPath = examine(Origin.PATH, inputs.pathJava(), probe);
        if (onPath != null) {
            if (onPath.usable()) {
                return new Resolution(Optional.of(onPath), rejected);
            }
            rejected.add(onPath);
        }

        return new Resolution(Optional.empty(), rejected);
    }

    /** Returns {@code null} when this origin named nothing at all, rather than a misleading miss. */
    private static Candidate examine(Origin origin, String location, VersionProbe probe) {
        if (location == null || location.isBlank()) {
            return null;
        }
        OptionalInt major = probe.majorVersion(location);
        if (major.isEmpty()) {
            return new Candidate(origin, location, OptionalInt.empty(),
                    "could not determine the Java version");
        }
        if (major.getAsInt() < FLOOR) {
            return new Candidate(origin, location, major, "Java " + major.getAsInt()
                    + " is below the required floor of " + FLOOR);
        }
        return new Candidate(origin, location, major, null);
    }

    /**
     * Reads the four locations from the real environment.
     *
     * <p>The project pin is {@code .oq/java} upward from the working directory, so a pin placed
     * at a repository root applies to every directory beneath it. The managed runtime lives under
     * {@code OQ_HOME}, falling back to {@code $HOME/.oq}, and its {@code java} is only offered
     * when the file exists — a missing directory is "not installed", not "broken".
     */
    static Inputs inputsFrom(Path workingDirectory, java.util.Map<String, String> env) {
        String override = env.get("OQ_JAVA");
        String pin = findProjectPin(workingDirectory).orElse(null);
        String managed = managedJava(env).orElse(null);
        String pathJava = env.getOrDefault("PATH", "").isEmpty() ? null : "java";
        return new Inputs(override, pin, managed, pathJava);
    }

    /** Walks up from {@code start} looking for {@code .oq/java}, stopping at the filesystem root. */
    static Optional<String> findProjectPin(Path start) {
        Path current = start == null ? null : start.toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(".oq").resolve("java");
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toString());
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    /** Where a managed runtime would be installed, or empty when no home can be determined. */
    static Optional<Path> managedHome(java.util.Map<String, String> env) {
        String home = env.get("OQ_HOME");
        if (home == null || home.isBlank()) {
            home = env.get("HOME");
            if (home == null || home.isBlank()) {
                home = env.get("USERPROFILE");
            }
            if (home == null || home.isBlank()) {
                return Optional.empty();
            }
            home = Path.of(home, ".oq").toString();
        }
        return Optional.of(Path.of(home));
    }

    /**
     * Returns the managed runtime's {@code java}, if one has been installed and validated.
     *
     * <p>{@code runtime/java} is the runtime's <em>home</em>, the directory a JDK archive extracts
     * to, and the executable is {@code bin/java} beneath it. It was briefly treated as the
     * executable itself, which no real JDK archive can satisfy: a runtime is a tree of
     * {@code bin}, {@code lib} and {@code release}, not one file. Nothing could install a runtime
     * when that assumption was written, so no test caught it until the installer existed and had
     * somewhere to put one.
     */
    static Optional<String> managedJava(java.util.Map<String, String> env) {
        Optional<Path> home = managedHome(env);
        if (home.isEmpty()) {
            return Optional.empty();
        }
        Optional<Path> java = managedExecutable(home.get());
        return java.map(Path::toString);
    }

    /** The {@code java} executable inside a managed home, or empty when nothing is installed. */
    static Optional<Path> managedExecutable(Path home) {
        String name = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows") ? "java.exe" : "java";
        Path java = home.resolve("runtime").resolve("java").resolve("bin").resolve(name);
        return Files.isRegularFile(java) ? Optional.of(java) : Optional.empty();
    }

    /** Asks a specific executable for its version. Injectable for tests. */
    interface VersionProbe {
        OptionalInt majorVersion(String executable);
    }

    /**
     * Runs {@code <executable> -version} and parses the major number.
     *
     * <p>{@code java} writes its version banner to stderr, so both streams are read. The banner
     * quotes the version ({@code openjdk version "21.0.1" 2023-10-17}); a {@code 1.8} style is
     * handled for completeness so an ancient runtime gets a "below the floor" message rather than
     * a parse failure.
     */
    static OptionalInt parseMajorVersion(String banner) {
        if (banner == null) {
            return OptionalInt.empty();
        }
        int open = banner.indexOf('"');
        if (open < 0) {
            return OptionalInt.empty();
        }
        int close = banner.indexOf('"', open + 1);
        if (close < 0) {
            return OptionalInt.empty();
        }
        String version = banner.substring(open + 1, close);
        String digits = version.startsWith("1.") ? version.substring(2) : version;
        int end = 0;
        while (end < digits.length() && Character.isDigit(digits.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(digits.substring(0, end)));
        } catch (NumberFormatException tooLarge) {
            return OptionalInt.empty();
        }
    }

    /** The real probe: executes the candidate and parses its banner. */
    static VersionProbe processProbe() {
        return executable -> {
            try {
                Process process = new ProcessBuilder(executable, "-version")
                        .redirectErrorStream(true)
                        .start();
                String output = new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                process.waitFor();
                return parseMajorVersion(output);
            } catch (IOException | InterruptedException failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return OptionalInt.empty();
            }
        };
    }

    /**
     * Renders the failure message when nothing resolved.
     *
     * <p>It names the floor, every origin that was checked and why each was refused, and the two
     * variables that would fix it. A diagnostic that omits the reason is why runtime problems get
     * reported as "install Java" when Java is already installed.
     *
     * <p>When called with the managed home it also names the directory a managed runtime would
     * occupy (ADR-0048 D8). Without it the message says what to install but not where, which
     * leaves the offline case indistinguishable from "the tool is not installed at all".
     */
    static String explainFailure(Resolution resolution) {
        return explainFailure(resolution, null);
    }

    /**
     * Answers whether a runtime could be acquired right now, if one is missing.
     *
     * <p>ADR-0048 D8. Separated from the message so a test can state the answer rather than
     * depend on the network of the machine running it. The real probe asks the network; a test
     * replaces it and asserts both branches.
     */
    interface Reachability {
        boolean networkAvailable();
    }

    /**
     * The real probe: whether any acquisition host answers.
     *
     * <p>An unreachable host is a slow DNS lookup, so the first answer that arrives decides.
     * A network that exists but cannot reach the vendor is reported as unavailable, because for
     * this purpose the only question is whether installing is possible.
     */
    static boolean networkReachable() {
        String[] hosts = {"api.adoptium.net", "github.com"};
        for (String host : hosts) {
            try {
                if (java.net.InetAddress.getByName(host).isReachable(2_000)) {
                    return true;
                }
            } catch (IOException | SecurityException ignored) {
                // Try the next host. A probe that throws answers "unreachable" for this host.
            }
        }
        return false;
    }

    static String explainFailure(Resolution resolution, String managedHome) {
        return explainFailure(resolution, managedHome, RuntimeResolver::networkReachable);
    }

    static String explainFailure(Resolution resolution, String managedHome,
            Reachability reachability) {
        StringBuilder message = new StringBuilder();
        message.append("no usable Java runtime found; Oqthara requires Java ")
                .append(FLOOR).append(" or newer.\n");
        if (resolution.rejected().isEmpty()) {
            message.append("Nothing was configured: no OQ_JAVA, no .oq/java pin, ")
                    .append("no managed runtime, and no java on PATH.\n");
        } else {
            message.append("Candidates that were rejected:\n");
            for (Candidate candidate : resolution.rejected()) {
                message.append("  - ").append(candidate.describe()).append('\n');
            }
        }
        if (managedHome != null && !managedHome.isBlank()) {
            message.append("A managed runtime would be installed at ")
                    .append(Path.of(managedHome, "runtime", "java"))
                    .append(", which does not exist.\n");
            if (!reachability.networkAvailable()) {
                message.append("The network is unreachable, so one cannot be downloaded now. ")
                        .append("An already-installed runtime is used offline without any ")
                        .append("network access.\n");
            }
        }
        message.append("Set OQ_JAVA to a Java ").append(FLOOR)
                .append("+ executable, or put one on PATH.");
        return message.toString();
    }
}
