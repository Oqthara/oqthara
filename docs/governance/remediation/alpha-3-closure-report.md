# Alpha 3 closure report

**Verdict: CLOSED WITH ONE UNMET REVIEW GATE, RECORDED AND NOT WAIVED.**

Alpha 3 is technically complete and its remediation is complete. One mandatory closure
criterion — independent review — **cannot be satisfied in this repository as configured**,
because the project has exactly one collaborator. That criterion is reported as **BLOCKED**,
not as passed and not as waived.

The distinction matters and is the point of this document. Every other criterion is met with
evidence below. The one that is not met is met by nobody: there is no second maintainer to
ask.

---

## 1. What changed, and the commit that changed it

Baseline before remediation: `a104629e6adef727b31f2c6f06a28a0045cd7835`.

Remediation commit: `e32215f59d74a04af4327587459bf0cb4518b884`, merged by
[#40](https://github.com/Oqthara/oqthara/pull/40) from branch `alpha3-remediation-verification`.
The branch's own commit is `63bb1bcadcb08e85740aa1f34e27c270241c180f`; GitHub squash-merged it,
so the branch SHA is not an ancestor of `main` and the merge SHA is the durable reference.

Pre-merge baseline: `a104629e6adef727b31f2c6f06a28a0045cd7835`.

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
| `scripts/tests/test_verify_conformance.py` | New. 8 tests covering the baseline, the executable pin, the compiled floor, and the divergence path. |
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
| `Oqthara/oqthara` HEAD | `a104629e…` before this PR; this PR's merge commit after |
| `Oqthara/Oqthara.github.io` | production `https://oqthara.github.io/` → HTTP 200 |
| Firestore `metadata/catalog.sourceCommit` | `a104629e…` — matches `main` |
| Firestore `metadata/catalog.status` | `published` |
| Release dispatch run `42552644364` | SUCCESS — 13-case matrix |
| Scheduled convergence run `42598062024` | SUCCESS — `none -> v0.1.0-alpha.3` |

### 2.2 PR #37 retrospective review with independent review — **BLOCKED**

The retrospective itself is complete: [`pr-37-retrospective.md`](pr-37-retrospective.md).
It establishes that #37 merged with `reviews: 0`, that 12 of its 15 changed paths were Java
despite a body claiming otherwise, and that it silently absorbed the work of #38 and #39.

**The independence half of this criterion is not met.** Both repositories have exactly one
collaborator, `Oqthara`. Every Hive agent pins the same model endpoint, so agent review is
same-model review and does not satisfy the order's requirement. See §5.

### 2.3 PR #38 and #39 dispositioned — **MET**

Both **closed without merging**, branches preserved, on evidence rather than inference:

| PR | Disposition | Verification performed |
| --- | --- | --- |
| [#38](https://github.com/Oqthara/oqthara/pull/38) | Closed, superseded | Patch applies cleanly to `main` and its test passes *on the patched tree*. But pristine `main` already produces a better message: `Methods.java:55` → `Errors.immutableRefusal`. Its asserted text `cannot modify this list` has **zero** occurrences on `main`. Merging would add a competing, narrower message. |
| [#39](https://github.com/Oqthara/oqthara/pull/39) | Closed, superseded | `length(0 to 3000000000)` already returns `3000000001` on `main`. The PR widens `Values.size` `int`→`long`, breaking the published `dev.oqthara:oq-runtime` API; `main` solved it additively via a new `length()`. |

Two genuinely missing artifacts from #39 were **harvested rather than discarded**: the
`language.md` 64-bit sentence and the interpreter-level assertion. Both were confirmed absent
from `main` before being added.

### 2.4 `main-protection` requires PR review and checks — **MET, then hardened**

The defect was real and is the root cause of #37: the ruleset had `deletion`,
`non_fast_forward`, and `required_status_checks` — and **no `pull_request` rule at all**. A
direct push was permitted and zero approvals were required.

`scripts/apply-branch-protection.sh` now also sets the `pull_request` rule: one required
approval, stale approvals dismissed on push, and review-thread resolution required.
`bypass_actors` remains empty, so the protection binds the maintainer too.

**Applied state, read back from the API on ruleset `26650412`:**

```
rules: deletion, non_fast_forward, required_status_checks, pull_request
pull_request:
  required_approving_review_count: 1
  dismiss_stale_reviews_on_push:   true
  required_review_thread_resolution: true
  allowed_merge_methods:           ["squash"]
bypass_actors:            []
current_user_can_bypass:  never
```

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

### 2.11 Remediation PRs have independent review and resolved conversations — **BLOCKED**

Same blocker as §2.2, and it now applies literally.

[#40](https://github.com/Oqthara/oqthara/pull/40) passed all 15 required checks — Ubuntu and
Windows on JDK 21 and 25, CircleCI macOS on JDK 21 and 25, the bytecode backend, the standalone
jar, installer tests on both platforms, CodeQL, `analyze`, and `jazzer-fuzz`. It has no review
threads to resolve. It merged with **zero approvals**, because at merge time no rule required
any.

From `e32215f5` onward that is impossible: `required_approving_review_count: 1` is active and
`current_user_can_bypass` is `never`. The next pull request cannot merge without an approval
that does not exist in this repository. That is the honest end state — the mechanism is now
correct, and the missing reviewer is a personnel gap, not a configuration gap.

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

| Command | Result |
| --- | --- |
| `./gradlew build` | 452 tests, 0 failures, 0 errors, 0 skipped; oq-core coverage 85.1% against an 80% gate |
| `python3 scripts/verify-conformance.py` | `ok: 192 compiled, 19 fallback, 0 divergences across 223 programs (207 executable)` |
| `python3 -m unittest discover -s scripts/tests` | 8 tests, OK |
| `python3 scripts/tests/test_watchdog.py` | 13 tests, OK |
| `python3 scripts/check-docs-sync.py` | all 7 checks pass |
| `./gradlew :oq-cli:test --tests '*StdlibCoverageTest*'` | 22 tests pass |
| Jar reproducibility from `a104629e` | sha256 matches the released asset |
| Website production | HTTP 200; `sourceCommit` matches `main` |

## 4. Residual risks

1. **Single-maintainer review.** Structural, not fixable by this remediation. Every
   review gate in the process is a self-review until a second collaborator exists.
2. **`gradlew.bat` checkout dirtiness.** Any contributor's first `git status` is noisy. A
   normalization commit (re-checkout with `core.autocrlf` and re-add) would fix it, but it
   touches a build-critical file and does not belong in a conformance PR.
3. **Website repository unprotected.** It receives dispatches and publishes production
   content with no ruleset at all.
4. **Empty dispatch payload.** Latent; the poller is the effective mechanism.

## 5. Why the independence gate is reported, not waived

The order says: *"Do not fabricate independent reviews."* It also says: *"If a specific
operation is blocked by permissions, unavailable independent review, or an irreversible
release-history risk, document that precise blocker and continue with the remaining work."*

Both instructions point the same way. Recording this gate as passed would be fabrication.
Waiving it would be a maintainer decision, and the maintainer is the only person who could
waive it — which is exactly the conflict that makes the gate meaningful.

So Alpha 3 is closed on technical and evidentiary grounds, with the review gate carried as an
open, named blocker. **To clear it:** add one collaborator with write access who did not author
the release, have them review [#37's retrospective](pr-37-retrospective.md) and this pull
request, and record their approval. No code changes are required.

One consequence deserves to be stated plainly, because it will be the first thing the next
contributor encounters: **as of `e32215f5`, nobody can merge a pull request to this repository.**
The `pull_request` rule requires one approval, there is exactly one collaborator, GitHub does not
count a self-approval, and `bypass_actors` is empty with `current_user_can_bypass: never`. This
is not a regression introduced by accident — it is the correct configuration for a repository
that is supposed to require review, applied to a repository that cannot currently supply one.

The maintainer has two legitimate options, and both are recorded rather than chosen silently:

1. **Add a second collaborator.** The rule then works as designed, and every gate in this
   report closes. This is the option the order's criteria describe.
2. **Lower `required_approving_review_count` to 0 and rely on required checks alone.** This
   restores a single-maintainer merge path, and it is *exactly* the configuration that let PR
   #37 merge unreviewed. Doing this would reopen finding H2.

This report recommends option 1 and does not weaken the rule to unblock itself.
