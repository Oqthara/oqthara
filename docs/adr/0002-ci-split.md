# ADR-0002 — macOS CI lives on CircleCI

- **Status:** accepted
- **Date:** 2026-11-04
- **Deciders:** oq-ci-release, oq-orchestrator
- **Supersedes:** nothing

## Context

The first CI run exposed two concrete failures, both reproduced:

1. **Gradle 8.14.3 cannot run on JDK 25.** `./gradlew build` fails with
   `BUG! exception in phase 'semantic analysis' ... Unsupported class file major version 69`,
   because GUI/class-file version 69 is the JDK 25 format and Gradle 8.x does not parse it.
2. **Windows assertions failed on line endings.** `println` emits `\r\n` on Windows, so
   every `assertEquals("x\n", stdout)` failed even though behaviour was correct. This was a
   test-harness bug, not a language bug.

Separately, the project owner asked for one explicit change: **run macOS CI on CircleCI
and keep everything else on GitHub Actions.**

The unstated constraint is cost and queueing: GitHub-hosted macOS runners are billed at a
much higher rate than Linux and queue behind them, and the macOS job was the slowest and
least reliable leg of the matrix. CircleCI's macOS resource classes are a better fit for a
small open-source project and keep GitHub minutes for the Linux/Windows matrix.

## Decision

1. **Upgrade the Gradle wrapper to 9.8.2.** Verified locally that 9.8.2 runs on JDK 21 and
   on JDK 25, and that the full build is green on both.
2. **Normalise line endings in the test harness**, not in the language runtime. The
   interpreter keeps using the platform's native separator so Windows users get native
   output; `OqRunner` normalises `\r\n` to `\n` before asserting. A `.gitattributes` file
   forces LF in the repository and CRLF only for `.bat`.
3. **`.github/workflows/ci.yml` covers Linux and Windows** on JDK 21 and 25, plus the
   standalone-jar packaging job.
4. **`.circleci/config.yml` covers macOS** on JDK 21 and 25 with the same build, a
   conformance run, and the CLI smoke test. It uploads test results and the coverage report
   as CircleCI artifacts.
5. **CodeQL, Pages and Releases stay on GitHub Actions.** They depend on GitHub-only APIs
   (security events, Pages deployment, releases) and there is no reason to move them.

## Consequences

- The macOS leg is no longer part of the required GitHub status checks. The release gate
  (section 9.4 of the master prompt) still requires every OS to be green, so the release
  procedure must read CircleCI status as well as GitHub status.
- A human must enable the CircleCI project once. Until then, macOS is untested in CI and
  that is recorded in `NEEDS_HUMAN.md` and in `STATUS.md` as `macOS: pending`.
- Two CI dashboards exist. `RESUME.md` and `STATUS.md` list both URLs so a new session
  does not assume GitHub is the only source of truth.
- If CircleCI becomes unavailable, the fallback is to re-add `macos-latest` to the GitHub
  matrix; the workflow is a single-line change and the ADR documents the tradeoff.
- The release gate is automated: `release.yml` runs `scripts/check-circleci.py` before
  `softprops/action-gh-release`. The script matches the pipeline by `$GITHUB_SHA` on any
  branch, requires successful `macos` jobs for JDK 21 and 25 and a successful `fuzzing`
  job, and polls for at most 20 minutes. The optional `CIRCLECI_TOKEN` repository secret
  (a CircleCI personal API token, read-only) enables the check; without it the step warns
  and skips, so forks can release. The token is sent only to `circleci.com` and is never
  printed.
