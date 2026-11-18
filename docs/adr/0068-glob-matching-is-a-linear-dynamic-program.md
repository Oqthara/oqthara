# ADR-0068 — Glob matching is a linear dynamic program, not a regular expression

**Status:** Accepted
**Date:** 2026-11-18
**Related:** [ADR-0043](0043-word-wrapping-and-text-diffs.md) for the same "replace a borrowed engine with one
we can reason about" decision, `Stdlib.patterns`, `Patterns.Glob`, `GlobTest`, and R-018 in
[`docs/governance/alpha-4-risk-register.md`](../governance/alpha-4-risk-register.md)

## Context

`fnmatch` and `glob` translate a user-supplied pattern into a `java.util.regex.Pattern` and ask the
regex engine whether a name matches. The translation quotes every literal, so the *pattern* cannot
inject regex syntax — but the shape of the expression it produces is still chosen by the user.
Each `*` becomes its own `[^/]*` quantifier, and adjacent quantifiers over the same characters give
the backtracking engine exponentially many ways to fail.

A7.8 added a fuzz target for the pattern builtins. Jazzer's built-in `RegexInjection` sanitizer
reported the finding, and a direct measurement confirmed it as a true positive rather than a
sanitizer heuristic:

```
pattern "*a" repeated 12 times, then "*b"   vs  name "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  before:  1564 ms, and doubling for every two stars added
  after:      4 ms, and flat as the pattern grows (0.10 / 0.08 / 0.08 / 0.15 ms at
            100 / 200 / 400 / 800 stars)
```

A 26-character pattern taking a second and a half is a denial of service a user of the CLI can aim
at themselves or at anything that runs an untrusted pattern. Nothing in the dialect needs
backtracking to be described: a glob is a regular language with a direct, obvious automaton.

## Decision

**`Patterns.Glob` parses a pattern into a list of tokens and matches with a dynamic program.** The
regex engine is no longer involved.

1. `parse` turns the pattern into `Token`s — `LITERAL`, `ANY_ONE`, `CLASS`, `STAR`, `GLOBSTAR`
   (for `**/`, which consumes whole segments) and `TRAILING_GLOBSTAR` (for a final `**`, which
   absorbs the rest). Every validation rule that the translator enforced moves here unchanged:
   an empty pattern, an unclosed `[`, an empty class, and `a**b` are all still user errors with
   the same messages.
2. `matchesText` keeps a `boolean[]` of "the tokens so far account for exactly the first *j* code
   points". Each token advances that row once, so the cost is *tokens × name length* with no
   alternatives to explore. A pattern can no longer make the matcher do super-linear work.
3. A character class is stored as alternating low/high code-point bounds, one pair per member, so
   a range keeps its endpoints. The parser rejects a reversed range (`[z-a]`) itself instead of
   relying on the regex engine to object. **A first draft expanded every range into its members
   instead, and that was a defect in this very ADR:** a class covering most of Unicode became
   1,114,111 ints, so a 360-character pattern exhausted a 256 MB heap before a single name was
   looked at. Keeping endpoints makes a pattern's cost proportional to the pattern's own length.
   The review that caught this is named under Evidence; `aClassSpanningMostOfUnicodeCostsOnlyItsOwnLength`
   pins it, and the same mutation that re-expands ranges now fails by name rather than by killing
   the test JVM.
4. Matching is done on `int[]` code points, obtained with `String.codePoints()`. This is the
   structural fix for the separate astral defect the same fuzz target found: the old translator
   walked `char` and quoted each surrogate half of an emoji separately, so `fnmatch("😀", "😀")`
   answered `false`. Code-point matching makes splitting an astral character impossible.

## Consequences

**Positive.** The ReDoS class is gone rather than mitigated: there is no backtracking to starve.
The astral defect cannot return. Matching a name no longer allocates a `Matcher` per call, and the
dialect's semantics stop depending on a library's quantifier behaviour — which is what made the
`**/` zero-directory case behave differently from every other glob implementation in the first
place.

**Negative.** The matcher is now code we own and must test ourselves. The old version delegated
character-class semantics to the JDK; the new one interprets `!`, `^`, `&` and `-` by hand.
`GlobTest` is the oracle: all 29 pre-existing cases pass unchanged, and four deliberate mutations
— modelling `**/` as "anything at any depth", letting `*` cross a separator, splitting astral
characters, and expanding class ranges back into their members — are each caught by a named test.

**Negative.** The linear claim is about the *name*, not the pattern's memory: a pattern's parse
cost is bounded by its own length, which is what the endpoint representation buys. A pattern
longer than the name it is matched against still costs a row per token. That is honest linearity
in both, but the two are not the same bound and the first draft conflated them.

**Neutral.** `matchesText` remains the entry point, so `glob`'s directory walk and the fuzz target
are untouched.

## Alternatives considered

- **Suppress the sanitizer.** It can be done — Jazzer reads `jazzer.disabled_hooks` through
  `JAVA_TOOL_OPTIONS`, which does reach the forked test JVM where a `-D` on the Gradle command line
  does not. Rejected: the finding was already measured as real, so suppressing it would hide a
  defect report behind a green build, which is exactly the failure mode R-024 and A7.7 describe.
- **Rewrite each `*` as an atomic group or a possessive quantifier.** This removes the ambiguity but
  keeps the regex, and the quote-the-literals translator is what made the expression's shape an
  accident rather than a design. It also leaves the astral defect to be fixed separately.
- **Replace the translator but keep `Pattern` with a `find()`-driven loop.** The `**/` zero-segment
  case and the "`*` must not cross `/`" rule already needed hand-written handling; once that logic
  exists, the regex is a second implementation of the same rules.

## Evidence

- `oq-stdlib/src/test/java/dev/oqthara/stdlib/GlobTest.java` — 32 tests, including
  `aPatternFullOfStarsIsMatchedInLinearTime`,
  `aGlobstarConsumesWholeSegmentsSoItCannotSwallowAHalfName`, and
  `aClassSpanningMostOfUnicodeCostsOnlyItsOwnLength`. The count was 31 for one commit because
  inserting a case above `aPatternFullOfStarsIsMatchedInLinearTime` swallowed that method's
  `@Test`, leaving the ReDoS regression case in the file but never run; it was restored and then
  sabotage-checked, and it fails when the pattern is put back to its pre-fix shape.
- The heap defect was measured directly: 60 classes spanning Unicode, a 360-character pattern,
  `-Xmx256m` → `java.lang.OutOfMemoryError: Java heap space` before the fix, and `2 ms` after.
  The same probe at 48,000 pattern characters then built and matched in `1 ms`.
- `oq-stdlib/src/fuzz/java/dev/oqthara/stdlib/StdlibFormatsFuzzTest.java` —
  `globPatternsNeverThrowUnexpectedExceptions`, plus `globClassesMatchTheirOwnMembers`, which was
  added once the heap defect showed the free-form targets could not reach a wide class:
  `63,011 runs in 151 s` passed, and the expanding mutation fails it in `58 runs`. Its first
  version built one class and passed against the defect, which is recorded here because a target
  that cannot fail is not evidence.
- `JAZZER_FUZZ=1 JAZZER_MAX_DURATION=180s ./gradlew --no-daemon :oq-stdlib:fuzzGlobPatterns`
  → `2,557,015 runs in 181 second(s)`, passed, with the `RegexInjection` sanitizer **enabled**.
