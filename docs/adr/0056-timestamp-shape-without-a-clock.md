# ADR-0056 — A timestamp's shape does not depend on its value

**Status:** Accepted
**Date:** 2026-11-13
**Related:** ADR-0036 (time zones and the filesystem), the language reference "Time" section

## Context

ADR-0036 gives time-zone output an explicit numeric offset precisely so that text carrying a
timestamp round-trips through every format the project already produces. It rejected a separate
"zoned instant" type on that argument: *"text with an offset round-trips through every format this
project already produces."* Auditing whether that still held found that it did not.

`java.time.OffsetDateTime.toString()` omits the seconds field when the second is zero. So
`tz_convert` printed two different shapes depending on its input's value:

```
$ print tz_convert("2026-11-10T14:04:00Z", "Europe/Lisbon")
2026-11-10T14:04Z            # instant_parse() rejects this
$ print tz_convert("2026-11-10T14:04:24Z", "Europe/Lisbon")
2026-11-10T14:04:24Z
```

The project's own output did not survive the project's own parser:

```
$ s = tz_convert("2026-11-10T14:04:00Z", "Europe/Lisbon")
$ print instant_parse(s)
error: instant_parse() could not read "2026-11-10T14:04Z"
```

The same API produced two further defects. `LocalDateTime.toString()` has the same omission, so
`datetime()` printed `2026-11-10T15:04` once in sixty runs and `2026-11-10T15:04:24` the rest — a
shape that varied with the wall clock. And the JavaBridge constructor path special-cased
`LocalDateTime` with `args.size() >= 5`, passing only five fields to `LocalDateTime.of`, so
`LocalDateTime(2026, 11, 10, 15, 4, 24)` silently discarded the `24` and produced `15:04:00` with
no error.

None of these was caught, because every existing `tz_convert` test used `:24` seconds — the one
value at which the defective and correct behaviour agree.

### Why `toString()` fails and the formatters do not

This is not a `java.time` bug; it is the documented contract of the value types' `toString()`,
which prefers the shortest text that parses back to the same value. It is only correct when the
consumer is `parse` on the same type. Here the consumer is `instant_parse`, which requires
`Z` or an offset *and* the full time, so the shortened form is not accepted.

Measured, not inferred, on JDK 21 and JDK 25:

| Call | Zero-second input | Non-zero input |
|---|---|---|
| `OffsetDateTime.toString()` | `2026-11-10T14:04Z` | `2026-11-10T14:04:24Z` |
| `DateTimeFormatter.ISO_OFFSET_DATE_TIME` | `2026-11-10T14:04:00Z` | `2026-11-10T14:04:24Z` |
| `LocalDateTime.toString()` | `2026-11-10T15:04` | `2026-11-10T15:04:24` |
| `DateTimeFormatter.ISO_LOCAL_DATE_TIME` | `2026-11-10T15:04:00` | `2026-11-10T15:04:24` |

The formatters are stable across all four combinations and across a sub-second sweep
(`.000000001`, `.500`, `.123456789`), each of which reparses to the original instant.

## Decision

Every date-time value that becomes language output is rendered by a named `DateTimeFormatter`,
never by `toString()`.

1. `tz_convert` uses `DateTimeFormatter.ISO_OFFSET_DATE_TIME`.
2. `datetime()` uses `DateTimeFormatter.ISO_LOCAL_DATE_TIME` through `Stdlib.formatLocalDateTime`,
   which also drops nanoseconds so the function reports whole seconds.
3. The JavaBridge `LocalDateTime` special case handles arities 5, 6 and 7 explicitly and rejects
   anything else, so an argument is never discarded.

`now()` and `today()` were already correct: `Instant.toString()` always emits seconds, and
`LocalDate.toString()` has no seconds field to omit.

## Consequences

1. `tz_convert` output reparses through `instant_parse` for every input, restoring the round-trip
   ADR-0036 relies on.
2. `datetime()` has one shape, independent of when it runs.
3. `LocalDateTime(...)` with six or seven arguments keeps the seconds and nanoseconds it was given.
4. Six regression tests cover this. Five were verified to fail against the previous code:
   `aZeroSecondInstantKeepsItsSecondsField`,
   `everyTzConvertResultIsAcceptedByInstantParse`, `aConvertedInstantKeepsSubSecondPrecision`,
   `datetimeAlwaysHasTheSameShape` and
   `everyLocalDateTimeArityKeepsTheSecondsItWasGiven`. The sixth,
   `aLocalDateStillTakesExactlyThreeArguments`, is a boundary guard rather than a failing-first
   test: it pins the neighbouring special case that the `LocalDateTime` repair could have broken.

## Alternatives considered

- **Accept the shorter form and teach `instant_parse` to read it.** Rejected: `Instant.parse`
  requires seconds, and widening the parser to accept a truncated instant makes two spellings of
  every zero-second timestamp valid, so a byte-comparison or a digest over a timestamp stops being
  meaningful.
- **Render with `Instant.toString()` after conversion.** Rejected: it discards the offset, which is
  the entire point of `tz_convert`.
- **Leave `datetime()` alone because local time is already documented as machine-dependent.**
  Rejected: machine-dependence is documented and deliberate; value-dependence is a defect. The two
  are different, and the reference says the local functions produce one shape.
- **Reject the sixth argument with an error instead of accepting it.** Rejected as the only change:
  the arity is valid Java, so refusing it would turn a silent wrong answer into a spurious
  limitation rather than fixing the dispatch.
