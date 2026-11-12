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
| R-005 | Compiler slot and constant-pool ceilings refuse large functions | Low | Low | Low | 4 | Accepted |

**R-002 — Bytecode backend and interpreter diverge.**
Two implementations of one contract is the project's largest structural risk. The compiled backend
is deliberately smaller than the language: capturing closures, `use`, modules as separate
artefacts and every host-dependent builtin are refused by name. A refusal is safe; a *silent*
difference is not, and the refusal list is large enough that a widening change could compile
something that used to be interpreted differently.

*Mitigation.* The interpreter is normative and the bytecode backend must either agree or refuse —
rule 2 of `oq-hive-development`. The measured subset is pinned: `204 of 213` executable
conformance programs compile and produce byte-identical output, asserted by
`BytecodeCompilerTest.everyCompilableConformanceProgramMatchesTheInterpreter`, so the number cannot
slide down silently. `scripts/verify-conformance.py` independently runs `oq run` and the built jar
over the whole corpus with `BASELINE = {"executable": 213, "compiled": 203}` and fails on mismatch.
`JarBuilder`-produced jars are compared against the interpreter by `BuildJarTest`. The boundary is
documented as an ordered list in `docs/spec/backend.md` "The current boundary", so widening it is a
deliberate act with a paper trail.

*Residual.* Both measurements come from the same source tree and the same authors, so they detect
divergence between the two backends but cannot detect a shared misreading of the specification.
See R-008.

*Evidence.* `docs/spec/backend.md` "The compiled subset, measured" · `scripts/verify-conformance.py`
line 31 · `oq-cli/src/test/java/dev/oqthara/cli/BytecodeCompilerTest.java` ·
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

*Evidence.* `oq-compiler/src/main/java/dev/oqthara/compiler/BytecodeCompiler.java` line 553
(`checkSlotLimit`), line 594.

### Dependencies and portability

| ID | Risk | Likelihood | Impact | Exposure | Workstream | Status |
|---|---|---|---|---|---|---|
| R-006 | SQLite is the first external runtime dependency and multiplies the artifact size | High | Medium | Medium | 8 | Accepted |
| R-007 | The JNI driver makes Alpine/musl unsupported | Medium | Medium | Medium | 9 | Accepted |
| R-008 | No dependency verification and no action pinning by digest | Medium | High | High | 9 | Open |

**R-006 — SQLite is the first external runtime dependency and multiplies the artifact size.**
`org.xerial:sqlite-jdbc:3.45.3.0` plus `org.slf4j:slf4j-nop:2.0.13` are the only external runtime
dependencies in the project, and they take the standalone jar from 435 KB to 13.5 MB. Every built
program that carries the driver now costs the same 13.5 MB, because `JarBuilder` copies both
`dev.oqthara` and `org.sqlite`/`org.slf4j` prefixes. A build that previously produced a small jar
now produces a large one, and the size is invisible until someone looks.

*Mitigation.* Recorded as a decision with its cost stated, not absorbed silently: ADR-0040 D1.
The dependency is one driver and one no-op logging binding, not a JDBC stack — general JDBC,
connection handles and `begin`/`commit`/`rollback` are declined. `BuildJarTest` compares the jar's
output with the interpreter's, so the packaging change is pinned by a test rather than assumed.

*Why accepted.* The alternative is no database support at all, or a hand-written SQLite binding,
which is a larger liability than a maintained driver.

*Evidence.* `docs/adr/0040-database-support.md` D1 · `oq-stdlib/build.gradle.kts` ·
`docs/governance/alpha-4-requirement-matrix.md` `§17` (435 KB → 13.5 MB).

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

**R-008 — No dependency verification and no action pinning by digest.**
There is no `gradle/verification-metadata.xml`, so Gradle resolves `sqlite-jdbc`, `slf4j-nop`,
Jazzer and the JUnit BOM by coordinate without a checksum manifest of the project's own. Every
GitHub Actions `uses:` in the workflows references a moving tag (`actions/checkout@v7`,
`actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, `actions/upload-artifact@v7`) rather than
a commit SHA. A compromised or retargeted tag executes in a job that holds `GITHUB_TOKEN` and, in
the release workflow, the publishing path.

*Mitigation.* Partially mitigated by accident of design: CI jobs declare `permissions: contents:
read`, the release workflow is gated on `scripts/check-circleci.py` fail-closed, and the publish
step uses `softprops/action-gh-release@v3` with no long-lived registry credential. None of that
prevents a malicious action from exfiltrating the job token.

*Why open.* Generating verification metadata is a one-command change
(`./gradlew --write-verification-metadata sha256`), but it must be regenerated on every dependency
change and it will fail the build until it is. Pinning actions by digest is a mechanical edit across
six workflow files. Neither is done, so the risk is recorded as `Open` with the fix known and
unstarted rather than described as mitigated.

*Evidence.* absence of `gradle/verification-metadata.xml` · `.github/workflows/ci.yml`,
`.github/workflows/release.yml`, `.github/workflows/codeql.yml`, `.github/workflows/nightly.yml`,
`.github/workflows/docker.yml`, `.github/workflows/site-content-dispatch.yml` (`uses:` lines).

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
seven methods and no permission hook, no allow-list, and no sandbox. `run_command` takes one
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
| R-014 | A Java callback cannot return an Oqthara function to its caller | Medium | Low | Low | 6 | Open |
| R-015 | Local and UTC time are easy to confuse | Medium | Low | Low | 8 | Mitigated |
| R-016 | XML is deliberately absent | Medium | Low | Low | 8 | Accepted |

**R-012 — Standard-library breadth has no denominator.**
"Broad standard library" is a requirement that cannot currently be measured, because nothing states
how many functions the reference language has. The matrix is explicit: "Still unmeasured against the
Python reference, so 'breadth' has no denominator." What is measured is the count on this side — 89
global functions and eight method families — which answers "how much is there" and not "how much is
missing". Three whole domains were recently found absent (encoding, hashing, and a filesystem group)
after the library had been described as broad, which is the failure mode the missing denominator
produces.

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

**R-014 — A Java callback cannot return an Oqthara function to its caller.**
The direction *into* Java is closed: a function argument that meets an interface with exactly one
abstract method becomes a `java.lang.reflect.Proxy` that calls back through the host that created
it, so `xs.forEach(fun(x) print x end)` and `Collections.sort(xs, (a, b) -> a - b)` both work on
both backends, and a callback over a `List<Integer>` is handed `long` rather than `Integer`. The
earlier entry described two independent failures and blamed dispatch for skipping reflection on a
non-`Class<?>` receiver. That was wrong — `xs.ensureCapacity(100)` and `Collections.sort(xs)`
both reach reflection — and the correction is recorded in ADR-0032 D5. The direction *out of*
Java remains closed: a method that takes a `Function` and hands one back on return yields a JDK
object that Oqthara can call through its methods but cannot hold as a `fun` value, and a Java lambda
never becomes a value the language can pass to `map`. Nothing in the standard library needs that
shape today, which is why this is Low rather than Medium.

The first half of that sentence was itself untrue until it was measured. Calling such a value did
not work: `Comparator.naturalOrder()` answers a package-private `Comparators$NaturalOrderComparator`
and `Function.identity()` answers a hidden lambda class, and reflection reports the *declaring*
class's accessibility rather than the interface's, so `compare` and `apply` were both refused with
"cannot access a member of class ... with modifiers public". The same signature is now resolved on a
public supertype, and both calls work; `JavaBridgeAccessibilityTest` pins them. The help text was
corrected with it — `map(xs, Comparator.naturalOrder())` used to say the value "cannot be called",
which contradicted the method call that had just succeeded, and now says that it is a Java object
whose methods are callable but which cannot stand where a `fun` is expected.

*Mitigation.* None available at the language level. The gap is named precisely, with the two distinct
causes distinguished, so it is not mistaken for a missing method.

*Why open.* This is the most-requested shape of Java interoperability that does not exist, and it is
open rather than accepted because no decision has been taken about whether a function value should
convert to a single-method interface implicitly. That decision needs an ADR.

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
| R-018 | Fuzzing does not reach text, path resolution, CSV or semantic analysis | Medium | Medium | Medium | 11 | Open |
| R-019 | A defect can survive in the one path `scripts/verify-conformance.py` does not compare | Low | Medium | Low | 11 | Mitigated |
| R-024 | A required CI context can vanish without failing | Medium | High | High | 11 | Mitigated |
| R-026 | The build cache replays a Linux test result as a macOS or Windows one | High | Medium | Medium | 11 | Mitigated |
| R-028 | A timing test asserts exact zero and fails only under CI load | Medium | Medium | Medium | 11 | Mitigated |

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

*Residual.* The failure mode itself is unavoidable: a required context is identified by name alone,
and GitHub cannot distinguish "not dispatched" from "still queued". The same silent stop could
happen on GitHub Actions, and the recovery is the mirror image — dispatch the CircleCI workflow by
hand and let the release gate accept that evidence. `installers-macos` remains CircleCI-only and is
not a required context, so a macOS installer regression would not block a merge.

*Evidence.* `docs/adr/0049-macos-ci-on-github-actions.md` · `.github/workflows/ci.yml` (matrix) ·
`scripts/apply-branch-protection.sh` (required contexts) · `scripts/check-circleci.py` (grouped
requirements) · `scripts/tests/test_check_ci_gate.py`.

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
`conformance/` holds 229 cases, and `scripts/verify-conformance.py` compares the interpreter against
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

**R-018 — Fuzzing does not reach text, path resolution, CSV or semantic analysis.**
Eight targets cover the lexer, the parser (three shapes), the whole parse→lower→emit pipeline, JSON
(two shapes) and the pure stdlib builtins. `docs/spec/fuzzing.md` "What is not covered yet" names the
rest of the stdlib — text operations, path resolution, CSV — plus semantic analysis, which has no
separate pass. Generated class files are checked for magic only; full verification happens when the
JVM loads them in `BytecodeCompilerTest`. So the fuzzer can reach the parser and not the resolver,
which is where a path joins an untrusted string to a base directory.

*Mitigation.* `fuzzTest` is not part of `check`, so ordinary builds stay fast, but it does run in the
`jazzer-fuzz` CircleCI job on every push and pull request and in `.github/workflows/nightly.yml` every
day at 03:00 UTC with a 60-second cap per target. Findings become permanent tests: the curated corpus
in `oq-core/src/fuzz/resources/dev/oqthara/core/curated-cases.txt` is replayed by
`CuratedCorpusTest` on every build, and `docs/spec/fuzzing.md` requires that a finding is never
closed by deleting the input.

*Why open.* Each gap is a target that has not been written. The eight that exist found seven real
defects, which is an argument for writing more rather than for trusting the current set.

*Evidence.* `docs/spec/fuzzing.md` · `oq-core/src/fuzz/`, `oq-stdlib/src/fuzz/`,
`oq-compiler/src/fuzz/` · `.github/workflows/nightly.yml` · `.circleci/config.yml`.

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
`scripts/check-links.py` (195 links) · the `Lexer` example in `docs/spec/language.md`
"Interoperability with Java", which is the corrected form of the draft that failed.

### Distribution and publication

| ID | Risk | Likelihood | Impact | Exposure | Workstream | Status |
|---|---|---|---|---|---|---|
| R-020 | Installer verifies integrity but not authenticity | Low | High | Medium | 9 | Accepted |
| R-021 | No update or rollback path; re-running the installer is the only upgrade | Medium | Low | Low | 9 | Open |
| R-022 | Versioned-docs script writes to a directory that no longer exists | High | Low | Low | 12 | Open |
| R-023 | Keyword count is stated two ways across four documents | High | Low | Low | 1 | Open |
| R-027 | Line-number citations in the stdlib inventory drift silently | High | Low | Low | 16 | Open |
| R-025 | Nothing executes the specification's code examples, so a wrong one ships silently | Medium | Low | Low | 16 | Accepted |

**R-020 — Installer verifies integrity but not authenticity.**
`scripts/install.sh` downloads `oq.jar` and `oq.jar.sha256` from the *same* GitHub release, checks the
digest, then runs `java -jar … --version` as a smoke test. `scripts/install.ps1` does the same with
`Get-FileHash -Algorithm SHA256`. The digest proves the bytes arrived unaltered; it does not prove who
produced them, because an attacker who can replace the jar can replace the checksum beside it. There
is no signature, no pinned public key, no Sigstore attestation and no Authenticode signature on the
Windows path.

*Mitigation.* The window is narrow: the assets live on GitHub's release infrastructure, the release
workflow regenerates the checksum from the built jar in a fresh directory and verifies it with
`sha256sum -c`, and the artifact is smoke-tested on a clean runner path before publication. The
checksum catches transport corruption and a truncated download, which is the common case and is
covered by `scripts/tests/run-installer-tests.sh` including a deliberately truncated response.

*Why accepted.* Signing needs a key with a custody story, and a self-issued key in a public
repository proves very little. Recorded rather than claimed as solved.

*Evidence.* `scripts/install.sh` (checksum fetch, `sha256_of`, smoke test) · `scripts/install.ps1` ·
`scripts/tests/run-installer-tests.sh` · `.github/workflows/release.yml` Checksums and
"Gate - verify the checksum in a fresh directory" steps.

**R-021 — No update or rollback path; re-running the installer is the only upgrade.**
There is no `oq update`, no version manager, no rollback to a previous release, and no bundled JDK —
both installers require a pre-existing `java` on `PATH` and fail if it is absent. Workstream 9 as
written asks for "JDK runtime selection, platform installers, integrity, update, rollback"; only the
installers and integrity exist. The workstream is unowned, per
[`alpha-4-workstream-assignment.md`](alpha-4-workstream-assignment.md).

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

**R-023 — Keyword count is stated two ways across four documents.**
`Lexer.KEYWORDS` holds 21 entries. `docs/stdlib/reference.md` says "21 keywords (budget: 30)" and
`CONTRIBUTING.md` says "The keyword budget is 30; we are at 21" — both correct, and
`scripts/check-docs-sync.py` keeps them correct. But `AGENTS.md` says "budget 30, now 20", and two
agent files (`oq-hive-orchestrator`, `oq-hive-language-syntax`) repeat "It currently uses 20." So two
of the four statements are correct and two are stale, and the ones checked by CI are not the ones an
agent reads first.

*Mitigation.* `scripts/check-docs-sync.py` cross-checks the reference against `Lexer.KEYWORDS` and
against `CONTRIBUTING.md`, so the machine-readable pair cannot drift. The agent-facing statements have
no equivalent check.

*Why open.* The fix is to make the agent files read the count from one source, or to extend
`scripts/check-docs-sync.py` to cover `AGENTS.md` and `.github/agents/*.agent.md`. Neither is
done.

*Evidence.* `oq-core/src/main/java/dev/oqthara/core/Lexer.java` `KEYWORDS` ·
`docs/stdlib/reference.md` line 11 · `CONTRIBUTING.md` line 9 · `AGENTS.md` line 21 ·
`.github/agents/oq-hive-language-syntax.agent.md` rule 2 · `scripts/check-docs-sync.py`
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

## Summary by exposure

| Exposure | Count | IDs |
|---|---|---|
| **Critical** | 1 | R-001 |
| High | 3 | R-002, R-008, R-024 |
| Medium | 12 | R-004, R-006, R-007, R-009, R-010, R-011, R-014, R-017, R-018, R-020, R-026, R-028 |
| Low | 12 | R-003, R-005, R-012, R-013, R-015, R-016, R-019, R-021, R-022, R-023, R-025, R-027 |
| **Total** | **28** | |

## Review cadence

The register is revisited at every one of these points, and at no point is it treated as current
because it was written down once.

1. **Before every release.** Any entry whose status changed must have its evidence and its ADR in
   the same pull request. A release note may not claim a risk is mitigated while this table says
   `Open`.
2. **At every Alpha boundary.** All 28 entries are re-read against the repository as it then
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

**Last verified:** `b5813d1` (merge of PR #84), 2026-11-10.
