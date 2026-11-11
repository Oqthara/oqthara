# ADR-0043 — Word wrapping and text diffs

**Status:** Accepted
**Date:** 2026-11-11
**Supersedes:** nothing
**Related:** ADR-0041 (statistics, identifiers, ordered search), ADR-0042 (whole-value compression)

## Context

The stdlib inventory ranks text processing as the most-used chapter of the Python reference and
records six of its nine modules as absent or partial. Two of those absences are pure functions
over values, which is the shape this language can accept without a lifetime construct:

- **Word wrapping to a column.** Every command-line tool wraps output, including this
  repository's own release tooling. A program that wants a table or a paragraph has to write
  the greedy loop itself, and the loop has an edge case (a word longer than the width) that is
  easy to get wrong.
- **A diff between two sequences.** Test failures and generated-file checks are the two places
  the project itself needs one; `check-docs-sync.py` and `gen-docs-catalog.py` both compare
  generated text against a committed copy and both report only that a difference exists.

Absence, measured against the built CLI before this ADR was written. Every name below is
rejected by the interpreter, not merely undocumented:

```
oq run a.oq     # print(wrap_text("a", 2))
error: 'wrap_text' is not defined
oq run b.oq     # print(dedent_text("  a"))
error: 'dedent_text' is not defined
oq run c.oq     # print(unified_diff(["a"], ["b"], "old", "new"))
error: 'unified_diff' is not defined
```

The same probe was run for `fill_text`, `indent_text`, `shorten_text`, `text_wrap`,
`diff_ratio` and `close_matches`. All nine are absent.

## Decision

**D1 — Six functions, and the names say what they do.** `wrap_text` returns a list of lines;
`fill_text` returns the same lines joined by `\n`; `dedent_text`, `indent_text` and
`shorten_text` are the remaining whole-value transforms; `unified_diff` returns a list of
lines in unified format. A caller who has `wrap_text` never needs a separate `text_wrap`.

**D2 — `wrap_text(text, width)` is greedy, and breaks nothing but at whitespace.** Words are
maximal runs of non-whitespace. A word longer than the width is broken at exactly the width
(`wrap_text("abcdefghij", 4)` is `["abcd", "efgh", "ij"]`). Runs of whitespace between words
collapse to a single space when two words share a line, and vanish at a line break — so
`wrap_text("a  b   c", 3)` is `["a", "b", "c"]` rather than any padding. Leading whitespace is
kept because a caller wrapping an already-indented string means the indent: `wrap_text("  lead",
30)` is `["  lead"]`.

**D3 — Hyphens are not break opportunities.** This is the one place the behaviour deliberately
differs from Python, whose `break_on_hyphens` defaults to on and would split `co-operate` at the
hyphen into `co-` / `operate`. Oqthara treats a hyphen as an ordinary character inside a word, so
`wrap_text("co-operate and re-enter", 6)` is `["co-ope", "rate", "and", "re-", "enter"]` — the
hyphen is only a break when the word is too long for the line and has to be split at the width
anyway. The reason to choose the simpler rule is that the hyphenated rule is not derivable from
the width: it needs an opinion about which hyphens are syllable boundaries, and neither the
language nor this ADR has one. The divergence is documented rather than silent, and
`TextWrapTest` pins the exact behaviour so a future change to it is a deliberate act.

**D4 — `width` must be at least 1.** `wrap_text("abc", 0)` raises
`wrap_text() needs a width of at least 1, got 0` rather than looping forever or returning
nothing. Python raises `ValueError` here for the same reason: a zero-width greedy loop makes no
progress.

**D5 — `unified_diff` takes two lists of texts and returns a list of lines.** The signature is
`unified_diff(before, after)` with optional third and fourth arguments naming the sides, giving
`--- <before-label>`, `+++ <after-label>` and one `@@ -a,b +c,d @@` hunk header per run of
changes with three lines of context. Two identical inputs return an empty list, which is the
only sane answer for "what changed" and is what makes the result usable in a condition.
`diff_context` is not exposed as a parameter: three is what every diff tool shows and a
parameter nobody sets is a knob that only adds a way to get it wrong.

`unified_diff` computes a longest common subsequence over lines. Python's `difflib` uses
`SequenceMatcher`, which prefers *the earliest* of several equally long matches; an LCS has no
such preference, so when the input contains repeated lines the two can choose different
alignments. `unified_diff(["1","1","2","2","3","3"], ["1","2","3"])` anchors the first `1`
here and Python anchors the second. Both outputs are correct: a sixty-case random check over a
fixed seed, `TextWrapTest.everyDiffReproducesTheAfterSideFromTheBeforeSide`, applies every diff
the way a patch tool does -- splicing each hunk at the offset its header names -- and confirms
the result is `after` exactly; a second check,
`aHunkHeaderAlwaysAgreesWithTheLinesUnderIt`, confirms each header's two counts match the lines
beneath it, which is what a patch tool relies on to find the next hunk. The difference is which
of two equally minimal edit scripts is written down, and that is worth stating because a reader
who diffs the same pair with both tools will see it.

**D6 — `dedent_text` and `indent_text` follow the common-prefix rule.** `dedent_text` removes
the longest whitespace prefix shared by every non-blank line, leaving blank lines alone, so
`dedent_text("  a\n    b")` is `"a\n  b"`. Whitespace-only input becomes `""`. `indent_text(text,
prefix)` prefixes every non-blank line and leaves blank lines empty — prefixing a blank line
produces trailing whitespace, which is the kind of invisible difference a diff then reports.

**D7 — `shorten_text(text, width)` collapses and truncates with a visible marker.** It
collapses whitespace, then if the result exceeds the width, cuts at a word boundary and appends
` [...]`. If the width is smaller than the marker itself, it raises rather than returning a
string longer than the limit it was asked to respect. The marker is four visible characters
rather than Python's three-character default because an ellipsis that can be confused with the
text is worse than one that cannot.

**D8 — No `textwrap` options object, no `difflib.SequenceMatcher`.** `initial_indent`,
`subsequent_indent`, `expand_tabs`, `replace_whitespace`, `drop_whitespace`, `max_lines` and
`placeholder` are all parameters of one function in Python. Exposing all seven would make
`wrap_text` a configuration surface with 128 behaviours, and the callers who need them can
compose the four functions here. `SequenceMatcher`'s opcode API returns a nested structure of
tag, i1, i2, j1, j2 that this language has no record type to carry; `unified_diff` returns
lines, which every consumer can print.

## Consequences

**What this closes.** Two of the six modules the inventory records as absent in Chapter 1 move
to covered: `textwrap` and `difflib`. This is the smallest useful slice of a chapter the
inventory calls the most-used one, and it is delivered with no dependency, no handle and no
new construct.

**What it does not close.** `string.templatelib`, `unicodedata`, character-class constants and
locale-independent case folding remain absent, and this ADR does not propose them. `difflib`'s
`get_close_matches` and `SequenceMatcher.ratio` are not delivered: both need a similarity
threshold and a scoring rule, and "how similar" is a decision this ADR declines to make on the
caller's behalf.

**The cost of the hyphen divergence.** A program ported from Python that relies on hyphen
breaking will produce different lines. This is a real incompatibility and the reason D3 is
written as a decision with a test rather than as an implementation detail. The alternative —
implementing Python's rule — would have required copying a heuristic whose documentation says
it is not a complete solution, and a rule that is wrong in some cases and documented as such is
worse than a rule that is simple and exact.

**Where the risk is.** Wrapping is arithmetic on a string, so there is no amplification or
traversal surface. The one resource consideration is that `wrap_text` builds a list whose size
is bounded by the input length: every output line is at least one character of input, so a
100 MB input produces at most a 100 MB list of lines. That is the same order as the input, not
an amplification, and it needs no cap.

**Verification.** Every example in this ADR was run against the built CLI before being written
down, and the 35 cases in `TextWrapTest` pin the behaviours the decisions claim: the wrap width
for every case from one character upward, the break-long-words rule, `fill_text`'s newline join,
`dedent_text` and `indent_text` on mixed and absent indentation, `shorten`'s marker, each diff
operation against a known pair, the header counts, and the two seeded property checks named in
D5.
