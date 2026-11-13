# ADR-0057 — Rendered text pastes back as the text it rendered

**Status:** Accepted
**Date:** 2026-11-14
**Related:** ADR-0024 (text is code points), ADR-0054 (HTML escaping), the language specification
"Working with values" section

## Context

`docs/spec/language.md` states that `repr` renders a value "so the line can be pasted back as
source", and `OqData.rebuildHint` repeats the claim in its javadoc. Both were false.

An audit of every rendering path — CSV, JSON, `decimal` and `number`, fractions, hex and base64,
and `repr` itself — found exactly one that broke its own documented guarantee. `Values.quote`
escaped five characters (`"`, `\`, `\n`, `\t`, `\r`) and emitted everything else verbatim. That
left two classes of text that did not survive a round trip:

| Text | `repr` before | Pasted back as | Verdict |
|---|---|---|---|
| `{x}` with `x = 99` | `"{x}"` | `99` | worse than a parse error: it silently became a different value |
| a NUL byte | a raw NUL inside the quotes | a NUL | *correct* — the parser keeps it |

The brace case is the real defect, and it is the dangerous kind. Unlike an unparseable rendering,
which fails loudly, `{x}` pasted back is syntactically valid and evaluates to something else. A
`rebuildHint` line is meant to be editable, so the failure would arrive as a wrong value rather
than a bad paste.

The NUL case was investigated and deliberately **not** changed. The lexer preserves raw control
characters — verified by writing a source file holding a literal NUL between quotes and reading
its length back as one — so escaping them is unnecessary. It is also impossible in the obvious
form: the parser has escape rules for `n t r \ " ' { } 0` only, and an unrecognised `\X`
expands to *two* characters, backslash plus `X`. Escaping an arbitrary control character as
`\` + the character would therefore corrupt it. A first attempt at this fix did exactly that and
was caught by a test that compared against the parser rather than against the escaper.

## Decision

**`Values.quote` escapes exactly the characters that would change meaning when pasted back:**
`"` and `\` (which would end or continue the literal), the three whitespace escapes the spec
already names, NUL, and an opening brace. Control characters stay raw, because the lexer keeps
them and no spelling exists for them.

Braces follow the parser's pairing rule rather than a blanket rule. The parser reads `{{` as a
literal `{`, and reads `}}` as a literal `}` **only while an unmatched `{{` is still owed a
partner**. So `quote` doubles an opening brace, and doubles a closing brace only when it closes one
that was doubled. A lone `}` is already literal and is emitted as-is; doubling it would add a
character instead of removing one. This asymmetry is not obvious and is the reason for the loop
counter in `quote`: the two functions must agree, and the test asserts the agreement by parsing.

## Alternatives considered

**Escape every control character as `\uXXXX`.** Rejected: the parser has no such escape, so the
rendering would be unparseable rather than round-tripping. `Json.writeString` can use `\uXXXX`
because JSON has that escape and Oqthara does not.

**Escape every control character as `\` + the character.** Rejected, and this was attempted first.
The parser's `default` branch appends both the backslash and the character, so the text gains a
byte. The round-trip test failed immediately, which is what it is for.

**Double both braces unconditionally.** Rejected: it renders `a} b` as `"a}} b"`, which pastes
back as four characters — a stray `}` gained, because no `{{` preceded the `}}` for the parser to
pair it with. Symmetry is visually appealing and wrong.

**Fix only `{` and leave `}` alone always.** Rejected for the opposite reason: `{{x}}` rendered as
`"{{{{x}}"`, which pastes back as `{{x}}` missing its last character, because the parser consumed
the first `}}` as the partner of the first doubled brace. The counter exists to prevent exactly
this.

## Consequences

`repr` now satisfies the guarantee the specification already made. The change is additive to
output only for text containing braces or NUL; every other value renders byte-identically, so no
existing program changes behaviour.

The guard is `ReprRoundTripTest` in `oq-core`, which parses the rendering and compares the parsed
text to the original rather than comparing `repr` to a string literal. It covers both directions
(the ordinary escapes; braces at the start, middle, end and nested; NUL; text nested inside a
list) and sweeps every ASCII character individually and joined. The test lives in `oq-core`
because it needs the parser, and `oq-core` depends on `oq-runtime`, not the reverse.

It is checked fail-first: with the previous `quote`, the brace cases fail and the ordinary-escape
cases pass. A test that passes without the fix is not a guard.
