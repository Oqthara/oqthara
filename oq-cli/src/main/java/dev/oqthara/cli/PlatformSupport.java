package dev.oqthara.cli;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which platforms a managed runtime could be installed on (ADR-0048 D2).
 *
 * <p>The ADR requires the supported list to be enumerated rather than implied, and requires a
 * platform outside it to get a diagnostic naming what was detected rather than a silent fallback
 * to an incompatible JVM. This class is that list and that diagnostic.
 *
 * <p>The normalisation is the substance, not the table. The JVM reports the same architecture as
 * {@code x86_64}, {@code amd64} or {@code x64} depending on the vendor and the operating system,
 * and the same processor as {@code aarch64} or {@code arm64}. Matching raw property values would
 * make support depend on which JDK the user happens to run — the exact bug class this class
 * exists to remove. Every spelling is folded to one canonical name first, and only then compared
 * against the supported set.
 *
 * <p>This says nothing about whether a runtime is *available*; it answers only whether the
 * platform is one the manager is able to serve. Acquisition is ADR-0048 D3 onwards and is not
 * implemented.
 */
final class PlatformSupport {

    /** A detected operating system and architecture, in canonical form. */
    record Platform(String os, String arch) {
        boolean supported() {
            return SUPPORTED.contains(this);
        }

        String label() {
            return os + "/" + arch;
        }
    }

    /** Exactly the platforms ADR-0048 D2 names, and no others. */
    private static final List<Platform> SUPPORTED = List.of(
            new Platform("linux", "x86_64"),
            new Platform("linux", "aarch64"),
            new Platform("macos", "x86_64"),
            new Platform("macos", "aarch64"),
            new Platform("windows", "x86_64"));

    private PlatformSupport() {
    }

    /**
     * Detects the current platform from the two JVM properties that describe it.
     *
     * <p>Takes the values rather than reading them so the mapping is testable on any host; the
     * caller passes {@code System.getProperty("os.name")} and {@code "os.arch"}. A missing or
     * unrecognised value yields {@link Optional#empty()} rather than a guess, because a wrong
     * platform name in a diagnostic is worse than an admitted unknown.
     */
    static Optional<Platform> detect(String osName, String osArch) {
        Optional<String> os = normaliseOs(osName);
        Optional<String> arch = normaliseArch(osArch);
        if (os.isEmpty() || arch.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Platform(os.get(), arch.get()));
    }

    /** Folds the operating-system spellings onto the three the ADR names. */
    static Optional<String> normaliseOs(String osName) {
        if (osName == null || osName.isBlank()) {
            return Optional.empty();
        }
        String folded = osName.toLowerCase(Locale.ROOT);
        if (folded.startsWith("linux")) {
            return Optional.of("linux");
        }
        if (folded.startsWith("mac")) {
            return Optional.of("macos");
        }
        if (folded.startsWith("windows")) {
            return Optional.of("windows");
        }
        return Optional.empty();
    }

    /** Folds the architecture spellings onto the two the ADR names. */
    static Optional<String> normaliseArch(String osArch) {
        if (osArch == null || osArch.isBlank()) {
            return Optional.empty();
        }
        String folded = osArch.toLowerCase(Locale.ROOT);
        return switch (folded) {
            case "x86_64", "amd64", "x64", "x86-64" -> Optional.of("x86_64");
            case "aarch64", "arm64" -> Optional.of("aarch64");
            default -> Optional.empty();
        };
    }

    /**
     * Explains why a managed runtime cannot be installed here.
     *
     * <p>The message names what was detected, because "unsupported platform" alone leaves the
     * user unable to tell a genuinely unsupported machine from a JDK reporting an unusual
     * spelling. It also says what to do, since resolution on PATH still works.
     */
    static String explainUnsupported(String osName, String osArch) {
        Optional<Platform> detected = detect(osName, osArch);
        StringBuilder message = new StringBuilder("a managed Java runtime cannot be installed on ");
        if (detected.isPresent()) {
            message.append(detected.get().label()).append('\n');
        } else {
            message.append("this platform (").append(describeRaw(osName)).append(", ")
                    .append(describeRaw(osArch)).append(")\n");
        }
        message.append("Supported platforms: ");
        for (int i = 0; i < SUPPORTED.size(); i++) {
            if (i > 0) {
                message.append(", ");
            }
            message.append(SUPPORTED.get(i).label());
        }
        message.append(".\n");
        message.append("Oqthara itself still runs here: set OQ_JAVA to a Java ")
                .append(RuntimeResolver.FLOOR)
                .append("+ executable, or put one on PATH.");
        return message.toString();
    }

    private static String describeRaw(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
