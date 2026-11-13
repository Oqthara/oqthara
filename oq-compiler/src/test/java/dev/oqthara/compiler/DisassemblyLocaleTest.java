package dev.oqthara.compiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The disassembly listing numbers each instruction with {@code String.format("%04d", index)}.
 *
 * <p>Without an explicit locale that call follows the host default, so under {@code ar_EG} the
 * third instruction is listed as {@code ٠٠٠٢} rather than {@code 0002} — a different numeral
 * system, in a column that is meant to be machine-parsable and is compared verbatim by tests and
 * by anyone reading a bug report. This holds the default locale hostage and asserts the listing
 * is byte-identical in every one of them.
 */
class DisassemblyLocaleTest {

    private Locale original;

    @BeforeEach
    void rememberDefaultLocale() {
        original = Locale.getDefault();
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(original);
    }

    private static final Locale[] HOSTILE = {
        Locale.ROOT,
        Locale.forLanguageTag("tr-TR"),
        Locale.forLanguageTag("ar-EG"),
        Locale.forLanguageTag("de-DE"),
        Locale.forLanguageTag("hi-IN-u-nu-deva"),
    };

    /** Two instructions, so at least one index is formatted rather than the empty-body branch. */
    private static String listing() {
        Ir.Function main = new Ir.Function(
                "main",
                List.of(),
                List.of(new Ir.Const(42L), new Ir.Print(1), new Ir.Load("x")));
        return Ir.disassemble(new Ir.Program(main, List.of()));
    }

    @Test
    void instructionIndicesUseWesternDigitsInEveryLocale() {
        for (Locale locale : HOSTILE) {
            Locale.setDefault(locale);

            String listing = listing();

            assertTrue(
                    listing.contains("  0001  print 1"),
                    () -> "index column is not western-padded under " + locale + ":\n" + listing);
            for (char c : listing.toCharArray()) {
                assertFalse(
                        Character.isDigit(c) && c > '9',
                        () -> "non-ASCII digit in listing under " + locale + ":\n" + listing);
            }
        }
    }

    @Test
    void theListingIsByteIdenticalInEveryLocale() {
        String baseline = null;
        for (Locale locale : HOSTILE) {
            Locale.setDefault(locale);

            String listing = listing();

            if (baseline == null) {
                baseline = listing;
            } else {
                assertEquals(baseline, listing, () -> "listing differs under " + locale);
            }
        }
    }
}
