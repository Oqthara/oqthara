# ADR-0075 — Workstream 2 owns the type model, and optional annotation stays closed

**Status:** Accepted
**Date:** 2026-11-19
**Depends on:** [ADR-0069](0069-oqthara-stays-dynamically-typed.md) (the typing decision this
does not reverse) · [ADR-0015](0015-type-families.md) (the inventory it builds on)
**Related:** [`alpha-4-workstream-assignment.md`](../governance/alpha-4-workstream-assignment.md)
(row 2, the gap this closes), [`alpha-7-first-report.md`](../governance/alpha-7-first-report.md)
(Item 5, where the row for this workstream said assigning it "is itself an ADR"; it reads
`Covered` now, so the sentence is quoted as what it said before this record), ADR-0001, ADR-0017,
ADR-0027, ADR-0028

## Context

Alpha 7 §5 orders "optional gradual typing with generics". The
[first report](../governance/alpha-7-first-report.md) found that §5 cannot begin as written, for two separate
reasons that were being read as one.

**The decision is closed against it.** ADR-0069 records that Oqthara remains dynamically typed and
that `data` fields stay untyped, superseding ADR-0015's open question and the decision brief.
Typing a field would need an ADR superseding ADR-0069 — not a feature branch, not a grammar patch.
Two conformance cases (`246`, `247`) refuse annotations with diagnostics that tell the writer the
language is dynamically typed, so the refusal is behaviour the language publishes rather than an
omission.

**The owner was missing.** Row 2 of
[`alpha-4-workstream-assignment.md`](../governance/alpha-4-workstream-assignment.md) — Type System
& Values — was the only one of twelve rows marked **Under-covered**, with the note "no role names
types". Its primary owner was `oq-hive-development` **"(de facto)"**, which the document is careful to
distinguish from an assignment. That role's scope does name the *value model* — its file opens
"Owns implementation — the interpreter, value model, …" — so it was not absent from the subject.
What it never named is the type model as a model: the families, what a value belongs to, and how
the specification states them. It produced the files the type model lives in without owning the
contract those files implement, which is the gap `"(de facto)"` was marking. Row 2 was the only row
marked `Under-covered`, which is the narrower claim this record relies on. It is not the only row
whose owner never names the workstream's subject in those words: rows 3 and 7 are also owned by
`oq-hive-development`, whose scope names modules rather than subjects. Those rows are marked
`Covered` because the modules that role names contain all of the work — a collection lives in
`oq-stdlib`, a class in `oq-core`. Row 2 is different in kind: only half of it is in a module that
role names, the value classes in `oq-runtime`, and the other half — the specification's account of
the families — is in `docs/spec/language.md`, which that role's scope does not mention. That is why
the row was not `Covered` and why the fix is a second owner rather than a wider reading of the
first.

The report's table said of workstream 2 that assigning it "is itself an ADR", and this is that
record. It is written
before any typing work, not after, because a workstream with no owner cannot be the workstream that
answers the question.

## Decision

**1. `oq-hive-language-syntax` owns the type model, and workstream 2 is assigned to it.**

The role already owns `docs/spec/language.md`, the lexer, the parser, the AST, and the syntax cases
in `conformance/`. `oq-hive-development` keeps the *runtime* half, where values actually live:
`Values.java`, `Value.java`, `OqTuple`, `OqSet` and the rest. That split follows the existing rows
rather than inventing one: workstream 1 is syntax, workstream 2 is the type model and its
specification, and the value classes are where that model is implemented rather than what it is. The
split has three parts, all of them written into the amended agent file: syntax owns the model and
its specification, `oq-hive-development` owns the value classes *and* the semantic-analysis pass
that would consume an annotation, and `oq-hive-testing-security` owns the `conformance/` corpus.
Row 2 becomes `Covered` with `oq-hive-development` as supporting owner, and its artefacts column
names what the primary produces: `docs/spec/language.md` `## Values` and `## Data types`, plus
ADRs 0015, 0069 and 0075.

**The artefacts cell moved, and that is part of the decision.** On `origin/main` row 2 listed
`Values.java` and `Value.java` — the files the type model is implemented *in* — as the artefacts of
`oq-hive-development` marked `(de facto)`, with `oq-hive-language-syntax` in the supporting cell.
The split here reverses those two cells and changes the artefacts to match the new primary. The row
keeps `docs/adr/0007-boxed-values.md` and `docs/adr/0017-equality-and-hashing.md`, which it listed
before this record and which no other row cites; dropping them would have silently unassigned two
decisions. What the row gives up is the claim that the runtime classes are the artefacts of the
primary owner, which was the misattribution the `(de facto)` marking was papering over.

**This decision required amending the role, and the amendment is part of it.** The cell above is
the second half of the sentence, not a footnote to it: the role's agent file previously said, in a
section written to close Alpha 7 Item 6, that the role owns "the syntax surface, **not the type
system** or the value model". Workstream assignment defines `Covered` as "that role's scope, as
written in its own agent file", and its own rule permits an owner change only when "the agent file
that defines the role changes in the same pull request, *or* when this document records why the
mapping is being read differently". This record takes the first route, not the second: a reading
would have left the role's file still disclaiming the model.
`.github/agents/oq-hive-language-syntax.agent.md` therefore changes here, in the same change: the
exclusion becomes the split this record states, and the `conformance/` corpus is attributed to
`oq-hive-testing-security`, which the assignment already names for it.

Without that edit, this ADR would have asserted an ownership the role's own file denied, and no
reading of the table could have made the row `Covered`.

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
| Leave workstream 2 `Under-covered` | Record the gap and proceed | The report found §5 could not begin without an owner, and §5 is a directive section. An `Under-covered` row is an accurate record of a gap, not a way to satisfy the section that names it |
| Assign workstream 2 to `oq-hive-development` formally | Make the de facto owner explicit | It would leave one role owning both the contract and the implementation of the contract, which is the arrangement that let the gap exist unnoticed. It also gives the role that would *implement* typing the authority to decide whether types are wanted |
| Assign to `oq-hive-architecture-governance` | Give the ADR-writing role the type model | It owns decisions, not language surface. That role does list `docs/spec/`, but only "for structural coherence, never for syntax detail" — the qualification is exactly the part the type model needs, since the model is stated in the specification's normative sections. A role that owns the directory for coherence and not for the content cannot maintain the contract. This is the option the decision brief recommended, and decision 1 explains why it was not taken |

## Consequences

**What is accepted.** Workstream 2 reads `Covered` while no type system is being built. That is a
true statement about ownership and a false one about scheduling, and the ADR says so in decision 2
rather than relying on a reader to check ADR-0069. Anyone who finds the row and expects typing work
will find the pointer that says otherwise.

**What is not changed.** No grammar, runtime, conformance case, or compatibility surface moves.
This ADR adds no keyword, no syntax, and no refusal. Both conformance cases that pin the refusals — `246` for
typed fields, `247` for typed parameters — continue to pass unchanged, and ADR-0069 remains Accepted and authoritative on the typing
question.

**The type model now has an owner with no work assigned in Alpha 7.** That is the intended state:
the owner exists so that a future proposal has somewhere to go, not because a proposal exists. The
workstream is `Covered` on the strength of the scope reaching it, which is how the document defines
every other row.

**§5 remains unimplemented, and this ADR does not implement it.** §5 orders typing and generics;
this record makes that order answerable rather than answered. It should be read as the ownership
half of the finding, not as its resolution.

## Evidence

- [`docs/adr/0069-oqthara-stays-dynamically-typed.md`](0069-oqthara-stays-dynamically-typed.md) —
  the typing decision, its reversal condition, and the warning that ownership is a separate change.
- [`docs/governance/alpha-4-workstream-assignment.md`](../governance/alpha-4-workstream-assignment.md)
  — row 2, now `Covered`. The "(de facto)" qualifier the previous marking carried is gone with the
  marking it qualified. The quoted sentence also survives elsewhere in this tree, in ADR-0069 and in
  the decision brief, both of which this list cites: what does not survive here is the row's live
  `Under-covered` verdict.
- [`.github/agents/oq-hive-language-syntax.agent.md`](../../.github/agents/oq-hive-language-syntax.agent.md)
  — the role's written scope, amended in this change from an explicit exclusion to the split above.
- [`docs/governance/alpha-7-first-report.md`](../governance/alpha-7-first-report.md) Item 5 — the
  finding that §5 could not begin as written, and that assigning its workstream warranted an ADR.
  The Item 5 row was rewritten by this change to record the assignment, so the quotation above is
  of the report as it stood, not of the table as it now reads.
- `conformance/246-typed-field-names-the-decision-error.oq` and
  `conformance/247-typed-parameter-names-the-decision-error.oq` — the refusal as published
  behaviour, unchanged by this record.
- `oq-core/src/main/java/dev/oqthara/core/Parser.java` (`rejectTypedField`, `rejectTypedParameter`)
  — where the refusal is implemented, and the comment naming the decision.
- `docs/governance/remediation/type-system-decision-brief.md` — the measurement the original
  decision rests on, including the maintainer's unavailability.
