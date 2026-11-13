# ADR-0055 — Output is byte-identical in every host locale

**Status:** Accepted
**Date:** 2026-11-13
**Related:** ADR-0024 (text is code points), the standard-library inventory Chapter 19,
the requirement matrix §17

## Context

The standard-library inventory records Chapter 19 (internationalization) as untouched, and its
acceptance criterion for any future work there reads: *"Output independent of host locale, proven
by running the suite under at least one non-English default."* Auditing whether that criterion
already held for the output the project ships found that it did not.

Three call sites used a locale-sensitive API without naming a locale, so their output depended on
the machine's default:

| Site | Call | Under `ar_EG` or `tr_TR` |
|---|---|---|
| `Diagnostics` | `String.format("%4d", line)` | `ar_EG` writes Arabic-Indic digits, changing both the numeral system and the width, which breaks caret alignment |
| `Ir` | `String.format("  %04d  %s%n", ...)` | same digit substitution in the IR listing |
| `Main` (runtime resolver JSON) | `origin().name().toLowerCase()` | `tr_TR` writes `overrıde` — dotless i — so the emitted `origin` value matches nothing a consumer expects |

None of these are exotic. `ar_EG` and `tr_TR` are ordinary locales, and the failures are silent:
the program exits 0 and prints something subtly wrong. A JSON field whose value is
`overrıde` is the worst kind of defect, because a consumer comparing against `"override"` reports
a missing value rather than a malformed one.

The audit also confirmed what was already right: `Values.formatDouble` names `Locale.ROOT`, and
`upper`/`lower` in the stdlib do too. The correct pattern was established; it simply was not
applied everywhere.

## Decision

**Every string the project produces is byte-identical regardless of `Locale.getDefault()`.**
No API whose output can reach a user, a file, or another process may consult the default locale.
Where a locale is needed, `Locale.ROOT` is named explicitly at the call site.

Four consequences follow.

1. **`format` and case conversion name their locale.** `toLowerCase()`/`toUpperCase()` with no
   argument are always defects, because a locale-sensitive mapping exists for every letter the
   default locale differs on. `String.format` without a leading locale argument is a defect only
   when it can emit a *digit* or a *decimal separator* — the `%d`, `%f`, `%e`, `%g` and `%a`
   conversions. The `%s`, `%c`, `%x`, `%X`, `%o` and `%%` conversions emit ASCII regardless of
   default locale, which was verified rather than assumed: under `ar-EG`, `%04X` of 160 is
   `00A0` while `%d` of 1234 is `١٢٣٤`. The `Json` module's `\u%04X` escapes are therefore correct
   as they stand and are not the same defect. The fix is mechanical but must not be mechanical
   *only*: the reason is recorded here so the next author does not reintroduce it, and so a later
   audit does not "fix" the hex sites or mistake them for unfixed ones.

2. **The rule is enforced by test, not by review.** Each of the three fixed sites now has a
   regression test that holds `Locale.getDefault()` at `tr-TR`, `ar-EG`, `de-DE`,
   `hi-IN-u-nu-deva` and the root locale and asserts byte-identical output:
   `DiagnosticsLocaleTest` for the diagnostic gutter, `DisassemblyLocaleTest` for the IR listing,
   and `theJsonReportIsByteIdenticalInEveryLocale` in `JavaCommandTest` for the resolver JSON.
   Each was checked against the pre-fix code and fails there — the diagnostic test on two of three
   assertions, the other two on the byte-identity assertion, with `tr_TR` producing
   `overrıde` and `project_pın` and `ar_EG` producing Arabic-Indic instruction indices. A test that
   passes without the fix is not a guard, so this was verified rather than assumed.

   The resolver test constructs its `Resolution` directly instead of invoking `oq java --json`.
   The command reads the ambient environment, so on a machine with no `OQ_JAVA` it resolves
   `path` — an origin with no `i` in it, which no case rule can mangle. A test written that way
   passes against the broken code and is therefore worthless; it was written, found vacuous, and
   replaced. `jsonRuntime`'s visibility was widened to package-private so the assertion has a
   target that always exercises the defect.

   `formatDouble`'s assertion passes before and after — it is there to stop a regression, not to
   demonstrate the fix.

3. **This is a prerequisite for Chapter 19, not a substitute for it.** The inventory's ranked
   list keeps message catalogues (`gettext`) and locale-aware collation (`locale`) absent. This
   ADR delivers the *invariant* those features would have to preserve, not the features. When
   formatting eventually takes an explicit locale, "explicit or root" remains the rule; the host
   default stays unconsulted.

4. **`Locale.ROOT` is chosen over `Locale.ENGLISH` deliberately.** English and root disagree on
   nothing this project formats today, but root is the locale with no region and no language, so
   it cannot acquire a rule that English later gains. Naming English would invite the question of
   which English.

## Alternatives considered

**Set `Locale.setDefault(Locale.ROOT)` at startup.** Rejected as the primary mechanism: it makes
the program's output correct only for code paths that run after the call, depends on initialisation
order, and silently fails in a library — which is what `oq-core` and `oq-runtime` are. A library
that mutates global locale state is also hostile to any host process embedding it. Naming the
locale at each call site is local, checkable, and has no ordering requirement.

**Accept locale-sensitive output as correct behaviour.** Rejected. The compiler's diagnostic gutter
is not a localised string; it is layout that the caret position is computed from. Arabic-Indic
digits in a gutter would need width-aware alignment to stay correct, which is a much larger change
than naming a locale, and would still leave JSON keys locale-dependent — which is indefensible in a
machine-readable field.

**Detect the defect with a static check instead of tests.** Attractive, and worth adding later, but
a grep for `String.format(` without `Locale` cannot distinguish the diagnosis from a false positive
without inspecting the conversion characters, and the exemptions above make the check
conversion-aware rather than textual. The test is a stronger guarantee today: it asserts the
property that matters (identical output), not a proxy for it.

## Consequences

Output is now reproducible across machines, which matters for the reasons in ADR-0022 and for CI:
a test asserting on a rendered diagnostic can no longer pass on the runner and fail on a
contributor's laptop because of a default locale the test never set.

The remaining locale work is unchanged and still absent: no message catalogues, no plural rules, no
locale-aware collation, no currency formatting. Chapter 19 stays absent, and the inventory's
per-domain row does not change. What changes is that the invariant those features must preserve now
holds and is enforced, so the chapter's acceptance criterion is no longer aspirational.
