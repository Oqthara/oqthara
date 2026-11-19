# ADR-0075 — Workstream 2 owns the type model, and optional annotation stays closed

**Status:** Accepted
**Date:** 2026-11-19
**Depends on:** [ADR-0069](0069-oqthara-stays-dynamically-typed.md) (the typing decision this
does not reverse) · [ADR-0015](0015-type-families.md) (the inventory it builds on)
**Related:** [`alpha-4-workstream-assignment.md`](../governance/alpha-4-workstream-assignment.md)
(row 2, the gap this closes), [`alpha-7-first-report.md`](../governance/alpha-7-first-report.md)
(Item 7, which asks for this record), ADR-0001, ADR-0017, ADR-0027, ADR-0028

## Context

Alpha 7 §5 orders "optional gradual typing with generics". The
[first report](../governance/alpha-7-first-report.md) found that §5 cannot begin as written, for two separate
reasons that were being read as one.

**The decision is closed against it.** ADR-0069 records that Oqthara remains dynamically typed and
that `data` fields stay untyped, superseding ADR-0015's open question and the decision brief.
Typing a field would need an ADR superseding ADR-0069 — not a feature branch, not a grammar patch.
Two conformance cases (`246`, `247`) refuse annotations with diagnostics that cite the decision, so
the refusal is behaviour the language publishes rather than an omission.

**The owner was missing.** Row 2 of
[`alpha-4-workstream-assignment.md`](../governance/alpha-4-workstream-assignment.md) — Type System
& Values — was the only one of twelve rows marked **Under-covered**, with the note "no role names
types". Its primary owner is `oq-hive-development` **"(de facto)"**, which the document is careful
to distinguish from an assignment: the role's scope never says it owns the type model, it merely
produces the files the type model lives in. Every other workstream has a role whose written scope
names its subject.

The report's table said assigning §5 "is itself an ADR", and this is that record. It is written
before any typing work, not after, because a workstream with no owner cannot be the workstream that
answers the question.

## Decision

**1. `oq-hive-language-syntax` owns the type model, and workstream 2 is assigned to it.**

The role already owns `docs/spec/language.md`, the lexer, the parser, the AST, and the conformance
corpus — which is every surface the type model is expressed through. `oq-hive-development` keeps
the *runtime* half, where values actually live: `Values.java`, `Value.java`, `OqTuple`, `OqSet`
and the rest. That split follows the existing rows rather than inventing one: workstream 1 is
syntax, workstream 2's artefacts are the value classes, and the type model is the contract between
them. Row 2 becomes `Covered`, with `oq-hive-development` as supporting owner, which is the
inverse of the de facto arrangement.

This is a scope decision about roles, not a change to any agent file. The agent files are
unchanged; the assignment document records which scope covers the work.

**2. §5's optional typing and generics stay closed. The reversal path is a new ADR, and this is not
it.**

ADR-0069 rejected runtime-asserted field types as *unrequested* rather than unsound, and set the
condition for revisiting: a program that needs one. No such program has been produced. Nothing in
§5's sentence overrides that, because a directive ordering work is not a program needing it.

Stating this here rather than leaving it implied matters for what comes next: §5 now has an owner
who can *refuse*, and a reader finding "Type System & Values — Covered" could reasonably conclude
typing is scheduled. It is not. The workstream is covered so that the question can be answered
deliberately; the answer remains ADR-0069's.

**3. The ownership gap and the typing decision are recorded separately, so closing one is never
mistaken for closing the other.**

ADR-0069's final section already warned about this and is now satisfied rather than contradicted:
it said changing who owns a workstream "belongs in its own governance commit". This is that commit.
A future change may assign workstream 2 elsewhere without touching the typing decision, and a future
ADR may reverse ADR-0069 without touching ownership. Neither implies the other.

## Alternatives rejected

| Option | What it would be | Why not |
| --- | --- | --- |
| Reverse ADR-0069 and build optional typing | A superseding ADR, grammar, both backends, adversarial tests for the refusal and its absence, then a conformance re-measurement | The condition ADR-0069 set has not been met: no program needs it. It was also decided *while the maintainer was unavailable*, expressly because it was the only option reversible at zero cost — so reversing it would reverse a decision taken on someone else's behalf, which needs the maintainer, not an agent |
| Leave workstream 2 `Under-covered` | Record the gap and proceed | The report says §5 cannot begin without an owner, and §5 is a directive section. An `Under-covered` row is an accurate record of a gap, not a way to satisfy the section that names it |
| Assign workstream 2 to `oq-hive-development` formally | Make the de facto owner explicit | It would leave one role owning both the contract and the implementation of the contract, which is the arrangement that let the gap exist unnoticed. It also gives the role that would *implement* typing the authority to decide whether types are wanted |
| Assign to `oq-hive-architecture-governance` | Give the ADR-writing role the type model | It owns decisions, not language surface. A role whose artefacts are ADRs and registries cannot maintain a normative spec section or a conformance corpus |

## Consequences

**What is accepted.** Workstream 2 reads `Covered` while no type system is being built. That is a
true statement about ownership and a false one about scheduling, and the ADR says so in decision 2
rather than relying on a reader to check ADR-0069. Anyone who finds the row and expects typing work
will find the pointer that says otherwise.

**What is not changed.** No grammar, runtime, conformance case, or compatibility surface moves.
This ADR adds no keyword, no syntax, and no refusal. Both conformance cases that pin the typed-field
refusal continue to pass unchanged, and ADR-0069 remains Accepted and authoritative on the typing
question.

**The type model now has an owner with no work assigned in Alpha 7.** That is the intended state:
the owner exists so that a future proposal has somewhere to go, not because a proposal exists. The
workstream is `Covered` on the strength of the scope reaching it, which is how the document defines
every other row.

**§5 remains unimplemented, and this ADR does not implement it.** §5 orders typing and generics;
this record makes that order answerable rather than answered. It should be read as the second half
of Item 7's finding, not as its resolution.

## Evidence

- [`docs/adr/0069-oqthara-stays-dynamically-typed.md`](0069-oqthara-stays-dynamically-typed.md) —
  the typing decision, its reversal condition, and the warning that ownership is a separate change.
- [`docs/governance/alpha-4-workstream-assignment.md`](../governance/alpha-4-workstream-assignment.md)
  — row 2, now `Covered`, and the "(de facto)" qualifier the previous marking carried.
- [`docs/governance/alpha-7-first-report.md`](../governance/alpha-7-first-report.md) Item 5 and
  Item 7 — the finding that §5 could not begin as written, and that assigning it is itself an ADR.
- `conformance/246-typed-field-names-the-decision-error.oq` and
  `conformance/247-typed-parameter-names-the-decision-error.oq` — the refusal as published
  behaviour, unchanged by this record.
- `oq-core/src/main/java/dev/oqthara/core/Parser.java` (`rejectTypedField`, `rejectTypedParameter`)
  — where the refusal is implemented, and the comment naming the decision.
- `docs/governance/remediation/type-system-decision-brief.md` — the measurement the original
  decision rests on, including the maintainer's unavailability.
