# 0015 — Type families and the value model

Status: accepted (2026-11-09, Alpha 4 Phase 0)

## Context

The Alpha 4 directive (§6) makes type-family consolidation the first foundational priority and
forbids building a large library on an inconsistent model. That presupposes an accurate
inventory, so this record is grounded in the code as it exists, not as the directive imagines it.

Oqthara is **dynamically typed and has no type checker**. `oq-core` contains `Ast`, `Diagnostics`,
`Env`, `Interpreter`, `JavaBridge`, `Lexer`, `OqFunction`, `Parser` and `Token` — there is no
semantic-analysis pass, no type annotations anywhere in the grammar, and `type_of` is an ordinary
runtime builtin. Any requirement phrased as "static inference" therefore cannot be satisfied by
adding a check; it would require a type system the language does not have. This record answers
§6.3 honestly on that basis rather than inventing an unenforceable static claim.

## Inventory as found (§6.1)

The canonical chokepoint is `Values.typeName(Object)`, which produces every user-visible type
name — `type_of`, diagnostics ("cannot add text and number"), and `repr` all route through it. The
inventory below is what it reports, verified by running the released jar.

| Family | Runtime representation | `type_of` |
|---|---|---|
| integer number | `Long` | `number` |
| decimal | `Double` | `decimal` |
| boolean | `Boolean` | `boolean` |
| text | `String` | `text` |
| absence | `null` | `nothing` |
| tuple | `OqTuple` | `tuple` |
| list | `List` (immutable, Java `List`) | `list` |
| set | `Set` (Java `Set`) | `set` |
| map | `Map` (Java `Map`) | `map` |
| range | `OqRange` | `range` |
| user record | `OqData` | the declaration name, e.g. `D` |
| task | `OqTask` | `task` |
| function | `OqCallable` | `function <name>` |
| host object | anything else | `java <SimpleName>` |

Two inconsistencies were found in this inventory, and both are now fixed rather than documented
as acceptable:

1. **`OqTask` had no case in `typeName`,** so `type_of(spawn(f))` printed `java OqTask` — a host
   class name leaking through the language's own type introspection. §6.3 asks how runtime
   representation is exposed; the answer must never be "whatever the JVM class is called".
2. The `java <SimpleName>` fallback is intentional and remains. It is the honest answer for a
   host object Oqthara genuinely does not model, and it is visibly distinct from every language
   type name. Silently mapping host types onto language ones would be the actual bug.

There is **one** numeric family with two representations, not two numeric types: `Long` and
`Double` are unified as the `number`/`decimal` distinction and interoperate through
`Arithmetic` and `Values.add`/`compare`. That unification once stopped at the container
boundary, so `1 == 1.0` was `true` while `[1] == [1.0]` was `false`; **ADR-0017 closed that
gap** and equality now widens at every depth. No collection, builtin or IR node ever invented
a *third* numeric rule. See "Hashability and equality" below.

## Decisions (§6.2)

### Type identity

Identity is **structural on the value**, not on a declared type, because there are no declared
types. `type_of` is the only introspection primitive and it is a function of the runtime value.
`data` declarations produce nominal *names* (`D` is not `E` even with identical fields), which is
the one place a name matters for identity.

### Assignment compatibility

Universal. Any value may be bound to any name; there is no declaration-time check, so there is
nothing to be incompatible with. The cost is that type errors surface at the operation
(`set([1]) - "a"`), not at the assignment, which is the deliberate trade of a dynamic language.

### Conversions

- **Implicit in arithmetic only**, and only between `Long` and `Double`. Both `1 + 1.5` and
  `1.5 + 1` succeed; the rule lives in `Arithmetic`.
- **No implicit coercion between text and other families in arithmetic**, but `+` is
  overloaded: if either operand is `text` the result is text concatenation via `display`, so
  `"a" + 1` is `"a1"` and `1 + "a"` is `"1a"`. This is a property of `+` specifically, not a
  general conversion — `"a" - 1` is an error. Interpolation (`"{x}"`) remains the idiomatic
  way to embed a value in text.
- **Explicit conversion builtins** are `number(x)` (text that looks like `42` or `3.14` to a
  number; raises a `help`-bearing error otherwise) and `text(x)` (the display form). They are
  the only sanctioned conversion path.
- **`number` does not narrow.** `number(3.7)` returns the decimal `3.7`, not `3`. Narrowing
  from decimal to integer is done with `round`, `floor` or `ceil`, each of which returns a
  `number`.

### Overflow and numerics

Integer arithmetic is Java `long` arithmetic and wraps on overflow. This is a documented
property, not an accident: the interpreter and the compiled backend both use the same JVM
operations, so they cannot disagree. An arbitrary-precision integer type is a separate future
decision, not a quiet change to `Long`.

### Hashability and equality

**Superseded in part by [ADR-0017](0017-equality-and-hashing.md).** This section recorded the
state at the time it was written: the numeric rule stopped at the container boundary, and
hash and equality disagreed. ADR-0017 fixed both and is the authoritative description of
`==`, hashing and container keying. What remains true from this section is the last point
below, that mutating a value used as a key makes it unreachable, which is a bounded
limitation rather than a defect.

`==` routes through `Values.equal`. **Since ADR-0017 it is uniformly structural**: numbers
widen at every depth, lists and tuples compare structurally in order, maps and sets compare
without order, and a tuple never equals a list.

```
print 1 == 1.0                 # true
print [1] == [1.0]             # true
print (1,) == (1.0,)           # true
print {1: "a"} == {1.0: "a"}   # true
print set([1]) == set([1.0])   # true
```

Hashing agrees with equality: a whole decimal hashes as the whole number it equals, and
containers hash by content, so a set cannot hold two values that compare equal and a map
finds a key regardless of which numeric representation wrote it.

The one remaining hazard is that **mutating a value used as a key loses the entry**, because
`OqData.hashCode()` and the structural hash of a list derive from mutable contents. Enforcing
hashability needs a type-level predicate the language does not have yet; ADR-0017 records it
as a known limitation.

### Generics

There are none, and none are planned at the value level. A `List` holds `Object`. §6.3 asks how
generic element types are represented and checked; the truthful answer is that they are neither,
because collections are heterogeneous by design. Adding element types would be a type-system
project, not a collection project.

### Static versus dynamic checks

Everything is dynamic. There is exactly one static check in the system: the bytecode backend's
`whyNot` refusal, which declines to compile programs it cannot represent faithfully and falls
back to the interpreter. That is a *code-generation* decision, never a program rejection — a
program the compiler refuses still runs.

### Interpreter versus bytecode representation

The interpreter stores `Object` in `Env`; compiled code stores `Object` in JVM local slots and
passes it through the same `Values`/`Arithmetic`/`Collections` helpers. There is deliberately **no**
second value model in the backend: a parity bug would otherwise be a difference between two
implementations rather than a bug in one. The verified consequence is the conformance figure —
192 of 223 programs compiled with 0 divergences.

## Consequences

- Requirement clauses phrased as "common result type" or "inference" are answered by *dynamic
  semantics*: a `try` expression's branches need not agree, because nothing checks them.
- The type names are now complete and tested (`everyValueReportsALanguageLevelTypeName`), so a
  future value family cannot be added without deciding its name.
- The `java <SimpleName>` fallback stays; it is a boundary marker, not a leak.
- **Equality and hashability are the open defect this record surfaces.** Three symptoms are
  reproducible on the current release: top-level numeric widening does not reach collection
  contents, equal numbers hash differently so a set can hold both `1` and `1.0`, and a mutated
  `data` key becomes unreachable. Fixing this means routing every comparison and hash through a
  single Oqthara-aware rule instead of `Object.equals`, and deciding whether mutable records may
  be hash keys at all. That is a semantic change and needs its own ADR; it is not folded into
  this inventory.
- A real type system and typed collection elements also remain open, and are recorded as such
  rather than quietly implied by the collection work that follows.
