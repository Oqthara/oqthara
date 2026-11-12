package dev.oqthara.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Platform enumeration (ADR-0048 D2). The table itself is small; the normalisation is what these
 * tests exist for, because a silent miss there produces the silent fallback the ADR forbids.
 */
class PlatformSupportTest {

    @Test
    void theNamedPlatformsAreSupported() {
        assertTrue(PlatformSupport.detect("Linux", "amd64").orElseThrow().supported());
        assertTrue(PlatformSupport.detect("Linux", "aarch64").orElseThrow().supported());
        assertTrue(PlatformSupport.detect("Mac OS X", "aarch64").orElseThrow().supported());
        assertTrue(PlatformSupport.detect("Mac OS X", "x86_64").orElseThrow().supported());
        assertTrue(PlatformSupport.detect("Windows 11", "amd64").orElseThrow().supported());
    }

    @Test
    void everyVendorSpellingOfTheTwoArchitecturesIsRecognised() {
        // The same processor, as reported by different vendors and operating systems. A miss here
        // would make support depend on which JDK the user happens to run.
        for (String x86 : new String[] {"x86_64", "amd64", "AMD64", "x64", "x86-64"}) {
            assertEquals("x86_64", PlatformSupport.normaliseArch(x86).orElseThrow(), x86);
        }
        for (String arm : new String[] {"aarch64", "arm64", "AARCH64"}) {
            assertEquals("aarch64", PlatformSupport.normaliseArch(arm).orElseThrow(), arm);
        }
    }

    @Test
    void aPlatformOutsideTheListIsUnsupportedRatherThanGuessed() {
        assertTrue(PlatformSupport.normaliseOs("FreeBSD").isEmpty());
        assertTrue(PlatformSupport.detect("FreeBSD", "x86_64").isEmpty());
        // Windows on ARM is outside the list: the ADR names Windows x86_64 only.
        assertFalse(PlatformSupport.detect("Windows 11", "aarch64").orElseThrow().supported());
    }

    @Test
    void anUnrecognisedValueYieldsNothingRatherThanAWrongName() {
        assertTrue(PlatformSupport.detect(null, "amd64").isEmpty());
        assertTrue(PlatformSupport.detect("Linux", "sparc").isEmpty());
        assertTrue(PlatformSupport.detect("Plan 9", "amd64").isEmpty());
    }

    @Test
    void theDiagnosticNamesWhatWasDetectedAndWhatToDo() {
        String message = PlatformSupport.explainUnsupported("Windows 11", "aarch64");
        assertTrue(message.contains("windows/aarch64"), message);
        assertTrue(message.contains("linux/x86_64"), message);
        assertTrue(message.contains("OQ_JAVA"), message);
        assertTrue(message.contains("Java 21"), message);
    }

    @Test
    void theDiagnosticStillReportsTheRawValuesWhenTheyAreUnrecognised() {
        String message = PlatformSupport.explainUnsupported("Plan 9", "sparc");
        assertTrue(message.contains("Plan 9"), message);
        assertTrue(message.contains("sparc"), message);
        assertTrue(message.contains("OQ_JAVA"), message);
    }
}
