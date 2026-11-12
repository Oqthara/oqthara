# Type model and typed `data` fields — decision brief

**State:** OPEN — awaiting a maintainer decision. Nothing here is implemented.
**Scope:** Directive §4 ("Language architecture and type model"), and
[ADR-0028](../../adr/0028-data-evolution.md)'s "typed fields" rejection, which this brief
reopens as a decision rather than a settled matter.
**Blocks:** nothing merged. Every behaviour described as current is shipped and tested.

This brief exists because §4 asks for a decision the maintainer has not made, and because the
question it raises — "should `data` fields be typed?" — is the one question in the Alpha 4
corpus whose answer would change the language rather than a component. Advisory work that
trespasses on it would be expensive to undo, so it is stated here rather than assumed.

It does not choose. It states what exists, what each option would cost, and what is forced by
the architecture as it stands.

## What exists today (verified, not recalled)

Every claim below was checked against `oq.jar` from the working tree, on both backends, rather
than inferred from the source or from an earlier record.

**The language is dynamically typed and has no checker.** `oq-core` contains `Ast`,
`Diagnostics`, `Env`, `Interpreter`, `JavaBridge`, `Lexer`, `OqFunction`, `Parser` and `Token`.
There is no semantic-analysis pass, no type annotation in the grammar, and `type_of` is an
ordinary runtime builtin. This is recorded in [ADR-0015](../../adr/0015-type-families.md) and is
unchanged.

**`data` fields are assignable unless the declaration says otherwise.** The modifier is trailing
and says `immutable`:

```
data Point(x, y)           # p.x = 5 is accepted; the value is a mutable record
data Point(x, y) immutable # p.x = 5 is refused
```

Measured. A mutable `Point` accepts `p.x = 5` and prints `5`. An `immutable` one refuses:

```text
error: Point is immutable, so field 'x' cannot be changed
  --> k.oq:3:2
   3 | p.x = 5
        ^

  help: Build a new value instead: Point(..., 2)
```

The same refusal is produced by the compiled jar, so the two backends agree on both the
acceptance and the wording — the interpreter is normative and the bytecode backend matches,
rather than the check living in one path.

**An immutable `data` is hashable; a mutable one is not.** `{Point(1, 2): "a"}` succeeds when
`Point` is `immutable` and is refused otherwise:

```text
error: a Point cannot be used as a map key
```

That is not a separate decision. It follows from structural hashing: a mutable value's hash
could change while it sits in a bucket, so ADR-0017 refuses it. The `immutable` modifier is
therefore what makes a record usable as a key, which is the reason it was added rather than a
formality.

**Construction is checked for arity and for missing fields, not for type.** `Point(1)` is
refused with `missing value for field 'y' of Point`. `Point(1, "two")` is accepted, because
there is no type to check it against. Default values are not a thing: every field must be
supplied.

**`data` cannot carry methods, implement a contract, or be inherited.** A method body inside a
`data` block is a parse error. This is deliberate per ADR-0027 and ADR-0028 — a `data` is a
value, a `class` is a thing with behaviour — but it is the closest thing in the language to the
"declaration vs behaviour" line that §4 asks about.

## The questions §4 asks

### 1. Does Oqthara remain dynamically typed?

**Recommendation: yes, and this brief does not propose otherwise.** Every reason to consider
changing it is a reason to want typed fields, and typed fields have a cheaper answer — see
question 2. A static checker retrofitted onto a dynamically-typed language with two backends
would need to be authoritative for the interpreter, which is normative by ADR-0003, or it would
be advisory and therefore unenforced. An unenforced type system is worse than none: it teaches
readers to trust a check that does not run.

The cost of staying dynamic is real and worth naming: a wrong field type is found at use rather
than at construction, and the error message names the operation that failed rather than the
assignment that was wrong. That is the trade the language already makes everywhere else.

### 2. Are typed `data` fields required, and if so how are they enforced?

Three shapes are possible, and they differ in more than syntax.

**Option A — no typed fields.** `data` stays as it is. A record documents its field meanings in
a docstring, and mistakes surface at the operation that first misuses a value.

- Cost: nothing. Zero compatibility risk. No new grammar.
- Benefit lost: a reader cannot tell from `data Point(x, y)` whether `x` is a number, and a
  constructor call with the arguments swapped is accepted.

**Option B — runtime-asserted field types.** A field may carry a type name; the constructor
checks it and refuses a mismatch, and assignment checks the same way.

```
data Point(x: number, y: number)
```

- Enforcement: runtime, at construction and at every assignment to the field. Both backends can
  implement it at the same chokepoint, because the check is an ordinary predicate over a value
  the interpreter already has.
- Benefit: swapped or mistyped arguments are refused at the construction site with the field
  named, which is where a reader looks. This is the only point in a dynamic program where the
  distinction is cheap.
- Cost: new grammar (a type annotation position), a new refusal message family, spec and both
  backends, plus a compatibility question — see below. It is *not* a type system: it says
  nothing about what a function returns or accepts.
- Risk of the wrong shape: a runtime annotation invites the reader to believe the value is
  checked everywhere. It is checked at the field, and a value obtained some other way is not.

**Option C — static checking.** A checker that proves field accesses and rejects programs before
they run.

- Cost: a semantic-analysis pass, a type language, inference or annotation for every binding,
  an agreement story with the interpreter, and a compatibility story for every existing program
  that would newly fail to compile.
- This is the option §4 forbids building merely to enable typed fields, and nothing in the brief
  argues for it.

**Recommendation: Option A now, Option B only if a concrete program is shown to need it.** The
honest statement is that no such program has been produced. The most likely candidate — a
constructor whose arguments are easy to swap — is refused at runtime only when the swapped value
is *wrong*, and the class of programs that suffer is narrower than it first appears. Option B
should be proposed with a program that demonstrates the failure it prevents, following the
standard this repository holds refusals to: a refusal exists because a program needed it.

### 3. Assignment and mutation compatibility

Option B raises a question Option A does not: **does a typed field make the record immutable?**
It must not, unless the maintainer wants it to.

A `data` is currently mutable by default and hashable only when `immutable`. If a typed field
were to imply immutability, every existing program that assigns to a field of a typed record
would break, and the `immutable` modifier would become either redundant or contradictory.
Typing a field and freezing a record are different claims, and the language should keep them
separate:

- `x: number` says *what may be stored*.
- `immutable` says *whether it may be stored again*.

Four combinations are then meaningful, and each is useful: typed and mutable (the common case),
untyped and mutable (today's default), typed and immutable (a validated key), untyped and
immutable (today's key).

This is a recommendation, not a finding — it is the shape that keeps the two existing behaviours
intact rather than the only possible one.

### 4. Constructor defaults and field validation

Neither exists today, and both are separable from typing.

- **Defaults.** `data Point(x, y = 0)` would remove the "must supply every field" rule. Useful
  and cheap, but it interacts with `immutable` and with the rebuild hint: the refusal above
  suggests `Point(..., 2)`, which is only correct because every field is required. A default
  would not change that hint's correctness, but it changes what "rebuild from fields" means.
- **Validation.** `data Email(address)` with a check that `address` looks like an email is what
  people actually want when they ask for types, and no type system can express it. It needs a
  place to put a constructor body, which `data` does not have. Adding one would make `data` and
  `class` converge, which ADR-0027 exists to prevent.

**Recommendation: neither, until asked for by a program.** Both are better served by `class`,
which already has constructors and `init` and can validate freely.

### 5. May `data` define methods or implement contracts?

Today: no, and it is a deliberate line — `data` is a value, `class` is behaviour. The
distinction survives because it is enforced by the grammar rather than by convention.

**Recommendation: keep it.** §14.2's own text asks whether these are needed; the answer that
costs nothing is to point at `class`, which already does both. If a `data` grows a method it is a
`class` that is spelled differently, and the language has enough ways to say one thing.

### 6. Compatibility implications for existing programs and both backends

**Option A:** none. **Option B:** the only change is *new* refusals — a program that compiles
today with a wrong-typed field would start failing at construction. That is a breaking change to
a program that was already wrong, which is the acceptable kind, but it is still a refusal that
did not exist and should be recorded as such in the changelog rather than described as
compatible.

There is no scenario in which either option changes the meaning of a program that is currently
correct on both backends, because the two backends already agree on every field operation
measured above.

## What is forced by the architecture

Two things are not decisions:

1. **Any field check must live where both backends reach it, or be implemented twice.** The
   `immutable` refusal above is produced identically by `oq run` and by the compiled jar, which
   is the standard. A check added only to the interpreter would make the backends diverge, and
   ADR-0003 makes the interpreter normative, so the compiled form must agree or refuse
   compilation.
2. **No annotation may be enforced statically without the checker Option C describes.** An
   annotation that is parsed and then ignored is worse than a syntax error, because it reads as
   a guarantee.

## The workstream gap

Directive §4 records that workstream 2, Type System & Values, is "under-covered in the current
workstream assignment". Row 2 of that document does exist, but it marks itself
**Under-covered** and names `oq-hive-development` only as a *"(de facto)"* owner — no role is
assigned to own the type model. The gap the directive names is therefore real and is about
ownership rather than a missing row, and this brief does not fix it by inventing an owner.

**Recommended action:** revise row 2 to name a real primary owner —
`oq-hive-architecture-governance` is the natural candidate, because the open questions are value
model and ADR decisions rather than implementation — with `oq-hive-development` as supporting and
this brief as its first artefact. It is left undone here because changing who owns a workstream
belongs in its own governance commit, not bundled into a technical brief; it is the natural
follow-up to whichever option is chosen.

## Recommended next step

Answer question 1. If the answer is "remains dynamically typed" (recommended), then the
outstanding §4 work is the workstream row above and nothing else, and ADR-0028's rejection of
typed fields becomes the recorded decision rather than a note. If the answer is "typed fields
are wanted", answer question 2 as Option B and question 3 as "typing does not imply immutability",
and the sequence is: grammar, spec, interpreter, bytecode backend, adversarial tests for both
the refusal and the absence of a wrong refusal, then the conformance re-measurement.
