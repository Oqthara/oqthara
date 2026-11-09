# 0013 — Destructuring binds and assigns, it does not pattern match

Status: accepted (2026-11-09, Alpha 4 Phase 8)

## Context

Alpha 3 had no destructuring at all. `a, b = [1, 2]` is a parse error ("statements end at the
end of the line"), and `(a, b) = [1, 2]` is rejected with "the left side of '=' must be a
variable, an item in a list/map, or a field". The language already has the two shapes worth
destructuring — `OqTuple` (`(1, 2)`, rendered as `(1, 2)` and reported by `type_of` as
`tuple`) and every list-like value that `Values.length` accepts — but no way to take them
apart.

The Alpha 4 directive (§11) asks for destructuring as a coherent capability, and
explicitly warns against conflating it with pattern matching. It also requires that the
semantics be specified before implementation, and that a partial write not happen after an
arity failure unless that is stated outright.

## Decision

Destructuring is a **binding operation**, not a test. A target list is a fixed-length shape
that the value must already fit; nothing about the value decides which branch runs, because
there are no branches.

### Forms

Only a bare, comma-separated target list followed by `=` is a destructuring statement.

```
a, b = pair            # rebind/define two names
const x, y = pair      # two immutable bindings
```

The parenthesised form `(a, b) = pair` is **not** destructuring. `(a, b)` is a tuple literal
whose elements are the *values* of `a` and `b`, so `(a, b) = pair` means "assign to a tuple
literal", which is as meaningless as `1 = 2` and keeps its existing diagnostic. Admitting it
would require reading `(a, b)` on the left as a target list and everywhere else as a
constructor — the same tokens with two meanings decided by position.

`a, b = 1, 2` is also not destructuring: the right side is a single expression, and `1, 2`
is a comma expression the language does not have. Wrap it: `a, b = (1, 2)`.

### Value shapes and arity

A value is destructurable when it is a tuple, list, set, range, map or text — the same set
`Values.length` already measures and `for ... in` already iterates.

- **Tuple, list, set, range:** the element count must equal the target count exactly.
- **Map:** the targets receive keys, matching `for k in map`. Values are reached through
  `map[k]`.
- **Text:** the targets receive single-character strings, matching `for c in text`.

There is no rest/starred target and no ignored target. Oqthara has no catch-all or discard
syntax to extend, and inventing `_` would be a trap: `_` is an ordinary identifier today
(`_ = 5` binds it), so giving it discard meaning would silently change the meaning of
existing valid programs. Use distinct names.

### Evaluation order and atomicity

1. The right-hand expression is evaluated once.
2. Its element count is checked against the target count.
3. Only then are any targets written, left to right.

A mismatch writes nothing. This is the requirement from §11 taken literally: a failed
destructuring must not leave half its targets updated. Checking before writing costs one
length measurement, and iteration is materialised before any binding so that a source whose
iteration is observable cannot interleave with the writes.

### Interaction with existing features

- **`const`:** each target becomes an immutable binding, with the same diagnostic as a
  single `const` (`cannot change 'x'`, reason "a 'const' binding"). Mutability is per
  statement, not per target.
- **Redeclaration:** a `const` target declares a name, so it obeys the rule a single
  `const` obeys: the name must not already exist in the current scope. Every target is
  checked before any is written, so `const a, b = (1, 2)` with `b` already declared
  refuses and leaves `a` unbound.
- **Assignment vs declaration:** `a, b = ...` follows the rule already used by `a = ...`:
  an existing mutable binding is updated; a name that is absent, or present only as a
  shadowable built-in, is defined. A `const` target therefore refuses with its own message
  rather than being silently rebound.
- **Overloads:** unaffected. Destructuring produces bindings, not callables.
- **`data`:** an `OqData` is not destructurable. Its fields are named, and positional
  access would invent an ordering the declaration does not promise. `data` values are
  taken apart by field name.
- **Java interop:** a Java `List` arriving through `use` is destructurable, since it is a
  `List`. A Java array is not, because it is not a `List` and `Values.length` does not
  measure it; the existing "cannot measure" diagnostic applies.

### Errors

Messages name the shortfall rather than the rule:

- too many targets: `cannot destructure 2 items into 3 names`, help naming the counts.
- not destructurable: `cannot destructure text`-style, reusing `type_of` vocabulary, with
  help listing the destructurable shapes.

## Consequences

The feature is small: one AST node, one parser entry point, one new IR instruction
(`DestructureParts`, which measures the value once and hands back its parts), and lowering
into the existing `Declare`/`Store` instructions. It cannot express pattern matching, guards
or alternatives, and extending it to them would be a separate feature with its own
specification — which is the boundary §11 asks for.

The rule itself lives in exactly one place. `Values.destructuringParts(value, count, span)`
both decides what is destructurable and enforces the count, and it is the function the
interpreter calls and the function compiled code calls through `Collections.destructure`.
That is deliberate: a duplicated rule is a rule that can drift, and the failure mode would
be a program the interpreter accepts and the jar rejects. Compiled code is atomic for the
same reason the interpreter is — the helper throws before returning any parts, so no
`Declare` or `Store` has run when the count is wrong.
