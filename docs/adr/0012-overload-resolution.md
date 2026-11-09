# ADR-0012: Deterministic `fun` overloading by arity

- Status: accepted
- Date: 2026-11-09
- Supersedes: the implicit "last declaration wins" behaviour of `fun`

## Context

Oqthara's `fun` parameters carry no type annotations:

```
fun area(side)
  return side * side
end
```

There is no syntax for `fun area(side: Number)`. Before this ADR, two declarations
with the same name did not form an overload set at all. `Interpreter.hoist` called
`Env.define` for each `FuncDecl`, so the second declaration *replaced* the first.
A program that declared `area(a)` and `area(a, b)` lost `area(a)` entirely, and
calling it reported:

```
error: area() expects 2 arguments, got 1
```

That message describes a function the author can still see in their source. The
real defect — that the one-argument function no longer exists — is invisible. This
is silent data loss, not a missing feature, so it is a correctness fix rather than
an enhancement.

`dev.oqthara.runtime.OverloadedFunction` existed but was used only for built-ins,
and its `call` implementation chose the first candidate whose arity matched:

```java
for (OqCallable candidate : candidates) {
    if (candidate instanceof NativeFunction function && function.accepts(args.size())) {
        return function.call(host, args, span);
    }
}
return candidates.get(0).call(host, args, span);
```

The fall-through to `candidates.get(0)` would invoke an unrelated function when no
candidate matched, and the loop itself was order-dependent. Both are prohibited.

## Decision

**Signature identity is (name, parameter count).** Two `fun` declarations form an
overload set exactly when they share a name and differ in parameter count.

### Why arity and not types

Types cannot participate in signature identity because the language has no
parameter type syntax and no static type annotations on bindings. Oqthara is
dynamically typed; a parameter's type is not known until a call arrives with an
argument. Ranking candidates by runtime argument type would make dispatch depend on
values rather than on the program's declarations, which breaks the requirement that
resolution be deterministic and independent of declaration order — two candidates
can accept the same arity, and their types are only discoverable per call.

Return type is not a discriminator, as required.

Arity-only overloading is also what the JVM backend can express natively: method
descriptors already encode parameter counts, so `area` with one parameter and
`area` with two are distinct JVM methods in the same class. No dispatch table, name
mangling or runtime lookup is needed in compiled code, which is what keeps
interpreter and bytecode semantics identical without duplicating resolution logic.

### Resolution

For a call with `n` arguments:

1. Collect every `fun` in scope with the called name whose parameter count is `n`.
2. If exactly one candidate remains, call it.
3. If none remains, report a no-match error listing the arities that do exist.
4. If more than one remains, report an ambiguity error naming the candidates.

Step 4 cannot occur for user functions under this rule, because parameter count is
unique within an overload set — two same-name functions with the same arity are a
duplicate declaration, reported at registration (see below) rather than at a call.
The ambiguity path still exists in the code and is tested, because built-ins are
registered in the same namespace and a built-in can share a name and arity with a
user function.

### Duplicate declarations

Declaring the same name with the same parameter count twice is an error at
declaration time, not a silent replacement:

```
fun f(a)
end
fun f(b)
end
```

This is a duplicate signature. Reporting it at the declaration keeps the failure
next to the line that caused it, instead of surfacing later at a confusing call
site.

### Scope and shadowing

An overload set lives in one scope. A nested `fun` with a name already visible in
an enclosing scope shadows the entire outer set rather than merging with it, so
adding a local function cannot change the meaning of an existing outer call.

### Numeric widening and conversions

None. Oqthara performs no implicit argument conversion at a call boundary, so there
is no conversion ranking to define. `area(2)` and `area(2.5)` both pass one
argument and dispatch to the same candidate; arithmetic inside the body applies the
language's existing numeric rules.

### Defaults, named arguments, variadic parameters

Not supported, so none participate in resolution. If any is added later, this ADR
must be revised before they affect dispatch, because adding a default or a variadic
tail makes parameter count non-unique and reintroduces the ranking problem that
arity-only resolution avoids.

### Generics

Oqthara has no generic functions. Nothing to infer.

### Recursion and calls within an overload set

A call inside a body resolves against the same set as a call outside it, so
`area(3)` inside `area(a, b)` reaches the one-argument function. Mutual recursion
across arities works because all functions in a block are hoisted before the block
runs.

### Function values and lambdas

An overload set is a value that can be called, not a first-class iterable of
functions. Passing `area` as a value and calling it through a variable resolves by
arity at the call, exactly as a direct call does. Lambdas are never overloaded;
they have a fixed signature.

### JavaBridge

Java methods already form their own overload sets, resolved by `JavaBridge` using
JVM rules including varargs. This ADR does not change Java interop. An Oqthara
`fun` and a Java class with the same name remain separate namespaces, as they are
today.

## Consequences

- A same-name declaration pair with different arities now works instead of
  silently discarding the first.
- A same-name declaration pair with the same arity now fails loudly instead of
  silently discarding the first.
- Compiled code needs no dispatch changes: JVM descriptors already differ by arity.
- `OverloadedFunction.call`'s first-match loop and `candidates.get(0)` fallback are
  removed, because both violate determinism.
- Type-based overloading remains unavailable and would require a separate decision
  to add parameter type annotations, which this ADR does not propose.
