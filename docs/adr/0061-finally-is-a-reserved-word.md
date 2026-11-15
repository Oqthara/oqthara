# ADR-0061 — `finally` is a reserved word, not a clause

**Status:** Accepted
**Date:** 2026-11-15
**Related:** [`contracts.md`](../spec/contracts.md) §8.3 (the recorded contradiction this closes),
[`language.md`](../spec/language.md) §Errors (the specification it makes true),
`Lexer.RESERVED`, `scripts/check-docs-sync.py` (the keyword-count gate)

## Context

`language.md` has said since Alpha 1 that the language has no `finally`: *"There is no
`finally` and no typed catch: one handler per `try` keeps the surface small."* The parser
nevertheless accepted the word. It was not a keyword, so it lexed as an identifier, and a
statement list happily absorbs a bare identifier as a variable read.

That made the failure depend on whether an error occurred, which is the worst shape a bug can
take:

```
try
    print "a"
catch e
    print "b"
finally
    print "cleanup"
end
print "done"
```

When nothing throws, the catch body never runs, so the absorbed line never executes: the
program prints `a`, `done`, and exits `0`. The cleanup is silently skipped. When something
does throw, the line executes as an undefined name:

```
caught

error: 'finally' is not defined
  --> g2.oq:5:1
   5 | finally
```

The diagnosis then names the wrong problem. The fault is a language construct that looks
supported and is not, and the programmer is told to declare a variable.

`docs/spec/contracts.md` §8.3 recorded the contradiction and allowed two outcomes: implement
`finally` fully, or reserve the word and reject it with a direct diagnostic. It ruled out only
one thing, silently absorbing it.

## Decision

`finally` is a **reserved word**. It is a member of `Lexer.KEYWORDS`, so it can never lex as
an identifier, and it starts no statement. The rule the decision buys is that no program can
contain a `finally` line and still run: every grammar position refuses it.

The *message* is not identical everywhere, and an earlier draft of this ADR claimed it was.
`scripts/probe-reserved-positions.py` probes the word against every grammar position a name can
occupy and reports what the compiler says at each. Run it to regenerate these figures rather than
trusting them:

```
$ python3 scripts/probe-reserved-positions.py
word: finally
positions probed: 42
accepted: 0
rejected: 42
refused by the reservation itself: 32
distinct messages: 6
   32x error: finally does not exist
    4x error: expected ']' to close the list
    3x error: unexpected keyword 'finally' after a statement
    1x error: unexpected ',' after a statement
    1x error: unexpected keyword 'finally' in a class body
    1x error: invalid expression inside text: finally does not exist

ok: no position accepts 'finally' as a name
```

Thirty-two of the 42 slots that only a name can fill -- a variable, a parameter, a field, the
`catch` name, a call target, an index -- reach `Parser.reserved` and say
`finally does not exist`. The other ten are refused by whatever rule owns that position, which is
the right outcome but a different sentence: `class finally()` and `contract finally` are
`unexpected keyword 'finally' after a statement`, a `finally` in a class body is
`unexpected keyword 'finally' in a class body`, `a, finally = [1, 2]` is
`unexpected ',' after a statement`, and a `finally` inside text interpolation is folded into the
enclosing message, giving `invalid expression inside text: finally does not exist`. The guarantee
is that the word is never accepted and never silently skipped, not that one string covers every
site. The probe exits non-zero if any position ever accepts the word, so `--check` can be wired
into a gate.

The four `expected ']' to close the list` rows are comprehensions, and they are not evidence
either way: comprehensions are unimplemented (`alpha-6-requirement-matrix.md` records every form
as `NOT STARTED`), so the list rule refuses the program before the word is examined. An earlier
draft of this paragraph listed a comprehension clause as a slot that reaches `Parser.reserved`,
which the language cannot produce.

```
error: finally does not exist
  --> f1.oq:5:1
   5 | finally
       ^^^^^^^

  help: Oqthara has no 'finally' clause: it was rejected in ADR-0061. Put the cleanup at the
        end of the 'try' block and at the end of the 'catch' block, or call one function from
        both.
```

Implementing `finally` was rejected on simplicity grounds. It is the one construct a `try`
needs that can observe and replace the control flow of both other branches: it must run on
normal completion, on a caught error, on a `return`, on a `break`, and on a `continue`, and a
`return` or error raised inside it must win over the one in flight. The interpreter and the
compiled backend would each need that whole matrix, in a language whose stated position is
that one handler per `try` is enough. `lock()` already takes a callback precisely because the
language has no `finally` (`ExecutorsAndCancellationTest`), so the cleanup idiom exists and is
tested.

Rejecting it costs one keyword out of a budget of 30 (21 → 22) and closes the contradiction
without new semantics.

### Why a keyword rather than a parser special case

The alternative was to leave `finally` out of `Lexer.KEYWORDS` and test for the identifier
`"finally"` in the statement parser. That is smaller, but it leaves the word an ordinary name
everywhere the parser does not happen to look — as a parameter, a field, a function name, an
assignment target — so `fun f(finally)` would still parse. The directive's rule is that
*unsupported syntax must produce an intentional diagnostic rather than be accepted as an
unrelated identifier*, and only reserving the word at the lexer guarantees that for every
position at once. `Lexer.KEYWORDS` is already the single source of truth the documentation
gate reads, so the classification cannot drift from the docs.

`Lexer.RESERVED` names the subset of keywords that are reserved but unimplemented. It exists
so the parser can produce the targeted "does not exist" diagnostic instead of the generic
"unexpected keyword" one, and so a future reserved word has somewhere to go.

### A reserved word pre-empts the delimiter diagnostic

`Parser.expect` raises the reservation before it raises its own complaint, so a reserved word
sitting in a slot that wants punctuation names the construct rather than the punctuation:

```
$ oq run g1.oq          # x = [1 finally]
error: finally does not exist

$ oq run g2.oq          # x = [1 catch]
error: expected ']' to close the list
```

`catch` is a keyword but not reserved, so it falls through to the delimiter message. The
asymmetry is deliberate. `catch` there is a plausible typo for a value the writer meant to type,
and "a `]` is missing" is the repair. `finally` there is evidence the writer believes the language
has a cleanup construct, and telling them a bracket is missing would send them to fix the wrong
thing -- they would add the `]`, run again, and still not have `finally`. Naming the word is the
only message that can stop that loop, which is the whole point of reserving it.

### Why signature help travels with this decision

This change also fixes `oq-lsp`'s signature help, and reviewers have twice asked whether that
belongs here. It does, for two reasons.

The defect is the same one. `SignatureHelp.isKeyword` held a hand-maintained copy of the keyword
set that had drifted from `Lexer.KEYWORDS`: it was missing six real keywords including
`finally`, and it carried nine words that are not keywords at all. The reservation above was
unreachable from the editor because of it -- a call to a function named `match` got no help,
and `finally` was classified as a plain identifier. Fixing the reservation in `oq-core` while
leaving the editor's duplicate in place would have shipped a language change the editor did not
see, which is the failure this ADR exists to prevent.

The fix is the minimal form of this decision. `isKeyword` is now a one-line derivation from
`Lexer.KEYWORDS`, so it cannot drift again, and the one position that is genuinely not a call
stays in `isDeclarationHeader` where the declaration is known. The alternative -- a separate PR
-- would have landed a lexer change with a known editor inconsistency in the same release.

The branch name (`oqthara-runtime-distribution-hardening`) does not describe this work. It was
assigned by the session tooling before the task was chosen and is not renameable through the
supported path, so it is left alone rather than rewritten with raw git. Reviewers should read the
PR title, this ADR and the commit subjects, not the branch name.

## Consequences

- A program that used `finally` as a variable now fails to parse. No pre-existing fixture,
  example, or test did; a repository-wide search outside the conformance cases this change adds
  found zero uses.
- The keyword count rises to 22. `docs/stdlib/reference.md` is regenerated by `oq doc`, and
  `CONTRIBUTING.md` and `docs/spec/design.md` state the new count. The gate in
  `scripts/check-docs-sync.py` compares all three against `Lexer.KEYWORDS`, so the three
  cannot disagree.
- The word is highlighted as `keyword.control.oq` in the editor grammar, so it reads as
  reserved everywhere it appears.
- Any future attempt to implement `finally` must begin by removing it from `Lexer.RESERVED`
  and this record must be superseded.
- `docs/spec/contracts.md` §8.3 keeps the reproduction and its outputs as the evidence that
  the defect existed, and records that it is closed.
