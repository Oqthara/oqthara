# Alpha 4 risk register

Directive §4.1 requires a maintained risk register. This is it. Every entry traces to something
the repository already says about itself — an ADR, a gap column in the traceability matrix, a
limit stated in the specification, or a workflow that does a specific thing. Nothing here is
invented to fill a row.

This closes the `Risk register` row of
[`alpha-4-requirement-matrix.md`](alpha-4-requirement-matrix.md).

## How to read an entry

| Field | Meaning |
|---|---|
| ID | Stable. `R-001` is never reused and never renumbered. Superseded entries keep their ID and say so. |
| Likelihood | `High` — expected to happen, or already has. `Medium` — plausible within the next two Alphas. `Low` — requires an unusual combination. |
| Impact | `High` — a user-visible wrong answer, a security failure, or a broken release. `Medium` — a limitation users must work around. `Low` — documentation or ergonomics. |
| Exposure | Derived, never assigned by hand. The matrix is below. |
| Workstream | The number from [`alpha-4-workstream-assignment.md`](alpha-4-workstream-assignment.md). |
| Status | `Open` — no mitigation beyond what is described. `Mitigated` — a control exists and is evidenced. `Accepted` — deliberately not mitigated, with a reason recorded in an ADR. |

### Exposure matrix

| Likelihood \ Impact | Low | Medium | High |
|---|---|---|---|
| **High** | Low | Medium | **Critical** |
| **Medium** | Low | Medium | High |
| **Low** | Low | Low | Medium |

## The register

### Governance

| ID | Risk | Likelihood | Impact | Exposure | Workstream | Status |
|---|---|---|---|---|---|---|
| R-001 | Single-human-maintainer governance with correlated AI review | High | High | **Critical** | 10 | Accepted |

**R-001 — Single-human-maintainer governance with correlated AI review.**
The repository has one human maintainer, and `required_approving_review_count` was lowered from 1
to 0 on ruleset `26650412` because a second approver does not exist. Every Hive role pins the same
custom-endpoint model, `deepseek-ai/deepseek-v4.1-flash-1017`, so six role names are six prompts
against one endpoint. Review is therefore correlated: a blind spot shared by the model is shared
by the reviewer, and the reviewer is the thing that was supposed to catch it. The second GitHub
account `JoaoGaValentim` belongs to the same person and does not constitute independence.

*Mitigation.* Everything a single maintainer can satisfy honestly remains enforced: pull requests
required, squash-only merges, no bypass actors, 12 required status checks with
`strict_required_status_checks_policy`, required review-thread resolution, stale-review dismissal
on push. Review is supplemented by machine evidence that does not depend on opinion — JaCoCo
`coverageGate` at 80% for `dev/oqthara/core` (`build.gradle.kts`), the interpreter-versus-jar
conformance comparison in `scripts/verify-conformance.py`, eight fuzz targets, and CodeQL on a
weekly schedule (`.github/workflows/codeql.yml`).

*Why accepted rather than mitigated.* The alternative is fabricated review. The exception record
states both failure modes explicitly, and choosing the honest one is the mitigation.

*Evidence.* [`remediation/solo-maintainer-exception.md`](remediation/solo-maintainer-exception.md)
· [`README.md`](README.md) OQ-DEC-2026-005 · `.github/agents/*.agent.md` · `build.gradle.kts`
(`coverageGate`).

### Compiler and runtime parity

| ID | Risk | Likelihood | Impact | Exposure | Workstream | Status |
|---|---|---|---|---|---|---|
| R-002 | Bytecode backend and interpreter diverge | Medium | High | High | 4 | Mitigated |
| R-003 | Module errors in a compiled jar report spliced line numbers | High | Low | Low | 4 | Accepted |
| R-004 | Compiled bytecode has no step budget, so only the heap bounds it | Medium | Medium | Medium | 4 | Accepted |
| R-029 | A compiled program's `print` is not atomic, so concurrent writes fuse lines | Medium | Medium | Medium | 4 | Mitigated |
| R-030 | A function argument can bind to an overload that cannot take code, so a callback silently never runs | Medium | High | High | 4 | Mitigated |
| R-005 | Compiler slot and constant-pool ceilings refuse large functions | Low | Low | Low | 4 | Accepted |

**R-002 — Bytecode backend and interpreter diverge.**
Two implementations of one contract is the project's largest structural risk. The compiled backend
is deliberately smaller than the language: capturing closures, `use`, modules as separate
artefacts and every host-dependent builtin are refused by name. A refusal is safe; a *silent*
difference is not, and the refusal list is large enough that a widening change could compile
something that used to be interpreted differently.

*Mitigation.* The interpreter is normative and the bytecode backend must either agree or refuse —
rule 2 of `oq-hive-development`. The measured subset is pinned: `214 of 223` executable
conformance programs compile and produce byte-identical output, asserted by
`BytecodeCompilerTest.everyCompilableConformanceProgramMatchesTheInterpreter`, so the number cannot
slide down silently. `scripts/verify-conformance.py` independently runs `oq run` and the built jar
over the whole corpus with `BASELINE = {"executable": 223, "compiled": 214}` and fails on mismatch.
The figures were `204 of 213` with `{"executable": 213, "compiled": 203}` until Alpha 6 lowered
`map`, `filter`, `sort_by` and a nested `use`, which moved nine cases from refused to compiled;
they are re-measured from a failing run rather than typed, as `alpha-6-requirement-matrix.md`
records.
`JarBuilder`-produced jars are compared against the interpreter by `BuildJarTest`. The boundary is
documented as an ordered list in `docs/spec/backend.md` "The current boundary", so widening it is a
deliberate act with a paper trail.

*Residual.* Both measurements come from the same source tree and the same authors, so they detect
divergence between the two backends but cannot detect a shared misreading of the specification.
See R-008.

*Evidence.* `docs/spec/backend.md` "The compiled subset, measured" · `scripts/verify-conformance.py`
line 40 (`BASELINE`) · `oq-cli/src/test/java/dev/oqthara/cli/BytecodeCompilerTest.java` ·
`oq-cli/src/test/java/dev/oqthara/cli/BuildJarTest.java`.

**R-003 — Module errors in a compiled jar report spliced line numbers.**
`oq build` flattens module sources into one program, so a failure inside an imported module is
reported against the spliced file rather than the module's own file, line and column. Under the
interpreter the same failure names the real module.

*Mitigation.* None. Recorded as a gap against `§14.3 Modules` in the traceability matrix, which is
where a reader looking for it will find it.

*Why accepted.* Fixing it needs a source map carried through lowering, which is a compiler change
of real size for a diagnostic-fidelity gain. The matrix names it rather than hiding it.

*Evidence.* `docs/governance/alpha-4-requirement-matrix.md` `§14.3 Modules` gap column ·
`docs/spec/backend.md` "The current boundary" item 3.

**R-004 — Compiled bytecode has no step budget, so only the heap bounds it.**
Compiled code is bytecode with no interpreter loop, so there is nothing to charge a step against.
The 50,000,000-step budget in `oq-core/src/main/java/dev/oqthara/core/Interpreter.java` does not
exist in compiled code. Output keeps its own ceiling through `OutputBudget.charge`, reached from
`Arithmetic.write`/`print`, so compiled code cannot print without bound — but compiled code that
allocates in a loop is stopped by the JVM heap and nothing else. It reports `the program ran out of
memory` rather than dumping a stack trace, which is presentation, not a limit.

The scope is compiled bytecode, not every jar. `oq build` chooses between two modes: when a program
uses something the backend refuses, the jar embeds the interpreter instead, and that launcher keeps
the interpreter's step budget because `EmbeddedMain.run` constructs an `Interpreter` with the
default `maxSteps`. The missing budget applies only to the mode that actually compiles.

*Mitigation.* The specification says so plainly: "it is still the heap that stops it, not a
budget. Run untrusted programs through `oq run`, or use a jar built as an interpreter launcher."
`JarBuilder` supports the interpreter-launcher mode, and `oq build` documents which mode it chose.

*Why accepted.* A step counter in generated code would cost throughput on every instruction for a
bound the language already declines to guarantee — `docs/spec/language.md` states that Oqthara is
not a sandbox.

*Evidence.* `docs/spec/language.md` §"Host calls are bounded" closing paragraph ·
`oq-runtime/src/main/java/dev/oqthara/runtime/Arithmetic.java` `chargeWritten` ·
`oq-runtime/src/main/java/dev/oqthara/runtime/OutputBudget.java` `MAX_BYTES = 16 MiB` ·
`docs/spec/backend.md` "Two jar modes, chosen automatically".

**R-029 — A compiled program's `print` is not atomic, so concurrent writes fuse lines.**
The interpreter emits one line with a single `PrintStream.println`, holding the stream lock across
the text and the newline together. The compiled backend lowers `print` to `Arithmetic.print`, which
issued `out().print(text)` and then `out().print(newline)` — two lock acquisitions — so a second
thread could write between them. This was never confined to the concurrency helpers:
`use java.lang.Thread` was enough. Measured on a program with two 400-line threads, built with
`oq build`:

```
                        before the fix                    after the fix
interpreter   0 of 40 runs fused a line          0 of 40 runs fused a line
compiled      40 of 40 runs fused a line,        0 of 40 runs fused a line,
              4 to 30 fused lines per run        every run 800 clean lines
```

Both backends wrote all 800 lines before the fix too, so the threads ran on both paths; only the
interleaving differed. A fused line is `AAAA…BBBB…`, which no program wrote. Counting lines alone
therefore does not see this defect — the newline is still written, it just lands in the middle of
another thread's text — which is why the measurement above counts lines that are neither 400 `A`s
nor 400 `B`s. On 800-character lines the fusion is far more frequent: 58 to 106 fused lines per
run over 20 runs.

*Mitigation.* `Arithmetic.print` now writes one line per call — `out().println(text)` followed by
an explicit `flush()` — which is the same single lock acquisition the interpreter's builtin uses,
and it preserves the `System.lineSeparator()` charge accounting. The measurement above is the
mitigation's evidence; `BytecodeCompilerTest.compiledPrintKeepsEachLineAtomicAcrossThreads` is the
control that keeps it, building a real jar and running it three times through a separate JVM. The
test was mutation-verified: reverting `print` to the two-call form makes it fail with a fused line
(`expected: <[]> but was: <[BBBB…AAAA, …]>`), and restoring the fix makes it pass.

The single `println` is only atomic if every caller holds the same lock, so `Arithmetic.out()` has
to be a real singleton. It was not: an unsynchronised check-then-act let racing callers each build
their own `PrintStream` around the same `System.out`, and two streams have two locks, which would
have restored the defect. Measured by racing eight callers at first use over 200 trials: 175 trials
observed more than one stream. `out()` now publishes under a class lock and re-checks inside it,
which took that to 0 of 200. `PrintStreamSharingTest` pins it, and reverting the fix fails that
test (`2 tests completed, 1 failed`).

*Why this is `Mitigated` rather than `Accepted` or `Open`.* The control exists in the source, is
cited by path, and is exercised by a test that fails without it — the conditions this register's
review cadence sets for `Mitigated`. The four concurrency builtins stay in
`Builtins.HOST_DEPENDENT`, but the reason is no longer this defect: with them removed, all of
`conformance/138`, `139`, `140` and `141` compile and print the interpreter's output, measured at
218 of 223 compiled with 0 divergences. Enabling concurrency in compiled output is a capability
change with its own review, so it is carried as an Alpha 7 item rather than taken here.

*Evidence.* `oq-runtime/src/main/java/dev/oqthara/runtime/Arithmetic.java` `print` ·
`oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java` `print` ·
`oq-cli/src/test/java/dev/oqthara/cli/BytecodeCompilerTest.java`
`compiledPrintKeepsEachLineAtomicAcrossThreads` ·
`oq-stdlib/src/main/java/dev/oqthara/stdlib/Builtins.java` `HOST_DEPENDENT` Javadoc ·
`docs/governance/alpha-6-conformance-fallback-accounting.md` "Concurrency".

**R-005 — Compiler slot and constant-pool ceilings refuse large functions.**
`BytecodeCompiler.checkSlotLimit` throws `NotSupportedException("too many variables for one
method")` once the local slot index would exceed 255, and the constant pool has the class-file
`65535` limit. A function with many locals therefore fails to compile and falls back to the
interpreter.

*Mitigation.* The fallback is the designed behaviour: a refusal names the reason and the
interpreter runs the program correctly. `docs/spec/backend.md` records that refusals are reported
by name in the `oq build` output.

*Why accepted.* The limit is the class-file format's, not the project's. Widening it means
splitting methods, which changes debugging fidelity for a case no conformance program reaches.

*Evidence.* `oq-compiler/src/main/java/dev/oqthara/compiler/BytecodeCompiler.java` line 588 and
line 627 (both `throw new NotSupportedException("too many variables for one method")`), line 586
(`checkSlotLimit`), line 1584 (the 65535 constant-pool refusal).

The line numbers here were wrong until the Alpha 7 boundary re-read. They read "line 553
(`checkSlotLimit`), line 594", which by then pointed at `needsScratchList` and a comment. That is
exactly the failure mode R-027 describes: the citation still resolved to real code, so it read as
verified while supporting nothing.

### Dependencies and portability

| ID | Risk | Likelihood | Impact | Exposure | Workstream | Status |
|---|---|---|---|---|---|---|
| R-006 | SQLite is the first external runtime dependency and multiplies the artifact size | High | Medium | Medium | 8 | Accepted |
| R-007 | The JNI driver makes Alpine/musl unsupported | Medium | Medium | Medium | 9 | Accepted |
| R-008 | No action pinning by digest; workflows use moving tags | Medium | High | High | 9 | Mitigated |

**R-006 — SQLite is the first external runtime dependency and multiplies the artifact size.**
`org.xerial:sqlite-jdbc:3.45.3.0` plus `org.slf4j:slf4j-nop:2.0.13` are the only external runtime
dependencies in the project, and they take the standalone jar from 435 KB to 12,821,046 bytes
(12.82 MB / 12.23 MiB), measured at `b5e758f`. Every built
program that carries the driver now costs the same size, because `JarBuilder` copies both
`dev.oqthara` and `org.sqlite`/`org.slf4j` prefixes. A build that previously produced a small jar
now produces a large one, and the size is invisible until someone looks.

The figure was written as 13.5 MB and restated that way in ADR-0040 five times. The Alpha 7
boundary re-read measured the jar and found 12.82 MB, so the number was 5% high in every place it
appeared. It is given in bytes above because MB and MiB are both plausible readings of "13.5 MB"
and neither matches: 12.82 MB is 12.23 MiB, and the claim was wrong in both units. The original
435 KB baseline is left as ADR-0040 recorded it and was not independently re-measured.

*Mitigation.* Recorded as a decision with its cost stated, not absorbed silently: ADR-0040 D1.
The dependency is one driver and one no-op logging binding, not a JDBC stack — general JDBC,
connection handles and `begin`/`commit`/`rollback` are declined. `BuildJarTest` compares the jar's
output with the interpreter's, so the packaging change is pinned by a test rather than assumed.

*Why accepted.* The alternative is no database support at all, or a hand-written SQLite binding,
which is a larger liability than a maintained driver.

*Evidence.* `docs/adr/0040-database-support.md` D1 · `oq-stdlib/build.gradle.kts` ·
`oq-cli/build/libs/oq.jar` (12,821,046 bytes at `b5e758f`) ·
`docs/governance/alpha-4-requirement-matrix.md` `§17`.

**R-007 — The JNI driver makes Alpine/musl unsupported.**
The first JNI dependency in the project means the driver's native library must match the platform.
Alpine and other musl-based images are not supported, and nothing in the repository detects that at
install time — a user on musl gets a failure at the first query, not at install.

*Mitigation.* Stated in ADR-0040 D1 and repeated in the traceability matrix. `Dockerfile` uses
`eclipse-temurin:21-jre`, which is glibc, so the project's own image is on the supported side and
`.github/workflows/docker.yml` runs the built image and greps for `Hello, World!` and a zero-failure
test run, which catches a broken driver in the supported configuration.

*Residual.* No pre-flight check in `scripts/install.sh` or `scripts/install.ps1` warns a musl user.
The failure is deferred to first use.

*Evidence.* `docs/adr/0040-database-support.md` D1 · `Dockerfile` ·
`.github/workflows/docker.yml` · `scripts/install.sh`.

**R-008 — No action pinning by digest; workflows use moving tags.**
Every GitHub Actions `uses:` in the workflows referenced a moving tag (`actions/checkout@v7`,
`actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, `actions/upload-artifact@v7`) rather than
a commit SHA. A compromised or retargeted tag executes in a job that holds `GITHUB_TOKEN` and, in
the release workflow, the publishing path. A tag is a pointer its owner can repoint, so the code
that ran was not the code this repository reviewed.

**Mitigated at the Alpha 7 boundary.** Thirteen `uses:` across the three workflows that hold write
scope now pin a 40-character commit with a comment naming the version:
`.github/workflows/release.yml` (six — `contents: write`, `id-token: write`),
`.github/workflows/site-content-dispatch.yml` (two — references `secrets.SITE_DISPATCH_TOKEN`) and
`.github/workflows/codeql.yml` (five — `security-events: write`).

Each pin was resolved by dereferencing the tag rather than taking its first SHA: `gradle/actions@v6`,
`softprops/action-gh-release@v3` and `github/codeql-action@v4` are annotated tags, and the object a
tag reference points at is a tag object, not a commit. Pinning the tag object's SHA names something
that is not a commit in the repository, and no tool would have complained.

**The control derives its scope rather than listing it.** `scripts/check-action-pins.py` reads every
workflow, selects those that declare a `write` permission or reference a `secrets.` value, and
requires a pinned commit and a version comment in each. This is deliberate: the acceptance named
the release and site-dispatch workflows, and the script's first run found a third —
`.github/workflows/codeql.yml` holds `security-events: write` — which was pinned rather than
exempted. A gate with a hardcoded file list would have certified two files and left the third open,
and the risk itself (accumulating mutable pins) is the kind that grows one addition at a time.
`scripts/tests/test_check_action_pins.py` names the three files explicitly on purpose: deriving the
expected set from the same helper the gate uses would let the test agree with a gate that checked
nothing.

*Residual.* The pins age between updates, and a pin is only as current as the last weekly run —
"pinned" never means "current on demand". Dependabot does bump a pinned `uses:` and rewrite the
version comment beside it from the plain `github-actions` entry this repository already has, so the
pins do not drift unattended; an earlier version of this paragraph said they would, and that claim
was corrected in A7.7 after being checked against Dependabot's own pull requests. What remains is
narrower: a comment naming no version is not rewritten when the pin moves, so the comment can go
stale while the pin does not. That is the trade recorded here: "a third party can repoint the tag" is
exchanged for "fixes arrive on a weekly schedule rather than immediately", and the second is visible
in this repository while the first was not.
`.github/workflows/ci.yml`, `.github/workflows/docker.yml` and `.github/workflows/nightly.yml` are
read-only and keep major-version tags; a test fails if one of them gains write scope, so the
exemption is enforced from the moment it would matter rather than by inspection. A brand-new read-only workflow
that later gains a secret is covered from that change, not from its creation.

*Evidence.* `.github/workflows/release.yml`, `.github/workflows/site-content-dispatch.yml`,
`.github/workflows/codeql.yml` (`uses:` lines) · `scripts/check-action-pins.py` ·
`scripts/tests/test_check_action_pins.py` · `docs/governance/alpha-7-security-baseline.md` A7.5 ·
`docs/governance/alpha-4-threat-model.md` TH-45.

### Resource lifetime and cancellation

| ID | Risk | Likelihood | Impact | Exposure | Workstream | Status |
|---|---|---|---|---|---|---|
| R-009 | No `close` primitive, so any future handle has no deterministic lifetime | Medium | Medium | Medium | 5 | Accepted |
| R-010 | No cancellation, and the interpreter checks interruption nowhere | Medium | Medium | Medium | 5 | Accepted |
| R-011 | Host builtins are unconfined by design; no capability model exists | High | Medium | Medium | 10 | Accepted |

**R-009 — No `close` primitive, so any future handle has no deterministic lifetime.**
The language has no `with` and no `close`. Every API so far avoids the problem by returning no open
handle: `http_get` opens and closes inside the call, `db_transaction(path, body)` opens, commits on
return and rolls back on raise, `read` returns text. That is a design that works only while the
invariant "no builtin returns an open handle" is never broken. Sockets, an HTTP server, key and
certificate APIs, and general JDBC are all declined for this reason, so the cost is already visible
as absent features rather than as latent defects.

*Mitigation.* The invariant is recorded four times, once per decision that depends on it, so a
future contributor meets it before proposing the handle: ADR-0037 D4 (no sockets, no HTTP server),
ADR-0038 D6 (no key or certificate API), ADR-0039 D2 (one shared executor, never shut down because
virtual threads do not keep the JVM alive), ADR-0040 D2 (every database call is one shot).

*Why accepted.* Adding `close` without a `with` construct would make lifetime the caller's problem
without giving the caller a way to express it. The decisions record the trade rather than the
omission.

*Evidence.* `docs/adr/0037-networking-and-urls.md` D4 ·
`docs/adr/0038-constant-time-and-password-hashing.md` D6 ·
`docs/adr/0039-executors-and-cancellation.md` D2 · `docs/adr/0040-database-support.md` D2 ·
`docs/spec/language.md` §"Host calls are bounded".

**R-010 — No cancellation, and the interpreter checks interruption nowhere.**
`spawn` starts a task on one shared virtual-thread executor. `await(task, seconds)` bounds the wait
and raises on timeout, but it does not cancel the task, because the interpreter does not check
`Thread.interrupted()` at any point in its evaluation loop. A timed-out `await` therefore leaves
work running until the process ends. `run_command` does terminate its child on timeout via
`destroyTree`, which is the one place a real stop exists.

*Mitigation.* `await` gains an optional timeout so a program cannot block forever by accident, and
the error explains that cancellation is absent rather than implying the task stopped. `sleep` is
capped at 10 seconds per call and 60 seconds total (`WaitedBudget.MAX_MILLIS`), which bounds the
most common way a task lives too long.

*Why accepted.* Cooperative cancellation needs an interruption check in the evaluation loop and a
semantic answer for what a task observes when it is stopped mid-write. Both are language decisions,
not an implementation detail, and ADR-0039 D4 records the refusal with reasons.

*Evidence.* `docs/adr/0039-executors-and-cancellation.md` D3, D4 ·
`oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java` `TaskRunner`, `MAX_SLEEP_MILLIS` ·
`oq-runtime/src/main/java/dev/oqthara/runtime/WaitedBudget.java` · `SECURITY.md` scope.

**R-011 — Host builtins are unconfined by design; no capability model exists.**
`read`, `save`, `delete`, `run_command`, `env`, `http_get` and the rest resolve paths against the
working directory and then permit anything: absolute paths pass through `Stdlib.resolve`, which
applies `.normalize()` and no root confinement. `OqHost` — the entire host boundary — declares
six methods and no permission hook, no allow-list, and no sandbox. `run_command` takes one
executable path and never goes through a shell, which removes shell-injection as a class, but the
executable itself is arbitrary.

*Mitigation.* The specification and the security policy both state the position rather than leaving
it to be discovered: "Oqthara is **not** a sandbox. A program that is allowed to run can read the
files the user can read, start the processes the user can start, and reach the network the user can
reach. The limits above prevent accidental exhaustion; they do not contain hostile code."
`SECURITY.md` puts user programs out of scope for the same reason: "a malicious `.oq` file is
equivalent to a malicious jar."

*Why accepted.* A capability model is a product decision that has not been taken, and the honest
posture is to say so. What exists is a set of accidental-exhaustion limits (16 MiB per transfer,
30 s per command, 10 s per sleep, 100,000 directory entries, 16 MiB total output), documented in
one table in the specification so a reader can see exactly what is and is not bounded.

*Evidence.* `docs/spec/language.md` §"Host calls are bounded" and the closing paragraph after the
table · `SECURITY.md` "Scope" · `oq-runtime/src/main/java/dev/oqthara/runtime/OqHost.java` ·
`oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java` `resolve`.

### Standard library breadth and behaviour

| ID | Risk | Likelihood | Impact | Exposure | Workstream | Status |
|---|---|---|---|---|---|---|
| R-012 | Standard-library breadth has no denominator | High | Low | Low | 8 | Open |
| R-013 | `data` cannot carry methods or implement contracts | High | Low | Low | 7 | Accepted |
| R-014 | A Java function value is callable but not fully a `fun` | Medium | Low | Low | 6 | Open |
| R-015 | Local and UTC time are easy to confuse | Medium | Low | Low | 8 | Mitigated |
| R-016 | XML is deliberately absent | Medium | Low | Low | 8 | Accepted |

**R-012 — Standard-library breadth has no denominator.**
"Broad standard library" is a requirement that cannot currently be measured, because nothing states
how many functions the reference language has. The matrix is explicit: "Still unmeasured against the
Python reference, so 'breadth' has no denominator." What is measured is the count on this side — 205
global functions and nine method families — which answers "how much is there" and not "how much is
missing". Three whole domains were recently found absent (encoding, hashing, and a filesystem group)
after the library had been described as broad, which is the failure mode the missing denominator
produces.

This entry carried "89 global functions and eight method families" until the Alpha 7 boundary
re-read. Both numbers were stale, and `alpha-4-stdlib-inventory.md` had already corrected them and
said so in its own text, which is the argument this entry makes: the register was quoting a figure
the companion document had retracted. The 89 was never the generated count — the reference produced
when it was written already listed 98 — and the method families have been nine since
`numberMethod` was added.

*Mitigation.* None beyond the honesty. The count is regenerated and checked:
`scripts/check-docs-sync.py` verifies the generated reference against the implementation, and `oq
doc` renders the whole table from `oq-stdlib` sources. `docs/catalog.json` is regenerated by
`scripts/gen-docs-catalog.py` and checked in CI.

*Why open.* Closing it needs a written inventory of the reference language's surface, which is a
research task, not a code change. Until it exists, every claim about breadth is a claim without a
scale.

*Evidence.* `docs/governance/alpha-4-requirement-matrix.md` `§17` gaps column ·
`docs/stdlib/reference.md` · `scripts/check-docs-sync.py` · `docs/catalog.json`.

**R-013 — `data` cannot carry methods or implement contracts.**
A `data` declaration gets a constructor, fields, structural equality, hashing and (with `immutable`)
map-key and set-member status. It cannot have methods of its own and cannot implement a contract,
so behaviour attached to a record lives in a free function that takes the record. Destructuring was
rejected by ADR-0013, and copy-with-changes is served by re-calling the constructor rather than by a
method.

*Mitigation.* Recorded as the gap on `§14.2` rather than rounded up to done, and the rebuild hint in
a refusal points at the constructor that does the job.

*Why accepted.* Methods on `data` need a member-resolution story that classes already have and
`data` deliberately does not; adding it would make two shapes of record with different capabilities.
The decision is recorded, and `§14.2` remains `PARTIAL` for this reason.

*Evidence.* `docs/governance/alpha-4-requirement-matrix.md` `§14.2` · `docs/adr/0028-data-evolution.md`
· `docs/adr/0013-destructuring.md` · `oq-cli/src/test/java/dev/oqthara/cli/DataTest.java`.

**R-014 — A Java function value is callable but not fully a `fun`.**
The direction *into* Java is closed: a function argument that meets an interface with exactly one
abstract method becomes a `java.lang.reflect.Proxy` that calls back through the host that created
it, so `xs.forEach(fun(x) print x end)` and `Collections.sort(xs, (a, b) -> a - b)` both work on
both backends, and a callback over a `List<Integer>` is handed `long` rather than `Integer`. The
earlier entry described two independent failures and blamed dispatch for skipping reflection on a
non-`Class<?>` receiver. That was wrong — `xs.ensureCapacity(100)` and `Collections.sort(xs)`
both reach reflection — and the correction is recorded in ADR-0032 D5. The direction *out of*
Java is closed as well, since #122 made a functional-interface value callable: a method that takes
a `Function` and hands one back on return yields a JDK object that Oqthara can call with `()`, can
hand to `map` and to a `fun` parameter, and can compose and re-pass to Java. The paragraph below
records how that half was closed; it was misread as describing a live gap until 2026-11-19
because the sentence it corrects had not been withdrawn.
Calling such a value did not originally work: `Comparator.naturalOrder()` answers a package-private `Comparators$NaturalOrderComparator`
and `Function.identity()` answers a hidden lambda class, and reflection reports the *declaring*
class's accessibility rather than the interface's, so `compare` and `apply` were both refused with
"cannot access a member of class ... with modifiers public". The same signature is now resolved on a
public supertype, and both calls work; `JavaBridgeAccessibilityTest` pins them. The help text was
corrected with it — `map(xs, Comparator.naturalOrder())` used to say the value "cannot be called",
which contradicted the method call that had just succeeded, and now says that it is a Java object
whose methods are callable but which cannot stand where a `fun` is expected.

What is still missing, measured 2026-11-19, is narrower than the heading: a function held in a
`data` field cannot be called through the field (`b.mapper(5)` reports "expects 0 arguments") even
when the function is plain Oqthara, and a default method is not reachable from the Oqthara side of
a proxy, so `cmp.reversed()` on a lambda is refused while `Comparator.naturalOrder().reversed()` is
not. Neither is a conversion question, and both are decided in the brief below.

*Mitigation.* The supporting direction is shipped and tested, and the two remaining gaps are named
with reproductions so they are not mistaken for the conversion question they were originally
filed as.

*Why open.* A decision was proposed about whether a function value should convert to a
single-method interface implicitly — Option A, the behaviour that exists, without adding an
implicit adapter in either direction. It is not yet accepted, so this row stays Open; what
remains open beyond it is the two narrower defects above, not the shape of the conversion.

*Decision.* [`docs/governance/remediation/java-function-conversion-decision-brief.md`](remediation/java-function-conversion-decision-brief.md)
(Alpha 7 §2.1) measures the current behaviour on both backends and proposes Option A. It awaits
acceptance; nothing in it is implemented.

*Evidence.* `docs/governance/alpha-4-requirement-matrix.md` `§16 JavaBridge expansion` gaps column ·
`docs/adr/0032-static-fields-and-varargs.md` · `oq-core/src/main/java/dev/oqthara/core/JavaBridge.java`.

**R-015 — Local and UTC time are easy to confuse.**
`today()` and `datetime()` return local values; `now()` and `timestamp()` return UTC. The two pairs
differ only by name, and a program that mixes them produces a time that is wrong by the machine's
offset with no error. The specification records the distinction only because it was measured, which
is itself evidence that the naming does not carry it.

*Mitigation.* `docs/spec/language.md` states the distinction explicitly after the time builtins, and
`tz_convert` requires an explicit IANA zone and refuses abbreviations, so a conversion cannot be
silently ambiguous. `docs/adr/0036-time-zones-and-filesystem.md` records it.

*Residual.* The naming remains the hazard; only the documentation mitigates it.

*Evidence.* `docs/spec/language.md` §"Dates and times" · `docs/adr/0036-time-zones-and-filesystem.md`
· `docs/governance/alpha-4-requirement-matrix.md` `§17`.

**R-016 — XML is deliberately absent.**
There is no XML parser. A hand-written one without XXE hardening would be a security regression, so
the gap is recorded rather than filled badly. Programs that need XML must shell out through
`run_command` or use Java interop.

*Mitigation.* The reason is documented in the `§17` gap column, so the absence reads as a decision.

*Why accepted.* The JDK's own XML stack is available through `use`, so a program that genuinely
needs it is not blocked — it is only not given a convenient builtin. Adding one would mean either
duplicating the JDK or exposing a parser whose default configuration the language would then have to
defend.

*Evidence.* `docs/governance/alpha-4-requirement-matrix.md` `§17` gaps column.

### Verification

| ID | Risk | Likelihood | Impact | Exposure | Workstream | Status |
|---|---|---|---|---|---|---|
| R-017 | Conformance corpus is circular: tests and implementation share one author | High | Medium | Medium | 11 | Accepted |
| R-018 | Fuzzing does not reach path resolution, directory traversal or semantic analysis | Low | Medium | Low | 11 | Mitigated |
| R-019 | A defect can survive in the one path `scripts/verify-conformance.py` does not compare | Low | Medium | Low | 11 | Mitigated |
| R-024 | A required CI context can vanish without failing | Medium | High | High | 11 | Mitigated |
| R-026 | The build cache replays a Linux test result as a macOS or Windows one | High | Medium | Medium | 11 | Mitigated |
| R-028 | A timing test asserts exact zero and fails only under CI load | Medium | Medium | Medium | 11 | Mitigated |
| R-031 | A gate's allow-list silently stops matching, so a required context goes permanently red | High | Medium | Medium | 11 | Mitigated |

**R-024 — A required CI context can vanish without failing, blocking every merge.**
The `main-protection` ruleset requires twelve status contexts. Between 2026-11-11 and 2026-11-12,
CircleCI stopped dispatching the `macOS / JDK 21` and `macOS / JDK 25` jobs on three consecutive
pull requests while continuing to report `ci/circleci: jazzer-fuzz` normally. The missing contexts
were **absent**, not failing, so `statusCheckRollup` read SUCCESS while `mergeStateStatus` read
BLOCKED and the pull request page showed an indefinite "Expected — Waiting for status to be
reported". Three pull requests (#127, #131, #132) were unmergeable for two sessions.

*Mitigation.* macOS moved to GitHub Actions under ADR-0049, the ruleset now requires
`macos-latest / JDK 21|25`, CircleCI keeps its macOS workflow as the fallback, and
`scripts/check-circleci.py` accepts either provider at release time while failing on a red result
from either. `scripts/tests/test_check_ci_gate.py` pins the anti-masking rule.

**A second mechanism, found on PR #264.** A pull request whose branch conflicts with its base gets
**no CI at all** from any `on: pull_request` workflow. GitHub cannot construct `refs/pull/N/merge`
without a merge base it can build, so the workflows do not dispatch, and nothing is reported as an
error. On #264 the symptom was `gh api .../commits/<sha>/check-runs` returning `total_count: 0` while
`gh run list` showed only older commits, and the last run was four commits behind. The pull request
sat unmergeable with no red check anywhere, and the diagnosis took reading `mergeable` — a field
nobody looks at while waiting for CI.

This is the same class as the CircleCI stop above and worse in one respect: the outage was visible
as an *absence of one context* against a PR that otherwise had checks, while a conflicted PR has no
checks to be compared against. `mergeStateStatus` reads `BLOCKED` in both cases, so branch
protection does prevent a merge — the loss is diagnosis, and the cost is however long nobody
notices, which on #264 was four commits.

**A third mechanism, found on PR #264 as well.** A dependency resolution failure aborts the build
before any of the repository's own code runs, and reads exactly like a code defect:

```
Could not resolve org.xerial:sqlite-jdbc:3.54.1.0.
  > Could not GET 'https://repo.maven.apache.org/maven2/.../sqlite-jdbc-3.54.1.0.pom'.
     > Received status code 429 from server: Too Many Requests
```

The job is called `Bytecode backend (JDK 25)` and it went red in 18 seconds, so the natural reading
is that the commit broke the JDK 25 build. It did not: Maven Central rate-limited the runner during
dependency resolution, the same commit had passed the same job twenty minutes earlier, and nothing
in the diff touched the compiler. Re-running the identical SHA against identical content is the
only thing that distinguishes the two causes, and a re-run is the one action a red check discourages.

The cost is diagnostic rather than protective — branch protection behaves correctly — but a
transient `429` is indistinguishable from a real regression without reading the log, and the log
sentence that names the cause sits below a summary line that does not. This is the same shape as
the two mechanisms above: the required context is absent or red for a reason that has nothing to do
with the change being reviewed.

*Residual.* The failure mode itself is unavoidable: a required context is identified by name alone,
and GitHub cannot distinguish "not dispatched" from "still queued". A conflicted branch is a third
case with the same read, and there is no signal that separates them. The same silent stop could
happen on GitHub Actions, and the recovery is the mirror image — dispatch the CircleCI workflow by
hand and let the release gate accept that evidence. `installers-macos` remains CircleCI-only and is
not a required context, so a macOS installer regression would not block a merge.

*Evidence.* `docs/adr/0049-macos-ci-on-github-actions.md` · `.github/workflows/ci.yml` (matrix) ·
`scripts/apply-branch-protection.sh` (required contexts) · `scripts/check-circleci.py` (grouped
requirements) · `scripts/tests/test_check_ci_gate.py`.

**R-031 — A gate's allow-list silently stops matching, so a required context goes permanently red.**
`scripts/check-ai-review.py` exempts automated authors from needing a review record, because a bot
cannot review its own change. The allow-list held `dependabot[bot]` and `renovate[bot]`, which is
how an older installation reports them. GitHub now reports an App-authored pull request as
`app/<slug>`, so Dependabot answers `app/dependabot` and the exemption stopped matching. The gate
then reported "PR has no AI review record" for every dependency pull request, and
`ubuntu-latest / JDK 21` — one of the twelve required contexts — went red and stayed red. PR #251, a
one-line gradle-wrapper bump, was unmergeable for this reason alone, and the red check said nothing
about the dependency.

*Mitigation.* Both spellings are exempt, and the tests pin the spelling `gh pr view --json author`
actually returns rather than the one the gate was written to expect. A test was added asserting an
unrecognised App is still refused, so the fix cannot be read as exempting `app/*`.

*Residual.* This is a general class, not a one-off: any gate that matches an external identifier
against a hard-coded list fails this way, and it fails **closed but silent** — the check goes red for
a reason unrelated to the change, so a maintainer reading only the red square learns nothing. The
existing tests could not catch it because a stub supplies the value the stub was written to supply;
only running the gate against the real API does. Nothing currently exercises the gates against live
GitHub data, so the next such drift will be found the same way this one was: by a pull request
sitting red for days.

*Evidence.* `scripts/check-ai-review.py` (`AUTOMATED_AUTHORS`) ·
`scripts/tests/test_check_ai_review.py` (`ItExemptsAutomatedAuthors`) · PR #251 · PR #254.

**R-026 — The build cache replays a Linux test result as a macOS or Windows one.**
`gradle.properties` enables the build cache, and `gradle/actions/setup-gradle@v6` in
`.github/workflows/ci.yml` keeps a single remote cache for the whole matrix. Gradle derives a `Test`
task's cache key from the classpath, the declared inputs, and the JVM — but not the operating
system. Every leg of the matrix therefore competes for the same key, and whichever platform runs
first donates its results to the others.

The effect was visible in the logs. On `macos-latest / JDK 21`, run `43302389917` reported:

```
> Task :oq-runtime:test FROM-CACHE
> Task :oq-core:test FROM-CACHE
> Task :oq-compiler:test FROM-CACHE
> Task :oq-stdlib:test FROM-CACHE
> Task :oq-lsp:test FROM-CACHE
> Task :bench-jmh:test FROM-CACHE
> Task :oq-cli:test
```

Six of the seven test tasks replayed. Windows in the same run showed the identical pattern, and the
`macos-latest / JDK 25` leg on pull request #132 finished in **25 seconds** — a duration incompatible
with having executed a test suite. The first macOS run ever recorded (`43300234346`) genuinely
executed `:oq-core:test`, `:oq-compiler:test` and `:oq-stdlib:test`, which dates the behaviour to
the second run onward and confirms it is cache reuse rather than a job that never ran.

This is a defect in the *evidence*, not in the tests. The macOS and Windows legs exist to catch
platform-specific behaviour — path separators, line endings, executable bits, native library
loading, process launch. A replayed Linux result demonstrates none of it, so a green platform leg
carried no more information than the Ubuntu leg, while still costing macOS minutes.

*Mitigation.* `build.gradle.kts` declares the operating system as an input of every `Test` task, so
cache entries no longer cross platforms. Caching is kept, not disabled: a repeat run of the same
platform on the same commit still hits, and the matrix legs now hold evidence specific to their
platform.

*Residual.* The cache key still ignores the JVM vendor and version beyond what Gradle infers, so two
legs of the same OS that differ only by JDK may share entries. That is sound while both JDKs run the
same bytecode, but it should be revisited if a JDK-specific failure is ever suspected. The
`fuzz*` tasks registered in `build.gradle.kts` inherit the same `configureEach` block and are
covered by the same input.

*Evidence.* `build.gradle.kts` (`tasks.withType<Test>().configureEach`) · `gradle.properties` ·
`.github/workflows/ci.yml` · run `43300234346` versus run `43302389917`.

**R-028 — A timing test asserts exact zero and fails only under CI load.**
`WaitedBudgetTest.aFinishedTaskChargesAlmostNothingWhateverItsTimeout` exists because of a real
defect: `OqTask.awaitWithin` used to bill the caller's *ceiling* rather than the time actually
spent waiting, so eight awaits of instant work under a ten-second ceiling reported "waited for too
long" in microseconds. The fix charges the elapsed time in a `finally` block, which is correct.

The test written for the fix then asserted the fix by following twenty awaits with
`charge(MAX_MILLIS)`. Because `WaitedBudget.charge` refuses when the running total exceeds the
maximum, that assertion only passes when the twenty awaits cost *exactly* zero milliseconds in
total. Any single scheduler pause inside one `future.get()` contributes a whole millisecond —
`elapsedMillis` truncates but does not round down to nothing — and the follow-up charge then
crosses the limit. It failed on `ubuntu-latest / JDK 25` in run `43323606693` and passed five
consecutive local runs, which is the signature of a test measuring the machine rather than the
behaviour.

This is the second entry in this register caused by an assertion that was stronger than its own
comment. The comment says the timeout is a ceiling and not a cost; the assertion says the total
must be zero. Those are different claims, and only the second one is false.

*Mitigation.* The follow-up charge is now made against `MAX_MILLIS - 5000`. Five seconds is three
orders of magnitude above the real cost of releasing twenty already-completed futures and three
orders below the 200 seconds that billing the ceiling would charge, so the assertion separates the
two behaviours with room to spare. The test was verified to still catch the original defect:
moving the charge back to the up-front position makes it fail, and restoring it makes it pass.

*Residual.* The slack is a constant, not a measurement, so a CI machine slow enough to spend five
seconds across twenty completed-future releases would still fail — that machine would be broken in
ways this test is not the right place to detect. Other timing assertions in this repository were
not audited in this pass; the class of defect is recorded here rather than claimed absent.

*Evidence.* `oq-runtime/src/test/java/dev/oqthara/runtime/WaitedBudgetTest.java` ·
`oq-runtime/src/main/java/dev/oqthara/runtime/{WaitedBudget,OqTask}.java` · run `43323606693`.

**R-017 — Conformance corpus is circular: tests and implementation share one author.**
`conformance/` holds 256 cases, and `scripts/verify-conformance.py` compares the interpreter against
the compiled jar over all of them. Both sides are built from the same source by the same process,
and the expected outputs were written by the same author as the implementation. The comparison
therefore answers "do the two backends agree" and cannot answer "is this the right behaviour". A
misreading of the specification is invisible to it, because the test encodes the same misreading.

*Mitigation.* Where an external oracle exists, it is used: `sha256("abc")`, `sha1("abc")`,
`md5("abc")` and the RFC 4231 HMAC vector are checked against their published values rather than
against a second implementation, and `pbkdf2_sha256` is cross-checked against Python's
`hashlib.pbkdf2_hmac`. Collection semantics are pinned against the Python-informed behaviour the
specification names. The baselines are measured, never inherited — OQ-DEC-2026-008 makes that a
decision, and `scripts/verify-conformance.py` fails if the executable corpus count changes rather
than accepting a new number.

*Why accepted.* The structural fix is an independent implementation or an independent author, and
neither exists in a one-person repository. The partial fix — preferring published vectors wherever a
standard defines one — is in place and is the reason the cryptographic builtins are the
best-anchored part of the library.

*Evidence.* `conformance/` · `scripts/verify-conformance.py` · `docs/governance/README.md`
OQ-DEC-2026-008 · `docs/governance/alpha-4-requirement-matrix.md` `§17` (published vectors).

**R-018 — Fuzzing does not reach path resolution, directory traversal or semantic analysis.**
Fourteen targets cover the lexer, the parser (three shapes), the whole parse→lower→emit pipeline, JSON (two
shapes) and the pure stdlib builtins, plus CSV, text wrapping, INI/properties, three glob shapes and three
compression shapes (decompress, zlib/gzip round trip, zip round trip). `./gradlew -q printFuzzTargets` prints
them, and is the authority this sentence follows.
`docs/spec/fuzzing.md` "What is not covered yet" names what is left: path resolution still needs an
`OqHost`, so every entry point that resolves a path against the working directory is unreachable;
`Patterns.glob`'s directory walk is exercised only by `GlobTest`, because the target calls
`matchesText`; and semantic analysis still has no separate pass. Generated class files are checked
for magic only; full verification happens when the JVM loads them in `BytecodeCompilerTest`.

*Mitigation.* `fuzzTest` is not part of `check`, so ordinary builds stay fast, but it does run in the
`jazzer-fuzz` CircleCI job on every push and pull request and in `.github/workflows/nightly.yml` every
day at 03:00 UTC with a 60-second cap per target. Findings become permanent tests: the curated corpus
in `oq-core/src/fuzz/resources/dev/oqthara/core/curated-cases.txt` is replayed by
`CuratedCorpusTest` on every build, and `docs/spec/fuzzing.md` requires that a finding is never
closed by deleting the input.

*Why mitigated rather than closed.* The two targets added in A7.8 found three defects outright and
led to a fourth through Jazzer's regex-injection sanitizer: `fnmatch` was building a backtracking
regex from user input, and a 26-character pattern took 1564 ms against a 30-character name with the
time doubling per two characters added. That is a true positive, so the sanitizer is **not**
suppressed anywhere in the build; the regex engine was removed instead (ADR-0068), and the target
now runs clean for 181 s with the hook enabled. Each of the four has a permanent test. The risk drops
to `Low` because the largest remaining gap is a traversal the unit suite covers directly, not because
the reach is complete.

*Evidence.* `docs/spec/fuzzing.md` · `docs/adr/0068-glob-matching-is-a-linear-dynamic-program.md` ·
`oq-core/src/fuzz/`, `oq-stdlib/src/fuzz/`, `oq-compiler/src/fuzz/` ·
`.github/workflows/nightly.yml` · `.circleci/config.yml`.

**R-019 — A defect can survive in the one path `scripts/verify-conformance.py` does not compare.**
The verifier measures the corpus, not the tool. A behaviour reachable only through a CLI subcommand
or a builtin the corpus never calls is outside both the parity comparison and the conformance count.
Coverage is guarded only for `dev/oqthara/core` at 80% line coverage, so `oq-compiler` and `oq-cli`
have no floor.

*Mitigation.* `oq-cli/src/test/java/dev/oqthara/cli/ConformanceTest.java` runs the corpus through the
CLI, `ExamplesRunTest` runs every file in `examples/` through the real CLI, and
`oq-compiler/src/test/java/dev/oqthara/compiler/LoweringTest.java` pins lowering directly — so the
compiler is exercised through the CLI tests rather than only through the corpus. The gap that
remains is the absence of a coverage floor outside `oq-core`.

*Residual.* A defect in an unexercised branch of `oq-compiler` or `oq-cli` is not detected by any
existing gate.

*Evidence.* `build.gradle.kts` `coverageGate` (80%, `dev/oqthara/core` only) ·
`oq-cli/src/test/java/dev/oqthara/cli/ConformanceTest.java`,
`oq-cli/src/test/java/dev/oqthara/cli/ExamplesRunTest.java` ·
`oq-compiler/src/test/java/dev/oqthara/compiler/LoweringTest.java`.

### Distribution and publication

| ID | Risk | Likelihood | Impact | Exposure | Workstream | Status |
|---|---|---|---|---|---|---|
| R-020 | The POSIX installer verifies authenticity but the PowerShell one verifies only integrity | Low | High | Medium | 9 | Mitigated |
| R-021 | No update or rollback path; re-running the installer is the only upgrade | Medium | Low | Low | 9 | Open |
| R-022 | Versioned-docs script writes to a directory that no longer exists | High | Low | Low | 12 | Open |
| R-023 | Keyword count is written down in six places and drifted in three | High | Low | Low | 1 | Open |
| R-027 | Line-number citations in the stdlib inventory drift silently | High | Low | Low | 16 | Open |
| R-025 | Nothing executes the specification's code examples, so a wrong one ships silently | Medium | Low | Low | 16 | Accepted |

**R-020 — The installers differ: POSIX verifies authenticity, PowerShell verifies only integrity.**
`scripts/install.sh` downloads three release assets — the jar, its `.sha256` checksum and its Sigstore
bundle, via `sha_url` and `sig_url` — checks the
digest, and then — if `cosign` is on `PATH` — runs `cosign verify-blob --bundle` against the Sigstore
bundle, reporting "Signature verified (cosign, keyless signer)". If `cosign` is absent it prints an
explicit warning that the SHA-256 "detects corruption and NOT tampering", and
`OQ_REQUIRE_SIGNATURE=1` turns that into a hard failure. `OQ_REQUIRE_SIGNATURE` is the only path that
makes authenticity mandatory, and it is off by default.

`scripts/install.ps1` does not do this. It fetches `oq.jar` and `oq.jar.sha256` and checks
`Get-FileHash -Algorithm SHA256`; it never fetches the bundle, never invokes `cosign`, and has no
`OQ_REQUIRE_SIGNATURE` equivalent. So on Windows the digest proves the bytes arrived unaltered and
nothing proves who produced them, because an attacker who can replace the jar can replace the
checksum beside it. There is also no Authenticode signature on `install.ps1` itself.

The release side is no longer the gap this entry originally described. `.github/workflows/release.yml`
holds `id-token: write`, signs with `cosign sign-blob --bundle` into a Sigstore bundle beside the jar,
and publishes three assets; `scripts/verify-signature.sh` is exercised by a step that tampers with the
jar and asserts verification then fails. `v0.1.0-alpha.6` shipped all three assets. The gap moved from
"nothing is signed" to "one of the two installers does not check the signature that now exists".

*Mitigation.* The window is narrow on both platforms: the assets live on GitHub's release
infrastructure, the release workflow regenerates the checksum from the built jar in a fresh directory
and verifies it with `sha256sum -c`, and the artifact is smoke-tested on a clean runner path before
publication. The checksum catches transport corruption and a truncated download, which is the common
case and is covered by `scripts/tests/run-installer-tests.sh` including a deliberately truncated
response. On the POSIX path, Sigstore signing closes authenticity whenever `cosign` is present.

*Mitigated at the Alpha 7 boundary.* `scripts/install.ps1` now downloads the release's Sigstore
bundle, runs `cosign verify-blob --bundle` against the downloaded jar when `cosign` is found, and
refuses the install when verification fails. When `cosign` is absent it prints the same warning the POSIX
installer prints — that the SHA-256 "detects corruption and NOT tampering" — and
`OQ_REQUIRE_SIGNATURE=1` turns a missing or unverifiable signature into a hard failure on both
platforms. The bundle is removed on every exit path, so a failed run leaves no signature material
behind beside an installed jar.

The lookup honours `OQ_COSIGN` as a test seam, and the installer test suite now carries the Windows
equivalents of the POSIX signature cases: signed-with-cosign verifies, tampered jar rejected,
signed-without-cosign warns and installs, signed-without-cosign under `OQ_REQUIRE_SIGNATURE` fails,
unsigned warns and installs, unsigned under `OQ_REQUIRE_SIGNATURE` fails, plus a positive control
proving the hash-checking stub accepts an untampered jar.

*Residual.* Authenticity is still opt-in by default. Without `cosign` and without
`OQ_REQUIRE_SIGNATURE=1` both installers accept a jar on its checksum alone, which is the documented
trade rather than an oversight. `install.ps1` itself still carries no Authenticode signature, so a
tampered copy of the installer could remove these checks before they run; that part of the entry is
untouched and is the reason it is not recorded as fully resolved.

*Evidence.* `scripts/install.ps1` (`Get-CosignCommand`, `Test-Signature`, `sigUrl`,
`OQ_REQUIRE_SIGNATURE`) · `scripts/tests/installer.tests.ps1` (six signature cases and a positive
control) · `scripts/install.sh` (the POSIX behaviour this mirrors) · `.github/workflows/ci.yml`
(the `windows-latest / installer tests` job that executes them) · the `ubuntu-latest` job, which
runs the same suite because the end-to-end block no longer depends on a Windows-only switch.

**R-021 — No update or rollback path; re-running the installer is the only upgrade.**
There is no `oq update`, no version manager, no rollback to a previous release, and no bundled JDK —
both installers require a pre-existing `java` on `PATH` and fail if it is absent. Workstream 9 as
written asks for "JDK runtime selection, platform installers, integrity, update, rollback"; only the
installers and integrity exist. The workstream is owned —
[`alpha-4-workstream-assignment.md`](alpha-4-workstream-assignment.md) records workstream 9 as
"Covered (ownership resolved 2026-11-11)" with `oq-hive-testing-security` as primary — and the gap
is capability, not ownership. An earlier version of this entry said the workstream was unowned,
which that file had contradicted since 2026-11-11.

*Mitigation.* None. `OQ_VERSION` pins a specific release and `OQ_INSTALL_DIR` pins the destination,
so a manual rollback is possible by re-running with an older tag — undocumented as a rollback
procedure, but mechanically available.

*Why open.* This is unbuilt scope, not a mitigated risk. It is listed so the gap is visible in the
one document whose purpose is to make gaps visible.

*Evidence.* `scripts/install.sh` (`OQ_VERSION`, `OQ_INSTALL_DIR`, `--version` smoke test) ·
`docs/governance/alpha-4-workstream-assignment.md` workstream 9.

**R-022 — Versioned-docs script writes to a directory that no longer exists.**
`scripts/build-versioned-docs.py` writes into `site/_site/versions/<version>/` and `site/versions.json`.
The `site/` directory does not exist in this repository — the website was extracted by
`docs/adr/0011-standalone-website-repository.md` — and no workflow invokes the script. It is
nevertheless listed in `scripts/README.md` as "Builds the versioned documentation bundle used by the
website." A maintainer following that line runs a script that creates a stray `site/` tree in a
checkout that is not supposed to have one.

*Mitigation.* None. `scripts/check-links.py` and `scripts/check-docs-sync.py` do not read it.

*Why open.* Either the script belongs in the website repository, or it should be deleted and the
README corrected. Both are one-line decisions that have not been taken.

*Evidence.* `scripts/build-versioned-docs.py` (`OUT = ROOT / "site" / "_site" / "versions"`) ·
absence of `site/` · `scripts/README.md` Documentation table ·
`docs/adr/0011-standalone-website-repository.md`.

**R-023 — Keyword count is written down in six places and drifted in three.**
`Lexer.KEYWORDS` holds 22 entries. Five documents repeat the number: `docs/stdlib/reference.md`
("22 keywords (budget: 30)"), `CONTRIBUTING.md` ("The keyword budget is 30; we are at 22"),
`docs/spec/design.md` (its simplicity table), `AGENTS.md`, and two `oq-hive-*` agent files.
At the Alpha 7 boundary re-read three of the six were stale — `AGENTS.md` said "budget 30, now 20"
and both agent files said "It currently uses 20" — and `scripts/check-docs-sync.py` read only the
three correct ones, so the copies an agent reads first were the ones CI could not catch.

This entry has now been wrong about itself twice. It first quoted `CONTRIBUTING.md` as saying "we
are at 21" and counted four statements in four documents. The Alpha 7 re-read corrected that to
five-and-five and still missed `docs/spec/design.md`, which the gate had been checking all along.
Two consecutive re-reads found this entry describing documents it had not re-opened, which is the
risk occurring inside the document that records it.

*Mitigation.* `scripts/check-docs-sync.py` cross-checks the reference against `Lexer.KEYWORDS`, and
since the Alpha 7 boundary re-read it also reads `AGENTS.md` and both agent files, requiring each to
state a budget and count that match the lexer. A missing or reworded statement is a problem rather
than a silent skip, because the way this drift survived was a file quietly leaving coverage.

*Why still open.* The count is now correct in all six places and cannot drift unnoticed again. What
remains is the root cause: the same number is written down six times. Collapsing it to one source
that the others cite would remove the class rather than the instance, and it is not done.

*Evidence.* `oq-core/src/main/java/dev/oqthara/core/Lexer.java` `KEYWORDS` ·
`docs/stdlib/reference.md` line 11 · `CONTRIBUTING.md` line 9 · `AGENTS.md` line 21 ·
`.github/agents/oq-hive-language-syntax.agent.md` rule 2 ·
`.github/agents/oq-hive-orchestrator.agent.md` ·
`scripts/tests/test_check_docs_sync_keywords.py`
`keyword_budget_is_honest`.

**R-027 — Line-number citations in the stdlib inventory drift silently.**
`docs/governance/alpha-4-stdlib-inventory.md` cites its evidence the way every other governance
document in this repository does: a backticked name and a line number, `` `sqrt`: 423 ``. Those
numbers are breadcrumbs, and nothing recomputed them. Measured against `HEAD` before Alpha 4 §17
added a single import, **112 of the 240 citations that name a `Stdlib.java` builtin already
pointed at the wrong line** (a further 47 cite names registered in another file, or a name that was
never a registration, and are checked separately) — `format` claimed 237 and sat at 246, `join`
claimed 238 and sat at 248, `read_bytes` claimed 562 and sat at 576. The drift was not uniform:
some sections had been edited since their numbers were written and others had not, which is why it
went unnoticed. The parity that finally exposed it was accidental: adding one import line shifted
every `Stdlib.java` registration by exactly one, so the citations became uniformly wrong and the
checker, which had been silently agreeing with a document it never actually recomputed, failed.

The failure mode is worse than a wrong number. A citation that lands on a plausible neighbouring
line still resolves, so a reader who follows it reads real code that does not support the sentence
they were checking. The document therefore looked more verified than it was for as long as the
drift lasted, and the three mutually inconsistent coverage tallies repaired alongside it (see the
inventory's own account) are the same class of defect: a document asserting facts about source that
nothing compared against the source.

*Mitigation.* The inventory's figures are now recomputed from the implementation rather than
carried forward, and `scripts/check-docs-sync.py` gained two guards. `stdlib_inventory_tallies_agree`
reconciles the per-domain table, the `Total` row and the status counts against the chapters they are
derived from, so the three-way disagreement cannot return. `stdlib_inventory_citations_resolve`
resolves every `name`:line citation against an actual scan for the registration form, which is what
turned the off-by-one into a build failure. The registration-site heading is matched by shape
(`^### The (\d+) names, by registration site$`) rather than by literal text, because the count lives
in the heading and a literal match made adding a builtin require editing the checker — the wrong
direction of fix, and a habit that would eventually have been used to silence a real failure.

*Residual.* Only this one document is covered. A line number in any other governance document,
ADR or `CHANGELOG` entry is still unchecked, and the citation checker resolves the *first*
registration of a name, so an overloaded builtin cited at its second site would report the
first site's line and pass. The general fix is the same work described in R-025: execute what the documents claim and
compare, rather than reading it.

*Evidence.* `docs/governance/alpha-4-stdlib-inventory.md` · `scripts/check-docs-sync.py`
(`stdlib_inventory_tallies_agree`, `stdlib_inventory_citations_resolve`) ·
`oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java`.

**R-025 — Nothing executes the specification's code examples, so a wrong one ships silently.**
`docs/spec/language.md` carries well over a hundred fenced examples. `scripts/check-docs-sync.py`
checks that the document states a version, that its version matches the build, and that certain
named constructs are described; `scripts/check-links.py` checks that its links resolve. Neither
runs an example. The conformance corpus is the only executed body of programs, and it is a
separate corpus under `conformance/`, so an example may be wrong in the specification while
every test passes.

This is not hypothetical. While promoting §16 of the requirement matrix, a drafted example read
`Lexer().tokenize("1 + 2")`. Executing it against the built CLI refused it —
`cannot create Lexer with 0 arguments` — because `Lexer` declares a two-argument constructor and
a static `lex`. The same draft would have merged had it not been tried, and nothing in CI would
have objected.

*Mitigation.* Every example added in this change was executed before being written down, and the
habit is worth keeping. The exposure stays `Low` rather than `Medium` because a wrong example
misleads a reader rather than corrupting a program, and because the examples that matter most —
the ones a reader copies into a file — are the ones most likely to be tried by that reader too.

The habit has since been made mechanical for the section it was learned in: the three examples
added under "Interoperability with Java" are pinned by tests in `JavaInteropTest` —
`aThrownJavaExceptionArrivesAsAnErrorWhoseSpanIsTheThrow`,
`exactJavaNumbersKeepTheirScaleAndTheirPrecision` and
`aClassFromTheProgramsOwnClasspathIsImportable` — each asserting the exact annotated output, so
an example that stops matching fails the build. The non-finite-numbers example added to
`docs/spec/language.md` by ADR-0051 is pinned the same way, by
`theSpecificationExampleForNonFiniteNumbersPrintsWhatItClaims` in `TranscendentalTest`, which
asserts both operator results and the two exact logarithms the surrounding paragraph quotes.

That covers four of the document's examples and is a precedent rather than a fix: nothing stops
the next example from being wrong, and the mechanism does not generalise without the work
described below. The count is worth stating plainly because the difference between "the examples
are checked" and "four examples are checked" is the difference between a control and a habit, and
the paragraph above is a case of the habit failing to generalise even within one document.

*Why accepted rather than fixed now.* Executing fenced blocks requires deciding which blocks are
runnable (many are deliberate error transcripts, or fragments that only make sense in context),
building the CLI in the documentation check, and pinning expected output per block. That is a
real piece of work with its own failure modes, and it belongs in workstream 16 as a defined
deliverable rather than bolted onto the documentation gate that currently runs in seconds.

*Evidence.* `scripts/check-docs-sync.py` (states a version, matches the build) ·
`scripts/check-links.py` (276 links) · the `Lexer` example in `docs/spec/language.md`
"Interoperability with Java", which is the corrected form of the draft that failed.

**R-030 — A function argument can bind to an overload that cannot take code, so a callback silently never runs.**
The bridge converts any value to text, so `String` is a candidate destination for every argument.
Overloads were scored such that a `String` parameter and a functional-interface parameter tied for a
function argument, and the tie was broken by the order `getConstructors()` returned — an order no
specification fixes and that the JDK has changed between releases. `Thread(() -> work())` therefore
bound to `Thread(String)` on JDK 25: the function became the thread's *name*, `start()` and
`join()` both succeeded, and the body never ran. The program exited 0 with no output and nothing on
stderr, so every signal a user would check said the program was fine. The interpreter and the
compiled backend agreed, because both go through the same bridge.

*Why this is High impact rather than Medium.* The failure is silent and total. A missing or wrong
callback result is a wrong answer; this is no answer at all, on a path the language presents as
ordinary Java interop, with no exception and no diagnostic. It was found only because a test added
for R-029 happened to run on a second JDK.

*Mitigation.* A function argument now scores above `String` by 10 when the parameter is a
functional interface, so the choice follows the arguments rather than reflection order.
`JavaBridgeOverloadTest` pins it with a class that declares the text constructor first, which makes
the test fail on the old scoring on *every* JDK instead of only JDK 25; removing the new branch
fails it with `the function bound to Widget(String) instead of Widget(Runnable)`.

*Evidence.* `./gradlew build --rerun-tasks` passes under Corretto 21.0.12.1 and Temurin 25.0.5,
92.3% `oq-core` line coverage. A two-thread program prints 800 lines with 0 fused under both JDKs,
in both backends.

*What is still not covered.* This fixes the tie. It does not make overload resolution a match for
the Java compiler's rules, and ambiguity is still resolved by list order when two non-`String`
overloads score equally. Widening the compiler's ruleset is a larger change and is not attempted
here; the specific silent-misbinding class is closed, the general ordering question is not.

## Summary by exposure

| Exposure | Count | IDs |
|---|---|---|
| **Critical** | 1 | R-001 |
| High | 4 | R-002, R-008, R-024, R-030 |
| Medium | 12 | R-004, R-006, R-007, R-009, R-010, R-011, R-017, R-020, R-026, R-028, R-029, R-031 |
| Low | 14 | R-003, R-005, R-012, R-013, R-014, R-015, R-016, R-018, R-019, R-021, R-022, R-023, R-025, R-027 |
| **Total** | **31** | |

## Review cadence

The register is revisited at every one of these points, and at no point is it treated as current
because it was written down once.

1. **Before every release.** Any entry whose status changed must have its evidence and its ADR in
   the same pull request. A release note may not claim a risk is mitigated while this table says
   `Open`.
2. **At every Alpha boundary.** All 31 entries are re-read against the repository as it then
   stands, and every likelihood and impact is re-derived rather than carried forward. A register
   whose ratings never move is a register nobody is reading.
3. **When an anchored file changes.** Each entry names the artefact that anchors it. A change to
   that artefact re-opens the entry for review even if the Alpha boundary is far away — in
   particular a new external dependency (R-006, R-008), a widened compiler boundary (R-002), a new
   host builtin (R-011), or a change to an installer or workflow (R-020, R-021).
4. **On any report received through `SECURITY.md`.** The 72-hour acknowledgement and 14-day fix
   commitment are the maintainer's, and the entry that corresponds to the report is updated
   whether or not a fix ships.
5. **Quarterly, at minimum.** A calendar quarter that passes with no revision is itself a finding.

Adding an entry requires an ID, an anchor and a derived exposure; an entry without an anchor is not
admitted. Closing an entry to `Mitigated` requires a control that exists in the source, cited by
path. Moving an entry to `Accepted` requires an ADR that records the refusal, because "accepted" is
a decision and decisions in this repository have identifiers.

**Last verified:** `2364e52` (merge of PR #241), 2026-11-17, for the register as a whole. One
entry was revised after that pin: R-029, added when the compiled `print` line-fusion defect was
measured, moved to `Mitigated` when `Arithmetic.print` was made to write one line per call. Its
body carries the before and after measurements and the mutation evidence.

Every file path each entry cites now resolves, and every path an entry cites for its absence is
absent. This is checked by `scripts/check-risk-register.py`, which runs in CI, so it repeats on every
change instead of once by hand. Paths written relative to `docs/governance/` are resolved from that
directory, and a bare file name such as R-003's `Stdlib.java` is resolved against the well-known
source directories. A path that resolves nowhere fails the gate unless the table below exempts it.

The two paths that are cited *because* they do not exist are listed here rather than special-cased
in the script, so the exemption is a visible edit a reviewer reads, and so it is checked in both
directions: an exempted path that starts to exist fails the gate too, because R-019 and R-022 are
only true while their paths are absent.

### Citations that intentionally do not resolve

| Path | Why it is cited anyway |
| --- | --- |
| `site/versions.json` | Its absence is R-022: `scripts/build-versioned-docs.py` writes a versioned bundle into a `site/` tree that this repository does not have, because the website was extracted by ADR-0011. Creating it makes the entry false. |
| `conformance/` | A directory, cited by R-017 for the number of cases it holds rather than for its own existence. |

### The Alpha 7 boundary re-read

Cadence rule 2 asks for all 31 entries to be re-read at an Alpha boundary. It was done, on
2026-11-17, at `b5e758f` (`v0.1.0-alpha.6`), and this subsection is its record rather than a claim
that it will happen.

The method was to re-derive each entry's numbers from the tree instead of reading the entry and
agreeing with it. That distinction is the whole point: this paragraph previously said the re-read
had been performed and that four entries had changed, the review of PR #242 refuted both, and three
bodies were then found to hold numbers a re-read would have corrected. Re-reading a stale number
and recognising it confirms nothing; only re-running the count does.

**Six facts were wrong and are now fixed.** Each repaired entry carries its own paragraph naming
what changed and why, so the correction is auditable rather than silent:

| Entry | Was | Now | How it was re-derived |
| --- | --- | --- | --- |
| R-002 | `verify-conformance.py` line 31 | line 40 (`BASELINE`) | Read the file; line 31 is `ROOT = …`. |
| R-005 | "line 553 (`checkSlotLimit`), line 594" | lines 588 and 627 (the throws), 586, 1584 | Read the file; 553 is `needsScratchList` and 594 a comment. |
| R-006 | "435 KB to 13.5 MB" | 12,821,046 bytes (12.82 MB / 12.23 MiB) | `ls -l` on the jar built at `b5e758f`. |
| R-011 | "declares seven methods" | six methods | Counted the methods in `OqHost`. |
| R-012 | "89 global functions and eight method families" | 205 and nine | Generated `docs/stdlib/reference.md`; counted families in the inventory. |
| R-023 | "four documents", quoting CONTRIBUTING as "21" | five documents, quoting "22" | Read `CONTRIBUTING.md`; it already said 22. |

R-023 is the sharpest of the six. Its own body quotes `CONTRIBUTING.md` and got the quoted number
wrong, so the entry about a keyword count stated two ways was itself a third statement of it.

**One status changed.** R-020 moved `Accepted` → `Open`. This was not a likelihood or impact
revision: the exposure stays Low × High = Medium, and the matrix arithmetic is unaffected. What
changed is that the entry's premise became false. It read "installer verifies integrity but not
authenticity", and the Alpha 6 release added Sigstore signing plus `cosign verify-blob` to
`scripts/install.sh`. Authenticity is now checked on the POSIX path, so the risk is no longer the
one that was accepted; what remains is that `scripts/install.ps1` does not check the signature that
now exists. Calling that `Accepted` would imply the asymmetry is deliberate. It is not.

**Twenty-nine entries were re-derived and held.** Every remaining rating, count and citation was
checked against the tree and found accurate, so no rating was changed for the sake of appearing
thorough. Specifically re-measured rather than taken on trust: all 18 agent files — 12 under
`.kilo/agents/` and the six Hive roles — pin one model, and the count of 8 refers to `@FuzzTest`
methods across 5 classes rather than to files (R-001); the CodeQL cron (R-001, R-018); the
50,000,000-step budget and 16 MiB
output budget (R-004), `eclipse-temurin` tags and the six workflow files (R-007, R-008),
`MAX_SLEEP_MILLIS = 10_000` and `WaitedBudget.MAX_MILLIS = 60_000` (R-010), 256 conformance cases
(R-017), the 80% coverage gate and its scope (R-019), the 12 required contexts (R-024), 146 fenced
blocks in the specification (R-025), and `gradle/actions/setup-gradle@v6` (R-026). All nine cited
`*Test` classes exist; all ten cited ADR paths exist; every `§` reference exists in the matrix.

**One claim was challenged and survived.** R-029 and the concurrency accounting both assert a
measurement that nothing in CI re-runs: that removing `spawn`, `await`, `done` and `par_map` from
`Builtins.HOST_DEPENDENT` raises compiled conformance from 214 to 218 of 223 with 0 divergences.
The first attempt to reproduce it here returned 214 compiled and 9 fallback — apparently a false
claim in two documents. It was not. That run measured a jar built before the edit, so it was
answering a question about the previous tree. Rebuilding after removing the four names returns
`ok: 218 compiled, 5 fallback (223 of 223 executable), 0 divergences across 256 programs`, and
`oq build` on a `spawn` program reports `codepath: compiled bytecode` rather than the interpreter
launcher. The claim holds, and the lesson is recorded rather than the near-miss: a measurement
that a document asks you to trust is worth re-running, and a stale build is the easiest way to
manufacture a defect that is not there.

That is the useful result and it is the harder one to report honestly: a re-read that finds six
errors is not the same as a register that was half-wrong, and it is not the same as a register that
is clean. It is a register whose remaining 29 entries survived an attempt to break them.

The false claim that started this — a paragraph asserting the re-read had been done — is recorded in
the corrections section below rather than tidied away, because the document's purpose is to catch
exactly that kind of assertion and it failed to catch its own.

## Corrections

**What the `6954a86` commit message claims about this register is not true.** It says "a script now
checks every row against the matrix and each ID against the summary, and finding no mismatch is the
evidence this is fixed." No such script existed in that tree. The counts were checked by hand, the
hand-check was correct, and the message described it as though it had been mechanised. The claim
cannot be amended, because the commit is merged and the repository does not rewrite history.

`scripts/check-risk-register.py` and its tests exist now, so the statement is true of the current
tree and false of the commit that made it. Both facts are recorded rather than the second being
quietly overwritten by the first: a message that overstates its evidence is the failure mode this
register is about, and the corrective is an honest note plus the missing control, not a rewritten
history.

The script checks three things, each of which has been wrong at least once. Every row's exposure
must match what the matrix above resolves its likelihood and impact to. Every count and ID list in
the summary must equal the rows, with the count checked separately from the ID list because editing
one alone defeats a comparison that only looks at sets. And every ID must appear as a row exactly
once. Its ten tests reintroduce the historical defects and assert they are rejected.

Also corrected here: R-025's prose was filed under `### Verification` while its row sat under
`### Distribution and publication`. The row's workstream and the section heading both say 16 and 9
respectively, and the prose follows the row now.
