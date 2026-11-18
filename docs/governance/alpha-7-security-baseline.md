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

This baseline covers the security backlog. The wider Alpha 7 directive's required ten-item first
report is [`alpha-7-first-report.md`](alpha-7-first-report.md), which reviews the risk-register
boundary, reproduces R-018, and records the type-system conflict between the accepted ADRs and the
directive's §5. Where the two disagree about scope, that report wins on scope and this one wins on
the security backlog; neither supersedes the other's evidence.

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

**Closes:** TH-44. **State:** done. **Status today:** `Mitigated`.

**Acceptance:** `gradle/verification-metadata.xml` with a checksum for both dependencies, generated
by `--write-verification-metadata` and committed, plus a CI step that fails on an unverified
dependency. The two pinned coordinates in the threat model (`sqlite-jdbc:3.54.1.0`,
`slf4j-nop:2.0.20`) must be re-read at the time of the change, because both moved once already.

**What was done.** `gradle/verification-metadata.xml` is committed with `verify-metadata` on and a
SHA-256 for every artefact and module descriptor the build resolves — forty-three components,
including `org.xerial:sqlite-jdbc:3.54.1.0` and `org.slf4j:slf4j-nop:2.0.20`. Both coordinates in
the build files matched the threat model's, so no version drift was corrected here.

Generating the file once was not enough. The first generation captured
`junit-bom-5.12.2.pom` but not `junit-bom-5.12.2.module`, so the next `--refresh-dependencies`
run failed on a component the generator had skipped; the file was regenerated against
`:oq-cli:standaloneJar :oq-cli:testClasses` with `--refresh-dependencies` so that every artifact
was actually resolved before being recorded.

Enforcement was then tested rather than assumed, three ways, each reproduced before being written
down: corrupting the `sqlite-jdbc` jar checksum to 64 zeros fails with
`One artifact failed verification: sqlite-jdbc-3.54.1.0.jar`; deleting that component fails with
both the jar and the `.pom` named; and corrupting the `slf4j-nop` checksum fails on
`:oq-cli:runtimeClasspath`. The first attempts at this used the `dependencies` report task and
`--rerun-tasks`, which resolve the graph and reuse the cache without re-verifying artifact bytes,
so they passed against a deliberately corrupted file. `--refresh-dependencies` on a configuration
that actually needs the jar is what makes the check real.

**The CI half needed two pieces, because the build alone is not the control.** `./gradlew build`
verifies only what it downloads, so on a warm cache it passes whether or not the metadata is
correct, and a deleted metadata file leaves every build green with nothing verified. So CI runs a
`--refresh-dependencies` resolution of `:oq-cli:standaloneJar` — one real verification per run that
a cache hit cannot skip — and `scripts/check-dependency-verification.py` separately asserts the
file exists, metadata verification is on, both pinned coordinates have a component at the version
the build file declares, and no checksum is a placeholder. That script's own tests and mutations
are in `scripts/tests/test_check_dependency_verification.py`.

**Why fourth:** it is mechanical but it touches every build, so it wants a quiet branch.

### A7.5 — SHA-pin the actions that hold write scope

**Closes:** TH-45. **State:** done. **Status today:** `Mitigated`.

**Acceptance:** every `uses:` in `release.yml` and `site-content-dispatch.yml` — the two workflows
holding `contents: write`, `id-token: write` or a repository secret — pins a commit SHA with a
comment naming the version, and a script test fails on a `@v`-style tag in those two files.
Read-only workflows may keep major-version tags, and saying so is part of the acceptance, because
an all-or-nothing rule here is not going to survive the first exception.

**What was done.** The scope was derived from what the workflows declare rather than from the list
above, and the derivation found one more file than the acceptance anticipated. `codeql.yml`
declares `security-events: write` — it uploads analysis results — so it is a write-scope workflow
and is pinned too. A literal reading of the acceptance would have left five mutable tags in a
workflow that writes. That is the reason `scripts/check-action-pins.py` computes scope from
declarations instead of naming files: a workflow added later with a write scope is covered without
anyone remembering this paragraph.

Three workflows are in scope, for three different declared reasons, and all thirteen `uses:` in them
now pin a full 40-character commit with a version comment:

| Workflow | Why it is in scope | Pins |
| --- | --- | --- |
| `release.yml` | `contents: write`, `id-token: write` | 6 |
| `site-content-dispatch.yml` | references `secrets.SITE_DISPATCH_TOKEN` | 2 |
| `codeql.yml` | `security-events: write` | 5 |

Every resolved SHA was checked against the tag's own commit before being committed, and annotated
tags were dereferenced rather than pinned to the tag object — `softprops/action-gh-release@v3` and
`github/codeql-action@v4` and `gradle/actions@v6` are annotated, so pinning the tag object's SHA
would have pinned something that is not a commit and would not resolve as a step.

`ci.yml`, `docker.yml` and `nightly.yml` are read-only and keep their major-version tags, as the
acceptance permits. The gate asserts that too: `test_the_read_only_workflows_are_out_of_scope`
fails if one of them starts declaring write scope, so the exemption cannot drift into silently
covering a workflow that has gained permissions.

**Why fifth:** it is the largest diff on the list, so it is the item most likely to need follow-up
work.

**Follow-up this creates.** None, and an earlier draft of this paragraph claimed otherwise. It said
Dependabot would not update a SHA pin without a config change, and that the pins would therefore age.
That was wrong, and it was written without being checked. Dependabot bumps a pinned `uses:` and
rewrites the version comment beside it, from a plain `github-actions` entry — which this repository
already has. The evidence is Dependabot's own pull request on its own repository, which moved
`github/codeql-action` to `33d7b85d`, the exact commit pinned here.

What is left is narrower and worth stating precisely. A pin still ages until the next weekly run, so
"pinned" never means "current". And a comment that contains no version is not rewritten even when the
pin moves, which is why `check-action-pins.py` now requires the comment to name one rather than
merely exist. See A7.7.

### A7.6 — `install.ps1` parity with `install.sh`

**Closes:** R-020, and the Windows half of TH-43. **State:** done. **Status today:** `Mitigated`.

**Acceptance:** `scripts/install.ps1` fetches `oq.jar.sigstore.json`, verifies it with `cosign`
when present, warns distinguishably when absent, and honours `OQ_REQUIRE_SIGNATURE`. The installer
test suite gains the PowerShell equivalents of the POSIX signature cases, including one that
asserts a tampered jar is rejected.

**What was done.** The installer downloads the bundle, runs `cosign verify-blob --bundle` against
the jar it just fetched, and fails the install when verification fails. When `cosign` is absent it
prints the same warning the POSIX installer prints and `OQ_REQUIRE_SIGNATURE=1` turns that into a
failure. `OQ_COSIGN` is a test seam. The suite gained six cases — signed verifies, tampered
rejected, signed-without-cosign warns, signed-without-cosign under `OQ_REQUIRE_SIGNATURE` fails,
unsigned warns, unsigned under `OQ_REQUIRE_SIGNATURE` fails — plus a positive control proving the
hash-checking stub accepts an untampered jar.

**What it cost to find out.** The first version read the bundle's size with `Get-Item`, which
refuses dotfiles without `-Force`. The throw was swallowed and treated as "no signature published",
so every signed release would have been reported unsigned and verified against nothing. That is a
silent failure on a path where silence is the whole risk: on a machine with no `cosign`, nothing
downstream would ever have noticed. Six cases failed first and the `-Force` fix is what turned them
green; reverting it fails three, including the tampered-jar case.

**A test-harness defect found on the way.** The end-to-end block passed `-WindowStyle Hidden` to
`Start-Process`, which is Windows-only, so on macOS and Linux the whole block threw before running a
case. Every end-to-end installer test, including the pre-existing ones, had only ever executed on
Windows. That was invisible because the block is wrapped in a python-availability check and the
failure looks like a missing server. It now branches on the platform and the Linux job runs the same
cases. This is the same shape as the gate defect A7.5 found: a check that reports nothing wrong
because it never ran.

**Why it was sequenced last:** it needed a Windows verification path. The workaround was to install
PowerShell locally and run the suite on macOS, which executes the same code — the OS-specific parts
are the stub extensions and the launcher text, both of which are branched. CI remains the authority;
this is a reason the item was last, not a reason it was skipped.

**Residual:** authenticity is still opt-in, and neither installer script is itself signed. Both are
recorded in R-020 rather than solved.

### A7.7 — audit A7.5's own claims
**Closes:** TH-45 by way of the check that reads it. **State:** done.

Two defects, both introduced or left by A7.5, both latent — nothing was broken in the tree. Both are
the class this Alpha exists to remove: a check that does not check, and a claim that was written
down without being executed.

**The gate accepted any comment.** `check-action-pins.py` required a pinned action to carry a
trailing comment and its error said the comment must name the version, but the only condition was
`if not comment:`. `# TODO fix this later`, `# v` and `# not a version at all` all passed. The
comment is what makes a pin reviewable, so a placeholder made a pin look reviewed while telling a
reader nothing — and because Dependabot only rewrites a comment it can find a version in, a junk
comment also stopped the pin from being updated. The rule now requires the comment to contain a
version; `# v4` still passes, because naming the release line is what a reviewer needs.

**The documentation claimed the opposite of the behaviour.** A7.5's follow-up paragraph and R-008's
residual both said Dependabot would not bump a SHA pin without a config change. It does. Its own
`dependabot-core` pull request #16786 moved `github/codeql-action` to `33d7b85d` and rewrote the
comment beside it, from a plain `github-actions` entry; `33d7b85d` is the commit this repository
pins. The claim came from reading the option list, where it is never stated either way — absence of a
statement is not evidence, and the source plus a real bot pull request are. Both paragraphs are
corrected where they live rather than left to be found again.

**Why it is its own item.** The wrong claim was made while doing A7.5 and would have been read as
fact, because residuals are trusted more than feature text — they are the part a reader assumes was
measured. A residual that was not executed is the most likely place for a false claim to sit.

**Acceptance.** Three tests fail against the old condition and pass against the new one; a positive
control proves the rule still accepts a real version, so it cannot be tightened into rejecting
everything and still pass. The real workflows pass unchanged.

### A7.8 — R-018's fuzzing reach, and the ReDoS it found
**Closes:** R-018, downgraded to `Mitigated`. **State:** done.

Two targets were added first, `fuzzStdlibFormats` and `fuzzGlobPatterns`, covering the four stdlib
areas R-018 named as unreached that a target could actually reach without an `OqHost`: CSV, text
wrapping, INI/properties, and glob patterns. Between them they account for **four** defects and
**one** security finding. A third, `fuzzGlobClasses`, was added afterwards because the review of
the rewrite produced a defect the first two could not have found.

**`text_wrap` dropped its indent** when the remaining text contained no word, so `wrap("  ", 10)`
returned a line of two spaces instead of no lines. **It also re-indented after a hard line break**
already inside the input, because the indent accumulator never reset. Both are ordinary bugs with
permanent tests.

**`fnmatch` was a ReDoS.** The sanitizer's report was treated as a true positive until proven
otherwise, and it was one: a 26-character pattern took 1564 ms against a 30-character name, with
the time doubling for every two stars added. The pattern built a backtracking regex, each `*`
becoming its own `[^/]*` quantifier over the same characters. The regex engine was removed and
replaced with a linear dynamic program (ADR-0068); the same case now takes 4 ms and the cost is
flat in the number of stars. **The sanitizer is not suppressed anywhere in the build**, and
`fuzzGlobPatterns` ran `2,557,015` executions in `181 s` with it enabled.

**An emoji pattern could never match itself.** The same target's cross-path invariant — a literal
pattern must match its own text — caught that the translator walked `char` and quoted each
surrogate half separately, so `fnmatch("😀", "😀")` answered `false`. Code-point matching removes
the split for good.

**Why it matters that the sanitizer was enabled.** Jazzer can be told to skip a hook through
`JAVA_TOOL_OPTIONS='-Djazzer.disabled_hooks=...'`, which does reach the forked test JVM where a
plain `-D` on the Gradle command line does not. That escape hatch was found, verified to work, and
then deliberately not used: a suppressed finding in a security Alpha is the same defect as a gate
that accepts a placeholder, which is what A7.7 had just finished removing.

**The rewrite introduced a fifth defect, and the review caught it.** A first draft expanded every
character-class range into its members. A class spanning most of Unicode therefore became 1.1
million ints, and a 360-character pattern exhausted a 256 MB heap before a single name was
compared: a memory amplification the ADR's own "pattern × name" claim did not cover, written by
the same pass that was fixing a denial of service. Ranges are kept as endpoints, the same probe now
answers in `2 ms`, and a 48,000-character pattern builds in `1 ms`. It is recorded here rather than
quietly fixed because the shape — a fix that restates its own guarantee too broadly — is the one
this Alpha keeps meeting.

**Acceptance.** Every one of the five has a permanent test that fails without its fix. Four
mutations of the new matcher — `**/` modelled as "anything at any depth", `*` allowed to cross a
separator, classes expanded char-by-char, and class ranges expanded back into their members — are
each caught by a named `GlobTest` case, so the matcher is not merely different but pinned. The
last one is caught by name rather than by taking down the test JVM with an `OutOfMemoryError`,
which is what it did before the test was written to catch it.

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

Two things in this Alpha are *new* defect reports. TH-15's mitigation stopped existing and nobody
noticed for three releases because no gate reads the threat model. And A7.5's own write-up asserted
Dependabot behaviour that was the opposite of the truth, beside a gate that accepted a placeholder
comment as a version — both found by auditing the item rather than by anything failing. The other
five corrections are drift of the ordinary kind — versions, line numbers, an installer that gained a
feature a sibling did not.

The backlog is seven items, all small, none of which makes the product safer against a hostile
program. They make the *documentation* true, which is a different and more achievable goal: a user
who reads that a threat is mitigated should be looking at a control that exists, and a user who
reads that the compiled backend refuses something should find that it does.

**Last verified:** the branch that adds this file, at the commit named in the pull request that
merged it. The pin must name a commit reachable from the branch under review, because
squash-merge discards branch SHAs and a pin to one of them stops resolving. Every fact in the
starting table above was measured against `43597e7`; the six repairs they forced are recorded in
the threat model's own `### The Alpha 7 boundary re-read` section, not restated here.
