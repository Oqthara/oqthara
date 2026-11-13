package dev.oqthara.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@code normalize} widens a Java return value to an Oq value, and it used to widen {@code
 * BigInteger} and {@code BigDecimal} to {@code double} unconditionally. Both classes exist to
 * carry values a double cannot hold, so that widening silently discarded the property the caller
 * asked for: adding {@code 0.000000001} to a thirty-digit amount answered {@code
 * 123456789012345680000000000000.0} and reported it as a {@code decimal}. These tests pin the
 * repair, and the boundary that stays a {@code number} (ADR-0045).
 */
class JavaBridgeExactnessTest {
    @Test
    void aBigDecimalWiderThanADoubleIsNotNarrowed() {
        assertInstanceOf(BigDecimal.class, JavaBridge.normalize(new BigDecimal("0.1")));
    }

    @Test
    void aBigDecimalKeepsItsPrecisionThroughTheBridge() {
        BigDecimal sum = new BigDecimal("123456789012345678901234567890.123456789")
                .add(new BigDecimal("0.000000001"));
        Object normalized = JavaBridge.normalize(sum);
        assertEquals(new BigDecimal("123456789012345678901234567890.123456790"),
                normalized);
        assertEquals("123456789012345678901234567890.123456790", normalized.toString());
    }

    @Test
    void aBigIntegerThatFitsALongStaysANumber() {
        assertEquals(42L, JavaBridge.normalize(BigInteger.valueOf(42L)));
    }

    @Test
    void everyLocalDateTimeArityKeepsTheSecondsItWasGiven() {
        // The bridge special-cased `args.size() >= 5` and passed only five fields to
        // LocalDateTime.of, so a sixth argument was discarded without an error:
        // LocalDateTime(2026, 11, 10, 15, 4, 24) produced 15:04:00 (ADR-0056).
        java.time.LocalDateTime five = (java.time.LocalDateTime) JavaBridge.construct(
                java.time.LocalDateTime.class,
                List.of(2026L, 11L, 10L, 15L, 4L), dev.oqthara.runtime.Span.synthetic(), null);
        assertEquals(java.time.LocalDateTime.of(2026, 11, 10, 15, 4, 0), five);

        java.time.LocalDateTime six = (java.time.LocalDateTime) JavaBridge.construct(
                java.time.LocalDateTime.class,
                List.of(2026L, 11L, 10L, 15L, 4L, 24L), dev.oqthara.runtime.Span.synthetic(), null);
        assertEquals(java.time.LocalDateTime.of(2026, 11, 10, 15, 4, 24), six);
        assertEquals(24, six.getSecond());

        java.time.LocalDateTime seven = (java.time.LocalDateTime) JavaBridge.construct(
                java.time.LocalDateTime.class,
                List.of(2026L, 11L, 10L, 15L, 4L, 24L, 123_456_789L),
                dev.oqthara.runtime.Span.synthetic(), null);
        assertEquals(java.time.LocalDateTime.of(2026, 11, 10, 15, 4, 24, 123_456_789), seven);
    }

    @Test
    void aLocalDateStillTakesExactlyThreeArguments() {
        assertEquals(java.time.LocalDate.of(2026, 11, 10), JavaBridge.construct(
                java.time.LocalDate.class, List.of(2026L, 11L, 10L),
                dev.oqthara.runtime.Span.synthetic(), null));
    }

    @Test
    void aBigIntegerWiderThanALongIsNotNarrowed() {
        BigInteger wide = new BigInteger("123456789012345678901234567890");
        Object normalized = JavaBridge.normalize(wide);
        assertEquals(wide, normalized);
        assertEquals("123456789012345678901234567891",
                ((BigInteger) normalized).add(BigInteger.ONE).toString());
    }
}
