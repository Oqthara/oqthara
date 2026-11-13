# Alpha 3 closure report

## ALPHA 3 CLOSED — WITH EXPLICIT SOLO-MAINTAINER GOVERNANCE EXCEPTION

The technical remediation and remaining closure work have been completed and validated.
Independent human review was unavailable because the project has one human maintainer. The
maintainer explicitly accepted this bounded governance exception.

## What that verdict does and does not mean

It **means** every mechanical control was satisfied and every technical finding was remediated
with executed evidence: the conformance verifier was repaired and wired into required CI, keyword
drift detection was rewritten to stop holding its own copy of the truth, `main-protection` gained
the pull-request rule it never had, the superseded pull requests were closed on evidence, and the
release provenance was resolved by reproducing the artifact rather than by asserting a story.

It **does not mean** independent human review happened. It did not. There is one human
maintainer, and the GitHub account `JoaoGaValentim` belongs to that same person. An approval from
it is a self-approval wearing a different avatar, and this report does not count it as anything
else.

The exception is narrow and named: `required_approving_review_count` is **0**. Everything else
stays enforced — pull requests mandatory, direct pushes rejected, all 12 required CI contexts
mandatory, review-thread resolution required, squash-only, `bypass_actors: []`,
`current_user_can_bypass: never`. The full terms are in
[`solo-maintainer-exception.md`](solo-maintainer-exception.md).

**On the collapsed approval count.** It was lowered to 0 deliberately and for a stated reason,
not because the gate was inconvenient. With a count of 1 and one human maintainer, the only two
reachable states are "nothing ever merges" and "the second account approves and the audit trail
lies." The second is fabricated review. The exception removes the incentive for it while leaving
every check a single maintainer can honestly satisfy intact.

---

## 1. What changed, and the commit that changed it

Baseline before remediation: `a104629e6adef727b31f2c6f06a28a0045cd7835`.

Remediation commit: `e32215f59d74a04af4327587459bf0cb4518b884`, merged by
[#40](https://github.com/Oqthara/oqthara/pull/40) from branch `alpha3-remediation-verification`.
The branch's own commit is `63bb1bcadcb08e85740aa1f34e27c270241c180f`; GitHub squash-merged it,
so the branch SHA is not an ancestor of `main` and the merge SHA is the durable reference.

| Ref | Value |
| --- | --- |
| `a104629e` | baseline before remediation |
| `63bb1bc` | remediation branch commit |
| `e32215f5` | **squash merge on `main`** |

**Evidence for the sequencing of §2.4.** The `pull_request` rule was applied *after* `e32215f5`
landed, not before, and this was deliberate. Applying it first would have left the repository
with a rule its only collaborator is forbidden to bypass and no second approver to satisfy —
the change would have been permanently unmergeable. Applying it after is not a bypass:
at the moment `#40` merged, the rule did not exist, so nothing was overridden. From
`e32215f5` onward, every change is bound by it, including future changes by the maintainer.

`bypass_actors` was empty before and remains empty, and `current_user_can_bypass` reports
`never`, so the maintainer cannot override the rule either.

| File | Change |
| --- | --- |
| `scripts/verify-conformance.py` | Replaced stale `FLOOR_COMPILED = 182` / `EXPECTED_EXECUTABLE = 197` with a single measured `BASELINE`; extracted `measure_corpus()` and `evaluate()`; `evaluate()` now reports every problem rather than stopping at the first. |
| `scripts/tests/test_verify_conformance.py` | New. 8 tests at closure, 19 today, covering the baseline, the executable pin, the compiled floor, and the divergence path. |
| `.github/workflows/ci.yml` | Two required steps: script self-tests, and `standaloneJar` + `verify-conformance.py`. |
| `scripts/check-docs-sync.py` | Keyword check now parses `Lexer.KEYWORDS` instead of holding its own copy; added a `CONTRIBUTING.md` count cross-check. |
| `CONTRIBUTING.md` | Keyword count 18 → 20. |
| `docs/spec/language.md` | Documented that range length is computed in 64-bit arithmetic. |
| `oq-cli/.../StdlibCoverageTest.java` | Added an interpreter assertion for `length(0 to 3000000000)`. |
| `CHANGELOG.md` | Three `### Fixed` entries under `[Unreleased]`. |
| `docs/governance/` | This report, the audit report, the PR #37 retrospective, the provenance record, and two decision records. |

## 2. Closure criterion by criterion

Each row names the evidence, not an assertion.

### 2.1 Both repositories revalidated, website synchronized — **MET**

| Item | State |
| --- | --- |
| `Oqthara/oqthara` HEAD | `e32215f5…` on `main`; this PR's merge commit after |
| `Oqthara/Oqthara.github.io` | production `https://oqthara.github.io/` → HTTP 200 |
| Firestore mirror, last verified sync | Firebase sync run `42677090443` read 68 source records from `Oqthara/oqthara@main` at `e32215f` |
| Firestore `metadata/catalog.status` | `published` |
| Release dispatch run `42552644364` | SUCCESS — 13-case matrix |
| Scheduled convergence run `42598062024` (in `Oqthara/Oqthara.github.io`) | SUCCESS — `none -> v0.1.0-alpha.3` |

### 2.2 PR #37 retrospective review — **CONTENT MET, INDEPENDENCE NOT MET (EXCEPTION ACCEPTED)**

The retrospective itself is complete: [`pr-37-retrospective.md`](pr-37-retrospective.md).
It establishes that #37 merged with `reviews: 0`, that 12 of its 15 changed paths were Java
despite a body claiming otherwise, and that it silently absorbed the work of #38 and #39.

**The independence half of this criterion was not met, and is not claimed as met.** There is one
human maintainer. `JoaoGaValentim` is the maintainer's own second account, not a second human.
Every Hive agent pins the same model endpoint, so agent review is same-model review.

The maintainer explicitly authorized accepting this bounded exception rather than manufacturing a
review. The exception and its limits are recorded in
[`solo-maintainer-exception.md`](solo-maintainer-exception.md), and §5 states what it would take
to close the gap for real.

### 2.3 PR #38 and #39 dispositioned — **MET**

Both **closed without merging**, branches preserved, on evidence rather than inference:

| PR | Disposition | Verification performed |
| --- | --- | --- |
| [#38](https://github.com/Oqthara/oqthara/pull/38) | Closed, superseded | Patch applies cleanly to `main` and its test passes *on the patched tree*. But pristine `main` already produces a better message: `Methods.java:55` → `Errors.immutableRefusal`. Its asserted text `cannot modify this list` has **zero** occurrences on `main`. Merging would add a competing, narrower message. |
| [#39](https://github.com/Oqthara/oqthara/pull/39) | Closed, superseded | `length(0 to 3000000000)` already returns `3000000001` on `main`. The PR widens `Values.size` `int`→`long`, breaking the published `dev.oqthara:oq-runtime` API; `main` solved it additively via a new `length()`. |

Two genuinely missing artifacts from #39 were **harvested rather than discarded**: the
`language.md` 64-bit sentence and the interpreter-level assertion. Both were confirmed absent
from `main` before being added.

### 2.4 `main-protection` requires pull requests and checks — **MET, HARDENED**

The defect was real and is the root cause of #37: the ruleset had `deletion`,
`non_fast_forward`, and `required_status_checks` — and **no `pull_request` rule at all**. A
direct push was permitted and zero approvals were required.

`scripts/apply-branch-protection.sh` now also sets the `pull_request` rule: a parameterized
approval count (`REQUIRED_APPROVALS`, currently `0` under the exception at
[`solo-maintainer-exception.md`](solo-maintainer-exception.md)), stale approvals dismissed on
push, and review-thread resolution required. `bypass_actors` remains empty, so the protection
binds the maintainer too.

The script is the in-band artifact: the policy lives in version control next to the code it
governs, so the change is reviewable in the same pull request that records it. Its payload is a
complete snapshot, because the ruleset API replaces the whole resource on `PUT` — a field
omitted from the payload is reset to the API default rather than left alone.

**Applied state, read back from the API on ruleset `26650412` after applying the change:**

```
rules: deletion, non_fast_forward, pull_request, required_status_checks
pull_request:
  required_approving_review_count:   0     # solo-maintainer exception, see below
  dismiss_stale_reviews_on_push:     true
  require_last_push_approval:        false
  require_code_owner_review:         false
  required_review_thread_resolution: true
  require_extra_approval_for_unattributed_changes: true
  required_reviewers:                []
  allowed_merge_methods:             ["squash"]
required_status_checks:    12 contexts, strict_required_status_checks_policy: true
bypass_actors:             []
current_user_can_bypass:   never
```

The approval count is 0, not 1. That is the authorized solo-maintainer exception, argued in full
in [`solo-maintainer-exception.md`](solo-maintainer-exception.md). The `pull_request` rule itself
remains active, so pull requests are still mandatory, review threads still block merge, and
squash remains the only merge method. What was removed is the one control a single human
maintainer cannot honestly satisfy — and with it, the incentive to fabricate one.

**Enforcement was tested, not assumed.** A direct commit to `main` was attempted and rejected:

```
$ git push origin main
remote: - Changes must be made through a pull request.
 ! [remote rejected] main -> main (push declined due to repository rule violations)
error: failed to push some refs to 'https://github.com/Oqthara/oqthara.git'
```

The probe commit was then discarded with `git reset --hard origin/main`, so nothing was left
behind in the tree.

The change was applied **after** `e32215f5` landed, and §1 records why that ordering is correct
rather than convenient: the rule did not yet exist when `#40` merged, so nothing was bypassed.
The alternative — apply the rule, then override it as an administrator — would have been a real
bypass, and is precisely what the order forbids.

### 2.5 Conformance counts reconciled, verifier non-weakening — **MET**

```
$ python3 scripts/verify-conformance.py
ok: 192 compiled, 19 fallback, 0 divergences across 223 programs (207 executable)
```

> This block is the output captured at closure (`e32215f`), and it is preserved unedited: this
> report is a record of the closure decision, so its evidence is dated on purpose. The corpus has
> grown since, and the same command now prints
> `ok: 203 compiled, 13 fallback, 0 divergences across 229 programs (212 executable)`. The
> property being evidenced — convergence of the interpreter and the compiled jar, with zero
> divergences — still holds; only the denominators moved. Run the command for current figures
> rather than quoting this one.

The two drift directions are both closed:

| Constant | Old semantics | Problem | New semantics |
| --- | --- | --- | --- |
| `EXPECTED_EXECUTABLE` | exact match, `197` | hard-failed at 207 | exact match against the measured 207 |
| `FLOOR_COMPILED` | lower bound, `182` | silently passed a real 192 — 10 programs weaker than measured | floor against the measured 192 |

Keeping the executable count exact-match means a corpus change still fails the gate. Making
the compiled count a floor means a compiler improvement does not. Neither direction can now
drift silently.

### 2.6 Verifier in required CI with regression coverage — **MET**

`.github/workflows/ci.yml` runs the script self-tests and then the verifier against a freshly
built `standaloneJar`, so the two engines are compared on every push.

```
$ python3 -m unittest discover -s scripts/tests -v
Ran 8 tests in 0.31s
OK
```

### 2.7 Keyword inventory agrees with `CONTRIBUTING.md`, drift detected — **MET**

18 → 20 in `CONTRIBUTING.md`. The checker no longer keeps its own list; it parses
`Lexer.KEYWORDS`. Both drift directions were proven by mutation:

```
$ sed -i '' 's/we are at 20/we are at 18/' CONTRIBUTING.md && python3 scripts/check-docs-sync.py
Documentation drift detected: CONTRIBUTING.md says 20 -> 18 ... exit 1

$ # add a bogus "when" to Lexer.KEYWORDS
$ python3 scripts/check-docs-sync.py
Documentation drift detected: keyword budget ... exit 1
```

### 2.8 Tag/release discrepancy resolved with auditable provenance — **MET**

[`alpha-3-release-provenance.md`](alpha-3-release-provenance.md). No tag was moved, deleted,
or re-created. The released `oq.jar` hashes to `3edfd8ef…` and is **byte-reproducible from
`a104629e`**, the commit the tag names. Built from `5b33f18` instead it is `7f80bdb9…`
— different. The tag is therefore correct, and the audit report's "tag mispointed" finding
was wrong. That retraction is recorded in place.

`v0.1.0-alpha.3` remains a lightweight tag while `.1` and `.2` are annotated. That is a real
inconsistency, recorded with the forward-looking practice rather than fixed by rewriting a
published ref — a rewrite the order permits only for documented technical necessity.

### 2.9 Website dispatch reliability verified — **MET**

The failures were `exit 4 — 8 RESOURCE_EXHAUSTED: Quota exceeded` on Firestore: a transient
infrastructure condition, not a code defect. The 10:03Z scheduled run self-healed without
intervention and synced alpha.3. The design is fail-closed and poll-based, so a transient
failure retries and converges rather than publishing stale content.

One latent weakness is recorded rather than fixed: the `repository_dispatch` payload arrives
with **empty** `TAG` and `SHA`. The poll covers this, so it is not a live defect, but a
dispatch-only path would have nothing to act on.

### 2.10 Closure and Phase 0 reports persisted — **MET**

This document and [`alpha-4-phase-0-first-session-report.md`](alpha-4-phase-0-first-session-report.md)
are in `docs/governance/remediation/`, in the canonical repository, describing final state.

### 2.11 Remediation PRs have review, resolved conversations and passing checks — **PARTIAL (checks and conversations MET; independent review NOT MET, exception accepted)**

**Checks and conversations: met. Independent review: not met, covered by the accepted exception.**
The heading is deliberately not "MET": one of the two clauses this criterion tests fails outright,
and a qualifier that reads as a footnote would misrepresent which clause failed.

[#40](https://github.com/Oqthara/oqthara/pull/40) passed all 15 checks that ran, including all 12
required contexts — Ubuntu and Windows on JDK 21 and 25, CircleCI macOS on JDK 21 and 25, the
bytecode backend, the standalone jar, installer tests on both platforms, plus CodeQL and the
Docker image build, which are not required. It had no review threads. It merged with zero
approvals because no rule required any at the time. The distinction between "15 checks ran" and
"12 checks are required" matters here, because the claim under test is about what was enforced.

[#41](https://github.com/Oqthara/oqthara/pull/41) carries the remaining governance work. Its diff
was checked by the maintainer against the complete change set and every claim in it was
re-verified by execution rather than by reading — but that is **author verification, not the
review this criterion asks for**, since the author and the reviewer are the same human. #41 has
no review threads; there was nothing to resolve. Thread resolution is enforced by the ruleset for
this and every future pull request.

No approval from `JoaoGaValentim` is counted as review evidence anywhere in this report. That
account belongs to the same maintainer, so an approval from it is a self-approval.

The approval count on `main-protection` is **0** under the documented solo-maintainer exception.
An approving review from `JoaoGaValentim` exists on #41 and is **explicitly not counted** as
independent review, because that account belongs to the same maintainer.

### 2.12 No unresolved Critical finding — **MET**

No Critical finding was raised in the audit or found during remediation.

### 2.13 Every High finding explicitly accepted — **MET**

| # | High finding | Disposition |
| --- | --- | --- |
| H1 | PR #37 merged un-reviewed with a materially incomplete description | Remediated retrospectively (§2.2); prevented by §2.4. Recorded as a permanent process failure in the retrospective. |
| H2 | `main-protection` had no `pull_request` rule | Fixed (§2.4) |
| H3 | Conformance verifier stale, weakening, and not in CI | Fixed (§2.5, §2.6) |
| H4 | Keyword count drifted, detector held its own copy | Fixed (§2.7) |

### 2.14 Medium/Low findings have owners and dispositions — **MET**

| Finding | Severity | Disposition |
| --- | --- | --- |
| `CHANGELOG.md [Unreleased]` empty despite two shipped fixes | Medium | Fixed — three entries added |
| `gradlew.bat` perpetually dirty in every fresh clone | Low | **Open, owned by maintainer.** `.gitattributes` declares `*.bat text eol=crlf` but the blob is stored inconsistently, so the file is modified on checkout in clean clones. Pre-existing, unrelated to this remediation, excluded from every commit here. |
| `v0.1.0-alpha.3` lightweight vs annotated | Low | Recorded, not changed (§2.8) |
| `repository_dispatch` payload arrives empty | Low | Recorded, mitigation is the poller (§2.9) |
| `Oqthara.github.io` has no ruleset | Medium | **Open, owned by maintainer.** Out of scope for this order; flagged for the next governance pass. |
| Firestore `releases/*` not anonymously readable | Low | Evidence gap, not a defect; verification requires authenticated access |

### 2.15 Final audit maps each requirement to evidence — **MET**

This document is that mapping.

---

## 3. Validation actually run

These are the results of the closure run at `a104629e`. They are a dated record, not a live
measurement; the suite has grown since (the verifier's self-tests were 8 then and 19 today), so
do not read any count here as the current one.

| Command | Result |
| --- | --- |
| `./gradlew build` | 452 tests, 0 failures, 0 errors, 0 skipped; oq-core coverage 85.1% against an 80% gate |
| `python3 scripts/verify-conformance.py` | `ok: 192 compiled, 19 fallback, 0 divergences across 223 programs (207 executable)` |
| `python3 -m unittest discover -s scripts/tests` | 8 tests, OK |
| `python3 scripts/test_watchdog.py` | 13 tests, OK |
| `python3 scripts/check-docs-sync.py` | exit 0 — 6 checks, 7 result lines |
| `./gradlew :oq-cli:test --tests '*StdlibCoverageTest*'` | 22 tests pass |
| Jar reproducibility from `a104629e` | sha256 matches the released asset |
| Website production | HTTP 200; `sourceCommit` matches `main` (live external state; not re-checked in-repo) |

## 4. Residual risks

1. **Single-maintainer review.** Structural, not fixable by this remediation. Every review gate in
   the process is a self-review until a second genuine human maintainer exists. The approval count
   is 0 by explicit exception; the gap it represents is real and is carried here rather than
   papered over.
2. **`gradlew.bat` checkout dirtiness.** Any contributor's first `git status` is noisy. A
   normalization commit (re-checkout with `core.autocrlf` and re-add) would fix it, but it
   touches a build-critical file and does not belong in a conformance PR.
3. **Website repository unprotected.** `Oqthara/Oqthara.github.io` receives dispatches and
   publishes production content with no ruleset at all. Out of scope for this order; flagged for
   the next governance pass.
4. **Empty dispatch payload.** Latent; the poller is the effective mechanism.

## 5. The governance exception, stated plainly

Alpha 3 closes with the review requirement **narrowed by explicit maintainer authorization**, not
satisfied.

The reasoning is short. The order forbids fabricating independent review. With one human
maintainer, there is exactly one way to make an approval appear on a pull request — approve from
the maintainer's own second account — and that is fabrication. So the honest options were to
leave the repository permanently unmergeable, or to recognize in policy that this project has one
maintainer.

The maintainer chose the second and authorized it explicitly. `required_approving_review_count`
is 0; every other control is intact and enforced. The full terms, including what was deliberately
*not* reduced, are in [`solo-maintainer-exception.md`](solo-maintainer-exception.md).

**Two things this report is careful not to do:**

1. It does not claim the independent-review criterion passed. It did not.
2. It does not cite the `JoaoGaValentim` approval on #41 as evidence of review. That account is
   the maintainer's, and counting it would be exactly the misrepresentation the order prohibits.

**To end the exception:** add one genuine human maintainer and run

```
REQUIRED_APPROVALS=1 scripts/apply-branch-protection.sh
```

The script parameterizes the count for this purpose. No code change is required, and the
exception document records the command so it is not forgotten.

## 6. Formal verdict

**ALPHA 3 CLOSED — WITH EXPLICIT SOLO-MAINTAINER GOVERNANCE EXCEPTION.**

- Technical remediation: complete at `e32215f5`. The final governance artifact that records this
  verdict, the exception and the Phase 0 assessment is the pull request that carries this report.
- Validation: full build green, conformance reconciled with zero divergences, drift detection
  proven in both directions, direct-push rejection observed, 14 checks green on the finalization
  pull request and 15 on the remediation pull request.
- Independent human review: **not performed**, because the project has one human maintainer. The
  maintainer explicitly authorized and accepted this bounded exception.
- Controls that remain active: pull requests mandatory, direct pushes prohibited, all 12 required
  CI contexts mandatory, review-thread resolution required, squash-only merges, no bypass actors,
  no administrative bypass, force-push and deletion protections intact.
- Independent review must be reconsidered when another genuine human maintainer joins. The exact
  command to restore it is recorded in
  [`solo-maintainer-exception.md`](solo-maintainer-exception.md) and in OQ-DEC-2026-010.

## 7. Closure record — merge and final state

This verdict was published by merging the finalization pull request through the protected
workflow. No bypass was used, no administrative override, and no approval was fabricated.

| Item | Value |
| --- | --- |
| Initial `main` (remediation baseline) | `a104629e6adef727b31f2c6f06a28a0045cd7835` |
| Remediation merge (#40) | `e32215f59d74a04af4327587459bf0cb4518b884` |
| Finalization PR | [#41](https://github.com/Oqthara/oqthara/pull/41) |
| Finalization branch head | `fdcd833291d2f5351f19437b7b66b1ed5aa45671` |
| **Final merge commit on `main`** | **`4f82fe98e0308d11a0cea8e2489eaf8c68bbfb06`** |
| Merge method | squash, through the protected pull-request workflow |

### Effective ruleset at closure

Read back from ruleset `26650412` after the merge, not before:

```
enforcement:      active
target:           branch   refs/heads/main
rules:            deletion, non_fast_forward, pull_request, required_status_checks
required_approving_review_count:   0        # the exception
required_review_thread_resolution: true
dismiss_stale_reviews_on_push:     true
allowed_merge_methods:             ["squash"]
required_status_checks:            12 contexts, strict_required_status_checks_policy: true
bypass_actors:                     []
current_user_can_bypass:           never
```

The only value that differs from a conventional protected branch is the approval count. A
direct push to `refs/heads/main` was attempted again after the merge and refused with
`GH013: Repository rule violations found for refs/heads/main`.

### Post-merge verification on `main`

| Workflow | Result |
| --- | --- |
| CI | success |
| CodeQL | success |
| Site content dispatch | success |
| Firebase sync (`Oqthara/Oqthara.github.io`) | success — read 70 source records from `Oqthara/oqthara@main` at `4f82fe9` |

### Checks on the finalization pull request

All 14 reported checks passed, including all 12 required contexts. Zero review threads, so
`required_review_thread_resolution` had nothing to block. `mergeStateStatus` reached `CLEAN`
before the merge.

### What an auditor will find if they check

- The `JoaoGaValentim` approval on #41 is present in the review history as `DISMISSED`. It was a
  self-approval, dismissed by `dismiss_stale_reviews_on_push`. It is not counted as review.
- No human other than the maintainer reviewed any of this work, and no document claims otherwise.
- The agent review that found the Critical defect is recorded as correlated, not independent.
- The review did find a real Critical defect — documents claiming an applied policy change that
  had not been applied — and it was fixed before merge rather than merged over.

---

## 8. Post-closure remediation — website publication (2026-11-13)

Work after the verdict was published. Nothing here reopens Alpha 3; it repairs two things the
closure report's own criteria depend on and records them where an auditor will look.

### 8.1 The code-block audit's exit contract was untrue

`Oqthara/Oqthara.github.io` ships an audit (`scripts/audit-code-blocks.mjs`) that reports two
kinds of finding: *drift* (a documented structure the grammar cannot colour) and *uncoloured
blocks* (documentation that would render with no grammar at all). Its header documented exit `2`
when either kind was found. The implementation exited `2` only when drift was found.

The verifier was therefore unable to fail on the second kind. That is the same class of defect
§2.1 repaired in `verify-conformance.py`: a documented contract the code did not implement.

Resolution, in the site repository:

- The audit now exits `2` for uncoloured blocks, and the header distinguishes *untagged* from
  *uncoloured* — the site infers a language for untagged fences
  (`src/lib/highlight.js:80`), so the two are not the same condition.
- Transcripts and diagrams are excluded by reuse of the renderer's own `looksLikeArt`
  predicate plus an output-line test, so the audit and the site agree by construction rather than
  by two rules that can drift apart.
- The step became **blocking** in `.github/workflows/pages.yml`; it had been `continue-on-error`.
- Merged as `d4c27c7a832712fbf757f7060e87bc9b5497dfd9`
  ([Oqthara.github.io#16](https://github.com/Oqthara/Oqthara.github.io/pull/16)) after
  `Build the static site` (4m26s) and `Cross-browser suite` (8m44s) both passed uncancelled.

One genuine defect was found in the language repository's documentation by the repair: exactly
one block (`docs/spec/language.md:1128`, the `secure_equal` example) was real Oqthara that the
inference declined to claim. The other five uncoloured blocks were command transcripts and a
diagram, where plain rendering is correct. Fixed upstream as
`b68b970b771e6d3c6957c1035ef1fa31e29b8a3c`
([#196](https://github.com/Oqthara/oqthara/pull/196)).

Order mattered and was proved rather than asserted: the site CI run against pre-fix language docs
failed at exactly that step with exit `2`, and the run against post-fix docs passed with exit `0`.

### 8.2 The website publication contract had no owner

`docs/governance/alpha-4-workstream-assignment.md` left workstream 12's website half unowned and
carried the assignment as a **proposal**. A proposal is not an owner.

Decision (2026-11-13): the dispatch path, documentation pipeline and website ADRs go to
`oq-hive-architecture-governance`, with `oq-hive-testing-security` reviewing the dispatch token's
scope. The assignment is reflected in that role's agent file in the same change. Merged as
`315a7574325b45c63d0383e65168f42c2a777305`
([#198](https://github.com/Oqthara/oqthara/pull/198)).

No workstream is now unowned. Workstream 2 remains `Under-covered`: a substantive gap in what the
roles name, not an ownership gap, and it is not dissolved by this change.

### 8.3 Dispatch reliability, verified rather than assumed

The dispatch is exercised end to end on every push to `main`: `site-content-dispatch.yml` ran and
succeeded on `b68b970`, and the website received `content-updated` at 14:01:36 and synced
successfully in the same minute. The design is deliberately one-way — the payload names the commit
and the site re-reads the repository rather than trusting the payload, so a forged or replayed
dispatch cannot publish arbitrary content.
