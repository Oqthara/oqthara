# ADR-0062 — A diagnostic names the construct, not the token after it

**Status:** Accepted
**Date:** 2026-11-15
**Related:** [`contracts.md`](../spec/contracts.md) §8.3 (the same wrong-token class),
[`language.md`](../spec/language.md) §Errors (the specification it makes true),
ADR-0061 (`finally` is refused outright, which this record contrasts with),
`Parser.CONSTRUCT_LEADERS`, `scripts/probe-construct-leaders.py` (the measured figures)

## Context

Oqthara has no pattern matching, no generators, no `async`, and no `import`. It never has.
Writing any of them, however, produced a diagnostic about something else entirely:

```
$ oq run m.oq           # match x
error: unexpected 'x' after a statement

$ oq run y.oq           # yield 1
error: unexpected number 1 after a statement

$ oq run c.oq           # result = [n * 2 for n in 1 to 3]
error: expected ']' to close the list
```

Each of those is true and each is useless. `match x` does contain an unexpected `x` from the
parser's point of view, so the message is not a lie; it simply describes the wrong thing. The
writer did not type a stray `x`, they typed a construct the language does not have, and no
amount of removing the `x` will produce a program that does what they meant.

The cost is a repair loop. A reader told `unexpected 'x'` deletes the `x`, runs again, and gets
`unexpected '='` or nothing at all. The `]` message is worse, because it is actionable: adding
the bracket produces a valid program with different behaviour than the comprehension the writer
intended. The diagnostic actively leads away from the answer.

The comprehension case is the sharpest, because the shape is unambiguous. A `for` inside `[`,
`{` or `(` cannot begin anything else Oqthara accepts, so there is exactly one thing the writer
could have meant.

## Decision

Two contextual rules, both in the parser.

**A leader word that cannot continue an expression names the construct.** `Parser.CONSTRUCT_LEADERS`
is `match`, `case`, `switch`, `yield`, `def`, `async`, `await`, `from`, `import`, `throw`.
When the parser has just read a bare identifier naming one of those words and the next token
cannot continue the expression, the error names the word and carries a hint saying what to write
instead.

Two expressions cannot sit next to each other in this grammar, so `match x` and `yield 1` have
no reading other than a writer asking for syntax that does not exist. Everything that *can*
continue an expression keeps the name legal, and that list is longer than it looks: operators,
call parentheses, dots, brackets, assignment, and the contextual range words `to` and `until`.

The rule is stated as "cannot continue" rather than "is followed by an operand" because the
inverse formulation was wrong twice. The first draft refused a leader followed by an identifier,
a number, a string, or any non-reserved keyword, which broke `match and 1` -- `and` is an
operator, so that is an ordinary expression over a variable named `match`. Taking literal
keywords only then broke `async fun g()`, where the token after `async` is the `fun` keyword
rather than an operand, and it is the one leader spelling that is followed by syntax.

The rule lives where the `Ast.Var` is created, not on the statement paths. The first two
versions put it on statement forms: the bare-expression path, then additionally the two
assignment branches after `x = yield 1` escaped. Both were incomplete in the same way --
`return yield 1`, `print yield 1`, `if yield 1`, `while yield 1`, `f(yield 1)`, `[yield 1]`,
`assert yield 1`, `for x in yield 1` and `x = [yield 1]` all still blamed the operand. Running
the check at the single site that constructs a variable reference covers every position at once
and cannot drift as statement forms are added.

```
error: match is not a construct in Oqthara
  --> m.oq:1:1
   1 | match x
       ^^^^^

  help: Oqthara has no pattern matching. Compare with 'if' / 'else if' and read the value with
        a 'data' field or an index.
```

**A comprehension names its shape.** When the token wanting a closing bracket is the keyword
`for`, the error names the comprehension instead of the bracket:

```
error: list comprehensions are not implemented
  --> c.oq:1:17
   1 | result = [n * 2 for n in 1 to 3]
                       ^^^

  help: Build the collection with a loop instead: create it, then push each element inside the
        loop body.
```

Every message is measured by `scripts/probe-construct-leaders.py`, which probes all ten leaders
before an operand, in two assignment positions, eight nested positions and three
terminator positions, all five comprehension shapes, sixty name positions, and one hundred and
fifty expression-continuation positions:

```
$ python3 scripts/probe-construct-leaders.py
leaders probed: 10
operand diagnostics naming the construct: 10 of 10
diagnostics carrying a construct-specific hint: 10 of 10
assignment values naming the construct: 20 of 20
nested positions naming the construct: 80 of 80
name positions per leader: 6
name positions still accepted: 60 of 60
expressions after a leader still accepted: 150 of 150
terminated statements not blamed as constructs: 30 of 30
comprehension shapes naming themselves: 5 of 5

ok: every construct leader names itself before an operand and stays usable as a name
```

The hint row is not decorative. The parser already emits a generic hint at that position --
`Statements end at the end of the line.` -- so a probe that only asked whether *a* hint exists
would report 10 of 10 before the change and prove nothing. The probe requires a
construct-specific fragment per leader for that reason, and an earlier draft of this record
carried the weaker figure until the baseline run exposed it.

Behind each row, the message that used to appear, measured on the same programs before the
change by running the same probe against the pre-change parser:

```
before: operand diagnostics naming the construct: 0 of 10
before: diagnostics carrying a construct-specific hint: 0 of 10
before: assignment values naming the construct: 0 of 20
before: nested positions naming the construct: 0 of 80
before: comprehension shapes naming themselves: 0 of 5
before: name positions still accepted: 60 of 60
before: expressions after a leader still accepted: 150 of 150
before: terminated statements not blamed as constructs: 30 of 30

after:  operand diagnostics naming the construct: 10 of 10
after:  diagnostics carrying a construct-specific hint: 10 of 10
after:  assignment values naming the construct: 20 of 20
after:  nested positions naming the construct: 80 of 80
after:  comprehension shapes naming themselves: 5 of 5
after:  name positions still accepted: 60 of 60
after:  expressions after a leader still accepted: 150 of 150
after:  terminated statements not blamed as constructs: 30 of 30
```

`60 of 60`, `150 of 150` and `30 of 30` in the "before" column are the point of the last three
rows: none of those positions were ever broken, and the fix had to leave all three exactly as
they were. The probe exits 1 on the before build with 125 failures -- one per refused probe --
and 0 on the after build, so it can gate. An earlier draft of this record claimed 24; that was
the operand and hint rows alone, counted before the assignment, nested, tuple and terminator
rows existed, and re-running the current probe against the pre-change jar is the only way to
get the number this table now states.

The messages themselves, side by side:

| Written | Before | After |
|---|---|---|
| `match x` | `unexpected 'x' after a statement` | `match is not a construct in Oqthara` |
| `case 1:` | `unexpected number 1 after a statement` | `case is not a construct in Oqthara` |
| `switch x` | `unexpected 'x' after a statement` | `switch is not a construct in Oqthara` |
| `yield 1` | `unexpected number 1 after a statement` | `yield is not a construct in Oqthara` |
| `def f(x)` | `unexpected 'f' after a statement` | `def is not a construct in Oqthara` |
| `async fun g()` | `unexpected keyword 'fun' after a statement` | `async is not a construct in Oqthara` |
| `await f()` | `unexpected 'f' after a statement` | `await is not a construct in Oqthara` |
| `from x import y` | `unexpected 'x' after a statement` | `from is not a construct in Oqthara` |
| `import foo` | `unexpected 'foo' after a statement` | `import is not a construct in Oqthara` |
| `throw "boom"` | `unexpected text after a statement` | `throw is not a construct in Oqthara` |
| `[n * 2 for n in 1 to 3]` | `expected ']' to close the list` | `list comprehensions are not implemented` |
| `{n: n for n in 1 to 3}` | `expected '}' to close the map` | `map comprehensions are not implemented` |
| `{n for n in 1 to 3}` | `expected '}' to close the set` | `set comprehensions are not implemented` |
| `(n for n in 1 to 3)` | `expected ')' to close the expression` | `generator comprehensions are not implemented` |
| `(1, 2, n for n in 1 to 3)` | `expected ')' to close the tuple` | `tuple comprehensions are not implemented` |

### Why these ten are not reserved

ADR-0061 reserved `finally` as a keyword, and the obvious move here is to do the same. It is
the wrong move, and the reason is that `finally` and these words differ in one decisive way:
nobody writes `finally` as an ordinary variable name, and people write `match` and `from` all
the time.

They already do, in this repository:

```
examples/grep.oq:20   for match in search(file, pattern)
examples/hanoi.oq:5   fun hanoi(n, from, to, via)
```

`await` is a plausible name for a field holding a pending result. Reserving the set would have
turned two shipped examples into parse errors, which is a regression sold as a diagnostic
improvement. So the refusal is *contextual*: it needs the next token to be one that cannot
continue an expression. The guard is `continuesExpression()`, and it is the load-bearing half of
the rule — `match` followed by an operator, a bracket, a dot, an assignment or the end of a
statement is a variable read, and `match x` is a construct that does not exist.

That distinction is exactly what the probe tests, in two directions. Its sixty name positions
(`assign-target`, `command-argument`, `for-variable`, `parameter`, `function-name`, `bare-read`,
for each of ten leaders) must all keep parsing, and so must its one hundred and fifty
continuation positions (fifteen ways of continuing an expression for each of ten leaders). A
future contributor who "simplifies" this by adding the words to `Lexer.KEYWORDS` fails the probe
and `ParserTest.everyConstructLeaderRemainsUsableAsAName`; one who "simplifies" it by refusing
any following token fails `ParserTest.anOperatorAfterALeaderNameIsStillAnExpression` and
`ParserTest.aContextualRangeOperatorAfterALeaderNameIsStillARange`.

### Why the comprehension rule needs no such guard

`for` *is* a keyword, so no legal collection literal can contain it. The rule "next token is
`for`" cannot misfire, and `(1 + 2)` and `[[1], [2]]` are pinned by
`ParserTest.anOrdinaryCollectionLiteralIsStillAccepted` so it stays that way.

The five shapes get five messages rather than one, because the repair differs: a list is built
with `push`, a map with an index assignment, a set with `add`. A single "comprehensions are not
implemented" would be accurate and would leave the reader to work out which of the four they
wrote.

### The reserved-word check moved first in `endStatement`

`Parser.endStatement` is where every wrong-token message in a statement originates. It now
checks `Lexer.RESERVED` before its generic message, so a reserved word in statement position
reports the reservation rather than the token after it. The two rules compose: `finally` used
as a construct is refused by the reservation (ADR-0061), and a leader word with an operand is
refused by this rule.

### Why these ten and not the others

The inclusion test is narrow on purpose: a word is a leader only if it is the keyword of a
construct that another language a pseudocode reader is likely to have written would use, **and**
the word can stay a legal identifier. Ten words pass. The ones that do not are worth naming,
because they were each considered:

- `class` is not a leader because `class` is implemented. `conformance/228-classes-and-contracts.oq`
  runs `class Square(side) implements Shape` today.
- `fn` is not a leader because `examples/memoize.oq:2` declares `fun memoize(fn)` and reads
  `fn(n)`. It is a shipped parameter name.
- `let`, `var`, `new`, `foreach`, `lambda`, `struct`, `enum`, `interface`, `repeat`, `function`
  and `unless` all still produce `unexpected '<token>' after a statement`. They are absent
  because nothing in the corpus writes them and the ten that are present cover every construct
  the matrix records as missing. Adding one is a one-line change to `CONSTRUCT_LEADERS` plus a
  probe row, and costs nothing against the keyword budget, because the rule is contextual.

That last property is the reason the set can grow safely: a word added to `CONSTRUCT_LEADERS`
that a program legitimately uses as a name in a continuing position is unaffected, and the
probe's sixty name positions and one hundred and fifty continuation positions would catch a
word that was not.

### A block terminator after a leader is not a construct

`match end` is invalid, but not because `match` is a construct: `x end` is invalid the same way,
since `end` does not belong on the line. So the refusal exempts `end`, `else`, `catch` and the
reserved words, and the program gets the ordinary `unexpected keyword 'end' after a statement`
instead. This was found in review against the first committed version, which asserted something
false about the source.

### What this does not do

It does not implement any of the ten constructs, and it does not make the gap smaller. A
program that uses `match` still cannot be written. The change is that the refusal now says so
in one step instead of two, and lands on the word the writer has to remove rather than on the
token after it.

The matrix rows for pattern matching, generators, lazy iteration and modules stay `NOT STARTED`
or `PARTIAL`; only the row for the diagnostic itself closes.

## Consequences

- The keyword count is unchanged at 22. The ten leader words are not keywords, so
  `docs/stdlib/reference.md`, `CONTRIBUTING.md` and `docs/spec/design.md` do not move and the
  documentation gate in `scripts/check-docs-sync.py` is untouched.
- Two shipped examples and any downstream code using these words as names keep working; the
  probe enforces it.
- Five new parser tests pin the behaviour, and the mutation evidence is that each fails when its
  rule is removed: forcing the guard off, making `continuesExpression()` always return false, and
  deleting the `rejectUnimplementedConstruct(name)` call site each produced three failing tests.
  The statement-path design this record superseded needed per-path mutation checks because each
  call site was independent; the chokepoint has exactly one.
- Three conformance cases were added: `242` and `243` are `.err` cases pinning the message, and
  `244` is executable and proves the leader words still work as names. `244` moved the measured
  corpus from 215 to 216 executable and 206 to 207 compiled; all three baseline pins were
  updated from that measurement, not toward it.
- Conformance case `204` uses `from` and `until` as parameter names and reads
  `for n in from until until`, which is why `to` and `until` are exempt from the refusal. That
  case is the standing regression guard for the contextual-range false positive; it caught the
  bug that unit tests and a hand-run sweep both missed.
- A future attempt to implement any of these ten constructs must remove its entry from
  `CONSTRUCT_LEADERS` and supersede this record.
