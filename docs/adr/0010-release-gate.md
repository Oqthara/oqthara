# ADR-0010: Fail-closed release gate on CircleCI commit statuses

- Status: accepted
- Date: 2026-11-05
- Supersedes: the credential-based release gate in ADR-0002 and the README release notes

## Context

CircleCI reports one commit status per job to GitHub. On 2cf0699 the release
depends on exactly these three:

- `ci/circleci: macOS / JDK 21`
- `ci/circleci: macOS / JDK 25`
- `ci/circleci: jazzer-fuzz`

## Decision

`scripts/check-circleci.py` reads those contexts from the GitHub commit-status
API for `$GITHUB_SHA` and fails closed. Missing or pending contexts are polled
until `--timeout-minutes` (default 30), then the gate exits 1 with each
context's state. A `failure` or `error` exits 1 immediately. All three
`success` exits 0 and prints each context with its target URL. A missing
`GITHUB_TOKEN` exits 2; there is no skip path.

Statuses are authoritative because GitHub aggregates them from CircleCI and
keeps the target URL of the job that produced them. They are already per-job,
so lower-level job detail adds nothing mandatory.

The old gate treated `CIRCLECI_TOKEN` as optional and degraded to a warning,
which could pass a release with no evidence. Now only `GITHUB_TOKEN` is
required (always present in Actions). `--circleci-token` is a pure
cross-check: when set it queries the CircleCI v2 API and fails if workflow/job
statuses disagree with GitHub; when unset nothing is skipped or warned about.
`--dry-run` prints the resolved requirement without network access.

## What fails the gate

Missing or pending context at timeout, any `failure`/`error`, a tag mismatch
against `build.gradle.kts`, `Main.java` or the jar manifest, a missing
CHANGELOG section, a checksum mismatch, or the clean-runner smoke test.
