# ADR-0069 — Oqthara stays dynamically typed, and `data` fields stay untyped

**Status:** Accepted
**Date:** 2026-11-18
**Supersedes:** [ADR-0015](0015-type-families.md)'s scope as an open question, and
[`docs/governance/remediation/type-system-decision-brief.md`](../governance/remediation/type-system-decision-brief.md)
as the live record of the decision
**Related:** [ADR-0003](0003-bytecode.md) (the interpreter is the primary backend),
[ADR-0017](0017-equality-and-hashing.md) (structural hashing),
[ADR-0027](0027-classes-and-contracts.md) and
[ADR-0028](0028-data-evolution.md) (typed fields rejected), Alpha 7 §5

## Context

ADR-0015 established that Oqthara is dynamically typed and has no type checker, and it did so as
an *inventory*: it answered "what is the value model" rather than "should it be". Alpha 4 §4 then
asked the question the inventory declined to answer, and a decision brief was written to put the
options on the table with their costs.

That brief is now decided — 2026-11-13, Option A, no typed fields — but the decision lives in a
governance remediation note, which is a record of a decision process rather than a durable
architectural decision. Alpha 7 §5 needs a record that a reader can find from the ADR index, that
answers §4 directly, and that says what the answer forbids. Nothing in this ADR changes behaviour:
it records a decision that was made and is already true of the code.

The brief remains the evidence. It measured every claim it makes against `oq.jar` on both
backends rather than inferring from source, and this ADR does not re-run those measurements or
restate them as new.

## Decision

**1. Oqthara remains dynamically typed. No static checker is built.**

A checker retrofitted onto a dynamically-typed language with two backends would need to be
authoritative for the interpreter, which ADR-0003 makes the primary backend, or it would be advisory and
therefore unenforced. An unenforced type system is worse than none, because it teaches readers to
trust a check that does not run.

**2. `data` fields remain untyped. No annotation position is added to the grammar.**

A runtime-asserted field type (`data Point(x: number, y: number)`) was considered and rejected as
*unrequested*, not as unsound: no program has been produced that needs it. The repository's
standard for a refusal or a feature is that a program needed it, and none has. If one appears, the
sequence is grammar, spec, interpreter, bytecode backend, adversarial tests for both the refusal
and the absence of a wrong refusal, then a conformance re-measurement — and a new ADR superseding
this one.

**3. Typing a field and freezing a record stay separate claims, should option 2 ever be revisited.**

`x: number` would say *what may be stored*; `immutable` says *whether it may be stored again*. A
typed field must not imply immutability. This is recorded now, while no annotation exists, because
the shape that would break existing programs is easy to introduce accidentally and cheap to rule
out in advance.

## Alternatives rejected

| Option | What it would be | Why not |
| --- | --- | --- |
| Static checking | A semantic-analysis pass, a type language, inference or annotation for every binding | Forbidden by §4 as a means to typed fields, and it would require a compatibility story and an agreement story with the normative interpreter that nothing needs |
| Runtime-asserted field types | `data Point(x: number, y: number)`, checked at construction and assignment | New grammar, a new refusal family, both backends, and a compatibility change — for a failure no program has demonstrated. Reversible at zero cost only while unbuilt, which is the state it stays in |
| Deferring the decision | Leaving §4 open | §5 is blocked on it, and an open decision is what the brief was written to close |

## Consequences

**What is accepted.** A wrong field type is found at the operation that misuses it rather than at
the construction that was wrong, and the diagnostic names the failed operation rather than the bad
assignment. A constructor called with its arguments swapped is accepted. These are real costs and
they are the trade the language already makes everywhere else.

**What is not changed.** No grammar, runtime, conformance case, or compatibility surface moves.
Every program that is valid today is valid after this record, which is why the decision was taken
without the maintainer: it is the only option reversible at zero cost.

**A `data` still carries no methods, contracts, or inheritance.** ADR-0027 and ADR-0028 govern
that and are unaffected. Validation — "`address` should look like an email" — is what people
actually want when they ask for types, and no type system expresses it; `class` already has
constructors and `init` and can validate freely.

**Reversal costs one decision, not one migration.** Since no program written under this ADR would
become invalid under runtime-asserted field types (that option only adds refusals), reopening this
is a new ADR, not a corpus rewrite.

## What this record does not fix

The brief documents a workstream gap: row 2 of
[`alpha-4-workstream-assignment.md`](../governance/alpha-4-workstream-assignment.md) marks itself
**Under-covered** and names `oq-hive-development` only as a *"(de facto)"* owner, so no role owns
the type model. That is about ownership rather than a missing decision, and changing who owns a
workstream belongs in its own governance commit. It is named here so that closing §4's decision is
not mistaken for closing §4's ownership gap.

## Evidence

`docs/governance/remediation/type-system-decision-brief.md` (the decision and every measurement it
rests on) · [ADR-0015](0015-type-families.md) (the inventory this builds on) ·
[ADR-0003](0003-bytecode.md) · [ADR-0027](0027-classes-and-contracts.md) ·
[ADR-0028](0028-data-evolution.md) · `oq-core/src/main/java/dev/oqthara/core/Values.java`
(`typeName`, the canonical chokepoint).
