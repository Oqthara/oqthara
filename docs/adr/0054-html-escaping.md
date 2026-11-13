# ADR-0054 — HTML escaping as a defence, with no parser

**Status:** Accepted
**Date:** 2026-11-13
**Related:** ADR-0036 (no `close`, so no handles), the standard-library inventory §16,
the requirement matrix §17

## Context

Alpha 4 §17 asks for standard-library breadth, and the inventory records Chapter 16
(structured markup) at 0 of 13 modules covered. Within that chapter the inventory draws a line
that this ADR keeps: **escaping is a defence, parsing is an attack surface.**

The distinction is not stylistic. Escaping turns a value into something safe to put in a page,
and its absence is the reason programs hand-roll a replacement chain. Parsing reads a document's
own declarations, which is how XXE and billion-laughs attacks arrive; every XML parser on the JVM
resolves external entities and processes DTDs unless it is explicitly configured not to, and a
hand-written parser re-implements that attack surface rather than avoiding it.

So the gap the inventory describes as most severe is not "no XML" — the absence of XML is the
correct position and the inventory already records it. The gap is that the one-line defence did
not exist, so every program that needed it wrote its own, and a hand-rolled chain is where the
order bug lives.

## Decision

Ship four functions and no parser.

| Function | Contract |
| --- | --- |
| `html_escape(text)` | Replaces exactly `&`, `<`, `>`, `"` and `'`, in that order — `&` first |
| `html_unescape(text)` | Resolves those six named entities plus `&#NNN;` and `&#xHHH;`; refuses unknown names and malformed or non-scalar numerics |
| `html_escape_attribute(text)` | Identical to `html_escape` today; a separate name so the context is visible and a future divergence has a place to land |
| `html_entity(charOrName)` | Answers the entity for a character or a bare name; refuses a character that has none |

Five properties are deliberate and each is pinned by a test.

**Escaping replaces `&` first.** Replacing `<` first would then escape the ampersand of the
`&lt;` just produced, yielding `&amp;lt;`, which renders as the literal text `&lt;`. That bug
looks like working software until someone reads the page, so
`ampersandIsReplacedBeforeTheOthersSoItsOutputIsNotEscapedAgain` asserts exact output for mixed
strings rather than round-tripping, which would pass either way.

**Escaping is not idempotent.** `html_escape(html_escape("&"))` is `&amp;amp;`, not `&amp;`. An
escape that recognised existing entities could not express text containing the literal four
characters `&amp;`, which makes it a smuggling route. Visibility is the correctness property;
a caller that wants the other direction has `html_unescape` and can say so.

**A bare ampersand is prose, and the candidate's shape decides.** `html_unescape("a & b")` is
`a & b`. Refusing it would make the function unusable on any sentence about ampersands. A
well-formed `&name;` that is not in the table *is* refused, because passing it through would
silently produce a different string than the caller asked for.

The boundary between those two rules is the part worth specifying, because the obvious
implementation — find the next `;`, treat everything between as a name — fails on ordinary text.
Under that rule `"Tom & Jerry; the cartoon"` names an entity called `" Jerry"` and is refused, so
a caller cannot unescape a sentence merely because it contains an ampersand and a semicolon. The
rule actually implemented is that the candidate is read as an *attempt* at an entity only when it
is shaped like one: letters and digits for a name, or `#` followed by digits, or `#x` followed by
hex digits. A space or a comma inside the candidate is proof the author was writing prose.
A leading `#` is an unambiguous numeric attempt even when its digit run is empty, which is why
`&#;` is still reported as missing digits rather than quietly reinterpreted as text — the two
rules have to meet somewhere, and this is the only meeting point that leaves both cases correct.

**Surrogates are refused, not just out-of-range code points.** A lone surrogate cannot be
encoded, so returning one hands back a string that fails later and elsewhere — when it is written
out. Refusing at the boundary puts the error where the bad input is.

## Consequences

- Chapter 16 moves from 0 to 1 of 13 covered. Ten of the remaining twelve are XML and stay
  excluded for the reason above; `html.parser` and `html.entities` remain absent.
- The entity table is six names, not HTML's two thousand. It is one `LinkedHashMap` and every
  surface derives from it, so `html_escape`, `html_unescape` and `html_entity` cannot disagree
  about which entities exist. A complete table would be a large data file whose correctness
  nobody would check.
- `html_entity` refuses a character with no entity rather than returning it unchanged.
  Unchanged output would make a template that emitted a bare letter look like a success.
- No implicit escaping anywhere. Nothing here writes a page, infers a context or guesses a
  charset.
- The security acceptance criterion in the inventory — "no XML entry point resolves an external
  entity or processes a DTD, proven by test" — is met vacuously: there is no XML entry point.
  What this ADR adds is the positive half, that escaping is exact and tested.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Ship an HTML parser too | Reading a document's declarations is the attack surface the inventory excludes. A parser without a hardening story is a security regression, and this language has no callback protocol for a SAX style anyway |
| Recognise already-escaped input in `html_escape` | Makes the literal text `&amp;` unrepresentable, which is a smuggling route |
| One `escape(text, context)` with a context argument | Two names cost nothing and state the caller's intent at the call site; a string context argument is a typo waiting to be silently accepted |
| Escape only the three text-content characters | Escaped strings are very often pasted into attributes, and an escape safe in one context but not the other invites the exact mistake it was called to prevent |
