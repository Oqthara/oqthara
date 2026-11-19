# ADR-0074 — A platform is validated only by a run on that platform

- **Status:** Accepted
- **Date:** 2026-11-19
- **Related:** [ADR-0002](0002-ci-split.md) (the CI split), [ADR-0049](0049-macos-ci-on-github-actions.md)
  (macOS on GitHub Actions), [ADR-0050](0050-platform-scoped-test-cache.md) (per-OS cache keys),
  finding 20 of [`reviews/PR-272.md`](../governance/reviews/PR-272.md),
  [`platform-validation-policy.md`](../governance/platform-validation-policy.md)

## Context

Finding 20 recorded a Windows-only defect that survived a green local suite, nine green gates and a
green conformance corpus. The defect is unremarkable — `Path.resolve("<t>")` throws
`InvalidPathException` on Windows and quietly finds nothing on macOS and Linux — but the way it
survived is not. Every check that reported success was correct about the machine it ran on, and no
check was answering the question "has this been run where the answer differs".

Three properties of the repository made that possible:

1. **A local green suite is evidence about one platform.** The developer's machine is one leg of a
   six-leg matrix. Nothing in the repository said that, so "the suite passes" read as a complete
   statement when it was a one-sixth statement.
2. **Skipped tests and passing tests are indistinguishable in a summary.** `assumeFalse(WINDOWS, …)`
   in `FileOperationsTest` is right: a FIFO cannot be created reliably on Windows. But a skip is
   reported as `skipped` in the JUnit XML and is therefore absent from the pass count, and no
   summary distinguishes "passed here" from "would not run here". A test that skips everywhere
   looks like a test that passes.
3. **A test can branch on the platform and pass on both.** `SafeArchiveTest` asserts the
   case-folding rejection on Windows and macOS and the opposite on Linux. Both outcomes are
   correct, and both are green — so the Linux leg proves nothing about the branch the other two
   take, and the green tick does not say which branch ran.

ADR-0050 already closed a related and worse hole: Gradle's build cache had no operating system in
its key, so the macOS and Windows legs replayed the Ubuntu result and proved nothing while costing
macOS and Windows minutes. Its remedy made the platform legs execute. It did not, and could not,
make anything state *which* platform a given test needs.

The consequence is a policy, not a code change. The defect classes above are not detectable by a
better test runner; they are detectable only by declaring the dependency and checking the
declaration.

## Decision

1. **Platform-dependent behaviour is declared, not inferred.** Every test whose outcome can differ by
   operating system names the environments it must run in, in a table in
   [`platform-validation-policy.md`](../governance/platform-validation-policy.md). The declaration
   is the claim; `check-platform-coverage.py` checks the claim still matches the code.

2. **`run` and `skip` are the only dispositions, and both are explicit.** A test declared `run` on
   an environment must execute there, not skip. A test declared `skip` must name why. An
   undeclared platform-conditional test is a failure, because the cheap way out of this policy is
   to leave the table empty.

3. **A declaration that contradicts its own guard is a defect.** If a test is declared `run` on
   Windows while its body begins with `assumeFalse(WINDOWS, …)`, the declaration is false and the
   gate rejects it. This is the specific mechanism that stops a platform-dependent test from
   counting as validated when it did not run.

   A guard is checked statically; a skip is also checked against what happened. The gate reads
   the JUnit XML a build leaves behind and fails when a test whose row says `run` on this host
   appears among its class's skipped cases. Without that half, `skipped=0` was a sentence in
   this policy that nothing implemented — the same shape of defect as a declared platform with
   no run, committed in the document that exists to forbid it.

4. **The merge-blocking set is the platform set.** The required status checks on `main` name every
   operating system in the support list at every supported JDK. A required context that omits a
   platform converts that platform into advice. The set is recorded in the policy with the date it
   was verified, because it lives in repository settings that no gate in this tree can read.

5. **A job that proves something no other job proves is required or it is not evidence.** The
   display-backed Swing job is the only place a frame is realized under a real X server. If it is
   not merge-blocking, then no merge-blocking job proves it, and its green tick is a fact about a
   runner that a broken change cannot affect.

   **As measured, it was not required, and is now.** The `main-protection` ruleset required twelve
   contexts and `Display-backed Swing` was not one of them, so the gap was live rather than
   hypothetical. The policy recorded it under *Not required, and therefore not evidence* rather
   than describing it as a requirement it was not.

   The gap is closed as of 2026-11-19: the context ran green on #273 and was added to
   `main-protection`, taking the required set to thirteen. A green run came first, then the
   ruleset change, because promoting a context that has never passed would have made every pull
   request unmergeable rather than making it safer. That ordering was imposed by the review of
   #273 (`docs/governance/reviews/PR-273.md`), not by this ADR, which does not state one. The
   policy and this decision are updated in the same change that records the run; the ruleset itself
   remains, as the consequences below say, an observation rather than a checked invariant.

6. **The matrix stays at six legs.** `./gradlew build` already runs the full suite on every leg, so
   the cost of platform coverage is already being paid and no test is opted into extra runs. The
   policy bounds what a test may *demand*, not what the matrix executes. A seventh leg needs a
   defect class the six cannot see, argued in an ADR.

## Consequences

- **A platform-conditional test cannot be added without declaring it.** The gate scans for
  `os.name`, `assume*`, `File.separatorChar`, `@EnabledOnOs`, the platform constants and the
  `.exe` / `.bat` / `unix:` markers, so the table is checked against the code rather than
  trusted. It does **not** see every possible platform predicate: a test that branches on
  `line.separator`, `File.pathSeparator`, a locale, or a JDK-version behaviour is outside what
  these markers find, and would have to be declared by hand. The list is a floor, not a proof.

- **A declaration cannot outlive its test.** The gate resolves every named test against the source
  and checks that the row names the class that actually declares it, so deleting or renaming a
  test leaves a stale row that fails rather than a row that quietly protects nothing.

- **Skipping remains legitimate.** The policy does not ban `assumeFalse`. Most of the guarded
  tests skip on Windows for a real reason — a FIFO, an execute bit, a POSIX shell, a POSIX
  attribute view — and driving them to pass would mean faking the capability, which finding 20's
  sibling defect already showed is worse than skipping. What the policy bans is *undeclared*
  skipping, and a skip recorded where the row promised a run.

- **A test can also give up without skipping, and that is worse.** Four tests return from a
  `catch` on a host that lacks a capability. They assert nothing there, but JUnit records a
  **pass**, so no skip count and no report notices. `SafeArchiveTest`'s execute-bit test is the
  example: it cannot make Windows withhold a bit Windows does not have. Those four are declared
  in the policy's silent-return table for the same reason the guards are declared — the
  behaviour is legitimate, the silence is not.

- **The policy cannot enforce repository settings.** Nothing in this tree can read `main`'s ruleset,
  so the required-context list is a recorded observation with a date rather than a checked
  invariant. The gate checks the half it can see: that each documented context is a job the
  workflow actually produces. A renamed job becomes a failing gate instead of a silently
  unsatisfiable requirement.

- **A green local suite is now stated to be one platform's evidence.** The ADR exists so a future
  reader cannot repeat finding 20 honestly: the claim "the suite passes" is no longer available as
  a complete claim, and saying it now requires saying where.
