# Alpha 4 Phase 0 — Gate Assessment After Alpha 3 Remediation

**Verdict: ALPHA 4 PHASE 0 — SATISFIED.**
**ALPHA 4 IMPLEMENTATION — READY FOR A SEPARATE MAINTAINER START DECISION.**

This is the authoritative Phase 0 assessment. It supersedes the verdict in the first-session
report ([`governance/remediation/alpha-4-phase-0-first-session-report.md`](governance/remediation/alpha-4-phase-0-first-session-report.md)),
which was written before remediation and is preserved there as a historical record.

Nothing in this document starts Alpha 4. Phase 0 is the gate *before* implementation; clearing
the gate is not a decision to proceed. That decision belongs to the maintainer and is not taken
here.

Baseline for the "before" column: `a104629e6adef727b31f2c6f06a28a0045cd7835`.
Remediation landed on `main` at `e32215f59d74a04af4327587459bf0cb4518b884`.

**Figures re-confirmed at `1e87f00`** (merge of PR #85), 2026-11-11: the conformance baseline was
re-run and the website mirror re-read, because both had moved since this document was first
written and a gate carried forward on stale numbers is not a gate. Each figure below now names the
run or command that produces it, so the next reader can re-derive it rather than trust it.

## Gate requirements

The Phase 0 gate has four requirements. Each is assessed below. Requirements 2, 3 and 4 are
supported by artifacts in this repository that a future reader can re-run. Requirement 1 concerns
live external state on a different host, so it is **recorded as observed rather than as
reproducible evidence** — the site moves, and a claim about it cannot be re-checked from the
repository alone.

| # | Requirement | Status |
| --- | --- | --- |
| 1 | Website synchronization verified | **MET** (observed; see caveat below) |
| 2 | Automatic dispatch reliability confirmed | **MET** — one residual weakness documented |
| 3 | Canonical documentation complete and accurate | **MET** |
| 4 | Final Alpha 3 closure evidence persisted | **MET** |

## 1. Website synchronization — MET (observed state)

The website (`Oqthara/Oqthara.github.io`) mirrors documentation, examples and releases from this
repository into Cloud Firestore, then serves them. "Synchronized" means the production site is
serving content derived from the current `main`.

Observed at the time of assessment, on the website's own infrastructure:

| Item | Observation |
| --- | --- |
| Production reachable | HTTP 200 |
| Mirror synced from current `main` | Firebase sync run `42951523503` logged: `read 114 source records from Oqthara/oqthara@main at 1e87f00` |
| Failure mode observed earlier | `exit 4 — RESOURCE_EXHAUSTED` (Firestore quota), self-healed at 10:03Z |
| Mirror source | website re-reads this repository; it does not trust the payload |

The sync-run line is the one durable artifact here: it names the upstream commit the mirror
actually read, so a future reader can confirm the site was synchronized past the remediation
commit rather than merely reachable. The remaining rows are observations about another
repository's deployment and a managed database, and cannot be reproduced from this repository.
The durable, checkable part of requirement 1 is the mirror's design — that it re-reads this
repository rather than trusting a payload — and that design is visible in
`.github/workflows/site-content-dispatch.yml` and in the website's convergence workflow.

The earlier `RESOURCE_EXHAUSTED` failures were quota exhaustion on the mirror side, not a defect
in this repository's configuration. They resolved without a change here. Recording them matters
because a quota failure looks identical to a configuration failure from the outside, and the
distinction decides who fixes it.

## 2. Automatic dispatch reliability — MET, with a known weakness

`.github/workflows/site-content-dispatch.yml` announces two events:

- `push` to `main` → `content-updated`
- `release` published → `alpha-release-published`

Reliability evidence:

| Item | Evidence |
| --- | --- |
| Dispatch runs observed | succeeded on both `push` and `release` events |
| Decision logic unit-tested in-workflow | `node scripts/dispatch-site-content.mjs --self-test` runs before any dispatch |
| Missing token behaviour | fails loudly rather than passing silently |
| Concurrency | `queue: max`, `cancel-in-progress: false` — a push immediately followed by a release both get announced |

**Residual weakness, documented and accepted.** A `repository_dispatch` payload arrives at the
website with empty `TAG` and `SHA`. The website's 15-minute poller covers this, so no content is
lost; it means an event-triggered update may degrade to a poll-triggered one. This is a latency
degradation, not a correctness gap, and it does not block the gate. It is tracked as a residual
risk in the Alpha 3 closure report.

This weakness does not undermine the security property the workflow was designed around. The
payload is deliberately non-authoritative — it names a commit or tag and the website re-reads
this repository. An empty payload therefore fails safe: the site falls back to looking, which is
exactly what it would do with a forged or stale payload.

## 3. Canonical documentation — MET

| Document | Location | State |
| --- | --- | --- |
| Alpha 3 closure report | `docs/governance/remediation/alpha-3-closure-report.md` | Verdict header, all criteria, residual risks |
| Solo-maintainer exception | `docs/governance/remediation/solo-maintainer-exception.md` | Authoritative governance record |
| PR #37 retrospective | `docs/governance/remediation/pr-37-retrospective.md` | Findings and independence blocker |
| Release provenance | `docs/governance/remediation/alpha-3-release-provenance.md` | Jar reproducibility evidence |
| Alpha 3 audit (historical) | `docs/governance/remediation/alpha-3-audit-report-2026-11-09.md` | Preserved with superseding notice |
| Phase 0 first session (historical) | `docs/governance/remediation/alpha-4-phase-0-first-session-report.md` | Preserved with historical notice |
| Phase 0 assessment | this document | Current gate |
| Decision log | `docs/governance/README.md` | OQ-DEC-2026-010 records the solo-maintainer approval count |

Placeholder check: the closure report contains no unresolved `REMEDIATION_SHA` or
`REMEDIATION_PR` markers. Both were replaced with the real commit and pull request references in
the finalization change, which is the change this assessment accompanies.

Documentation drift is now machine-checked rather than maintained by hand:

- `scripts/check-docs-sync.py` runs eight checks and exits non-zero on drift.
- The version claim, the conformance counts and the keyword inventory are each derived from
  source rather than restated by hand.
- `docs/catalog.json` is regenerated by `scripts/gen-docs-catalog.py`; a stale catalog fails the
  check.

## 4. Final Alpha 3 closure evidence — MET

| Evidence | Where |
| --- | --- |
| Conformance verifier corrected and in required CI | `scripts/verify-conformance.py`, `.github/workflows/ci.yml` |
| Measured baseline | 203 compiled, 13 fallback, 0 divergences across 229 programs (212 executable) |
| Regression tests for the verifier | `scripts/tests/test_verify_conformance.py` |
| Keyword drift detection | `scripts/check-docs-sync.py`, parsing `Lexer.KEYWORDS` |
| Ruleset repaired and enforced | `main-protection`, read back after update |
| Direct-push rejection demonstrated | push to `main` refused by repository rule |
| Release provenance resolved | released `oq.jar` reproducible from `a104629e` |
| Superseded PRs closed with reasons | #38, #39 |

The closure verdict and its governance exception are recorded in the closure report. Evidence is
persisted in the repository rather than in a session workspace, so it survives independently of
any one maintainer's machine.

## What is not claimed

- **Independent human review was not performed.** There is one human maintainer. The
  `JoaoGaValentim` account is the same person, and an approval from it is a self-approval that
  this assessment does not count. See
  [`governance/remediation/solo-maintainer-exception.md`](governance/remediation/solo-maintainer-exception.md).
- **AI review is not independent review.** All agents in this project pin the same model
  endpoint, so their review is correlated with the author's. It is supplemental assurance, and it
  is recorded as such.
- **Phase 0 satisfaction does not authorize Alpha 4.** The gate is clear; the start decision is
  separate and remains with the maintainer.
