# 0028 — Immutable `data` declarations

Status: accepted (2026-11-09, Alpha 4 Phase 14.2)

## Context

Alpha 3 `data` has one asymmetry that no other value family has. It defines structural
equality (`Point(1, 2) == Point(1, 2)` is `true`) and it defines a structural hash, but it
refuses to be used where that hash would be needed:

```
data Point(x, y)
p = Point(1, 2)
m = {p: "a"}
```

```
error: a Point cannot be used as a map key
  help: Hashing is structural, so mutating the value afterwards would change its hash and
        make the entry impossible to find again. Use an immutable value instead: a tuple, a
        frozenset, text or a number.
```

The refusal is correct. `p.x = 99` is legal, and a key that can change its own hash is a
defect waiting to happen. But the diagnostic names the remedy — "use an immutable value" —
and the language offers no way to make a `data` type immutable. Every other family resolves
this consistently:

| Family | Equality | Mutability | Usable as key |
| --- | --- | --- | --- |
| tuple | structural | immutable | yes |
| `frozenset` | structural | immutable | yes |
| list | structural | mutable | no |
| map | structural | mutable | no |
| set | structural | mutable | no |
| `class` instance | identity | mutable | yes (identity hash) |
| `data` | structural | mutable | **no** |

`data` is the only row where a family supplies structural equality and a structural hash
but is barred from every place the hash is used. A `class` instance escapes the same problem
by not being structurally equal in the first place: its identity hash cannot change.

The Alpha 4 directive (§14.2) asks for "immutability options if justified" and for the
equality/hash relationship to stay consistent. It also requires that the "interaction with
classes/interfaces" be considered and that generated behaviour not change surprisingly.

## Decision

A `data` declaration may carry the modifier `immutable`. Its fields are fixed at
construction, and the value becomes usable as a map key and a set member.

```
data Point(x, y) immutable
p = Point(1, 2)
m = {p: "origin-ish"}
print m[Point(1, 2)]        # origin-ish — structural lookup works
print set([p, Point(1, 2)]) # {Point(x: 1, y: 2)} — one member, not two
p.x = 9                     # error: Point is immutable, so field 'x' cannot be changed
```

### Scope of the refusal

Only field assignment is refused. Everything else about the value is unchanged: it is
constructed the same way, compared structurally the same way, hashed the same way, rendered
the same way, and read the same way. `immutable` changes who may write a field and where the
value is allowed to travel — nothing else.

A refusal must name the type and the field, and must say how to obtain a changed value,
because the obvious repair (build a new one) is not discoverable from the error alone:

```
error: Point is immutable, so field 'x' cannot be changed
  help: Build a new value instead: Point(x: 9, y: 2) — defaults still apply to fields you
        leave out.
```

### Interaction with classes and contracts

`data` still cannot carry methods, implement a contract, or be inherited. This was true
before this ADR and remains deliberately true: `data` is the value family, `class` is the
identity-and-behaviour family, and the directive's warning against surprising changes to
generated behaviour argues for keeping the two apart rather than growing `data` into a
second class system.

Two consequences are worth stating outright because they are the cases a reader will try
first:

- An immutable `data` may still hold a mutable value. `data Box(items) immutable` fixes the
  *field*, not its contents; `b.items.push(1)` still works. This is the same rule a tuple
  follows — `(list, 1)` is an immutable tuple containing a mutable list — and changing it
  would require deep freezing, which §14.2 does not ask for and which no other family
  implements.
- Because the fields are only fixed, not frozen, an immutable `data` is **only as hashable
  as its contents**. `Value.requireHashable` descends into the field values with the same
  recursion the tuple and frozenset branches use, so `Box([1]) immutable` is refused as a
  map key for exactly the reason `([1],)` is: the key could be mutated into
  unreachability after it was stored. The recursion is what makes the summary in the
  decision table — structural equality implies a structural hash implies usable as a key —
  an *if and only if* rather than an implication that holds only for flat values.
- An immutable `data` is *structurally* equal to a mutable `data` of the same type with the
  same fields only when both are the same declaration. Equal-but-differently-mutable values
  cannot arise, because mutability is a property of the type, not of the instance.

### Equality and hashing stay one rule

The hash is not new. `OqData.hashCode` already delegates to the structural hash in
`Value`, and `Value.hash` already has an `OqData` branch. `immutable` does not introduce a
second hash; it removes the guard that stops the existing one from being reached.

That matters for correctness: the interpreter and the compiled backend must agree. Both
construct through `OqData`, so both inherit the same hash and the same promotion rules
(`1` and `1.0` hash alike inside an object, per ADR-0017).

### JVM representation

An immutable `data` is still an `OqData`. One instance type, one `equals`, one `hashCode`,
one renderer. Mutability is a `boolean` field on the instance, set once by the constructor,
so a compiled program and an interpreted one cannot disagree about whether a given value is
frozen.

The alternative — a separate `OqFrozenData` subclass, mirroring `OqTuple` versus a list —
was rejected. `OqTuple` is a distinct type because it renders differently (`(1, 2)` versus
`[1, 2]`) and has different `List` semantics. An immutable `Point` renders exactly like a
mutable one and reads exactly like one, so a subclass would buy nothing and would split
every `instanceof OqData` site in the runtime.

### Runtime enforcement

`OqData.set` is the single write path; the interpreter's `FieldAssign` branch and
`DataValues.put`-based compiled writes both reach it, and `fields()` hands out an
unmodifiable view so no caller can reach past the check. Writes that rebuild the value —
`clone`, above all — must carry the modifier across, or they would launder an immutable
value into a mutable one and break the promise that made it a legal key. The runtime therefore refuses there
rather than in the interpreter, so neither backend can forget the rule. The refusal carries
no message of its own because the two callers know different things: the interpreter has the
span and the declared field list and can produce the full diagnostic with the rebuild hint.

## Alternatives considered

| Alternative | Why not |
| --- | --- |
| Make every `data` immutable | Breaks working Alpha 3 programs. The directive says preserve working semantics unless an explicit change is specified. |
| Add a `frozen(value)` builtin that freezes any mutable value in place | Crosses family boundaries, would need to handle lists, maps, sets and every nesting combination, and could not be enforced on a value already stored in a map without a scan. |
| Drop the map-key refusal and accept mutable keys | Reintroduces exactly the defect the diagnostic describes: a stored key whose hash changes becomes unfindable. |
| Let `data` implement contracts and gain a `text()` override | Wanted eventually, but it is §14.1/§16 work, not §14.2, and mixing the two would make the class/value distinction harder to see rather than easier. |
| Destructuring for `data` | ADR-0013 deliberately rejected positional access: `data` fields are named, and a positional order is not promised by the declaration. `immutable` does not change that argument. |
| Typed fields (`data Point(x: Int)`) | Requires a type system the language does not have. Rejected for Alpha 4, and confirmed as the standing decision on 2026-11-13: the whole language is dynamically typed and `data` is not the place to start introducing static types. [`type-system-decision-brief.md`](../governance/remediation/type-system-decision-brief.md) records the reasoning and the cost of reversing it. |

## Consequences

- One new keyword-position: `immutable` after the field list of a `data` declaration. It is
  contextual, not a reserved word, so `immutable = 1` still names a variable — the same
  treatment `class`, `contract`, `init` and `implements` already receive.
- `requireHashable` gains an `OqData` branch that passes when the value is immutable, and
  the diagnostic for a mutable one now names the remedy explicitly.
- A `data` declaration that is both immutable and has a mutable default (for example
  `data Bag(items = []) immutable`) is legal but nearly always a mistake: every instance
  shares no default (defaults are evaluated per construction, as Alpha 3 established) but
  the list inside is still mutable. The spec documents it rather than rejecting it, because
  refusing it would be a guessing game about intent.
- Existing programs are unaffected: with no modifier, `data` behaves exactly as it did.
- The conformance baseline gains one executable case. The four coordinated baseline
  locations move together (`verify-conformance.py`, `BytecodeCompilerTest`, and the two
  `docs/spec/backend.md` counts), as every count change in this project must.
