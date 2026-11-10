# ADR-0027 — Classes and contracts: a nominal object model on top of `data`

- **Status:** accepted
- **Date:** 2026-11-11
- **Supersedes:** nothing
- **Related:** ADR-0015 (type families), ADR-0017 (equality and hashing), ADR-0025 (refusals report the call site)
- **Directive:** Alpha 4 Master Execution Directive §14.1 (classes and interfaces), §6.3 (type contracts)

## Context

`data` is the only user-defined type in Alpha 3. It generates a constructor, field access
and mutation, structural equality, a hash and a readable text form, and it deliberately has
no inheritance and no methods.

§14.1 asks for classes, constructors, instance fields and methods, visibility,
interfaces ("contracts"), implementation checking, dispatch and polymorphism — while
warning: "Do not overdesign a reflection-heavy enterprise object model. Start with the
smallest robust model that supports real Oqthara programs."

The design below is grounded in what the existing implementation actually does. Every claim
about current behaviour in this record was produced by running the released CLI, not read
off the source.

### Inventory as found

| Probe | Observed on `main` at `f8bfa60` |
| --- | --- |
| `data Point(x, y)` then `p = Point(1, 2)` | `Point(x: 1, y: 2)` |
| `type_of(p)` | `Point` |
| `p.x = 9` | mutation allowed |
| `p.nope` | `error: type Point has no field named 'nope'` + `help: Available fields: x, y` |
| `p.sum()` | `error: type Point has no method 'sum'` |
| `p.x()` | `1` — field then call, not a method |
| `fun P.norm()` | `error: expected after the function name` |
| `class Point` | `error: unexpected 'Point' after a statement` |
| `data P(y = x + 1)` before `x` exists | `error: 'x' is not defined` at the *declaration* |
| default reads a name defined later | evaluated at **construction**, so `x = 10`, `x = 20`, `P()` gives `21` |
| `data Bag(items = [])`, two instances | independent lists; a mutable default does not leak |
| `fun f(items = [])` | `error: expected to close the parameter list` — `fun` has no defaults |

Three consequences follow, and they constrain the design:

1. **`data` defaults are per-construction and late-bound.** A default is stored as a
   `Supplier` closing over the declaration `Env`, so it observes the current value of any
   name it reads. `data P(y = x + 1)` fails at the declaration only because `x` is not yet
   *defined*; it is not folded at declaration time.
2. **A method that is really a field call already works.** `p.x()` reads the field and calls
   the value, because the parser produces a `Field` inside a `Call` rather than a
   `MethodCall`. There is no `data` method dispatch to collide with.
3. **`this`, `class` and `contract` are all valid identifiers today** --- verified by
   assigning to each. Making any of them a reserved word would silently break working
   programs, so none of them becomes a keyword.

### Bytecode reality

The compiled backend is not a stub. `Ir.Function` is emitted as a real JVM method per arity,
`Ir.MakeData` builds records through `OqData.make`, and `Ir.MethodCall` routes through
`Methods`, which is the same `MethodResolver` the interpreter uses (ADR-0025's commit message
and `BytecodeCompiler.emitMethodCall`'s comment both state this).

But the backend already **refuses** rather than fakes: a function that mentions a top-level
name is rejected with "mentions a top-level variable, which the compiled backend does not
capture", and the CLI *prints the reason* (`codepath: interpreter launcher (…)`) instead of
pretending it compiled. Class methods reference `this` and therefore their receivers, so on
that precedent they are refused the same way until a later phase compiles them. That is an
explicit, visible, named limitation --- not hidden fallback, which §16 forbids.

## Decisions

### D1 — `class` is a declaration, not a keyword family

```
class Counter(value = 0)
  fun bump()
    this.value = this.value + 1
    return this.value
  end
end
```

`class` and `this` are **contextual**: they are recognised only in the positions that make
them declarations or receivers. `class = 1` and `this = 5` keep working. This mirrors the
existing treatment of `to`/`until`, which the parser recognises by lexeme rather than by
token kind.

The smallest useful model is one block shape:

```
class Name(field, field = default)
  fun method(arg, ...)
    ...body...
  end
end
```

so classes reuse the parser shape of `data` for the header and of `fun` for the body. There
are no nested classes, no `extends`, and no static members in this phase (`§14.1` says
"static members **if justified**", and nothing in the current corpus justifies them).

### D2 — Inheritance is deliberately absent

§14.1 permits inheritance "only if it remains simple and coherent with the type system". The
type system (ADR-0015) is dynamic, structural for values, and nominal only for `data` names.
Adding inheritance would introduce: a second identity notion, method-resolution order,
`super`, constructor chaining, and variance questions --- for zero demonstrated need. This
record therefore **declines inheritance** and records the decline, rather than leaving it
ambiguous. Contracts (D4) supply the polymorphism that inheritance would otherwise provide
for real programs.

### D3 — Methods, fields and `this`

- A method is looked up **on the class instance only**, after the existing `MethodResolver`
  declines. This keeps collection, text, number and `data` methods exactly where they are and
  makes class dispatch a strict addition. An instance method therefore cannot silently
  shadow `list.push`.
- Field and method names live in **one namespace per class**: declaring both `x` and `fun x()`
  is an error at declaration.
- `this` is bound in the method scope as a fixed (immutable) binding. It is not a keyword, so
  a program that already uses `this` as an ordinary name keeps working; inside a method the
  binding simply exists.
- Calling a method on `nothing` keeps the existing message and does not become a
  `NullPointerException` (PR #66's contract).
- A method that falls off the end returns `nothing`, exactly as `fun` already does.

### D4 — `contract` is the interface

```
contract Shape
  fun area()
  fun name()
end

class Circle(r) implements Shape
  fun area()
    return 3.14159 * this.r * this.r
  end
  fun name()
    return "circle"
  end
end
```

- `contract` declares method **names and arities**, with no bodies.
- `implements` is checked at **class declaration time**, not at first call, so a missing
  method is reported where the mistake is.
- Because the language has no static types, `implements` is a *checked promise*, not a
  usable type. A value is polymorphic by duck typing, as it already is for every collection
  protocol (ADR-0023). This record states that plainly instead of implying a type system that
  does not exist.
- Contracts are not inherited by `data` in this phase; `data` remains the simple record.

### D5 — Equality stays structural, and that is a decision

`data` instances compare structurally (ADR-0017). Classes are **nominal and referential**:
two `Counter` instances with equal fields are *not* equal, and `==` is identity. The reason
is that a class has methods and possibly mutable state; defining equality by fields would
make a mutable object change its hash, which ADR-0017 already records as a hazard.

`repr` follows the same split: `data` prints `Point(x: 1, y: 2)` and an instance prints
`<Counter object>`, naming the class and never its fields. A `text()` method does not change
that in this phase: printing stays a property of the value, so the same object cannot print
two ways depending on which method a class happens to define.

### D6 — Evaluation order and error behaviour

Construction evaluates arguments left to right in the **caller's** scope, then evaluates
missing-field defaults in **declaration order** in the declaration scope, then runs the
`init` body if one is declared. A failure in any step raises before a half-built instance
becomes visible, so no program observes a partially initialised object.

`init` is optional and is the only constructor body:

```
class Counter(start)
  init
    this.value = start
  end
  fun bump()
    return this.value
  end
end
```

Every field named in the header holds a value before `init` runs: the caller's argument when
one was passed, otherwise the declared default, otherwise construction fails. `init`
therefore never observes an unset field, and it **cannot introduce a new one** --- assigning
`this.total` in `init` when `total` is not in the header is an error naming the missing
field. Adding a field is a header edit, which keeps the field set readable from one place.


Declaration checks are not limited to the top level. A declaration is legal anywhere a
statement is, so a class written inside a function or a block faces exactly the same checks as
one at the top level. The first implementation validated only `program.statements()`, so a
nested class could implement a contract that did not exist, or declare the same method twice,
with no diagnostic at all. Validation now walks nested blocks before execution begins.

### D7 — Visibility is declared, not enforced by JVM tricks

§14.1 lists "visibility and encapsulation". The honest minimal answer for a dynamic
language with no module security boundary is a **single underscore convention plus a
declaration-level check**: a name beginning with `_` is private to the class, and reading or
writing it from outside raises `field '_value' of Counter is private`. This is a real, testable rule rather
than a claim about "access modifiers" the runtime cannot enforce against reflection.

The check applies to every way a field can be named, dot-assignment and dot-read alike. An
instance deliberately has **no index form**: `Counter(1)["_value"] = 99` is rejected rather
than reaching the field. The first implementation allowed that write, which was a privacy
hole because there was no matching read path to justify it; fields are named, not numbered.

### D7a — An instance carries the declaration it was built from

The first implementation registered classes in a map keyed by simple name and looked the
declaration up again at dispatch time. A class declared inside a function shadows one of the
same name at top level, so building an inner instance retargeted every existing instance of
the outer class: their methods dispatched into the inner class's bodies, and their `init` ran
in the inner class's declaration scope. Instances now bind their owning declaration at
construction and dispatch through that handle, so a nested class cannot reach an outer
instance. The registry remains for name resolution at the construction site, which is the
only place a name is meaningful.

### D8 — Java interoperability

A class instance is a host object to Java, and the existing `JavaBridge` already exposes host
objects. Two directions are in scope and are tested separately:

- **Java from Oqthara**: unchanged; `JavaBridge` handles it and PR #66 hardened the field
  read path.
- **Oqthara from Java**: a class instance is an `OqInstance` carrying its class name. It
  cannot be passed where Java requires a specific interface type, because there is no proxy
  generation in this phase. That limitation is recorded, and passing an instance to a Java
  method that accepts `Object` works.

## Rejected alternatives

| Alternative | Why rejected |
| --- | --- |
| Make `class`/`this` reserved words | Breaks programs that use those names today (measured). |
| Add `extends` | Needs method-resolution order, `super`, constructor chaining and a second identity notion, for no demonstrated need. |
| Give classes structural equality | A mutable instance would change hash on mutation; ADR-0017 already documents that hazard for `data` keys. |
| Reuse `data` for classes | `data` is a value type by contract (`Bag(items = [])` independence is pinned). Classes must be reference types with identity. |
| Dispatch class methods through `MethodResolver` | The interface has no `this` parameter, and widening it changes a published seam used by the compiler. Dispatch stays in `Interpreter`, which owns scope. |
| Compile class methods to bytecode now | `this`-capturing bodies hit the same top-level-capture refusal the backend already uses for closures. Refuse visibly, compile later. |
| Proxy-generate Java interfaces | Needs bytecode generation into the user's classpath; out of scope and not required by any current program. |

## Consequences

- `class`, `contract`, `implements`, `init` and `this` enter the parser as **contextual**
  forms. No existing program changes meaning, which the regression suite pins.
- The interpreter gains one new value type, `OqInstance`, and `Values.typeName` gains one
  case, so `type_of` reports the class name exactly as it already does for `data`.
- `data` is untouched: its defaults, independence and structural equality keep their current
  pinned behaviour.
- The compiled backend refuses `this`-capturing method bodies with a named reason and the
  CLI prints it. §16's ban on hidden fallback is honoured; §16's wish for full bytecode
  interop is not yet met and is not claimed.
- Inheritance and Java-interface proxying are recorded as declined / deferred with reasons,
  so a later reader does not mistake their absence for an oversight.
