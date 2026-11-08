# Alpha 3 engineering roadmap

Target release: **0.1.0-alpha.3**. Owner: `oq-hive-orchestrator`. Task
`OQ-TASK-ALPHA3-001`.

Inputs: the Alpha 3 engineering directive, the
[baseline audit](audit/baseline-2026-11.md), the implementation, and the
[six Hive agent definitions](../../.github/agents/). This road-map is a decision record, not
a proposal: the points below are settled and the sections that follow execute them.

## 1. Target version

`0.1.0-alpha.3`. The directive fixes this value, so no range is negotiable here. Four
canonical declarations exist and one is already stale; see the version work in §9.

## 2. Goals

1. **Ship tuples** — a complete, specified, tested, immutable value type that is
   unambiguously not a list, from source syntax through the interpreter and the bytecode
   backend.
2. **Close the contract gaps** the baseline audit reported as HIGH — operator precedence and
   associativity, and the four reachable features that no document specifies.
3. **Bound the unbounded** — make the execution step budget actually cover the paths that
   escape it, and prove it with regression tests.
4. **Test the dangerous surface** — the HTTP and process builtins, so the security posture is
   a measured claim rather than an assumption.
5. **Make the version identity true** in every place it is stated.

## 3. Non-goals

Explicitly out of scope for Alpha 3. Each is either a structural decision that needs
governance this cycle does not have a mandate for, or work that would inflate the change
past the point where it can be verified.

| Non-goal | Why not now |
| --- | --- |
| Destructuring and pattern matching | The directive lists them as an interaction to *evaluate*, not to build. Named-tuple destructuring is a second feature with its own grammar; see `OQ-DEC-2026-006` for the deferral. |
| Named tuple fields, tuple types in a static type system | Oqthara has no static type checker; adding one to support tuples would be a language redesign, not a feature. |
| Structuring `serve` | `serve` is listed in a checker whitelist but is not implemented. Removing one stale regex is cheap; implementing a server is not an Alpha 3 feature. |
| Retiring or merging `.kilo/agents/` | `OQ-DEC-2026-002` and the directive both defer this to a human decision. |
| A sandbox for host access | Out of scope. What Alpha 3 does instead is *stop claiming* safety and *measure* the real behavior. |

## 4. Mandatory tuple feature scope

### 4.1 Syntax

Parentheses already mean grouping, and lambda parameter lists already use them. Tuples use
the same character with a comma as the disambiguator, which keeps the lexer untouched and
adds no keyword:

| Form | Meaning |
| --- | --- |
| `(a)` | grouping — the inner expression, no tuple |
| `()` | error: empty tuples are not part of the language |
| `(a,)` | one-element tuple |
| `(a, b)` | two-element tuple |
| `(a, b,)` | two-element tuple; a trailing comma is always allowed |
| `((a, b), c)` | nested tuple; the inner `(a, b)` is itself a tuple |
| `(a, b) -> ...` | still a lambda; the lambda lookahead runs first |

A trailing comma is *required* only for the one-element form, because `(a,)` is the only
spelling that cannot be confused with grouping. Newlines are allowed after a comma, as they
already are in list and set literals.

### 4.2 Semantics

- **Immutable.** A tuple's length and elements are fixed at construction. `xs[0] = 1` on a
  tuple is an error, and every mutating method the list surface exposes is refused with a
  message that names the tuple as immutable.
- **Indexable and iterable.** `t[0]`, `t[-1]`, `length(t)` and `for x in t` all work,
  because those are reads.
- **Equality is structural and order-sensitive.** `(1, 2) == (1, 2)` is `true`;
  `(1, 2) == (2, 1)` is `false`. A tuple never equals a list, even with identical elements,
  because the types differ and the language contract says so.
- **Comparison is lexicographic** where the elements are comparable, matching the existing
  list rule, so `<` and sort orders stay consistent.
- **`in` on a tuple is membership**, not string containment.
- **`type_of` reports `tuple`**, and `repr` prints `(1, 2)` with the trailing comma for the
  one-element form so the rendering round-trips through the parser.
- **Not a list.** `[1, 2]` stays a mutable list; `(1, 2)` is an immutable tuple. `in` on a
  list is membership and stays so. Concatenation and the mutating list methods are not part
  of the tuple surface.

### 4.3 Deferred capabilities

Deferred, stated plainly so nobody discovers it by experiment:

1. Destructuring — `a, b = (1, 2)` — is not supported in Alpha 3.
2. ~~Tuples are not compiled to bytecode~~ — done: `oq build` emits a real tuple type, so a
   built jar matches `oq run`.
3. No named fields and no tuple type annotations, because there is no static type system.
4. No tuple `+`, `*`, or repetition operators; those stay list-only and raise `cannot add a
   tuple` rather than quietly returning a list.

## 5. Specification work

| Item | Detail |
| --- | --- |
| Precedence and associativity | All ten levels, read out of `Parser.java` rather than from convention, with each level's associativity stated and each documented. |
| `in` | Formalized as a comparison-level operator, left-associative, with membership semantics. |
| `write` | Formalized: like `print` but with no trailing newline. |
| Compound assignment | `+=`, `-=`, `*=`, `/=` formalized as `x = x <op> y` with one evaluation of the target. |
| `format` | Formalized as an alias of `text`-style interpolation builtin, with its exact arity. |
| Tuples | Syntax, semantics, immutability, the list distinction, and the deferral list above. |
| Version header | Corrected, and made checkable by the docs checker so it cannot drift again. |

## 6. Implementation work

| Layer | Change | Complexity |
| --- | --- | --- |
| `oq-runtime` | New immutable tuple value; `Values` type name, repr, equality, comparison, contains and index handling | Medium |
| `oq-core` AST | One new expression node | Low |
| `oq-core` parser | Tuple parsing inside the existing parenthesized-expression path, after the lambda lookahead | Medium |
| `oq-core` interpreter | Evaluate the node; refuse tuple writes with a clear diagnostic | Low |
| `oq-compiler` | IR instruction, lowering, scope-freedom, scratch-slot planning, bytecode emission | High |
| `oq-stdlib` | Method resolution over tuples: reads allowed, mutators refused | Low |

The compiler item is the expensive one: it is the only layer where a new value shape has to
be threaded through slot planning and collection construction. It is worth doing because a
tuple that only works in the interpreter would violate the interpreter/compiler consistency
requirement.

## 7. Test and conformance work

Unit, integration and conformance coverage for: literals, singleton, nested, tuples in
variables, as parameters, as return values, the tuple/list distinction, immutability errors,
parentheses ambiguity, precedence (every level), associativity (explicit left and right),
compound assignment, and interpreter/compiler equivalence. Plus security tests for the HTTP
and process builtins and execution-budget regression tests.

The conformance invariant is preserved: exactly one expectation per program, and no existing
case is weakened.

## 8. Security work

The baseline audit reported three unbounded paths and two untested host builtins. Alpha 3
does four things and, deliberately, does not do a fifth:

1. Bound `print` output so a program cannot emit unbounded bytes.
2. Bound file reads so `read` cannot load an arbitrarily large file into memory.
3. Give `run_command` a timeout, so a program cannot hang forever.
4. Add tests for `http_get`, `http_post` and `run_command` covering malformed input, empty
   input, oversized input and refusal cases.
5. **Not** sandbox anything. No claim of a sandbox is made because none exists; the
   documentation says the truth about what these builtins can reach.

## 9. Documentation work

- The roadmap (this file), the tuple decision record, and a decision record for the
  precedence table.
- `docs/spec/language.md`: the five entries in §5 plus tuples.
- `CHANGELOG.md`: an `0.1.0-alpha.3` section.
- Version declarations synced: `build.gradle.kts`, `Main.VERSION`, the LSP server's
  advertised version (currently `0.1.0-alpha.1`), the spec header (currently
  `0.1.0-alpha.1`), and the changelog.
- The docs checker extended to compare the spec header against `Main.VERSION`, closing the
  blind spot that let the two drift.
- `docs/catalog.json` regenerated for every new document.

## 10. Compatibility strategy

Alpha 3 is **additive at the language level and honest about the one incompatibility it
creates**:

- Every existing program keeps its meaning. No precedence level moves, no operator is
  re-spelled, no existing literal changes type.
- `(a)` remains grouping. Only the comma changes a parenthesized expression into a tuple, so
  no existing program becomes a tuple by accident.
- The four newly documented features were already reachable and already behaved this way;
  documenting them removes ambiguity rather than changing behavior.
- **The one breaking surface is a name that previously resolved to something else.** The
  tuple type name is new, so `type_of` on a new value reports a new string; no existing
  value's type name changes.

## 11. Risks

| Risk | Likelihood | Impact | Mitigation |
| --- | --- | --- | --- |
| Tuple/list confusion in the value model because both are `List`-shaped | Medium | High | A distinct immutable type, and explicit conformance cases that assert a tuple never equals a list |
| Compiler and interpreter disagree on tuple rendering | Medium | Medium | Equivalence tests that run the same program both ways |
| Parentheses ambiguity mis-parses an existing program | Low | High | Full regression suite; `(a)` covered by an explicit case |
| Step-budget caps break a legitimate program | Medium | Medium | Caps are generous and documented, with tests at the boundary |
| Security tests that touch the network become flaky | High | Low | Tests bind to loopback or assert refusal without requiring egress |

## 12. Dependencies between tasks

```
spec (tuples + precedence + four features)
  ├── implementation (runtime → core → compiler → stdlib)
  │     └── tests (unit, conformance, equivalence)
  ├── version sync ── docs checker
  └── security tests (independent of tuples)
review ── after all of the above
```

Implementation depends on the spec because the spec names the semantics. Tests depend on
implementation. Review depends on everything. Security and version work are independent and
can land on their own.

## 13. Acceptance criteria

1. The tuple contract is documented, including what is deferred.
2. `(1, 2)`, `(1,)`, nested tuples, tuple parameters and tuple returns all execute.
3. A tuple is observably distinct from a list: different type name, different rendering,
   never equal.
4. Tuple immutability is enforced with a clear error, not silently ignored.
5. All ten precedence levels and their associativity are documented and covered by tests.
6. `in`, `write`, compound assignment and `format` each have a normative disposition.
7. The three unbounded execution paths are bounded, with regression tests.
8. HTTP and process builtins have security tests.
9. Every version declaration says `0.1.0-alpha.3`, and the checker enforces it.
10. `./gradlew build` passes, coverage stays at or above 80% on `dev/oqthara/core`, and both
    docs checkers pass.

## 14. Agent ownership

| Work | Owner |
| --- | --- |
| Tuple syntax, precedence, spec | `oq-hive-language-syntax` |
| Runtime, AST, parser wiring, compiler | `oq-hive-development` |
| Conformance, security, budget tests | `oq-hive-testing-security` |
| Decision records, roadmap, coherence | `oq-hive-architecture-governance` |
| Independent review | `oq-hive-review-escalation` |
| Integration and final report | `oq-hive-orchestrator` |

## 15. Estimated technical complexity

| Task | Complexity |
| --- | --- |
| Tuple syntax and parser disambiguation | Medium |
| Tuple value model and immutability | Medium |
| Compiler tuple support | High |
| Precedence and associativity documentation | Low |
| Four undocumented features documentation | Low |
| Step-budget bounds | Medium |
| HTTP and process security tests | Medium |
| Version sync and checker | Low |

No calendar estimates: complexity is a property of the change, schedule is a property of the
week, and only the first is something a technical document can honestly assert.
