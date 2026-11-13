# Alpha 4 threat model

Workstream 10 (Security Engineering) requires a threat model for the language runtime and its
host boundary. This is it.

The scope is the Oqthara implementation and the distribution machinery around it: the lexer,
parser, interpreter, bytecode compiler, standard library, Java interop boundary, the jar and
installer path, and the content pipeline that feeds the website. User programs are **out of
scope as victims** and **in scope as attackers**: `SECURITY.md` says so plainly — "Oqthara
executes arbitrary code by design, so a malicious `.oq` file is equivalent to a malicious jar".
A program that runs is trusted to do what it says. What this model examines is everywhere a
program's *input* — a file, a URL, a database, an environment variable, a command line — stops
being data and starts steering the runtime.

This closes the `Threat model` row of
[`alpha-4-requirement-matrix.md`](alpha-4-requirement-matrix.md).

## Method

Two framings, used together, because neither alone describes this project honestly.

**Asset, attacker, surface.** First: enumerate what is worth taking, who is trying to take it,
and every point where outside data reaches the runtime. This framing carries the sections below.
It is the more useful one here because the interesting boundary is not a network perimeter — it
is the line between "a program printed something" and "the JVM did something".

**STRIDE, per asset.** Second: for each surface, ask whether the plausible failure is spoofing,
tampering, repudiation, information disclosure, denial of service, or elevation of privilege.
This catches the threats the asset framing misses, particularly repudiation (a build that
cannot be traced to a commit) and denial of service (which is the dominant residual risk in this
codebase — see [Resource exhaustion](#5-resource-exhaustion)).

No numeric risk scoring is used. A score that says "7.2" invites an argument about the number
instead of about the control. Each threat instead gets an explicit status with the reasoning
attached, and every status is one of:

| Status | Meaning |
|---|---|
| **Mitigated** | A control exists in the source, is cited by path and line, and has a test that would fail without it. |
| **Partially mitigated** | A control exists but its coverage is knowingly incomplete, and the gap is named. |
| **Accepted** | No control, and the decision to have none is recorded — usually in an ADR, which is why the reason survives. |
| **Not mitigated** | No control, no recorded decision to omit one, and the threat is live. |

The distinction between `Accepted` and `Not mitigated` is the one that matters most. An accepted
threat is a choice; an unmitigated one is an oversight. Reporting an oversight as an acceptance
would make this document useless.

## Assets

| ID | Asset | Why an attacker wants it | Where it lives |
|---|---|---|---|
| A-1 | The user's filesystem | Read, write, delete anything the user can | Reached through the file builtins; `Stdlib.resolve` |
| A-2 | The user's credentials in the environment | Tokens, cloud keys, `AWS_SECRET_ACCESS_KEY` | `env()` reads the whole environment by name |
| A-3 | The network position of the host | Reach internal services the user can reach | `http_get`/`http_post`; no egress policy |
| A-4 | The JVM's authority inside the process | Arbitrary class loading and reflection | `use`, `JavaBridge` |
| A-5 | The database files a program is handed | Integrity and confidentiality of stored rows | `DatabaseModule` |
| A-6 | The integrity of a published `oq.jar` | Anything a downstream user executes | `scripts/install.sh`, `scripts/install.ps1`, release workflow |
| A-7 | The integrity of the website's catalogue | Content that readers trust | `scripts/dispatch-site-content.mjs`, `docs/catalog.json` |
| A-8 | The maintainer's release tokens | Ability to publish as the project | GitHub Actions secrets, `SITE_DISPATCH_TOKEN` |
| A-9 | Availability of the machine running a program | CPU, memory, file descriptors, disk | Every unbounded loop and every large read |
| A-10 | Reproducibility of a build | Knowing which source produced which artefact | Git history, PR merge, checksums |

## Trust boundaries

There are five boundaries that matter, and only one of them is a network boundary.

```
  boundary 1                boundary 2                    boundary 3
  ┌─────────────┐      ┌──────────────────┐        ┌────────────────────┐
  │ .oq source  │ ───► │ Parser / Lexer   │ ─────► │ Interpreter        │
  │ conformance │      │ BytecodeCompiler │        │ or compiled class  │
  └─────────────┘      └──────────────────┘        └─────────┬──────────┘
   untrusted text        untrusted text                     │
                                                            │ boundary 4
                                    ┌───────────────────────▼──────────────┐
                                    │ Standard library builtins            │
                                    │ (36 host-dependent names)            │
                                    └──┬────────┬────────┬────────┬────────┘
                                       │        │        │        │
                            boundary 5 │        │        │        │
                                  ┌────▼──┐ ┌───▼───┐ ┌──▼────┐ ┌─▼─────┐
                                  │ files │ │ env / │ │ HTTP  │ │ JVM   │
                                  │       │ │ procs │ │       │ │ refl. │
                                  └───────┘ └───────┘ └───────┘ └───────┘
                                    host OS, network, classpath
```

| # | Boundary | Crossing is | Checked by |
|---|---|---|---|
| 1 | Source text → parser | A `.oq` file, a conformance case, a REPL line, or fuzz input | `Parser` nesting limits; nothing else — parsing a program is not a security event, because a program that cannot be parsed does not run |
| 2 | AST → compiled bytecode | The program the user chose to compile | `BytecodeCompiler` slot limit; the four ordered refusal classes |
| 3 | Bytecode → JVM verifier | Generated class files | The JVM's own verifier — the only control, and a strong one |
| 4 | Program → host | 36 builtin names in `Builtins.HOST_DEPENDENT` | Per-call budgets, one shared interface `OqHost` with no capability hook |
| 5 | Runtime → operating system | Files, processes, sockets, environment | Nothing in Oqthara. The OS's own permissions are the control. |

Boundary 4 is the interesting one and boundary 5 is the honest one: Oqthara adds no confinement
at the operating-system edge, and `docs/spec/language.md` line 1335 says so in the specification
itself — "Oqthara is **not** a sandbox. A program that is allowed to run can read the files the
user can read, start the processes the user can start, and reach the network the user can reach.
The limits above prevent accidental exhaustion; they do not contain hostile code."

Every threat below is either about boundary 4 or about the machinery that ships the runtime. None
of them is about a hostile program being stopped, because that is not a promise this project
makes.

## Entry points

`oq-stdlib/src/main/java/dev/oqthara/stdlib/Builtins.java` lines 45–54 declare exactly 36 names
as `HOST_DEPENDENT`. That set is the authoritative list of builtins the runtime knows cannot run
inside generated bytecode, and `Builtins.supports` (line 69) returns `false` for every one of
them, which is what makes the compiler refuse the call at
`oq-compiler/src/main/java/dev/oqthara/compiler/BytecodeCompiler.java` line 1245 rather than
emit it.

| Group | Builtins | What crosses the boundary |
|---|---|---|
| Program | `args`, `input`, `exit` | Command-line arguments and stdin |
| Files | `read`, `read_lines`, `save`, `append`, `exists`, `list_files`, `delete`, `make_dir`, `file_size`, `path_join`, `path_name`, `path_parent`, `cwd` | Paths on the host filesystem |
| Time and randomness | `now`, `today`, `datetime`, `timestamp`, `sleep`, `random`, `random_item` | Clock; a non-cryptographic PRNG |
| Network | `http_get`, `http_post` | Outbound HTTP and HTTPS |
| Process and environment | `env`, `run_command`, `cpu_count`, `memory_mb` | Environment variables; a child process |
| Concurrency | `spawn`, `await`, `done`, `par_map` | Virtual threads on a shared executor |
| Collection helpers | `sort_by`, `map`, `filter` | Listed because they take a callable and a callable can be host-dependent |

The three collection helpers appear in the set for a structural reason rather than a security
one: the compiler's callable machinery cannot yet accept a closure, so they are refused with the
rest. That is worth noting because a reader who counts the set as a security allow-list would
overcount the surface by three.

Two builtins outside the list are also worth naming, because they are entry points of a
different kind: `secure_equal`, `pbkdf2_sha256` and `secure_random_hex` are pure computation over
their arguments, and `use` is not a builtin at all — it is interpreter syntax that reaches
`Class.forName`. `use` is covered under [Java interop](#2-java-interop--javabridge).

---

## 1. Host access surface

**Assets at risk.** A-1 (filesystem), A-2 (environment), A-3 (network position), A-9
(availability).

**Boundary crossed.** Boundary 4 — program to host — and then boundary 5, which Oqthara does not
police.

**Entry points.** The 36 `HOST_DEPENDENT` names above.

### What actually bounds them

`oq-runtime/src/main/java/dev/oqthara/runtime/OqHost.java` is the entire host interface, and it
has six members:

```java
Object call(Object callee, List<Object> args, Span span);
PrintStream out();
PrintStream err();
BufferedReader in();
List<String> programArgs();
Path workingDirectory();
```

There is **no capability parameter, no permission set, no allow-list and no hook for one.** A
builtin that wants to read a file calls `Path.of(...)` and `Files.readAllBytes(...)` directly. A
builtin that wants to start a process calls `new ProcessBuilder(...)` directly. The interface is a
dependency-injection seam for testing, not a security boundary, and reading it as one would be a
mistake worth correcting in a review.

A grep across the whole repository for `SecurityManager`, `AccessController`, `doPrivileged` and
`setAccessible` returns nothing. The Java platform's own confinement mechanism is not used, and
in current JDKs it is removed or degraded anyway, so its absence is a correct reading of the
platform rather than an oversight.

What does exist is a set of per-call ceilings, documented as part of the language contract in
`docs/spec/language.md` under "Host calls are bounded" and implemented in
`oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java`, plus the two runtime budgets in
`oq-runtime/src/main/java/dev/oqthara/runtime/`:

| Builtin | Ceiling | Constant | Line |
|---|---|---|---|
| `print`, `write`, `write_text` | 16 MiB total output, per process | `OutputBudget.MAX_BYTES` | `OutputBudget.java` 14 |
| `read`, `read_lines` | 16 MiB per file | `Stdlib.MAX_TEXT_BYTES` | 55 |
| `input()` | 16,777,216 characters per line | `Stdlib.readLineBounded` | 1820 |
| `run_command` | 30 s total, 16 MiB output, one executable, no shell | `Stdlib.COMMAND_TIMEOUT` | 60 |
| `http_get`, `http_post` | 15 s connect, 30 s headers, 30 s body, 16 MiB body | lines 66, 72, 78 | — |
| `save`, `append` | 16 MiB per call | `MAX_TEXT_BYTES` | 55 |
| `sleep` | 10 s per call, 60 s total | `MAX_SLEEP_MILLIS`; `WaitedBudget.MAX_MILLIS` | 81; `WaitedBudget.java` 15 |
| `list_files` | 100,000 entries | `MAX_LIST_ENTRIES` | 97 |

### Threats

| ID | Threat | STRIDE | Status |
|---|---|---|---|
| TH-01 | A program reads any file the user can read, including `~/.ssh/id_ed25519` | Information disclosure | **Accepted** |
| TH-02 | A program writes or deletes any file the user can write | Tampering | **Accepted** |
| TH-03 | A program exfiltrates the environment through `env("AWS_SECRET_ACCESS_KEY")` | Information disclosure | **Accepted** |
| TH-04 | A program reaches internal network services through `http_get` | Information disclosure / elevation | **Accepted** |
| TH-05 | A program starts an arbitrary executable through `run_command` | Elevation of privilege | **Accepted**, with the shell excluded |
| TH-06 | A program exhausts the disk by writing 16 MiB per call in a loop | Denial of service | **Partially mitigated** |
| TH-07 | A program blocks a `java -jar` process indefinitely because compiled code has no step or host-call budget | Denial of service | **Accepted**, documented |
| TH-08 | A user runs an untrusted program believing the limits are a sandbox | Spoofing (of the safety property) | **Partially mitigated** |

**TH-01, TH-02, TH-03, TH-04 — accepted, and stated in the specification.**
`Stdlib.resolve` (line 1723) does exactly this: an absolute path is returned unchanged, a relative
path is resolved against `host.workingDirectory()` and normalised. There is no root confinement,
no deny-list, and no advisory lock. `docs/spec/language.md` line 1205 states the consequence for
the file builtins — "Neither copying nor reading is confined to the working directory" — and line
1335 states it for the whole runtime. `env` is `System.getenv(name)` at line 1664, one string in,
one string or `null` out. `http_get` uses `java.net.http.HttpClient` with `Redirect.NORMAL` at
line 1452 and no egress policy.

This is `Accepted` rather than `Not mitigated` because the decision is recorded in the
specification and in `SECURITY.md`, and because the alternative — a permission model — is a
language design change, not a patch. Naming it as a threat here is what stops a future reader
mistaking "there are limits" for "there is a sandbox".

**TH-05 — accepted, with one real control.**
`run_command` at line 1665 builds `new ProcessBuilder(List.of(command))`. The single-element
list is the control: there is no shell, so `;`, `&&`, `$(...)` and quoting are not interpreted,
and the argument is one executable path with no arguments at all.
`HostBuiltinLimitsTest.runCommandDoesNotGoThroughAShell` (line 256) pins that behaviour. The
ceiling is one 30 s deadline covering both the output read and the exit wait,
`destroyTree(process)` on breach, and a 16 MiB output cap —
`runCommandTimeoutKillsTheWholeProcessTree` (266) and `runCommandOutputIsBounded` (347) test
both. So a program cannot pass arguments, cannot inject a second command, and cannot make the
child run for more than 30 s. It can still run `/bin/sh` *as the executable* and then feed it
through a file, which is why TH-05 stays accepted rather than mitigated: excluding the shell
from `ProcessBuilder` does not exclude the shell from the filesystem.

**TH-06 — partially mitigated.**
`save` and `append` each charge `MAX_TEXT_BYTES` per call, so a single call cannot write more than
16 MiB. Nothing caps the number of calls. A loop that appends 16 MiB a thousand times fills the
disk, and only the step budget (`Interpreter.maxSteps`, 50,000,000 at line 52) stands between
the program and a full volume — a budget that
`HostBuiltinLimitsTest.theWriteLimitIsPerCallSoSaveThenAppendCanBuildALargerFile` (line 467)
deliberately demonstrates is per-call rather than per-process. The gap is real and intentional:
a per-process write budget would break legitimate log-generating programs. It is
`Partially mitigated` because the per-call ceiling bounds the damage per unit of progress but not
the total.

**TH-07 — accepted, and it is the sharpest limitation in this document.**
`docs/spec/language.md` lines 1327–1338 state it without softening: a jar built as **compiled
bytecode** "is real bytecode, not an embedded interpreter, so it has no step budget and no
host-call deadlines: only its output is capped". A compiled program that loops runs until
something outside stops it, and runaway allocation is stopped only by the JVM heap, which
`BuildJarTest.aCompiledJarReportsRunningOutOfMemoryAsAProgramError` (line 247) pins as a program
error rather than a crash.

There are two jar shapes and the distinction is not visible in the file name:

| Jar shape | Step budget | Host-call deadlines | Output cap |
|---|---|---|---|
| Compiled bytecode | None | None | 16 MiB |
| Interpreter launcher (`codepath: interpreter launcher`) | `maxSteps` 50,000,000 | Yes, per the table above | 16 MiB |

`oq-cli/src/main/java/dev/oqthara/cli/EmbeddedMain.java` line 60 is the launcher path: it builds an
`Interpreter` with the default `maxSteps` and runs the source. A program the compiler refused —
anything using capturing closures, `use`, modules as separate artefacts, or a host-dependent
builtin — is built as a launcher and therefore keeps its budgets. A program the compiler accepted
gets strictly less protection than the same program under `oq run`. The specification says "Run
untrusted programs through `oq run`, or use a jar built as an interpreter launcher", which is
correct advice and is also the whole mitigation.

**TH-08 — partially mitigated by documentation only.**
The phrase "not a sandbox" appears in `docs/spec/language.md` (line 1335) and the limits table is
labelled as preventing "accidental exhaustion". `SECURITY.md` puts user programs out of scope.
That is three places saying the same thing, which is as much as documentation can do. Nothing
stops a reader from stopping at the table and concluding the opposite, because a table of limits
*looks* like a sandbox. This is the threat most likely to be realised by a well-meaning user
rather than an attacker.

### Residual risk

Total for A-1, A-2, A-3, A-5 and A-9: the program has the user's authority, minus a shell in
`run_command`, plus per-call ceilings that bound the cost of a mistake. There is no boundary to
erode, so there is no residual risk in the usual sense — the risk is the design.

### Required tests

| Test | Pins |
|---|---|
| `HostBuiltinLimitsTest.exactlySixteenMebibytesIsAllowed` (104) | The ceiling is inclusive, so the documented number is the real number |
| `HostBuiltinLimitsTest.oneBytePastSixteenMebibytesIsRefused` (114) | The ceiling is a refusal, not a truncation |
| `HostBuiltinLimitsTest.readRefusesAFileLargerThanTheLimit` (158) | The read path enforces it as well as the write path |
| `HostBuiltinLimitsTest.runCommandDoesNotGoThroughAShell` (256) | TH-05's single control |
| `HostBuiltinLimitsTest.runCommandTimeoutKillsTheWholeProcessTree` (266) | A child cannot outlive its deadline by spawning |
| `HostBuiltinLimitsTest.theWriteLimitIsPerCallSoSaveThenAppendCanBuildALargerFile` (467) | TH-06's gap is deliberate and documented in a test |
| `FileOperationsTest.walkDoesNotFollowASymbolicLinkIntoACycle` (189) | A symlink loop cannot hang `walk` |
| `StepBudgetTest.infiniteLoopStopsWithADiagnostic` (33) | The interpreter has a step budget at all |

**Missing test.** No test asserts that a *compiled* jar has no step budget. The behaviour is
documented in `docs/spec/language.md` and derivable from the absence of a counter, but nothing
fails if a future change gives compiled code a budget or takes one away. A test that builds a
looping program, runs the jar with a wall-clock limit, and asserts it is still running after the
interpreter would have stopped would close TH-07's documentation gap. It is not present in the
repository.

---

## 2. Java interop / JavaBridge

**Assets at risk.** A-4 (JVM authority), and through it A-1, A-3 and A-8.

**Boundary crossed.** Boundary 4, then boundary 5, without the operating system being involved at
all.

**Entry points.** `use <class>`, then method calls, constructor calls and field reads on the
resulting values.

### The real boundary

`oq-core/src/main/java/dev/oqthara/core/Interpreter.java` line 470, `loadClass`, builds a
candidate list — the path as written, then `java.lang.<path>` and `java.util.<path>` when the path
has no dot — and calls `Class.forName(candidate)` on each. Nothing filters the result. Any class
on the classpath is loadable, which for the standalone jar means the JDK plus `sqlite-jdbc` plus
SLF4J, and for a program run from a directory means whatever a curious user put on `-cp`.

`oq-core/src/main/java/dev/oqthara/core/JavaBridge.java` (343 lines) then reaches that class
through four public-member paths:

| Capability | Accessor | Line | Modifier filter |
|---|---|---|---|
| Construct | `type.getConstructors()` | 47 | Public constructors only |
| Instance method | `receiver.getClass().getMethods()` | 76 | Public methods only |
| Static method | `type.getMethods()` | 104 | `Modifier.isStatic` required |
| Static field | `type.getField(name)` | 136 | `Modifier.isStatic` required |
| Instance field | `receiver.getClass().getField(name)` | 146 | Public field only |

The `get*` family returns public members by definition, so `setAccessible` is never needed and
never used — grep confirms it appears nowhere in the repository. That is a genuine, if narrow,
control: `private` and module-protected members are unreachable, and a JDK class that hides its
dangerous surface behind a private constructor is not constructible from Oqthara.

### Threats

| ID | Threat | STRIDE | Status |
|---|---|---|---|
| TH-09 | `use java.lang.Runtime` then `getRuntime().exec(...)` reaches process execution without `run_command` | Elevation of privilege | **Not mitigated** |
| TH-10 | `use java.nio.file.Files` reaches the whole filesystem API without the file builtins and without their ceilings | Tampering / information disclosure | **Not mitigated** |
| TH-11 | `use java.net.http.HttpClient` reaches the network without the HTTP timeouts and body cap | Information disclosure | **Not mitigated** |
| TH-12 | `use java.lang.System` then `exit(0)` ends the process without `exit()` semantics | Denial of service | **Not mitigated** |
| TH-13 | A reflective call bypasses a builtin's argument validation, so a `run_command`-shaped operation is reachable with arguments | Elevation of privilege | **Not mitigated** |
| TH-14 | Loading an attacker-named class runs its static initialiser | Elevation of privilege | **Not mitigated** |
| TH-15 | A host-dependent builtin is reachable from compiled bytecode through the bridge | Elevation of privilege | **Partially mitigated** |

These are grouped deliberately. `run_command` accepting one executable with no arguments is a
meaningful restriction; `Runtime.getRuntime().exec(new String[]{"/bin/sh","-c","..."})` is the same
operation unrestricted, reachable in two lines of Oqthara without any builtin. The JavaBridge does
not widen the *set* of things a program can do — it is already a JVM process — but it does remove
every ceiling the standard library imposes, and the ceilings are the only control this project
has.

**TH-15 is the exception.** `Builtins.supports` (line 69) refuses host-dependent names, so the
compiler cannot emit a call to one. But the bridge is a *method* path, not a builtin path: a
compiled program that can call `Class.forName` and then `getMethod(...).invoke(...)` is invoking
JDK reflection, not a builtin. Whether the compiler refuses `use` is the question that decides
it, and it does — "use/Java interop" is the second of the four ordered refusals in
`docs/spec/backend.md` line 274, so a program using the bridge is built as an interpreter
launcher and keeps its budgets. That is the mitigation, and it is exactly as strong as the
compiler's refusal list.

### Is this a bug or a design?

Neither, on the evidence. There is no ADR that grants or denies a capability model for the
bridge; `docs/adr/0032-static-fields-and-varargs.md` covers overload selection and field access,
not authorisation. `SECURITY.md` puts user programs out of scope, and a program that can call
`System.exit` is doing what its author wrote. But the *threat model* has to say what a reader
would not guess: the standard library's limits do not apply to the bridge, and there is no way for
an embedding application to grant `read` without granting `Runtime.exec`.

For an embedding application — the use case `OqHost` implies — that is a real gap. A host that
wants to run Oqthara code with a filesystem but no process execution can construct an `OqHost`
that fails `workingDirectory()` and gets a program that cannot read relative paths; it cannot stop
`use java.lang.Runtime`. There is no hook to implement.

### Residual risk

The bridge is a complete escape from every library-level control, in one direction only: it grants
nothing the process did not already have, and removes everything the library added. Status is
`Not mitigated` for TH-09 through TH-14 because that is the honest reading; `Partially mitigated`
for TH-15 because the compiler refuses the path into compiled code.

### Required tests

| Test | Pins |
|---|---|
| `JavaBridgeVarargsTest` | Varargs selection does not pick the wrong overload |
| `JavaBridgeFieldTest` | Static field reads return `NOT_FOUND` rather than throwing across the boundary |
| `BytecodeCompilerTest.javaInteropIsRefusedByName` | `use` is refused by name, so the bridge cannot enter compiled bytecode |
| `BytecodeCompilerTest.hostDependentBuiltinsStayOnTheInterpreter` | Six host builtins are refused by name, not by accident |

**Missing tests.** No test asserts that a private member is unreachable. No test asserts the
behaviour of `use java.lang.Runtime` — neither that it works nor that it is refused. Both are
worth adding if the bridge is ever given a policy, because a policy with no test is a comment.

---

## 3. SQL injection surface

**Assets at risk.** A-5 (database integrity and confidentiality).

**Boundary crossed.** Boundary 4, from a program's string to a SQL engine.

**Entry points.** `db_query`, `db_execute`, `db_transaction`, all in
`oq-stdlib/src/main/java/dev/oqthara/stdlib/DatabaseModule.java`.

### The control

`DatabaseModule.bind` (line 220) is the whole mitigation and it is a good one:

```java
int expected = statement.getParameterMetaData().getParameterCount();
if (expected != parameters.size()) {
    throw new OqError(call + "() has " + expected + " placeholder"
            + (expected == 1 ? "" : "s") + " but got " + parameters.size()
            + " parameter" + (parameters.size() == 1 ? "" : "s"), span,
            "Pass one value per '?', in order. Unbound placeholders become NULL "
                    + "without an error, so this is checked before running.");
}
```

Three properties, each doing separate work:

1. **Values are bound, never concatenated.** The loop calls `statement.setObject(i + 1, value)`,
   `setInt` for booleans, `setBytes` for bytes. `ADR-0040` D3 records the decision: "Parameters
   are always bound; SQL is never assembled."
2. **The placeholder count is validated before execution.** This is the non-obvious half. SQLite
   binds an unbound placeholder as `NULL` rather than failing, so without the check a forgotten
   parameter would silently write `NULL` into a column and exit 0. The comment inside `bind`
   records the measurement that motivated it ("Measured: INSERT with one placeholder and `[]`
   stored `nothing` and exited 0") — a correct design arrived at by observing the wrong one.
3. **The statement is a `PreparedStatement`**, so even a value that looks like SQL is sent as a
   value. There is no string interpolation anywhere in the module.

The same loop handles types explicitly: `null`, `Boolean` (bound as `setInt`, 1 or 0), `Long`,
`Double`, `String` and `byte[]`. A `List` is refused with its own message, because a list bound
whole would be stored as a Java object SQLite cannot read back. Everything else falls through to
`statement.setString(i + 1, Values.display(value))` — bound as text, never spliced into the
statement. So a `data` instance or a map is stored as its display form, which is a type-fidelity
limitation rather than an injection path.

### Threats

| ID | Threat | STRIDE | Status |
|---|---|---|---|
| TH-16 | A program interpolates user input into the `sql` argument and an attacker controls it | Tampering / elevation | **Accepted** — the API cannot prevent it; the guidance can |
| TH-17 | An unbound placeholder silently writes `NULL` | Tampering | **Mitigated** |
| TH-18 | A value's type is coerced into SQL syntax | Tampering | **Mitigated** |
| TH-19 | A `db_transaction` body escapes its transaction and leaves the connection open | Tampering / denial of service | **Mitigated** |
| TH-20 | A query returns an unbounded result set into memory | Denial of service | **Not mitigated** |
| TH-21 | A path containing JDBC URL syntax changes the connection | Tampering | **Partially mitigated** |

**TH-16 — accepted.** `db_query(path, sql, params)` takes SQL as a string. A program that builds
that string by concatenating input has an injection, exactly as a Java program would. Nothing in
the API distinguishes a literal from a built string. `docs/stdlib/reference.md` and ADR-0040 D3
both say to bind; a program that does not is doing what it wrote. This is the standard position
for a database API in any language, and calling it `Accepted` rather than `Not mitigated` is
correct because the alternative — parsing the SQL to detect concatenation — is not something a
library can do.

**TH-17, TH-18, TH-19 — mitigated, with tests.**
`DatabaseTest.parametersAreBoundNotSpliced` (line 153) and
`DatabaseTest.aWrongParameterCountIsAnErrorNotASilentNull` (170) pin the first two.
`db_transaction` is a body rather than a sequence of calls (`ADR-0040` D4), `ACTIVE` is a
`ThreadLocal<Connection>` (line 42), a nested transaction is refused (line 96), and
`withConnection` closes on every path with try-with-resources (line 156).
`DatabaseTest.aTransactionRollsBackWhenTheBodyRaises` (206),
`DatabaseTest.aNestedTransactionIsRefused` (226) and
`DatabaseTest.aConnectionIsClosedWhenTheStatementFails` (101) pin the third.

**TH-20 — not mitigated.** `readRows` (line 182) loops `while (rows.next())` at line 186 and builds a
`List<Object>` of `OqMap` rows. There is no row cap and no `setMaxRows`. Each row is charged
against the step budget only in the sense that the whole `db_query` call is one interpreter step —
`chargeTransfer` is not called from `DatabaseModule`. So `SELECT * FROM` a large table materialises
the whole result in the heap, and the JVM's `OutOfMemoryError` is the only bound. `MAX_LIST_ENTRIES`
(100,000) caps `list_files` and has no database equivalent. This is a genuine asymmetry with no
counterpart in the risk register's standard-library group — the nearest entry, `R-009`, is about
handle lifetime rather than result-set breadth — and no ADR records a decision not to cap it.

**TH-21 — partially mitigated.** `withConnection` line 147 builds the URL by concatenation:
`"jdbc:sqlite:" + path`. SQLite's JDBC URL grammar accepts suffixes such as `?mode=ro` or
`?cache=shared`, so a path that is really `"app.db?mode=rwc"` changes the connection's behaviour.
`requirePath` (line 278) checks only that the path is non-empty text, and the parent-directory
check (line 151) tests `Files.isDirectory(parent)` on the literal string before the suffix. In
practice the only caller of this API is the program's own author, so the threat is "a program
confuses itself", not "an attacker redirects a connection". It is `Partially mitigated` because
the parent check catches the common mistake while the URL suffix does not.

### Residual risk

Injection in the API sense is closed. Injection in the program sense is the program's business.
The open item is TH-20: an unbounded result set is the one place the standard library's "bounded
host calls" promise does not reach.

### Required tests

| Test | Pins |
|---|---|
| `DatabaseTest.parametersAreBoundNotSpliced` (153) | A value containing SQL text is stored as text |
| `DatabaseTest.aWrongParameterCountIsAnErrorNotASilentNull` (170) | TH-17 |
| `DatabaseTest.aBlankStatementIsRejectedBeforeReachingTheDriver` (277) | Input validation precedes the driver |
| `DatabaseTest.aMissingDirectoryIsNamedNotJustASqlError` (257) | TH-21's partial control |
| `DatabaseTest.aConnectionIsClosedSoTheFileIsNotLocked` (88) | TH-19 |
| `DatabaseTest.aNestedTransactionIsRefused` (226) | TH-19 |

**Missing test.** No test asserts a row cap, because there is no cap. A test that inserts 200,000
rows and asserts `db_query` refuses rather than exhausting the heap would fail today.

---

## 4. Path traversal

**Assets at risk.** A-1 (filesystem), A-9 (availability of a build host).

**Boundary crossed.** Boundary 4 for the file builtins; boundary 1 for jar entry names during a
build.

**Entry points.** `read`, `save`, `append`, `exists`, `list_files`, `delete`, `make_dir`,
`file_size`, `copy_file`, `move_file`, `read_bytes`, `write_bytes`, `walk` — all of which route
through `Stdlib.resolve` — and `JarBuilder`, which writes entries into a zip.

### Two different questions

Path traversal in a language runtime is not one threat. A program writing `../../etc/passwd` is
the program doing what it was written to do, and it is accepted under TH-02. A *build tool*
writing an entry named `../../evil.class` into a jar is the build tool being attacked through its
input, and that is a genuine vulnerability class (zip slip). The repository treats them
differently, correctly.

### Threat: file builtins

`Stdlib.resolve` line 1723:

```java
Path path = Path.of(Values.display(value));
if (path.isAbsolute()) return path;
Path base = host.workingDirectory();
if (base == null) base = Path.of("").toAbsolutePath();
return base.toAbsolutePath().resolve(path).normalize();
```

The `.normalize()` removes `..` segments, and the doc comment explains why the resolution is
against the working directory at all — so `oq run` and a generated jar behave identically
regardless of where the process was started. Neither is a confinement control, and the
specification does not present one: line 1205 again.

`walk` (line 606) uses `Files.walk(root)` without `FOLLOW_LINKS` (the comment at 613 records the
reason), so a symlink cycle cannot make it recurse forever;
`FileOperationsTest.walkDoesNotFollowASymbolicLinkIntoACycle` (189) pins it. `list_files` (483)
uses `Files.list` and caps the *emitted* names at `MAX_LIST_ENTRIES` (97) — but the stream is
`sorted()` before the cap applies, and `java.util.stream.Stream.sorted()` buffers every element,
so a directory with ten million entries is fully materialised before the ceiling is consulted. The
source comment records this as known debt. It is a denial-of-service gap, not a traversal one, and
it is listed under TH-06's family below.

### Threat: jar entry names

`oq-compiler/src/main/java/dev/oqthara/compiler/JarBuilder.java` line 110, `isSafeEntryName`:

```java
if (entry == null || entry.isBlank()) return false;
if (entry.startsWith("/") || entry.startsWith("\\")) return false;
if (entry.length() > 1 && entry.charAt(1) == ':') return false;
String normalized = entry.replace('\\', '/');
for (String segment : normalized.split("/")) {
    if (segment.equals("..")) return false;
}
return true;
```

It rejects an absolute POSIX path, an absolute Windows path, a drive letter at index 1, and any
`..` segment — including a backslash-separated one, because the separator is normalised before the
split. It is applied at both the reading site (line 181) and the writing site (line 207), so an
entry cannot slip through by being named in one place and created in another.
`BuildJarTest.unsafeJarEntryNamesAreRejected` (line 166) enumerates `../evil.class`,
`dev/../../evil.class`, `/abs/evil.class`, `C:/evil.class`, `..\evil.class` and the empty string —
the six cases that exercise the four checks.

That is **Mitigated**, and it is the strongest control in this document after the JVM verifier.
One limitation worth stating: `isSafeEntryName` is a name check, not a size check. Both copy
paths read a whole entry before writing it — `Files.readAllBytes` from a directory (line 187),
`zip.transferTo(buffer)` from a jar (line 213) — so nothing bounds the size of a single entry. A
library jar carrying a zip bomb in a wanted prefix (`dev/oqthara/`, `org/sqlite/`,
`org/slf4j/`) would inflate it. `PREFIXES` (lines 30–45) limits the blast radius to its nine
prefixes, and the build reads from the local Gradle cache and the JDK rather than from a
download, so the practical exposure is a compromised dependency rather than a hostile jar — see
[Supply chain](#7-supply-chain).

### Threats

| ID | Threat | STRIDE | Status |
|---|---|---|---|
| TH-22 | A program reads or writes outside the working directory | Tampering | **Accepted** (TH-01, TH-02) |
| TH-23 | A jar entry name escapes the output archive (zip slip) | Tampering | **Mitigated** |
| TH-24 | A symlink makes `walk` recurse forever | Denial of service | **Mitigated** |
| TH-25 | A zip bomb in a dependency inflates into the build's heap | Denial of service | **Not mitigated** |
| TH-26 | `list_files` buffers an enormous directory before applying its cap | Denial of service | **Partially mitigated** |

### Residual risk

Traversal as a *vulnerability* is closed at the only place the repository writes paths derived
from input. Traversal as a *capability* is the design. The open item is TH-25, which is a build
concern rather than a runtime one.

### Required tests

`BuildJarTest.unsafeJarEntryNamesAreRejected` (166),
`FileOperationsTest.walkDoesNotFollowASymbolicLinkIntoACycle` (189),
`FileOperationsTest.walkListsNestedFilesAsRelativePaths` (172),
`FileOperationsTest.copyRefusesAnExistingTargetRatherThanOverwritingIt` (152).

**Missing test.** No test feeds `isSafeEntryName` a percent-encoded or Unicode-normalised name.
Both would still contain a literal `..`-free string and both are harmless to a zip reader, so the
gap is theoretical — but a reader should not have to derive that.

---

## 5. Resource exhaustion

**Assets at risk.** A-9 (availability).

**Boundary crossed.** Boundary 4, and within the runtime itself.

**Entry points.** Every loop, every recursive call, every large transfer, and every construct the
parser can nest.

### The inventory of limits

This is the one area where the repository has more controls than documentation, so the table is
the section.

| Limit | Value | Enforced in | Line |
|---|---|---|---|
| Interpreter steps | 50,000,000 | `Interpreter` | 52, checked at 99 |
| Interpreter recursion depth | 800 | `Interpreter` | 54, enforced at 173 |
| Call stack | 400 frames | `Interpreter` | 667 |
| Output bytes | 16 MiB shared, per process | `OutputBudget` | 14 |
| Total wait | 60 s | `WaitedBudget` | 15 |
| Parser expression nesting | 128 | `Parser` | 19 |
| Parser block nesting | 128 | `Parser` | 21 |
| Interpolation nesting | 16 | `Parser` | 28 |
| JSON nesting | 256 | `Json` | 20 |
| Value hash depth | 100 | `Value` | — |
| Bytecode local slots | 255 | `BytecodeCompiler.checkSlotLimit` | 553 |
| Sleep per call | 10 s | `Stdlib.MAX_SLEEP_MILLIS` | 81 |
| Directory listing | 100,000 entries | `Stdlib.MAX_LIST_ENTRIES` | 97 |
| PBKDF2 iterations | 1 … 10,000,000 | `Stdlib.MAX_PBKDF2_ITERATIONS` | 744 |

### Threats

| ID | Threat | STRIDE | Status |
|---|---|---|---|
| TH-27 | An infinite loop under `oq run` never terminates | Denial of service | **Mitigated** |
| TH-28 | Deep recursion crashes the JVM stack instead of raising | Denial of service | **Mitigated** |
| TH-29 | Deeply nested source crashes the parser | Denial of service | **Mitigated** |
| TH-30 | A compiled jar loops forever | Denial of service | **Accepted** (TH-07) |
| TH-31 | `par_map` fans out one task per element with no bound | Denial of service | **Not mitigated** |
| TH-32 | A JDBC result set is unbounded | Denial of service | **Not mitigated** (TH-20) |
| TH-33 | `list_files`/`walk` buffer before capping | Denial of service | **Partially mitigated** (TH-26) |
| TH-34 | A large function exceeds 255 local slots and fails at compile time | Denial of service (of a build) | **Accepted** |
| TH-35 | A fuzz-reachable crash exists in an unfuzzed component | Denial of service | **Partially mitigated** |

**TH-27, TH-28, TH-29 — mitigated, with tests that fail without the control.**
`StepBudgetTest.infiniteLoopStopsWithADiagnostic` (33),
`StepBudgetTest.unboundedRecursionStopsWithADiagnostic` (46) and
`StepBudgetTest.printLoopStopsAtTheOutputCeiling` (62) cover the interpreter.
`NestingLimitTest.deeplyNestedCallsAreRejectedNotCrashed` (41),
`deeplyNestedParenthesesAreRejectedNotCrashed` (48),
`deeplyNestedBlocksAreRejectedNotCrashed` (53) and
`oneLevelBelowTheLimitIsAcceptedAndOneAboveIsRejected` (58) cover the parser — and the names are
the finding: they say *rejected, not crashed*, because the failure mode without the limit is a
`StackOverflowError` in the parser rather than a diagnostic.

`OutputBudget` is a static `AtomicLong` reset by `Interpreter.run` at line 125, so the ceiling is
per process and per run rather than per call. Two threads in one program share it, which is the
intended reading of "16 MiB of output, total".

**TH-31 — not mitigated, and it is the largest concurrency gap.**
`spawn` submits to a shared executor created once at `Stdlib` line 91
(`Executors.newVirtualThreadPerTaskExecutor()`) and never shut down. `par_map` line 1639 creates a
fresh executor per call in a try-with-resources and launches every future before waiting on any:

```java
try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
```

A virtual thread per element, one per element, no cap. `MAX_LIST_ENTRIES` (100,000) bounds a
`list_files` result but nothing bounds the list handed to `par_map`, so a list of a million
elements starts a million virtual threads. Virtual threads are cheap; a million of them is still a
million. There is no test that asserts a fan-out ceiling because there is no ceiling.

Relatedly, `OqTask.awaitWithin` (line 45) raises on timeout and explicitly does not cancel: "The
task is not cancelled, and any work it does continues."
`ExecutorsAndCancellationTest.aTimeoutDoesNotCancelTheTask` (69) pins that. There is no
interruption check anywhere in `oq-core` or `oq-runtime` — a grep for `isInterrupted` and
`Thread.interrupted` returns nothing — so a program cannot stop work it started, and a host
embedding the runtime cannot stop it either. That is the subject of
`docs/adr/0039-executors-and-cancellation.md` D4, which records the omission rather than
approximating cancellation.

**TH-34 — accepted, and correctly so.** `checkSlotLimit` (line 553) throws
`NotSupportedException("too many variables for one method")` rather than emitting invalid
bytecode. A refusal is the safe direction: the program does not run wrong, it does not run.

**TH-35 — partially mitigated by eight fuzz targets and a named gap list.**
`docs/spec/fuzzing.md` "What is not covered yet" (line 140) is unusually candid: text operations,
path resolution and CSV are unfuzzed; semantic analysis has no separate pass; class files are
checked for magic only at fuzz time and fully verified when the JVM loads them in
`BytecodeCompilerTest`.

| Target | Module | Covers |
|---|---|---|
| `LexerFuzzTest` | `oq-core/src/fuzz` | Tokenisation of arbitrary text |
| `ParserFuzzTest` | `oq-core/src/fuzz` | Three methods — parse, error recovery, nesting |
| `PipelineFuzzTest` | `oq-compiler/src/fuzz` | Parse → lower → compile |
| `JsonFuzzTest` | `oq-stdlib/src/fuzz` | Two methods — parse and round-trip |
| `StdlibFuzzTest` | `oq-stdlib/src/fuzz` | Formatting and text helpers |

`FuzzTest` (`oq-core/src/test/java/dev/oqthara/core/FuzzTest.java`) and `CuratedCorpusTest` replay
`oq-core/src/fuzz/resources/dev/oqthara/core/curated-cases.txt` so a finding becomes a permanent
case. The `fuzzTest` task is not part of `check`; CircleCI runs the `jazzer-fuzz` job and
`.github/workflows/nightly.yml` runs 60 s per target at 03:00 UTC. So fuzzing is continuous but
not gating, which is the right trade for a project with one maintainer and the wrong trade for a
project claiming exhaustive assurance. This document claims neither.

**Note on `PREFIXES` in a build.** `JarBuilder` copies only nine prefixes; `org/sqlite/` and
`org/slf4j/` are among them because a database program needs the driver. A jar built from a
program that never touches a database still carries whatever is reachable from the driver's
package, which is why ADR-0040 records the 435 KB → 13.5 MB cost and why
`BuildJarTest.aBuiltJarCarriesTheDatabaseDriver` (187) asserts it deliberately rather than
tolerating it.

### Residual risk

The interpreter is bounded in every dimension that has a name. Compiled bytecode is bounded only
in output. Concurrency is unbounded in fan-out and uncancellable. A database read is unbounded.
Those three are the residual, and all three are recorded above rather than papered over.

### Required tests

Listed inline per threat. The gap is TH-31: no test asserts a `par_map` fan-out limit, and none
could, because the limit does not exist.

---

## 6. Cryptography misuse

**Assets at risk.** Anything a program thinks is a secret. A-2 and A-5 in practice.

**Boundary crossed.** None. This is an API-shape threat: the library can be misused without any
attacker being present.

**Entry points.** `secure_equal`, `secure_random_hex`, `pbkdf2_sha256`, `sha256`, `random`,
`random_item`.

### Threats

| ID | Threat | STRIDE | Status |
|---|---|---|---|
| TH-36 | A program compares a secret with `==` instead of `secure_equal` and leaks timing | Information disclosure | **Accepted** |
| TH-37 | A program uses `random()` to generate a token | Spoofing | **Partially mitigated** |
| TH-38 | A program hashes a password with `sha256` instead of `pbkdf2_sha256` | Information disclosure | **Partially mitigated** |
| TH-39 | A program passes an iteration count that makes a hash uselessly weak or a call look hung | Denial of service / information disclosure | **Mitigated** |
| TH-40 | A program needs a key or certificate API and has none, so it improvises | Information disclosure | **Accepted** |
| TH-41 | `secure_equal` documentedly does not cover non-text secrets, and a program treats it as universal | Information disclosure | **Accepted**, documented |
| TH-42 | A hand-rolled comparison reimplements constant time and gets it wrong | Information disclosure | **Mitigated** by not existing |

**TH-36 — accepted, and `ADR-0038` D2 records why.** `secure_equal` is opt-in because making `==`
constant-time for every value would cost every comparison in the language. D2's argument is that
the name says what it is for, so the misuse is a naming failure rather than a library failure.
`ConstantTimeAndHashingTest` covers the comparison thoroughly — eight tests from
`identicalTextIsEqual` (59) to `nonTextIsRefused` (103) — but no test can detect a program that
chose `==`.

**TH-37 — partially mitigated.** `random` and `random_item` use `ThreadLocalRandom` (lines 424,
429, 434), which is a fast non-cryptographic generator and is correctly not seeded from a secure
source. `secure_random_hex` (721) uses a `SecureRandom` held as a static at line 1952 and is
capped at 4096 bytes per call. The two are deliberately different functions with names that say
which is which; but nothing stops a program from calling `random()` and putting the result in an
`Authorization` header. `docs/stdlib/reference.md` is the only control.

**TH-38, TH-39 — mitigated.** `pbkdf2_sha256` (753) is a separate builtin from `sha256`, which
`ADR-0038` D4 records as the reason it exists. The iteration count is required, has a floor of 1
and a ceiling of `MAX_PBKDF2_ITERATIONS` = 10,000,000, and the error for a count outside the range
explains both bounds and suggests 600,000. Six tests pin it:
`zeroIterationsIsRefused` (154), `anIterationCountAboveTheCeilingIsRefused` (161),
`aNegativeIterationCountIsRefused` (169), and the known-vector tests at 111, 117 and 123 which
establish that 1, 2 and 4096 iterations produce the values they should. The salt is a parameter
rather than generated, with a comment explaining that generating it would require returning a
record the library does not have — a limitation stated rather than hidden.

`secure_equal` itself is `MessageDigest.isEqual` (line 737) and the comment above it records the
rejected alternative: a hand-written xor accumulator, "only correct if the compiler does not
reintroduce a branch". That is the right call and the right reason, and it makes TH-42 mitigated
by absence — there is no hand-written comparison to get wrong.

**TH-40, TH-41 — accepted, documented.** `ADR-0038` D6 declines a key or certificate API because
there is no value type to hold one, and D7 explicitly claims no constant-time guarantee beyond
`secure_equal`. D3 states that `secure_equal` accepts text only, which `nonTextIsRefused` (103)
pins and which means a program comparing `read_bytes` output must compare hex text — the test at
95 (`comparingDifferentEncodingsOfTheSameBytesIsNotEqual`) shows the trap. Both refusals are
recorded decisions with identifiers, which is what makes them `Accepted` rather than
`Not mitigated`.

### Residual risk

The primitives are correct and their names are honest. The risk is entirely in callers choosing
the primitive that is fast rather than the one that is safe, and no library can close that.

### Required tests

`ConstantTimeAndHashingTest` — 18 tests, listed above, covering comparison, known vectors, format
and iteration bounds. Nothing is missing at the primitive level.

---

## 7. Supply chain

**Assets at risk.** A-6 (integrity of a published `oq.jar`), A-8 (release tokens), A-10
(reproducibility).

**Boundary crossed.** Boundary 5, from GitHub to a user's machine.

**Entry points.** `scripts/install.sh`, `scripts/install.ps1`, the release workflow, the Docker
image, and the Gradle build's dependency resolution.

### Threats

| ID | Threat | STRIDE | Status |
|---|---|---|---|
| TH-43 | A tampered `oq.jar` is installed because the checksum is fetched from the same place as the jar | Tampering | **Partially mitigated** |
| TH-44 | A dependency is substituted because no verification metadata pins it | Tampering | **Not mitigated** |
| TH-45 | A compromised action tag runs with `contents: write` | Elevation of privilege | **Not mitigated** |
| TH-46 | The Docker image runs as root and a program writes outside `/work` | Elevation of privilege | **Not mitigated** |
| TH-47 | A release artefact is not traceable to a commit | Repudiation | **Partially mitigated** |
| TH-48 | A malicious pull request reaches a workflow that holds `SITE_DISPATCH_TOKEN` | Elevation of privilege | **Mitigated** |

**TH-43 — partially mitigated, and the gap is fundamental to the design.**
`scripts/install.sh` line 143 downloads `oq.jar.sha256` from the same release as `oq.jar`, and
line 163 compares the two after normalising with `shasum`/`sha256sum`. `scripts/install.ps1` does
the same with `Get-FileHash -Algorithm SHA256`. Then line 168 runs `java -jar "$tmp" --version` as
a smoke test, which the shell installer's own test suite covers —
`scripts/tests/run-installer-tests.sh` includes a checksum-mismatch case —
`case_checksum_preserves_previous` at line 280, which asserts the message contains "Checksum
mismatch" at line 292 and that the previous jar survives — and a truncated-download case
(`case_interrupted_download`, line 296).

A checksum fetched from the same origin as the artefact detects a corrupted download. It does not
detect a compromised release, because an attacker who can replace the jar can replace the
checksum in the same action. A grep across the repository for `cosign`, `sigstore`, `gpg`,
`minisign`, `attestation` and `authenticode` returns nothing. So the control is integrity against
transit failure, not authenticity against a hostile origin, and the distinction is worth stating
because "we verify a checksum" reads like the stronger claim.

The release workflow (`.github/workflows/release.yml`) regenerates the checksum itself and
verifies it with `sha256sum -c` in a fresh directory at line 82, then smoke-tests in `/tmp/smoke`
at line 86. That catches a broken upload; it cannot catch a compromised runner.

**TH-44 — not mitigated.** There is no `gradle/verification-metadata.xml` in the repository. The
two external runtime dependencies — `org.xerial:sqlite-jdbc:3.45.3.0` and
`org.slf4j:slf4j-nop:2.0.13` in `oq-stdlib/build.gradle.kts` — are resolved by coordinate and
checksum-verified only by whatever the registry and the Gradle cache provide by default.
`.github/dependabot.yml` exists for `gradle` and `github-actions`, weekly, with a limit of three
open PRs, which is a reasonable update mechanism and not a substitution control. This is new
exposure: ADR-0040 records that the project "previously had **zero** external runtime
dependencies" and that this ADR "spends that property deliberately". The spend is recorded; the
consequence — a dependency graph that now needs pinning — is not yet addressed.

**TH-45 — not mitigated.** Every `uses:` in `.github/workflows/` names a moving tag:
`actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`,
`actions/setup-node@v4`, `softprops/action-gh-release@v3`, `github/codeql-action/*@v4`. A moving
tag is a pointer its owner can repoint. `.github/workflows/release.yml` declares
`permissions: contents: write`, so a compromised action in that workflow can publish a release.
SHA pinning with a comment naming the version is the standard mitigation and it is not present.

**TH-46 — not mitigated.** The `Dockerfile` builds from `eclipse-temurin:21-jdk` and runs from
`eclipse-temurin:21-jre`, sets `WORKDIR /app`, copies the jar, writes a `/usr/local/bin/oq`
wrapper, then `WORKDIR /work` and `ENTRYPOINT ["java", "-jar", "/app/oq.jar"]`. There is **no
`USER` directive**, so the container runs as root. `.github/workflows/docker.yml` builds the image
and runs `--version`, a hello-world program, and `oq test tests` from it — so the image is
exercised and the root user is not. A program in a container can therefore write to `/app` and
replace `oq.jar`, and the next invocation of `oq` runs the replaced jar. The mitigating argument is
that a container is discarded and that a program with root inside its own container has root
inside its own container; that argument is reasonable and it is not written down anywhere, which
is why the status is `Not mitigated` rather than `Accepted`.

**TH-47 — partially mitigated.** A release is built by a workflow triggered by a tag, and the PR
that produced the commit is in the history with the required checks recorded against it. There is
no build-provenance attestation, no reproducible-build comparison, and no signature over the
artefact. A user who trusts the release trusts GitHub's account security and the maintainer's
token hygiene. Release notes are generated by `softprops/action-gh-release@v3`, which records what
changed but not what was built from where.

**TH-48 — mitigated, and it is the best-designed control in the pipeline.**
`.github/workflows/site-content-dispatch.yml` runs with `permissions: contents: read`, sets
`persist-credentials: false` at line 62, and passes the token through `env:` rather than
interpolating it into a command. The script it runs,
`scripts/dispatch-site-content.mjs`, reads the token at line 27 and never logs it; the
`--self-test` at line 271 runs *before* the token is read, so a change that broke the decision
matrix fails without the secret being loaded. The payload is built at line 209 and checked by
`isNotificationOnly` (line 104), which allows exactly three string keys — `reason`, `ref`, `tag` —
and rejects anything else. That is a deliberate design against a content-injection threat: the
website reads GitHub itself, so a payload that carried content would be a second source of truth
that could silently disagree with the first. A pull request cannot reach the token because the
workflow only runs on `push` to `main` and on `release`.

### Residual risk

The distribution path is honest about what it verifies and silent about what it does not. The two
substantive gaps are TH-44 (no dependency pinning) and TH-45 (moving action tags), both of which
are one file away from fixed. TH-46 is one `USER` line.

### Required tests

`scripts/tests/run-installer-tests.sh` — `case_checksum_preserves_previous` (line 280) and
`case_interrupted_download` (line 296), both driven by local fixture servers with no network
access.
`scripts/dispatch-site-content.mjs --self-test` — ten cases covering the decision matrix.

**Missing tests.** Nothing verifies the Docker image's user. Nothing verifies a signature, because
there is none.

---

## 8. Website and documentation pipeline

**Assets at risk.** A-7 (catalogue integrity), A-8 (dispatch token), and the reader's trust that a
published page reflects `main`.

**Boundary crossed.** Boundary 5, from a repository commit to a public site.

**Entry points.** `scripts/gen-docs-catalog.py`, `docs/catalog.json`,
`scripts/dispatch-site-content.mjs`, `.github/workflows/site-content-dispatch.yml`, and the
generated `oq doc` output.

### Architecture, because it is the control

ADR-0011 moved the website to its own repository. This repository therefore does not render
pages; it *notifies* a site that content changed, and the site fetches from GitHub itself. That
one decision removes most of the injection surface: there is no template rendered here from
repository content, no HTML assembled from Markdown, and no build step that could interpolate a
filename into a page.

### Threats

| ID | Threat | STRIDE | Status |
|---|---|---|---|
| TH-49 | A dispatch payload injects content into the website | Tampering | **Mitigated** |
| TH-50 | A pull request reaches the dispatch token | Elevation of privilege | **Mitigated** (TH-48) |
| TH-51 | A docstring in a `.oq` file injects markup into `oq doc` output | Tampering | **Not mitigated** |
| TH-52 | The catalogue is stale and a published page cites a document that changed | Repudiation | **Partially mitigated** |
| TH-53 | A versioned-docs script writes outside its directory or to a path that no longer exists | Tampering | **Not mitigated** |
| TH-54 | A document is added and the catalogue gate fails, blocking every merge | Denial of service (of the project) | **Mitigated** by a documented step |
| TH-55 | A managed runtime directory writable by another user is activated as the interpreter | Elevation of privilege | **Accepted**, latent — no command installs a runtime yet |

**TH-49 — mitigated.** `isNotificationOnly` (line 104) is a positive allow-list over both keys and
types: every entry must be one of `reason`, `ref`, `tag` *and* a non-empty string, and an empty
object is rejected. The `--self-test` cases at 130 include "an unreadable release payload
announces nothing". A payload cannot carry a document, a title, or a filename.

**TH-51 — not mitigated, and worth naming because it is the inverse of TH-49.**
`oq doc` renders documentation comments from source. If a comment contains text that a downstream
renderer treats as markup, that text reaches the rendered output — this is the normal Markdown
injection question and the answer here is that the renderer is out of scope, because it lives in
the website repository. `oq-cli/src/main/java/dev/oqthara/cli/DocGenerator.java` escapes nothing;
whether it needs to depends on a consumer this repository does not own. Status `Not mitigated`
rather than `Accepted` because no decision record covers it.

**TH-52 — partially mitigated.** `scripts/gen-docs-catalog.py --check` and
`scripts/check-docs-sync.py` run in `.github/workflows/ci.yml` line 46 and
`.github/workflows/release.yml` line 31, so a stale catalogue fails a pull request. That is a real
gate and it is also a burden: adding a document under `docs/` outside `docs/adr/` and `docs/spec/`
requires regenerating `docs/catalog.json`, and `scripts/check-docs-sync.py` includes a
`keyword_budget_is_honest` check that reads the keyword count from two of the four documents that
state it. So the gate catches staleness in the fields it reads and not in the fields it does not.
This is the mechanism by which `R-023` in the risk register exists:
`docs/stdlib/reference.md` line 11 and `CONTRIBUTING.md` line 9 say 21 keywords and agree with
`Lexer.KEYWORDS`, while `AGENTS.md` line 21 and two agent files say 20, and the gate does not read
those.

**TH-53 — not mitigated, and it is a live defect.**
`scripts/build-versioned-docs.py` writes to `site/_site/versions/` and `site/versions.json`, but
`site/` does not exist in this repository — ADR-0011 removed it — and no workflow invokes the
script. `scripts/README.md` still advertises it as part of the website build. The threat is not
that it is exploited; it is that a maintainer following the README runs a script that writes to a
path the repository does not have and gets either a created directory or a failure, with no test
either way. This belongs to the same family as `R-022` in the risk register.

**TH-54 — mitigated by convention.** The catalogue gate is a denial-of-service threat against the
project's own merge throughput: a contributor who adds a document and does not run
`python3 scripts/gen-docs-catalog.py` gets a red build with the message "docs/catalog.json is
stale". The `docs/governance/` sections of `docs/catalog.json` are keyed by the first path segment
under `docs/`, so a new governance document is a catalogue entry, not an exception. The step is
documented; it is not automated into a pre-commit hook.

**TH-55 — accepted for now, and latent rather than live.**
`RuntimeInstaller` activates a JVM it previously unpacked under a managed home. `activateAtomically`
(line 71) renames the staged directory into place and `RuntimeRetention` deletes older ones, but
nothing checks who owns the managed home or the unpacked tree before the result is executed. On a
shared host, a directory another user can write to would let them replace the JDK that `oq` then
runs.

It is recorded as *latent* because no command installs a runtime yet: `Main` dispatches `run`,
`repl`, `check`, `version`, `help`, `fmt`, `new`, `doc`, `test`, `build` and `java`, and never
constructs a `RuntimeInstaller`. Only the tests do. The check that closes this belongs with the
command that makes it reachable — an owner comparison against the current user on the managed home
and on the unpacked tree, before `activateAtomically` is allowed to publish it — and adding it now
would be untestable through any real entry point. `docs/governance/alpha-4-stdlib-inventory.md`
line 386 already records the absence of ownership support in the shipped `stat` builtin, which is
the same gap seen from the language side.

### Residual risk

The dispatch path is the strongest part of the pipeline and the documentation tooling is the
weakest. Neither is a runtime concern, which is why this section is short: the language's security
posture does not depend on the website, and the website's integrity depends on a token that a pull
request cannot reach.

### Required tests

`scripts/dispatch-site-content.mjs --self-test` (ten cases),
`scripts/check-docs-sync.py` (executed by `.github/workflows/ci.yml` line 46 and
`.github/workflows/release.yml` line 31).

**Missing tests.** No test executes `scripts/build-versioned-docs.py`. No test asserts that every
document stating the keyword count agrees with `Lexer.KEYWORDS`.

---

## Threat summary

| Status | Count | Threats |
|---|---|---|
| **Mitigated** | 14 | TH-17, TH-18, TH-19, TH-23, TH-24, TH-27, TH-28, TH-29, TH-39, TH-42, TH-48, TH-49, TH-50, TH-54 |
| **Partially mitigated** | 12 | TH-06, TH-08, TH-15, TH-21, TH-26, TH-33, TH-35, TH-37, TH-38, TH-43, TH-47, TH-52 |
| **Accepted** | 14 | TH-01, TH-02, TH-03, TH-04, TH-05, TH-07, TH-16, TH-22, TH-30, TH-34, TH-36, TH-40, TH-41, TH-55 |
| **Not mitigated** | 15 | TH-09, TH-10, TH-11, TH-12, TH-13, TH-14, TH-20, TH-25, TH-31, TH-32, TH-44, TH-45, TH-46, TH-51, TH-53 |

Fifty-five threats. The distribution is the finding: the runtime's *accident* controls are
excellent and its *adversary* controls are absent by design, while the distribution machinery has
fifteen genuinely open items that are nearly all small, cheap fixes — a `USER` line, a
verification-metadata file, SHA-pinned actions, a row cap, a fan-out cap.

## What this model does not cover

| Area | Why not |
|---|---|
| The JVM, the JDK and `sqlite-jdbc` as attack surfaces | Out of the project's control; the mitigation is patching, which `.github/dependabot.yml` and the JDK update cadence provide |
| The website repository | A separate repository with its own maintainers; this model covers only what is dispatched to it |
| `oq-lsp` | A language server reads untrusted workspace files by definition; it has no network surface and no privilege beyond the editor's |
| Compiler correctness as a security property | A miscompiled program is a correctness bug, covered by `R-002` and the conformance baseline, not a threat |
| Timing side channels other than `secure_equal` | `ADR-0038` D7 declines to claim any, so there is nothing to model |
| Multi-tenant hosting | No such product exists; `OqHost` has no capability hook, so a multi-tenant deployment would have to add one first |

The `oq-lsp` exclusion deserves a caveat: a workspace file is untrusted input, and the same
`Parser` and `Lexer` limits that protect `oq run` protect the language server, so the boundary is
at least the same boundary. What is *not* modelled is a malicious `.oq` file that a developer
merely opens.

## Correlated-review limitation

This threat model was written by the same model endpoint that wrote the code it examines, under
the same six role names, in the same session family. Every agent in `.github/agents/` pins
`deepseek-ai/deepseek-v4.1-flash-1017`; the pin is recorded as `OQ-DEC-2026-005` in
[`README.md`](README.md). Six role names are six prompts against one endpoint, so a blind spot
shared by the model is shared by the reviewer, and the reviewer is the thing that was supposed to
catch it.

The practical consequence for this document is specific: **an absence it reports is more
trustworthy than a presence it reports.** "There is no `SecurityManager`" is a grep result and can
be checked in a second. "The path handling is adequate" is a judgement, and it is a judgement made
by the process that wrote the path handling. A reader should re-derive every `Mitigated` verdict
from the cited line before relying on it, and should treat the `Not mitigated` list as a floor
rather than a ceiling — the threats this model failed to imagine are, by construction, the ones
correlated review cannot find either.

`docs/governance/remediation/solo-maintainer-exception.md` records the related governance
position: `required_approving_review_count` was lowered from 1 to 0 on ruleset `26650412` because
a second human approver does not exist, and the second GitHub account `JoaoGaValentim` belongs to
the same person. What remains enforced is mechanical — required pull requests, squash-only merges,
no bypass actors, twelve required status checks with `strict_required_status_checks_policy`,
required review-thread resolution, and stale-review dismissal on push. None of that is
independent review.

So: this is **supplemental assurance, never independent human review.** It is a structured
inventory of what the repository does and does not do, produced by the process that built it, for
the benefit of a future reader who did not. It should be read as a map, and a map drawn by the
cartographer is still useful as long as nobody mistakes it for a survey.

The only genuinely independent signals in this repository are the ones a machine produces without
an opinion: the JVM's own bytecode verifier, the interpreter-versus-jar conformance comparison in
`scripts/verify-conformance.py`, the JaCoCo `coverageGate`, the fuzz targets, and CodeQL. Where
this document and one of those disagree, the machine is right.

## Review cadence

This model is revisited when any of the following happens, and at least once per Alpha:

1. A builtin is added to or removed from `Builtins.HOST_DEPENDENT`. The list is the entry-point
   inventory, so it changing invalidates the inventory.
2. A new external runtime dependency is added. `ADR-0040` is the precedent, and the risk it
   introduced (TH-44) is still open.
3. The compiler's refusal list changes. Four refusals carry most of the compile-time security
   story, and adding a fifth or removing one moves several threats at once.
4. A workflow gains a write permission or a new secret. TH-45 and TH-48 are both about this.
5. A threat moves from `Not mitigated` to anything else, or a new one is found. A threat model
   that only grows is one nobody is maintaining.
6. The `SECURITY.md` scope changes. If user programs ever come into scope, most of this document's
   `Accepted` verdicts become wrong.

A threat is admitted with an ID, a status, and at least one cited path. A `Mitigated` status
requires a test that would fail without the control — a control with no test is a comment, and a
comment is not a mitigation. A `Not mitigated` status requires nothing but honesty, which is the
point of having the category.

**Last verified:** `27cac46` (merge of PR #136), 2026-11-12.
