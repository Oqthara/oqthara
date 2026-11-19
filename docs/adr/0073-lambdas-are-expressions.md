# ADR-0073 — A lambda body is one expression, never a block

**Status:** Accepted
**Date:** 2026-11-20
**Related:** `docs/spec/language.md:33` (values table), `:444` (lambda forms), `:288`
(`items.sort(item -> ...)`), row L4.9 of
[`alpha-7-language-core-capability-matrix.md`](../governance/alpha-7-language-core-capability-matrix.md)

## Context

The Alpha 7 language-core audit recorded L4.9 as **Ambiguous** and could not settle it from the
documentation, because the documentation does not address it either way:

- `docs/spec/language.md:33` lists the lambda form as `x -> x + 1` and calls it a first-class value.
- `:444` gives `double = x -> x * 2` and `add = (a, b) -> a + b` — both single expressions.
- `:288` shows `items.sort(item -> [-item.count, item.name])`, still one expression.
- `fun` is the keyword that opens a block and closes with `end`, and the language's rule is
  general: blocks close with `end`.

Nothing states whether `->` may be followed by a newline and an indented block. A reader who has
learned "Oqthara blocks close with `end`" could reasonably expect it to, and the audit recorded that
expectation as an ambiguity rather than as a defect, because no document promised the block form.

The behaviour is unambiguous. `->` takes an expression:

```
f = x ->
  y = x * 2
  return y
end
print f(3)
```

```
error: expected an expression
  --> blk.oq:1:9
```

The parser reports the error at the `->`, one character after the operator, and never reads the
block. The expression form works:

```
f = x -> x * 2
print f(3)
```

```
6
```

## Decision

A lambda body is exactly one expression. `->` does not open a block, and `x ->` at the end of a
line is a parse error rather than the first line of a multi-line body.

The rule follows from the form rather than from a restriction on it. A lambda is a **value** —
`:33` classifies it beside numbers and text — and a value expression has a value at the end of one
expression. A block is a **statement** construct: it has no value, which is why `fun` needs
`return` to produce one. Allowing `->` to open a block would make the two forms differ only in
spelling, and the language would carry two ways to write a function that are the same function.

Anyone who needs a body of several statements has `fun`:

```
fun f(x)
  y = x * 2
  return y
end
```

That is the language's one obvious way to write a named, multi-statement function, and it already
exists. The lambda exists for the case `fun` cannot serve — passing behaviour inline to
`items.sort`, `par_map` and `Collections.sort` — where a single expression is the whole point.

## Consequences

L4.9 changes from **Ambiguous** to **Intentionally refused**. The capability is not missing: it is
declined, the specification now states the rule, and `conformance/262-lambda-bodies-are-expressions.oq`
pins both the working form and the error.

The cost is that a lambda cannot contain a statement, so `x -> (print x; x)` is not expressible.
The mitigation is `fun` plus a reference, which is one extra line and named rather than anonymous.
An inline callback that genuinely needs to print is rare, and when it appears the honest answer is
that the author wanted a named function.

The parse error is reported at the `->` and reads "expected an expression", which is accurate but
terse. A better message would name the two options — an expression after `->`, or `fun` for a
block. That improvement is not made here because it is a parser change with its own test surface,
and §4's audit scope does not extend to rewriting diagnostics; it is recorded in the matrix's
bounded backlog instead.

## Alternatives considered

**Allow block-bodied lambdas.** Rejected: it gives the language two spellings of one construct,
the simplicity test fails on the "exactly one obvious way" question, and nothing in 0.2.0 asks for
it. It also expands the parser's grammar at a point §4 explicitly puts out of scope.

**Refuse lambdas entirely.** Rejected: `items.sort(item -> ...)`, `par_map` and
`Collections.sort` all take them, the specification documents all three, and the conformance suite
exercises them. This would be a removal, not a decision.

**Leave it Ambiguous.** Rejected: an ambiguity that has been investigated and answered is a
decision that has not been written down. The audit's job is to replace "we do not know" with "we
decided", and leaving the row Ambiguous would schedule work that has already been considered and
refused.
