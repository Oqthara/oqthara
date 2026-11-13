package dev.oqthara.core;

import dev.oqthara.runtime.OqError;
import dev.oqthara.runtime.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiler output must not depend on the host locale.
 *
 * <p>These are not hypothetical locales. Under {@code ar_EG}, {@code String.format("%4d", 7)}
 * produces Arabic-Indic digits ({@code ٳ}), so a diagnostic gutter renders as a different
 * numeral system and, worse, changes width — which breaks the caret alignment that points at the
 * offending column. Under {@code tr_TR}, {@code "OVERRIDE".toLowerCase()} is {@code overrıde}
 * (dotless i), so the runtime-resolution JSON reports an origin value no consumer can match.
 * Under {@code de_DE}, {@code %.12f} writes {@code 0,333} instead of {@code 0.333}.
 *
 * <p>Every one of these is fixed by naming {@link Locale#ROOT} at the call site. This test holds
 * the default locale hostage for the duration and asserts byte-identical output, so a future
 * locale-sensitive {@code format} or case conversion added to a diagnostic fails here rather than
 * in a user's terminal.
 */
class DiagnosticsLocaleTest {

    private Locale original;

    @BeforeEach
    void rememberDefaultLocale() {
        original = Locale.getDefault();
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(original);
    }

    /** Locales whose numeral systems or case rules differ from the root locale. */
    private static final Locale[] HOSTILE = {
        Locale.ROOT,
        Locale.forLanguageTag("tr-TR"),
        Locale.forLanguageTag("ar-EG"),
        Locale.forLanguageTag("de-DE"),
        Locale.forLanguageTag("hi-IN-u-nu-deva"),
    };

    private static String renderOneError() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        Diagnostics.print(
                err,
                new OqError("unexpected token", Span.at("sample.oq", 7, 3)),
                List.of("line one", "line two", "line three", "line four",
                        "line five", "line six", "let x = "),
                false);
        err.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theGutterUsesWesternDigitsInEveryLocale() {
        for (Locale locale : HOSTILE) {
            Locale.setDefault(locale);

            String rendered = renderOneError();

            // Line 7 must appear as three spaces then "7". Arabic-Indic or Devanagari digits
            // would satisfy contains("7") never, so assert the exact gutter line.
            assertTrue(
                    rendered.contains("   7 | let x = "),
                    () -> "gutter is not western-padded under " + locale + ":\n" + rendered);
            for (char c : rendered.toCharArray()) {
                assertFalse(
                        Character.isDigit(c) && c > '9',
                        () -> "non-ASCII digit in diagnostic under " + locale + ":\n" + rendered);
            }
        }
    }

    @Test
    void theCaretStaysAlignedWithTheColumnInEveryLocale() {
        String baseline = null;
        for (Locale locale : HOSTILE) {
            Locale.setDefault(locale);

            String rendered = renderOneError();

            if (baseline == null) {
                baseline = rendered;
            } else {
                assertEquals(baseline, rendered, () -> "output differs under " + locale);
            }
        }
    }

    @Test
    void aDecimalFromTheRuntimeKeepsADotInEveryLocale() {
        for (Locale locale : HOSTILE) {
            Locale.setDefault(locale);

            String shown = dev.oqthara.runtime.Values.display(1.0 / 3);

            assertEquals("0.333333333333", shown, () -> "decimal separator changed under " + locale);
        }
    }
}
