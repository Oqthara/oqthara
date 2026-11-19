# ADR-0071 — `+` coerces to text when either side is text

**Status:** Accepted
**Date:** 2026-11-20
**Related:** [ADR-0069](0069-oqthara-stays-dynamically-typed.md) (Oqthara stays dynamically
typed), [ADR-0015](0015-type-families.md) (type families), `Values.add`,
`docs/spec/language.md` precedence table (level 5), row L2.6 of
[`alpha-7-language-core-capability-matrix.md`](../governance/alpha-7-language-core-capability-matrix.md)

## Context

The Alpha 7 language-core audit recorded L2.6 as **Undocumented**, which is a specific and
narrow claim: the behaviour exists, it is reachable from ordinary programs, and the specification
does not say it is a rule. It is not a claim that the behaviour is a defect, and the audit could
not make that claim without a documented expectation to contradict.

The behaviour is this. `+` adds two numbers. When either operand is text, `Values.add` falls
through to `display(a) + display(b)`, so `1 + "a"` is `1a`, `"a" + 1` is `a1`, `"a" + {"n": 1}` is
`a{"n": 1}`, and `"a" + (1 to 3)` is `a1 to 3`. The precedence table at
`docs/spec/language.md:309` lists level 5 as `+`, `-` with the meaning "add, subtract" and says
nothing about text. No conformance program exercises it.

Two questions have to be settled before the behaviour can be documented, and they are the reason
this needs a decision record rather than a paragraph in the spec.

First, **is coercion right, or should `+` refuse mixed operands?** The alternative is a type
error: `1 + "a"` fails with something like "cannot add text and number". That is defensible and
it is what several small languages choose, because it keeps the operator's meaning narrow.

Second, **if it coerces, what is the conversion?** `display` is the same function `print` uses, so
`1 + "a"` agreeing with `print 1` and then `a` is a real consistency argument. Concatenating
rather than adding when the right side is text is the choice that makes `"total: " + n` work
without a conversion call, and that pattern is most of why the branch exists.

## Decision

`+` coerces to text when either operand is text. The conversion is `display` on both operands,
which is the conversion `print` performs, so the two cannot disagree. Numbers still add when both
operands are numbers, lists still concatenate when both are lists, and text wins over every other
family.

This is documented rather than removed, for three reasons.

It is already load-bearing. Removing it would break every program that builds a message with `"a"
+ n`, and the audit found the behaviour by running it, not by looking for it — nobody wrote a
conformance case because nobody doubted it worked. A change that breaks working code to satisfy a
purity argument is a language redesign, not a documentation fix, and §4 does not authorize one.

It is consistent with the value model. Oqthara stays dynamically typed (ADR-0069), values are Java
objects (ADR-0001), and `display` is defined for every value. An operator that accepts any pair of
values and produces text is a smaller addition than a special-case refusal that has to enumerate
which combinations are allowed.

It is the reading that makes the common case quiet. `"count: " + n` is the reason people reach for
`+`; a language that refuses it needs a conversion function, and Oqthara does not have a
conversion function for exactly this because it never needed one.

The conversion is stated as `display`, not as "to string", because the second phrase does not
determine an output for a map or a nested list and `display` does.

`nothing` is the exception, and the decision records it rather than smoothing it over. `"a" +
nothing` is `anothing`, but `1 + nothing` fails with `cannot add number and nothing`. The branch
reached is `isNumber(a) && isNumber(b)`, then `a instanceof String`, then list, map, set; when
neither operand is text and no family matches, the error path runs. So the coercion is "text on
either side wins", and the operand that is `nothing` is converted only when text is on the other
side. That is an asymmetry, and it is the behaviour that exists; the specification should state it
as it is rather than state the tidier rule the code does not implement.

## Consequences

The spec gains a sentence at the precedence table stating the rule and naming `display` as the
conversion, and a conformance case pins it: numbers and text in both orders, text with a list, a
map, a boolean and a range, and the `1 + nothing` refusal. The case is what makes the row Complete
rather than Undocumented.

`+` no longer has one meaning. It is addition, list concatenation, or concatenation depending on
its operands, and a reader who sees `+` in unfamiliar code cannot tell which without knowing the
types. This is the price of the decision and it is worth naming: it is the same trade `print`
already makes, and it is why ADR-0069 chose dynamic typing.

Mixed `+` where neither side is text and the families differ — a list plus a number, `nothing` plus
a number — still fails with the existing errors. This decision changes nothing about those, and the
conformance case asserts `1 + nothing` so that "coerces" is not read as "accepts anything".

There is no implicit text conversion anywhere else. `-`, `*` and comparison operators continue to
refuse mixed operands, and `==` continues to compare families rather than coercing. If that
inconsistency is later judged a problem, it is a separate decision.
