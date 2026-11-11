# ADR-0030: Documentation comments

**Status:** Accepted
**Date:** 2026-11-10
**Supersedes:** nothing
**Related:** ADR-0029 (closures and modules)

## Context

The maintainer asked for multi-line documentation comments with JavaDoc-like metadata, and for
the reference documentation to be generated from those comments rather than written twice.

Before this decision the language had multi-line text literals and nothing else. A literal
standing before a `fun` was parsed as an expression statement, evaluated, and discarded. Nothing
in the AST recorded it, so no tool could read it back:

```
$ oq run d1.oq
3                      # the literal before `fun add` was evaluated and dropped
```

There was no reflection either. `doc_of(add)` and `arity(add)` are both undefined names, so a
program cannot ask a function about itself at run time. Any documentation facility had to be
built into the declaration, not bolted on afterwards.

## What the language already did

| Property | Verified |
|---|---|
| `"""..."""` is a single text literal | `length("""abc""")` is `3` |
| It spans lines, keeping them | `"""line one\nline two"""` prints both lines |
| Its common indentation is stripped | the lexer calls `dedent` before building the literal |
| A literal beginning a line before a `fun` is allowed and ignored | `oq run` printed `3` |

So the syntax the maintainer asked for already existed. What was missing was any way for that
literal to mean something.

## Decision

**D1. A lone multi-line literal before a declaration documents it.** The parser attaches it to
the `fun`, `data`, `class` or `contract` that follows and records it on the declaration as an
optional `doc` field. The literal does not survive as a separate statement.

```
"""Adds two numbers."""
fun add(a, b)
  return a + b
end
```

The attachment is decided by lookahead, not by consuming the literal and inspecting what came
next. If the literal is not followed by a declaration, it stays an ordinary expression statement
exactly as before. This is what keeps the change from altering the meaning of any existing
program: the previous behaviour is the fallback, not an error.

**D2. `"""` gets its own token kind.** A single-quoted string is a value. A double-triple-quoted
one is a value *or* a documentation comment, and the parser can only tell which by looking at
what follows. Giving multi-line text the `MULTILINE` kind made that distinction available
without changing the token for ordinary strings.

`MULTILINE` is accepted everywhere `STRING` is, so a multi-line literal is still usable as a
value — as an argument, a `data` field default, a map key, or the right-hand side of `=`. It was
probed in each of those positions.

**D3. Tags begin a line with `@name`.** A tag runs to the next tag or the end of the literal.

| Tag | Meaning | Rendered as |
|---|---|---|
| `@param name text` | a parameter, by name | `- \`name\` text` |
| `@return text` | what the call produces | `Returns: text` |
| `@throws Error text` | the failure it raises | `Throws: text` |
| `@since version` | when it appeared | `Since: version` |
| `@author text` | who wrote it | `Author: text` |
| `@deprecated text` | what to use instead | `Deprecated: text` |
| `@see other` | a related declaration | `See also: other` |

An `@` that does not begin a line is prose: `Sends mail to @home when idle.` has no tags. An
`@` followed by a non-letter is also prose: `Costs @ each.` has none.

A tag the language does not know is **kept**, not refused. `@example add(1, 2)` renders under
`@example`. Refusing it would make a docstring a breaking change the moment a future tag is
written or a tool adds one, and the parser has no way to know which tag names a later version
will define.

**D4. A `@param` names its parameter explicitly.** The parser does not match the third word of
the tag against the parameter list positionally, because a docstring and a parameter list can
drift apart silently if it does. `@param a the first addend` documents `a` and nothing else; a
`@param` naming a parameter the declaration does not have is ignored rather than an error,
because it may document a parameter that was removed and the documentation is not the
authority on the signature.

**D5. The AST carries the documentation, and only the AST does.** There is no side table keyed
by declaration, no global registry, and no reflection entry point. A tool that wants the
documentation parses the file and reads `FuncDecl.doc()`. This means the documentation cannot
disagree with the parse of the file it came from, and it disappears with the file rather than
outliving it.

**D6. `oq doc --source` generates reference pages from those declarations.** It is deliberately a
separate mode from `oq doc [root]`, which generates the standard library reference from the
Java registration calls:

```
oq doc --source src/            # to standard output
oq doc --source src/ --out api.md
```

A directory is walked for `.oq` files; a file is documented alone. A declaration with no
documentation comment appears as `_Undocumented._` rather than being omitted, so the page shows
the gap. The footer counts both, so "how much of this project is documented" is answerable from
the generated page instead of by review.

**D7. A method inside a class body carries its own documentation.** The class body accepts a
multi-line literal in front of a `fun` and attaches it to that method, which lands in
`MethodDecl.doc()` by the same rules as D1. Without this, a class with ten methods could be
documented only as a whole, at the level of the class, and the methods themselves would be
invisible to `oq doc --source`. Only a literal followed by `fun` is attachment; a literal
before anything else in a class body remains the error it always was, because a class body
holds nothing else a comment could describe.

Methods are rendered one heading level below their class, so the page keeps a method with the
class it belongs to. Two classes may declare a method of the same name, and a method heading
alone would not say which one a reader is looking at. Methods count towards the documented and
undocumented totals alongside top-level declarations.

## Alternatives considered

| Alternative | Why not |
|---|---|
| A `##` comment syntax | The language has no block comments. Introducing one adds a second way to write a comment, and `#` comments are already line-scoped. The literal is the syntax the maintainer asked for, and it already lexed. |
| Treat *any* preceding string as documentation | A single-quoted string is a value on the previous line far more often than it is documentation, and silently reinterpreting it would break `print "x"` before a declaration. Only the multi-line form is unambiguous. |
| Refuse an unknown tag | Makes adding a tag a breaking change, and a docstring is documentation rather than a checked interface. Kept instead under its own name. |
| Store tags on the runtime function | There is no reflection, so no program could reach them; it would also make documentation a run-time cost for code that never reads it. |
| Generate the page from a separately written file | That is the duplication the requirement exists to remove; the page would then be able to describe a function the source does not have. |
| Attach by column position (a literal at column 0 only) | Indentation is not semantic in this language, and a nested function inside a body is legitimately indented. Lookahead on the *next token* is the rule that survives both. |

## Consequences

- **Nothing existing changes meaning.** A multi-line literal before a non-declaration is still an
  expression statement. Verified: `print 1`, then a bare literal, then `print 2` prints `1` and
  `2` with three statements parsed — the middle one is an `ExprStmt`.
- **A docstring cannot appear before an assignment or a `print`.** It attaches to declarations
  only. `"""x"""` before `print 1` remains an ignored value, which is what it was.
- **The literal is still a literal.** It can be assigned, printed, and used as a value anywhere
  a string can, and the docstring on a declaration is an added field rather than a replacement.
- **Documentation is per declaration, not per field or method.** A `data` field or a class method
  cannot carry its own comment yet; `@param` covers the fields of a record and the parameters of a
  function. Per-method documentation for classes would need a place in the grammar for a literal
  inside the class body.
- **`oq doc --source` reads one directory at a time and does not follow `use "oq:..."`.** It
  documents the files it is given, in name order, without resolving imports; a file documented
  as a bare path is documented exactly as it parses.
- **The generated page is downstream of the parser.** A syntax error in one file fails the run
  and names the file, rather than skipping it, because a page that silently omitted a
  half-documented file would be worse than no page.
