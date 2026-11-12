# ADR-0050 — Test cache entries are scoped to the operating system

- **Status:** accepted
- **Date:** 2026-11-12
- **Deciders:** maintainer
- **Supersedes:** nothing (amends [ADR-0049](0049-macos-ci-on-github-actions.md) and [ADR-0002](0002-ci-split.md) in consequence only)
- **Related:** [ADR-0010](0010-release-gate.md) (release gating), [ADR-0049](0049-macos-ci-on-github-actions.md) (macOS on GitHub Actions)

## Context

The CI matrix runs the test suite on six legs: three operating systems times two JDKs. The
platform legs are not redundant. They exist to catch behaviour that differs by operating system —
path separators, line endings, executable bits, native library loading, and process launch. ADR-0002
and ADR-0049 both treat the macOS and Windows legs as evidence that the suite passes *there*.

That evidence was not being produced.

`gradle.properties` sets `org.gradle.caching=true`, and `gradle/actions/setup-gradle@v6` in
`.github/workflows/ci.yml` maintains one remote build cache shared by every leg of the matrix.
Gradle derives a `Test` task's cache key from the classpath, the declared inputs, and the JVM
identity. The operating system is not part of it. All six legs therefore compete for the same key,
and whichever platform runs first donates its results to the other two.

The logs made this visible once the macOS legs moved to GitHub Actions and their output became as
easy to read as the Ubuntu leg's:

| Run | Leg | Test tasks reporting `FROM-CACHE` |
| --- | --- | --- |
| `43300234346` | `macos-latest / JDK 21` | none — every test task executed |
| `43302389917` | `macos-latest / JDK 21` | `oq-runtime`, `oq-core`, `oq-compiler`, `oq-stdlib`, `oq-lsp`, `bench-jmh` |
| `43302389917` | `windows-latest / JDK 21` | the same six |

Only `:oq-cli:test` genuinely executed on the later runs. The clearest symptom was the
`macos-latest / JDK 25` leg on pull request #132, which completed in **25 seconds**: no test suite of
this size runs in 25 seconds on a macOS runner, and it did not — it replayed.

The first macOS run ever recorded executed `:oq-core:test`, `:oq-compiler:test` and
`:oq-stdlib:test` for real. The change appeared on the second run and has persisted. That ordering
establishes cache reuse as the mechanism: the tasks were not skipped, and the jobs did run.

The defect is in the **evidence**, not in the tests. A green macOS leg backed by a replayed Linux
result carries exactly as much information as the Ubuntu leg, while still consuming macOS minutes.

## Decision

1. **Every `Test` task declares the operating system as an input.** In `build.gradle.kts`,
   `tasks.withType<Test>().configureEach` gains
   `inputs.property("operatingSystem", System.getProperty("os.name"))`. Cache entries no longer
   cross platforms.

2. **The build cache stays enabled.** `org.gradle.caching=true` is not weakened, and no `Test` task
   is marked uncacheable. The problem was an over-broad key, not caching. A repeat run of the same
   platform on the same commit still hits the cache, which is the property worth keeping — CI
   re-runs after an unrelated documentation change remain fast.

3. **The same block covers the fuzz tasks.** `fuzz${target.id}` tasks are registered through the
   same `configureEach`, so they inherit the input without a second declaration.

4. **The dashboard of "did this leg do work" is the raw log, not the job status.** A leg that
   finishes unusually fast is a signal to read the log for `FROM-CACHE` lines, not to trust the
   green tick. This is recorded here because it is the exact failure that went unnoticed: the jobs
   were green, the checks were satisfied, and the platform coverage was empty.

## Consequences

- **Six cache entries replace one per test task.** Each commit can now produce a cached entry per
  operating system instead of a single shared one. For a suite of this size the storage cost is
  modest, and it is bounded by the cache eviction rules of `setup-gradle`.

- **The platform legs now cost what they claim to cost.** macOS test executions will run on the
  runner rather than replaying, which raises wall-clock and billing on those legs. That cost was
  already being paid; it was simply buying nothing.

- **Cross-platform regressions can no longer hide behind a green tick.** A change that breaks path
  handling on Windows will fail the Windows leg instead of replaying the Ubuntu result.

- **JDK variation within one operating system is still shared.** Two legs of the same OS that differ
  only by JDK version may share entries when Gradle's inferred JVM identity does not distinguish
  them. This is deliberate for now: both JDKs run the same bytecode, and a JDK-specific failure has
  never been observed. If one is, the same mechanism extends — add the Java version to the input
  property.

- **The evidence is reproducible.** The `FROM-CACHE` lines are in the public logs for both runs
  named above, so the claim can be re-derived without trusting this document.
