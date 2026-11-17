# Alpha 7 security baseline

Alpha 7 is a security Alpha. This document is its baseline: what the tree actually contains, what
[`alpha-4-threat-model.md`](alpha-4-threat-model.md) says about it after the boundary re-read, and
the ordered backlog that follows. It exists because the threat model had not been re-read since
Alpha 4 and, when it finally was, one of its mitigations had stopped existing three releases
earlier.

Nothing here is a plan to make Oqthara a sandbox. `SECURITY.md` and the threat model both say the
opposite, in the same words: "Oqthara is not a sandbox." A program that runs is trusted to do what
it says. What this Alpha can fix is the gap between what the documentation *promises* and what the
tree *does* — because that gap is the one that misleads a user into a decision they would not have
made.

## The measured starting point

Every number below was re-derived from the tree on the commit that carries this file. None is
carried forward from an earlier document.

| Quantity | Value | Source |
| --- | --- | --- |
| Threats in the model | 55, in four status buckets 14 / 12 / 14 / 15 | `alpha-4-threat-model.md` `## Threat summary` |
| Threats not mitigated | 15 | same |
| Threats partially mitigated | 12 | same |
| Risk-register entries | 30 | `scripts/check-risk-register.py` |
| `Builtins.HOST_DEPENDENT` names | 33 | `Builtins.java` line 61 |
| Cached paths, script tests | 255 | `unittest discover -s scripts/tests` |
| Conformance programs | 256, baseline 223 executable | `scripts/verify-conformance.py` |
| Dependencies with no verification metadata | 2 | `oq-stdlib/build.gradle.kts` lines 6, 9 |

## What the re-read found

The full record is in the threat model's `### The Alpha 7 boundary re-read`. The headline, because
it changes how the compiled backend should be described:

**TH-15's mitigation no longer exists.** The entry read: "the compiler refuses `use` … a program
using the bridge is built as an interpreter launcher and keeps its budgets." ADR-0066 made the
`use` statement compile, and `docs/spec/backend.md` says so. Measured on this tree:

```
$ java -jar oq.jar build forname.oq -o z.jar     # use java.lang.Class; Class.forName("java.lang.Runtime")
codepath: compiled bytecode
$ java -jar z.jar
java.lang.Runtime

$ java -jar oq.jar build files.oq -o y.jar       # use java.nio.file.Files; Files.readString(...)
codepath: compiled bytecode
$ java -jar y.jar
true
```

The builtin names are still refused — `print read("x")` reports `codepath: interpreter launcher`
— so the set of things reachable is unchanged from the interpreter. What is gone is the claim
that compiling narrows it. A user who chose `oq build` believing it was the more contained
artefact was wrong, and the document told them so.

This was not a code defect. The code does what ADR-0066 says. It was a documentation defect of the
most expensive kind: a *mitigation* that had silently become a *claim*, in the document whose job
is to prevent exactly that.

## The backlog

Ordered by what it buys per unit of change. Each item names the threat or risk it closes, the
evidence that it is closed, and the reason it is not first.

### A7.1 — Bound the compiled backend's reachability story, in the spec

**State:** done, at the commit that carries this paragraph.
**Closes:** nothing. **Prevents:** TH-15 recurring in a new document.

`docs/spec/backend.md` and `docs/spec/language.md` both describe the backend boundary and both are
accurate. What neither says is the thing a security-minded reader needs: that a compiled jar
reaches the same host surface as the interpreter, with *fewer* limits. The language spec states
the missing budget plainly (line 2031) but does not connect it to interop, and the backend spec's
boundary list describes the refusal order as a *feature* list without saying what it is not.

**Acceptance:** `backend.md` states, in one sentence a reader cannot mistake, that compiling
changes performance and not containment, and cites the measured probe. A conformance case or a
script test asserts that `use java.lang.Class` with `Class.forName` builds as
`compiled bytecode` — so if a future change *does* start refusing the bridge, the documents fail
a test rather than drifting again.

**Why first:** it is a paragraph and a test, it closes the highest-consequence finding of the
re-read, and every other item is larger.

**What was done.** `backend.md`'s boundary list now carries the paragraph, phrased as what
compiling is *not*: "Compiling is a performance decision, not a containment one." It names the
three measured escapes (`Files.readString`, `Runtime.exec`, `Class.forName`), states that a
compiled jar has no step budget or host-call deadline, and states the asymmetry that a builtin is
refused by name and `use` is not. `BuildCommandTest.useEscapesTheRefusalThatABuiltinTriggers`
asserts both halves — `use java.lang.Class` with `Class.forName` builds `compiled bytecode`, and
`print read("/etc/hosts")` builds an interpreter launcher reporting `is not compiled yet`. The
test was mutation-checked by inverting its first assertion, which fails it, so it is not a test
that would pass either way.

### A7.2 — Verify the threat model the way the risk register is verified

**State:** done, in PR #244. **Closes:** the structural gap that let six facts go stale.
**Prevents:** the same drift.

`scripts/check-risk-register.py` exists because that register was found making claims nothing
checked. The threat model has the identical blind spot and no gate at all: nothing in `scripts/`
reads the file, so its counts, its ID-to-bucket arithmetic and its line citations can drift in
silence. All three did.

**Acceptance:** `scripts/check-threat-model.py` asserts that every `TH-` row in a table has an ID,
that the four summary buckets partition the ID set exactly once each, that each bucket's declared
count equals its listed IDs, and that every cited path resolves — reusing the resolver already
written for the risk register rather than a second implementation. It runs at `ci.yml` beside the
register check. Its tests include a mutation that breaks the arithmetic and one that breaks a path.

**Why second:** the mechanism is already built and proven once. The marginal cost is the parser,
and the payoff is that this document stops being able to drift. It is second rather than first
only because A7.1 is smaller.

**What was done, and what it cost.** `scripts/check-threat-model.py` checks the four things above
and runs at `ci.yml` beside the register check. 18 tests, mutation-tested seven ways. Building it
turned up a finding worth recording here because it bears on the other items: reading the gate
back found **three ways for a real check to stop checking while the build stayed green** — a
total the parser could not read was silently skipped, an invented status word made a row vanish
and blamed the summary instead of the status cell, and one of my own edits installed a check that
did not exist. None was caught by a test, because every test mutated the *document* rather than
the *checker*. Absence of a check is the failure mode this class of gate has, and it is invisible
to the gate itself.

### A7.3 — `USER` in the Dockerfile, and a test that would fail without it

**Closes:** TH-46. **State:** done. **Status today:** `Mitigated`.

**Acceptance:** the runtime stage adds a non-root `USER`; `.github/workflows/docker.yml` runs
`id -u` in the image and asserts it is not `0`, so removing the directive fails a workflow rather
than a review.

**What was done.** The runtime stage creates `oq` (uid and gid 1001) with `--create-home`, chowns
`/work` to it and switches to it before the entrypoint. The workflow asserts both halves of the
claim: `id -u` is not `0`, and appending to `/app/oq.jar` fails. The second assertion is the one
that matters — the envelope says a program "writes outside `/work`", and the damage is the ability
to replace the jar that will run next, not the uid itself.

`/work` is writable so programs can still write where they are meant to; `/app` is root-owned and
read-only to the runner. `/home/oq` exists and is owned by `oq`, but nothing in the `oq test` path
writes there — `TestRunner` only reads sources and resolves paths (`TestRunner.java:24-47`), and
`InstallLock` is not reached from the test path. That is why the existing workflow steps keep
working unchanged.

**Verification is CI-only.** `docker info` fails on the development machine, so this item's
executed evidence is the `docker.yml` run on the pull request, not a local build. The pull request
says so rather than implying a local check happened.

**Why third:** it is the cheapest genuine mitigation on the list and the only one whose absence is
`Not mitigated` with no recorded decision. It is third because it needs a working Docker daemon to
verify, which local runs do not have.

### A7.4 — Gradle dependency verification metadata

**Closes:** TH-44. **Status today:** `Not mitigated`; `gradle/` holds only `wrapper/`.

**Acceptance:** `gradle/verification-metadata.xml` with a checksum for both dependencies, generated
by `--write-verification-metadata` and committed, plus a CI step that fails on an unverified
dependency. The two pinned coordinates in the threat model (`sqlite-jdbc:3.54.1.0`,
`slf4j-nop:2.0.20`) must be re-read at the time of the change, because both moved once already.

**Why fourth:** it is mechanical but it touches every build, so it wants a quiet branch.

### A7.5 — SHA-pin the actions that hold write scope

**Closes:** TH-45, partially. **Status today:** `Not mitigated`, nine moving tags.

**Acceptance:** every `uses:` in `release.yml` and `site-content-dispatch.yml` — the two workflows
holding `contents: write`, `id-token: write` or a repository secret — pins a commit SHA with a
comment naming the version, and a script test fails on a `@v`-style tag in those two files.
Read-only workflows may keep major-version tags, and saying so is part of the acceptance, because
an all-or-nothing rule here is not going to survive the first exception.

**Why fifth:** it is the largest diff on the list and Dependabot cannot bump a SHA pin without a
config change, so it is the item most likely to need follow-up work.

### A7.6 — `install.ps1` parity with `install.sh`

**Closes:** R-020, and the Windows half of TH-43. **Status today:** R-020 is `Open`.

**Acceptance:** `scripts/install.ps1` fetches `oq.jar.sigstore.json`, verifies it with `cosign`
when present, warns distinguishably when absent, and honours `OQ_REQUIRE_SIGNATURE`. The installer
test suite gains the PowerShell equivalents of the POSIX signature cases, including one that
asserts a tampered jar is rejected.

**Why last:** it requires a Windows verification path this environment cannot execute, so its
evidence has to come from CI. That is a reason to sequence it, not to skip it.

## What Alpha 7 will not do

- **No capability hook on `OqHost`.** The threat model names it as the gap an embedding
  application would hit — "there is no way for an embedding application to grant `read` without
  granting `Runtime.exec`". That is a language design change with an ADR of its own, not a
  security fix, and doing it badly would produce the illusion of a sandbox.
- **No sandbox, no seccomp, no policy engine.** `SECURITY.md` puts user programs out of scope as
  victims. Adding a policy layer would contradict the language's stated position rather than
  implement it.
- **No moving the four concurrency builtins off `HOST_DEPENDENT`.** This was carried into Alpha 7
  as a candidate. The measurement reproduces — removing `spawn`, `await`, `done` and `par_map`
  raises compiled conformance from 214 to 218 of 223 with 0 divergences, verified on this tree
  after the first attempt measured a stale jar — but it is a *capability* change, not a security
  one, and compiled code has no step budget (TH-07). Enabling concurrency only on the backend with
  no budget is the wrong direction during a security Alpha. It stays refused, and the reason is
  now measured rather than assumed.

## The honest summary

Exactly one thing in this Alpha is a *new* defect report: TH-15's mitigation stopped existing and
nobody noticed for three releases because no gate reads the threat model. The other five
corrections are drift of the ordinary kind — versions, line numbers, an installer that gained a
feature a sibling did not.

The backlog is six items, all small, none of which makes the product safer against a hostile
program. They make the *documentation* true, which is a different and more achievable goal: a user
who reads that a threat is mitigated should be looking at a control that exists, and a user who
reads that the compiled backend refuses something should find that it does.

**Last verified:** the branch that adds this file, at the commit named in the pull request that
merged it. The pin must name a commit reachable from the branch under review, because
squash-merge discards branch SHAs and a pin to one of them stops resolving. Every fact in the
starting table above was measured against `43597e7`; the six repairs they forced are recorded in
the threat model's own `### The Alpha 7 boundary re-read` section, not restated here.
