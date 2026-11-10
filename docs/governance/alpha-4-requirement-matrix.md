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

**Last verified:** `c7b7fa1` (merge of PR #71), 2026-11-10.

## Language features (§14)

| Requirement | State | Evidence | Gaps |
| --- | --- | --- | --- |
| §14.1 Classes and contracts | `DONE` | PR #68 · ADR-0027 · `docs/spec/language.md` "Classes and contracts" · `ClassTest` (39 tests) · `conformance/228` | Bytecode backend refuses classes and reports `codepath: interpreter launcher`, so a built jar still runs them correctly; recorded as a parity limitation in ADR-0027 |
| §14.2 `data` evolution | `PARTIAL` | ADR-0028 · `immutable` modifier lets a `data` value be a map key or set member, which every `data` was previously refused for; `DataTest` (22 tests) · `conformance/229`; Alpha 3 semantics pinned unchanged by six of those tests; both backends enforce it at the single write path | Typed fields need a type system the language does not have; destructuring was rejected by ADR-0013 on positional-access grounds; copy-with-changes is served by the constructor the rebuild hint points at, not by a method; `data` still cannot carry methods or implement contracts |
| §14.3 Closures | `DONE` | Capture is by reference, `const` is capturable, a captured parameter outlives its call, nested functions are mutually recursive, and an outer write after a closure's declaration is visible to it. Both backends agree (`make_adder(10)(5)` is `15` and a rebound `n` reads `2` under `oq run` and under `java -jar`). Specified by [ADR-0029](../adr/0029-closures-and-modules.md) and [language.md](../spec/language.md#closures) | Lifetime is the JVM's: a closure keeps its captures alive while it is reachable and there is no way to release them. There is no `fun` expression, so the only closure is a nested declaration |
| §14.3 Modules | `DONE` | `use "oq:name"` loads `name.oq` beside the program, in `modules/` or in `lib/`; loads at most once, so `a↔b` cycles terminate; module top-level statements run before the importer's; flat namespace with a duplicate top-level name refused at the importer; module locals invisible; load depth limit 50; `oq build` splices modules so the jar is standalone. Errors keep the module's own file, line and column, and an imported module's excerpt no longer quotes the importer's source. Specified by [ADR-0029](../adr/0029-closures-and-modules.md) and [language.md](../spec/language.md#modules) | `oq build` splices modules, so a compiled jar reports a module failure against spliced line numbers instead of the module's own file and line. `use name` without the `oq:` prefix is a Java import, so a module-name typo reports `cannot find Java class` rather than a missing module |
| §15 Docstrings and generated documentation | `PARTIAL` | (1) a multi-line literal before `fun`/`data`/`class`/`contract` attaches to it as a `Docstring` with `@param`/`@return`/`@throws`/`@since`/`@author`/`@deprecated`/`@see`; (2) `MULTILINE` is its own token kind, so a literal that precedes no declaration still evaluates as a value and no existing program changes meaning; (3) unknown tags are kept under their own name rather than refused; (4) `oq doc --source <path>` renders a file or a directory walk, marks undocumented declarations `_Undocumented._`, counts them, and writes `--out`; (5) 30 tests over the tag parser, the parser attachment rule and the generator (`DocstringTest`, `DocstringParsingTest`, `DocSourceTest`); (6) `docs/spec/language.md` and ADR-0030 specify it; (7) lexer and parser fuzz targets reach unterminated and escaped triple-quoted shapes. (8) `oq-lsp` answers `textDocument/hover` with markdown holding the declaration signature and its comment, and describes a builtin from the interpreter table with the arity the call site is checked against; a position that names nothing returns null and an unparseable document resolves nothing (ADR-0031; 13 unit tests plus 1 end-to-end JSON-RPC test). **Not delivered:** signature help (parameter hints while typing arguments), a separate request with its own payload that hover does not imply; per-field and per-method documentation, since fields and methods cannot carry their own literal; `use` following in either `--source` or hover, so a module richer than the importing file is described separately; and website generation with drift detection |
| §16 JavaBridge expansion | `PARTIAL` | Each boundary behaviour was probed at the CLI, and the two that did not hold were repaired. (1) **Overload selection** ranks candidates by fit against the converted arguments, so `String.valueOf(5)`, `(2.5)` and `(true)` each reach their own overload and `Integer.valueOf(5)` and `Integer.valueOf("5")` reach different ones; a varargs match loses a tie to a fixed one. (2) **Boxing** converts `long`/`double`/`boolean`/`char` to their wrappers and the reverse, so `Integer.parseInt("42")` is `42`. (3) **Varargs** packs the tail into the declared array, so `String.format("%s-%s", "a", "b")`, `String.join`, `Arrays.asList` and `Formatter.format` all work, including with an empty tail. (4) **Exception translation** turns a Java throwable into `ClassName: message` with an Oqthara span and excerpt (`NumberFormatException: For input string: "not a number"`). (5) **Static fields** read off a class (`Integer.MAX_VALUE`, `Math.PI`, `Math.E`), while an instance field named on the class is refused. (6) **Classloader** reaches any class on the server's classpath, verified with `use dev.oqthara.core.Lexer`. Specified in `docs/spec/language.md` "Interoperability with Java" and [ADR-0032](../adr/0032-static-fields-and-varargs.md); 8 new tests in `JavaBridgeVarargsTest` and 3 in `JavaBridgeFieldTest` | **Callbacks are absent in both forms**, for two independent reasons: `list.forEach(x -> print x)` reports `type list has no method 'forEach'` because dispatch consults the Oqthara method table before Java reflection and skips it only for a `Class<?>` receiver, and `Collections.sort(xs, cmp)` reports `has no static method 'sort'` because no conversion turns a function value into a functional interface. There is no workaround, so this row does not suggest one. `new X(...)` is not syntax — the class name is the constructor. `Long.MIN_VALUE` has no literal |
| §17 Broad standard library | `PARTIAL` | Alpha 3 stdlib, Python-informed; PR #63 benchmarks every collection family. **Measured this round** rather than assumed: 89 global functions and eight method families (`list`, `map`, `set`, `frozenset`, `range`, `text`, `number`, `iteratorMethod`) across collections, text, math, files, JSON, time, HTTP, concurrency and processes. Two whole §17 domains were absent and are now delivered: encoding (`hex_encode`/`hex_decode`, `base64_encode`/`base64_decode`) and hashing (`sha256`, `sha1`, `md5`, `hmac_sha256`, `secure_random_hex`), all built on JVM providers and specified by [ADR-0033](../adr/0033-encoding-and-hashing.md); 19 tests, with `sha256("abc")`, `sha1("abc")`, `md5("abc")` and the RFC 4231 HMAC vector checked against their published values rather than against a second implementation | Still unmeasured against the Python reference, so "breadth" has no denominator. §17.2 has JSON but no CSV or XML. §17.3 has paths and files but no time-zone arithmetic or deterministic close. §17.4 has `run_command` but no logging and no argument parser. §17.5 has `http_get`/`http_post` but no URL parsing, sockets or TLS configuration. §17.6 has hashes and HMAC but no password KDF — stated as absent rather than approximated. §17.7 has concurrency but no executor or cancellation. §17.8 has no database support at all |

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
