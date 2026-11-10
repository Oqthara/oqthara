# 0029 — Closures and modules

Status: accepted (2026-11-10, Alpha 4 Phase 14.3)

## Context

Alpha 3 shipped closures and modules without a specification for either. §14.3 asks for
lexical scoping, captured variables and constants, lifetime, recursion, imports, module
initialization, cycles, visibility and error reporting. Probing the Alpha 3 CLI found that
almost all of it already worked and none of it was written down.

The risk this ADR addresses is not that the behaviour is wrong. It is that behaviour nobody
documented is behaviour nobody can rely on, and a later change can silently break it. The
work here is therefore mostly recording what is true, with one defect fixed along the way.

## What was already true

Every claim below was reproduced against `oq 0.1.0-alpha.3` before it was written down.

| Property | Observed |
|---|---|
| Nested `fun` captures an enclosing local | `make_adder(10)` returns a function; `a(5)` is `15` |
| The capture is by reference, not by value | A counter closure increments from `1` to `2` across calls |
| An outer assignment is visible to a function that captured it | `n = 0`; `bump()` twice; `n` is `2` |
| A function sees a rebinding that happens after its declaration | `n = 1`; `fun f()` returns `n`; `n = 2`; `f()` is `2` |
| A `const` is capturable | `const LIMIT = 3` used inside `fun over(n)` |
| A captured parameter outlives the call that made it | `box("kept")()` is `"kept"` |
| Nested mutual recursion resolves | `even` and `odd` declared in one `fun` body call each other |
| Modules import functions and `data` types | `use "oq:helper"` then `twice(3)` and `Point(1, 2)` |
| A module loads once, however many times it is imported | A diamond import prints the module's top-level output once |
| A cycle terminates | `a` imports `b`, `b` imports `a`; both functions are callable |
| Module top-level statements run before the importer's | The module's `print` appears first |
| Module locals are not visible to the importer | `hidden` inside a module function is unbound in the main program |
| A duplicate top-level name is refused | `'twice' is already defined with 1 parameter` |
| `oq build` embeds modules | A jar built from a program with `use "oq:helper"` runs standalone |

Both backends agree: the closure and counter programs produced identical output from
`oq run` and from `java -jar`.

## Decision

**Closures are lexically scoped and capture by reference.** A nested `fun` sees the
enclosing binding, not a snapshot of its value, because that is what the two-definition
probe showed and because capture-by-reference is the only rule under which `const` and
`var` differ for the right reason: a `const` cannot be reassigned, so capturing one is
observationally identical to capturing its value.

**Lifetime is the JVM's.** A captured binding stays alive while any closure that captured
it is reachable. No reference counting, no explicit free, no cycle collector of our own.
This is a consequence of running on the JVM and is stated rather than implemented.

**Modules are source inclusion with a load-once rule.** `use "oq:name"` resolves
`name.oq` next to the program, in `modules/`, or in `lib/`, parses it, and splices its
top-level statements before the importer's. Functions and `data` types are hoisted, so
order within the merged program does not matter. A module is loaded at most once per run;
a repeated import is a no-op, which is what makes diamond imports safe.

**Cycles are allowed and terminate.** The import set is a `LinkedHashSet` keyed by the
normalized absolute path, so the second sighting of a file is skipped rather than
recursed into. A cycle therefore loads each file once, in first-visit order.

**Visibility is one flat namespace.** Modules contribute functions and types; they do not
contribute locals, and they cannot hide a declaration from the importer. There is no
`private`, no export list and no qualified name. A name collision is an error at the
importer, naming the function and its arity.

**Error reporting keeps the module's own position.** A failure inside a module reports the
module's file, line and column. It does not report a position in the main program, because
the failure is not there.

## The defect

The renderer quoted the *main program's* source line under a *module's* position:

```
error: type number has no method 'nope'
  --> boom.oq:2:11
   2 | print explode(1)
                 ^
```

`boom.oq` line 2 holds the offending expression; the line shown was `m5.oq` line 2. The
position was right and the excerpt was from a different file, which is worse than showing
no excerpt: it looks like evidence and is not.

`Details.render` now takes the name of the file the retained source came from and draws the
excerpt only when it matches the span's file. When it does not match, the position line
still names the real module and the rest of the diagnostic is unchanged. Programs with a
single file are unaffected, since name and span always agree there.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Capture by value | Would make the counter closure print `1` twice, contradicting Alpha 3 and every language a user is likely to arrive from |
| Deep-copy captured values at closure creation | Same output difference as above, plus a cost on every closure for a case nobody asked for |
| Qualified module names (`helper.twice`) | A larger language change than §14.3 asks for; flat inclusion is what Alpha 3 does and what the tests already pin |
| Explicit export lists | Same scope concern; and with a single flat namespace, "export everything" is already the rule |
| Eager rejection of import cycles | Alpha 3 terminates correctly on them, so rejecting would be a regression; mutual recursion across two files is legitimate |
| A `Module` object with methods | Would give modules identity, which invites mutable module state; §14.3 explicitly asks to avoid dynamic global state |
| Suppressing the whole diagnostic for module errors | Loses the position, which is the one thing the user needs |

## Consequences

- Nested functions are the only way to make a closure; there is no `fun` expression, so a
  closure always has a name at its definition site.
- A captured mutable binding is shared, so two closures over the same variable see each
  other's writes. This is intentional and now documented.
- Module initialization order is dependency order, then declaration order within a file.
  A module whose top-level code prints will print once, before the importer's code.
- `oq build` flattens modules into one source, so a built jar does not need the `.oq` files
  at run time. Line numbers of the main program are preserved; module lines are not, so a
  compiled jar reports module positions against a shifted line. This is a known cosmetic
  limitation of flattening and is recorded here rather than fixed, because a correct fix
  needs a source map rather than a splice.
- `use "oq:name"` is the only module syntax. The bare `use name` form is a Java class
  import and will report `cannot find Java class 'name'` when the file does not exist,
  which is a confusing message for a module typo. Recorded as a residual risk; the repair
  is a message change, not a semantics change.
