# ADR-0010: Fail-closed release gate on CI evidence for a commit

- Status: accepted; amended by [ADR-0049](0049-macos-ci-on-github-actions.md) (macOS
  evidence may now come from either provider)
- Date: 2026-11-05
- Supersedes: the credential-based release gate in ADR-0002 and the README release notes

## Context

On 2cf0699 the release depended on exactly three CircleCI commit statuses:
`ci/circleci: macOS / JDK 21`, `ci/circleci: macOS / JDK 25`, and
`ci/circleci: jazzer-fuzz`. CircleCI reports one commit status per job.

That made CircleCI the sole source of macOS evidence, which ADR-0049 reversed
after CircleCI stopped dispatching the macOS jobs: the contexts never arrived,
so no release could ever satisfy this gate, and no pull request could satisfy
the branch ruleset either.

## Decision

`scripts/check-circleci.py` reads CI evidence for `$GITHUB_SHA` from **two**
GitHub APIs and fails closed:

- the commit-status API, where CircleCI (and anything else configured to) reports;
- the check-runs API, where GitHub Actions reports.

Each requirement is a **group** of acceptable context names, and a group passes
when one member is `success`:

| Requirement | Acceptable contexts |
| --- | --- |
| Fuzzing | `ci/circleci: jazzer-fuzz` |
| macOS / JDK 21 | `macos-latest / JDK 21` **or** `ci/circleci: macOS / JDK 21` |
| macOS / JDK 25 | `macos-latest / JDK 25` **or** `ci/circleci: macOS / JDK 25` |

A group fails if **any** member reported `failure` or `error`, even when another
member succeeded (`_group_state`). This asymmetry is the point: an `any(...)`
implementation would let a green GitHub Actions leg mask a red CircleCI leg and
turn the gate into a rubber stamp. A group with no successful member and no
failure is `waiting`, which is polled until `--timeout-minutes` (default 30)
before exiting 1.

Check runs are normalised into the commit-status shape, and only
`status == "completed"` with `conclusion == "success"` counts as success.
`neutral`, `skipped` and anything non-terminal map to `pending` so that a job
which did not actually run can never be mistaken for evidence.

Cross-provider consistency is still available via `--circleci-token`: it queries
the CircleCI v2 API and reports a problem when a CircleCI job that *did* run
disagrees with GitHub. It deliberately no longer demands that every CircleCI job
exist, because a release whose macOS evidence came from GitHub Actions is valid.

`GITHUB_TOKEN` is required; missing it exits 2. There is no skip path.
`--dry-run` prints the resolved groups without network access.

## What fails the gate

Missing or pending evidence at timeout, any `failure`/`error` from either
provider, a tag mismatch against `build.gradle.kts`, `Main.java` or the jar
manifest, a missing CHANGELOG section, a checksum mismatch, or the clean-runner
smoke test.
