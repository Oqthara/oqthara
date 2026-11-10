# Alpha 4 requirement traceability matrix

Directive §4.3 makes this matrix a principal-agent responsibility: every Alpha 4 requirement in
the master execution directive must have a row, and every row must point at the evidence that
satisfies it.

This matrix is maintained by hand and describes **only what the repository actually contains**.
A row moves to `DONE` when the evidence exists on `main`; nothing is marked done because it was
planned, attempted, or believed to work. Where a requirement is partly met, the row says which
part is missing rather than rounding up.

Legend: `DONE` evidence merged and validated · `PARTIAL` some acceptance criteria unmet ·
`NOT STARTED` no implementation · `BLOCKED` waiting on something outside the work itself.

**Last verified:** `d75f2c1` (merge of PR #70), 2026-11-10.

## Language features (§14)

| Requirement | State | Evidence | Gaps |
| --- | --- | --- | --- |
| §14.1 Classes and contracts | `DONE` | PR #68 · ADR-0027 · `docs/spec/language.md` "Classes and contracts" · `ClassTest` (39 tests) · `conformance/228` | Bytecode backend refuses classes and reports `codepath: interpreter launcher`, so a built jar still runs them correctly; recorded as a parity limitation in ADR-0027 |
| §14.2 `data` evolution | `PARTIAL` | ADR-0028 · `immutable` modifier lets a `data` value be a map key or set member, which every `data` was previously refused for; `DataTest` (22 tests) · `conformance/229`; Alpha 3 semantics pinned unchanged by six of those tests; both backends enforce it at the single write path | Typed fields need a type system the language does not have; destructuring was rejected by ADR-0013 on positional-access grounds; copy-with-changes is served by the constructor the rebuild hint points at, not by a method; `data` still cannot carry methods or implement contracts |
| §14.3 Closures | `DONE` | Capture is by reference, `const` is capturable, a captured parameter outlives its call, nested functions are mutually recursive, and an outer write after a closure's declaration is visible to it. Both backends agree (`make_adder(10)(5)` is `15` and a rebound `n` reads `2` under `oq run` and under `java -jar`). Specified by [ADR-0029](../adr/0029-closures-and-modules.md) and [language.md](../spec/language.md#closures) | Lifetime is the JVM's: a closure keeps its captures alive while it is reachable and there is no way to release them. There is no `fun` expression, so the only closure is a nested declaration |
| §14.3 Modules | `DONE` | `use "oq:name"` loads `name.oq` beside the program, in `modules/` or in `lib/`; loads at most once, so `a↔b` cycles terminate; module top-level statements run before the importer's; flat namespace with a duplicate top-level name refused at the importer; module locals invisible; load depth limit 50; `oq build` splices modules so the jar is standalone. Errors keep the module's own file, line and column, and an imported module's excerpt no longer quotes the importer's source. Specified by [ADR-0029](../adr/0029-closures-and-modules.md) and [language.md](../spec/language.md#modules) | `oq build` splices modules, so a compiled jar reports a module failure against spliced line numbers instead of the module's own file and line. `use name` without the `oq:` prefix is a Java import, so a module-name typo reports `cannot find Java class` rather than a missing module |
| §15 Docstrings and generated documentation | `NOT STARTED` | — | Multiline docstrings, metadata tags, `oq doc` integration and drift detection absent |
| §16 JavaBridge expansion | `PARTIAL` | PR #64 tests the Java boundary for every collection family; PR #66 fixes field reads on `nothing` | Overload selection, boxing rules, varargs, callbacks, exception translation and classloader boundaries are not specified |
| §17 Broad standard library | `PARTIAL` | Alpha 3 stdlib, Python-informed; PR #63 benchmarks every collection family | Breadth targets against the Python reference are not measured, so completeness is unknown |

## Collections (§9)

| Requirement | State | Evidence | Gaps |
| --- | --- | --- | --- |
| §9.7 collection acceptance criteria | `DONE` | `docs/governance/alpha-4-9-7-collection-evidence.md` · PRs #61, #63, #64, #67 | — |

## Governance (§4)

| Requirement | State | Evidence | Gaps |
| --- | --- | --- | --- |
| §4.3 requirement traceability matrix | `DONE` | This document | — |
| §4.1 workstream assignment | `NOT STARTED` | `.github/agents/*.agent.md` define six roles | The twelve workstreams in §4.1 are not mapped onto those roles |
| Risk register | `NOT STARTED` | — | Directive §4.1 requires it; no register exists |
| Threat model | `NOT STARTED` | — | Workstream 10 requires it; no model exists |
| §4.2 independent review roles | `BLOCKED` | `docs/governance/remediation/solo-maintainer-exception.md` | One human maintainer; independent human review is unavailable and is never claimed |

## Ordering

`§10` overload dispatch (PR #44) landed before `§14`, so the matrix records it as delivered
out of the directive's sequence. This is a note about order, not a defect: overload selection
was a prerequisite for method dispatch in classes, and building it first avoided a rewrite.

## How a row is closed

A row may move to `DONE` only when all of the following hold on `main`:

1. The implementation is merged through a protected pull request with all required checks green.
2. The behaviour is pinned by a test that has been shown to fail without the change.
3. The specification and any ADR describe the behaviour as implemented, not as intended.
4. The conformance baseline has been re-measured rather than adjusted by hand.

Anything less is `PARTIAL`, and the row says what is missing.
