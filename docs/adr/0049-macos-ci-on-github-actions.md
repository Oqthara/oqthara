# ADR-0049 — macOS CI runs primarily on GitHub Actions

- **Status:** accepted
- **Date:** 2026-11-12
- **Deciders:** maintainer
- **Supersedes:** nothing (amends [ADR-0002](0002-ci-split.md) decision 4 and its consequences)
- **Related:** [ADR-0002](0002-ci-split.md) (platform CI split), [ADR-0010](0010-release-gate.md) (release gating)

## Context

ADR-0002 moved macOS to CircleCI and closed with an explicit contingency:

> If CircleCI becomes unavailable, the fallback is to re-add `macos-latest` to the GitHub
> matrix; the workflow is a single-line change and the ADR documents the tradeoff.

That contingency has now been triggered, twice, in the same shape.

The `main-protection` ruleset requires twelve status contexts, two of which are
`ci/circleci: macOS / JDK 21` and `ci/circleci: macOS / JDK 25`. On three consecutive pull
requests, each on a different head commit, CircleCI reported `ci/circleci: jazzer-fuzz` and
nothing else. The two macOS contexts were **absent**, not failing:

| Pull request | Head commit | Required contexts green |
| --- | --- | --- |
| #127 | `4b5af56` | 10 / 12 |
| #131 | `1dfa67f` | 10 / 12 |
| #132 | `dd0b500` | 10 / 12 |

Absent is not the same as failed, which makes this failure mode unusually bad to diagnose
and unusually easy to misread:

- `statusCheckRollup` reports **SUCCESS**, because everything that reported did pass.
- `mergeStateStatus` reports **BLOCKED**, because two required contexts never arrived.
- The pull request page shows "Expected — Waiting for status to be reported" indefinitely,
  with no red mark and no failing job to open.

The documented consequence of ADR-0002 — "Two CI dashboards exist" — also meant nobody was
looking at the second dashboard. A CI provider that silently stops dispatching work is a
worse failure than one that goes red, because nothing pages.

A first re-run of the CircleCI workflow was attempted before this ADR and the macOS contexts
still did not appear. The project has already spent more than one session on this blocker
alone.

## Decision

1. **`macos-latest` joins the `test` matrix in `.github/workflows/ci.yml`.** The matrix
   becomes `[ubuntu-latest, windows-latest, macos-latest]` × `["21", "25"]`, six legs. The
   job name template is unchanged, so the new contexts are exactly
   `macos-latest / JDK 21` and `macos-latest / JDK 25`.
2. **CircleCI keeps its full macOS configuration.** `.circleci/config.yml` is not edited.
   The `macos` workflow still builds and tests macOS on JDK 21 and 25 against the
   `macos-arm` executor. It is the fallback, and it stays exercised rather than archived,
   because an unexercised fallback is not a fallback.
3. **The ruleset requires the GitHub Actions contexts, not the CircleCI ones.** A ruleset
   cannot express "either provider"; a required context that never arrives blocks every
   merge, which is the defect being fixed. The `macos-latest / JDK 21|25` contexts replace
   `ci/circleci: macOS / JDK 21|25` in `scripts/apply-branch-protection.sh`, and the count
   stays at twelve.
4. **The release gate accepts either provider.** `scripts/check-circleci.py` now models each
   requirement as a *group* of acceptable contexts and merges GitHub commit statuses with
   GitHub check runs before evaluating. macOS is satisfied by
   `macos-latest / JDK 21` **or** `ci/circleci: macOS / JDK 21`, and likewise for JDK 25.
   This is where the fallback actually lives now.
5. **A failure from either provider fails the gate.** Grouping uses
   `_group_state`, in which any member in `failure`/`error` decides the group. The naive
   implementation (`any(state == "success")`) would let a green GitHub leg mask a red
   CircleCI leg, converting a safety net into a loophole. The rule is pinned by
   `scripts/tests/test_check_ci_gate.py`.
6. **`ci/circleci: jazzer-fuzz` remains required.** It is the only source of fuzzing
   evidence and it does report reliably.

## Consequences

- **Two providers now run the same macOS build.** That is deliberate redundancy and it
  costs double macOS minutes. GitHub-hosted macOS runners are billed at roughly ten times
  the Linux rate, so this materially raises CI cost for an open-source project — the exact
  tradeoff ADR-0002 was written to avoid. It is accepted because a merge gate that cannot
  pass is more expensive than the minutes: three pull requests sat blocked across two
  sessions.
- **GitHub macOS runners queue behind Linux runners.** A macOS leg may take longer to
  *start* than CircleCI's dedicated macOS executor. The timeout is raised to 40 minutes for
  `macos-latest` (`timeout-minutes: ${{ matrix.os == 'macos-latest' && 40 || 20 }}`) to
  absorb queue time without masking a genuine hang on the other legs.
- **Provider divergence becomes possible.** The two providers run independently pinned
  toolchains, so they can disagree. When they do, both results are visible on the pull
  request, and the release gate fails on either one being red rather than picking a winner.
- **Reverting is still a one-line change**, as ADR-0002 promised: remove `macos-latest`
  from the matrix, restore the two `ci/circleci:` contexts in
  `scripts/apply-branch-protection.sh`, and revert `REQUIRED_CONTEXTS` in the gate. This ADR
  is the place to record why, if it happens.
- **`installers-macos` stays on CircleCI.** It is not a required context, it is not
  duplicated on GitHub Actions, and installer coverage for macOS therefore still depends on
  CircleCI dispatching. That residual dependency is recorded in
  `docs/governance/alpha-4-risk-register.md`; duplicating it is deferred until installer
  coverage is promoted to a required check.
- **Branches opened before this change must be rebased.** Their heads predate the
  `macos-latest` matrix legs, so they will never produce the newly required contexts.
  Rebasing onto a `main` that contains this workflow is the only way for them to merge.
