# ADR-0001 — Syntax style

- **Status:** accepted
- **Date:** 2026-11-04
- **Deciders:** oq-architect, oq-orchestrator

## Context

Oqthara exists to make the JVM pleasant. The syntax is the product, so it was chosen
before the parser was written. Three block styles were on the table:

1. **`end`-terminated blocks** (Lua, Ruby, MATLAB flavour)
2. **Brace blocks** (`{ }`, Java/C/Go flavour)
3. **Indentation blocks** (Python flavour)

The guiding constraint is that Oqthara must read like pseudocode and must never look
like Rust. It also has to be cheap to parse with a hand-written recursive-descent
parser, and it has to look good inside a Markdown document and a slide.

## Decision

**`end`-terminated blocks, with newlines as statement separators.**

```
if score >= 90
  print "A"
else if score >= 70
  print "B"
else
  print "C"
end
```

Rationale, evaluated against the simplicity test:

| Question | `end` | Braces | Indentation |
|---|---|---|---|
| Explainable in one sentence | Yes | Yes | Yes |
| New keyword | `end` (1) | none | none |
| One obvious way | Yes | Yes, but `;` creeps in | Yes, but tabs/spaces fights |
| Parser cost | Low | Low | Requires INDENT/DEDENT tokens and column bookkeeping |
| Layout-preserving formatting | Free | Needs a formatter | Source layout is semantic |
| Cut-and-paste safety | High | High | Low (the invisible block boundary breaks) |
| Reads like pseudocode | High | Medium | High |

Braces were rejected because they drag in the rest of the C family's habits
(semicolons, one-line statements, brace-on-next-line debates, and a strong visual
resemblance to Java — the exact verbosity Oqthara is replacing). Indentation was
rejected because it makes copy-paste and diff review risky, and because it forces the
lexer to carry a significant amount of state for little user benefit.

`end` adds exactly one keyword, is impossible to get wrong visually, survives
cut-and-paste, and keeps the parser simple. It is also the style already present in
the project's own founding sketch.

Newlines separate statements. Semicolons are accepted as optional separators so that
one-liners in the REPL and test fixtures stay possible, but the formatter never emits
them. Inside `( )`, `[ ]` and `{ }`, newlines are insignificant, so multi-line lists
and maps need no continuation markers.

## Other decisions taken at the same time

- **Keywords in English.** The documentation, the standard library and the error
  messages are English, so the keywords are too. A future translation layer is a
  separate, non-breaking feature.
- **No required type annotations.** Types are inferred. Annotations are additive
  later, never mandatory.
- **`nothing` instead of `null`.** Reading `nothing` out loud is unambiguous, and it
  keeps a Java-flavoured keyword out of the surface language.
- **No mandatory `main`.** Code at file level runs top to bottom. We can still add an
  explicit entry point for packaged applications later.
- **Text literals use `"` and `'`.** Both are strings; there is no character type. A
  single character is just a one-character string.
- **Interpolation with `{expr}`.** No `+` needed, and `{{` escapes a literal brace.
- **`data` for records.** Fields, constructor, `equals`, `hashCode` and `toString`
  are generated. No inheritance.
- **Ranges are expressions.** `1 to 5` and `0 until 5` produce a first-class range
  value that loops, lists and `length` accept.
- **Lambdas are `x -> expr` and `(a, b) -> expr`.** No `fn`, no `=>`, no `->` for
  return types.
- **No Rust surfaces.** No lifetimes, no borrow markers, no `&`, no `Option`/`Result`
  wrappers, no `match` with `=>`. Errors are ordinary exceptions with good messages.

## The twenty programs

The comparison corpus is checked in under `docs/adr/0001-programs/`. Each program is
shown in the accepted `end` form and the two rejected forms. The count of tokens,
characters and lines was compared to keep the accepted style from being secretly
more verbose.

Programs covered: hello, FizzBuzz, word count, file read, JSON, HTTP, records,
collections, closures, sorting, error handling, recursion, ranges, string
interpolation, maps, sets, null handling, classes-with-methods, concurrency and a
small CLI.

Result: across the twenty programs, the `end` form was the same length as the brace
form within 4% of characters, and 11% shorter than the indentation form once
continuation lines were taken into account. It had no parsing ambiguities and no
layout-sensitive failures after copy-paste.

## Consequences

- The lexer must emit `NEWLINE` tokens but suppress them inside brackets.
- The parser needs a `block()` rule that stops on `end` and `else`.
- Optional semicolons must be tolerated in the REPL and in one-line test fixtures.
- Formatters must be idempotent and must not introduce semicolons.
- A future indentation style would be a breaking change; this ADR is the record of
  why it is not on the table.
