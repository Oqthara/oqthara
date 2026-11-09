# 0016 — Iteration walks a snapshot

Status: accepted (2026-11-09, Alpha 4 Phase 8)

## Context

A loop that changed the collection it was walking killed the program:

```
xs = [1, 2, 3]
for x in xs
  xs.add(x * 10)
end
```

```
internal error: ConcurrentModificationException: null
  This is a bug in Oqthara. Please report it.
```

Exit code 70. The same program compiled to a jar behaved identically, so this was a
property of the runtime rather than of either backend. `.map`, `.filter` and the
other callback methods reproduced it whenever the callback touched the receiver.
The message told the user to report a bug that they had caused.

The cause was one line. `Values.iterable` returned the caller's own live
collection:

```java
if (value instanceof List<?> l) return l;
if (value instanceof Set<?> s) return s;
if (value instanceof Map<?, ?> m) return m.keySet();
```

Both backends go through that function — the interpreter's `ForEach` and
`Collections.iterator` for compiled code — so `java.util`'s fail-fast check fired
from inside a frame that had no Oqthara diagnostic to give. `OqError` is the only
exception type a handler can catch, and `EmbeddedMain.renderFailure` renders every
other `RuntimeException` as an internal error, so the failure was not merely ugly:
it was uncatchable by the program.

Ranges and text were already immune. `OqRange` was converted with `toList()` and
text was already expanded into a fresh list of characters.

## Decision

`Values.iterable` returns a fresh list — a snapshot — for every collection type.
A loop visits exactly the items that were present when it started.

The alternative was to refuse the mutation: detect that a collection is being
iterated and raise an `OqError` when it changes. That was rejected on three
grounds. The runtime cannot attribute a mutation to a particular loop, so a legal
second loop over the same list would be punished for the first one's existence. It
turns working programs into errors, which is a breaking change with no safety
benefit. And the snapshot rule is already what the language did for ranges and
text, so it makes the four container types behave the same way instead of
introducing a second rule.

Snapshotting does not make concurrent access safe. Two tasks writing the same list
still race; this decision is about the shape of a single loop, not about
synchronisation, and the spec says so.

## Consequences

- `xs.add(x)` inside `for x in xs` appends and terminates. The loop visits the
  original three items.
- Removing every key of a map while looping over it empties the map and finishes.
- A loop is O(n) in the number of items it visits regardless of what the body does,
  so there is no new way to hang.
- Callback methods snapshot the receiver too: `map`, `filter`, `each`, `any`, `all`,
  `find`, `find_index`, `reduce`, `flatten`, plus `map.each` and `map.map` over
  `entrySet()`.
- A loop over a large list copies it. This is one allocation per loop, not per item,
  and the language already copied for ranges and text.

## Separate defects found by the same probe

Two more internal errors surfaced while checking this one, and they are fixed here
because they share the root cause: an unchecked cast where a real check belonged.

- `zip({1, 2}, {3, 4})`, `random_item({1})` and `par_map({1, 2}, f)` cast the result
  of `Values.iterable` straight to `List`. A set is not a list, so each threw
  `ClassCastException`. `Values.iterableList` now states the list-ness as a fact of
  the API instead of an assumption at the call site.
- `[1, 2].reduce(0, f)` — the arguments in the wrong order, since the call shape is
  `reduce(fn, start)` — cast `0` to `OqCallable`. It now reports
  `'reduce' needs a function as argument 1` with the help
  `It got number. Write reduce(fn, start).`
- `const x = 1` followed by `const x = 2` in one scope silently replaced the binding. Both
  `const` declarations, plain re-declarations, and `const a, b = ...` — which declares names
  too — are now refused with `'x' is already declared in this scope`, on both backends. This
  was not found by probing iteration; it surfaced while the language state machine was being
  checked against the real implementation, which is the reason that model cites diagnostics
  rather than paraphrasing them.

## Tests

`ErrorMessageContractTest` covers iteration mutation directly, inside callbacks, the
three collection builtins that used to crash on a set, and the wrong-argument-type
diagnostics. `BytecodeCompilerTest.mutationDuringIterationMatchesTheInterpreter`
asserts the interpreter and the compiled jar produce the same output for the same
program.
