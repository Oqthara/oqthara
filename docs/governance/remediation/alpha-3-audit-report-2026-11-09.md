# Alpha 3 Closure Report (Directive §3.3)

**Status at time of writing: NOT CLOSED — closure blocked, and the block is deliberate, not accidental.**

> **This is the read-only audit, preserved as a historical record.** It was written before
> the maintainer authorized remediation, and it is kept unedited apart from this notice so
> that the audit's errors remain visible. Two of its findings were later overturned by
> evidence gathered during remediation:
>
> - **§1 "Topology finding" is WRONG.** It calls the tag's position a "real defect". The
>   released `oq.jar` is byte-reproducible from `a104629e` and not from `5b33f18`, so the
>   tag names the commit the artifact was actually built from. See
>   [`alpha-3-release-provenance.md`](alpha-3-release-provenance.md).
> - **§2's version-consistency PASS was correct** but was the only check that passed; the
>   conformance verifier it relies on was itself failing. See
>   [`alpha-3-closure-report.md`](alpha-3-closure-report.md).
>
> The current closure verdict lives in [`alpha-3-closure-report.md`](alpha-3-closure-report.md).

Prepared under the Alpha 4 master execution directive §3.1–§3.3. Read-only audit; no
repository files were created, modified, or deleted. All facts below are pinned to
`origin/main` as re-verified via `git show origin/main:<path>`.

---

## 1. Repository baseline

| Item | Value |
| --- | --- |
| Repository | `Oqthara/oqthara` |
| Final baseline commit | `a104629e6adef727b31f2c6f06a28a0045cd7835` |
| Commit subject | `ci: announce new content to the website (#37)` |
| Committed | 2026-11-09T02:37:17Z |
| Tag | `v0.1.0-alpha.3` → lightweight → `a104629e…` (**same commit as `main` HEAD**) |
| Release | `v0.1.0-alpha.3`, prerelease `true`, published 2026-11-09T02:45:12Z, `target_commitish=a104629e…` |
| Alpha 3 squash | `5b33f1891294b91a7d52df863a4a6f854fb440fa` — ancestor of `main` |
| Commits after squash | exactly one: `a104629` (PR #37) |

**Topology finding.** The `v0.1.0-alpha.3` tag points at the **PR #37 merge commit**, not at
the Alpha 3 squash `5b33f18`. The release tag therefore includes a `ci:`-scoped PR that
changed 12 Java files. This is a real defect: the release artifact's tag does not
correspond to the reviewed Alpha 3 deliverable.

**Local worktree caveat.** The maintainer's clone sits at `7ffb6755…`, two commits behind
`origin/main`. Any filesystem-derived fact about corpus size, spec content, or governance
files is unreliable unless read via `git show origin/main:<path>`. Three earlier claims in
this session were wrong for exactly this reason and are retracted in §1.1.

## 1.1 Claims retracted from the first-session report

Three claims in the earlier A–G report were derived from the stale local worktree and are
**wrong**. They are retracted here rather than silently dropped:

| Earlier claim | Correct finding | Evidence |
| --- | --- | --- |
| `docs/governance/` does not exist | **Exists**: `README.md`, `audit/baseline-2026-11.md`, `roadmap-alpha-3.md` | `git ls-tree -r origin/main docs/governance/` |
| Conformance is 209 `.oq` / 12 `.err` / 197 executable | **223 / 16 / 207** | counted from `git ls-tree origin/main conformance/` |
| Keyword list read from an outdated spec | **20 keywords, budget 30** — reconfirmed | `docs/stdlib/reference.md:9-11` |

Note that `docs/governance/roadmap-alpha-3.md` **is a plan, not a closure report** — it
carries target `0.1.0-alpha.3`, owner `oq-hive-orchestrator`, and task `OQ-TASK-ALPHA3-001`,
and its §13 lists ten acceptance criteria with no completion markers. Its existence therefore
does **not** satisfy precondition 6.

## 2. Version consistency — PASS

`0.1.0-alpha.3` in all five checked locations: `Main.java`, `build.gradle.kts`,
`docs/spec/language.md`, `CHANGELOG.md`, LSP server. Enforced by `check-docs-sync.py`.

## 3. Relevant PR states

| PR | Title | State | Base | Note |
| --- | --- | --- | --- | --- |
| #36 | `Oqthara 0.1.0-alpha.3 — language implementation and conformance` | MERGED | `7ffb6755` | The Alpha 3 deliverable. Squashed to `5b33f18`. |
| #37 | `ci: announce new content to the website` | MERGED | `5b33f18` | **Retrospective-review subject.** See §5. |
| #38 | `fix(interop): report immutable Java list mutations as language…` | **OPEN** | `5b33f18` | Redundant — superseded by `main`. |
| #39 | `fix(runtime): preserve 64-bit range lengths` | **OPEN** | `5b33f18` | **Regression risk** — widens a published API. |

`#38` and `#39` are stale forks. Both target `5b33f18`, and `5b33f18` is an ancestor of
`main`, so their work already landed by a different route in PR #37.

No GitHub issues and no milestones exist on the repository — there is no closure-tracking
artifact of any kind.

## 4. CI status — PASS

`main` at `a104629`: **all 12 required status contexts satisfied** — 11 check-runs SUCCESS
plus 4 CircleCI commit statuses success (`jazzer-fuzz`, `macOS / JDK 21`,
`macOS / JDK 25`, `installers-macos`), which overlap rather than add, since CircleCI
reports through both API surfaces.

Run `42551955360` (2026-11-09T02:39:15Z) logged:
`backend.md claim 192/207 matches BytecodeCompilerTest (192) and conformance/ (207 executable of 223 .oq files)`.

Conformance corpus on `main` (counted directly from git, not from a log): **223 `.oq` / 16
`.err` / 207 executable** — every `.err` has a matching `.oq`, so 223 + 16 = 239 files map to
223 distinct programs, of which 16 are expected-failure cases and **207 are executable**.
`backend.md:92` and `:381` state 192 of 207; `BytecodeCompilerTest:1056` asserts 192. Consistent.

## 5. PR #37 retrospective review — FAIL

**Verdict: gate-legal, review-illegal, and materially misdescribed.**

Title is `ci: announce new content to the website`. The body asserts verbatim:

> "No Java source, runtime, compiler, standard library, or `gradlew.bat` content is
> intentionally changed."

**This is false.** 15 files changed, +564/−27 — **12 are Java**: `ErrorMessageContractTest`,
`Interpreter`, `JavaBridge`, `Collections`, `Errors`, `Methods`, `Values`,
`ImmutableRefusalTest`, `ValuesTest`, `Stdlib`, `HostBuiltinLimitsTest`,
`TupleMethodRefusalTest`.

**Two other PRs' work was absorbed.** Commit `1b0dfb98` carries PR #38's immutability scope;
`ad160d06` carries PR #39's 64-bit length scope — both implemented differently and without
those PRs' review.

**Zero independent review.** `reviews: 0`, `reviewDecision: ""`, 15 commits, merged by
`Oqthara` at 2026-11-09T02:37:18Z. The PR's own body demanded the opposite:

> "**Remaining acceptance gates** — 1. Obtain an independent review of the exact final PR head."

**Root cause.** Ruleset `main-protection` (id `26650412`, active, `bypass_actors: []`) enforces
`deletion`, `non_fast_forward`, and `required_status_checks` (12 contexts, strict). It has
**no `pull_request` rule and no required reviews**. CI was the only gate, and CI cannot
detect a false description.

**Mitigating factors, verified.** The revert pair `54f55ee5`→`0da4f5b2` is clean —
`OqTuple.java` on `main` is byte-identical to `5b33f18`. The dispatch script is sound:
token read from `env:`, never logged, `persist-credentials: false`, self-test precedes the
token read. The release notes **do** disclose both absorbed fixes.

**Merge topology nuance.** PR #37 was **squash-merged**: `a104629` has exactly one parent,
`5b33f18`. Its head `7a53338b` and its work commits (`1b0dfb98`, `ad160d06`, `5ff37570`) are
therefore **not ancestors of `main`** — they exist only on the closed PR branch. Their
*content* did land: `HostBuiltinLimitsTest` on `main` no longer creates fixtures in a plain
temp directory (line 313 resolves `fixture.bat` and then leaves the test temp dir, line 314),
which is the `5ff37570` fix. Any future audit must diff content, not test ancestry, or it
will wrongly conclude these fixes never shipped.

**Residual defect.** `CHANGELOG.md [Unreleased]` is empty — the two absorbed fixes are
absent from the changelog while present in the GitHub release notes.

## 6. Website synchronization

| Item | Value |
| --- | --- |
| Production | `https://oqthara.github.io/` HTTP 200 |
| Firestore `metadata/catalog.sourceCommit` | `a104629e6adef727b31f2c6f06a28a0045cd7835` — **matches** |
| Firestore `metadata/catalog.status` | `published` |
| Release dispatch (`oqthara` run `42552644364`) | **SUCCESS** — 13-case matrix, `Dispatched alpha-release-published.` |
| Downstream `alpha-release-sync.yml` | `42552657376`, `42552693593` **FAILED** — `8 RESOURCE_EXHAUSTED: Quota exceeded` |
| Scheduled convergence | `42598062024` **SUCCEEDED** — converged |

Website repo `Oqthara/Oqthara.github.io` has **0 rulesets** and no protection.

`releases/*` Firestore docs are **not anonymously readable** (403), so `releases/v0.1.0-alpha.3`
cannot be externally verified. This is a genuine evidence gap, not a failure.

## 7. Known regressions and debt

1. **`scripts/verify-conformance.py` is stale and unwired** — `EXPECTED_EXECUTABLE = 197`
   (line 26) is an **exact match** requirement, so the script would hard-fail today with
   `executable corpus changed: 207 != 197`. `FLOOR_COMPILED = 182` (line 25) is only a
   *floor*, so the current 192 silently passes it — the guard has drifted 10 programs weaker
   than the real measurement, which is the more insidious half of the defect. The script is
   **not invoked by any workflow** (`.github/` has zero references); only
   `scripts/README.md:29` mentions it. The drift is invisible because `check-docs-sync.py` —
   which *is* wired into `ci.yml:46` and `release.yml:31` — is the checker that actually runs.
2. **`CONTRIBUTING.md:9` says "we are at 18" keywords** — measured count is **20**
   (`docs/stdlib/reference.md:9-11`). `check-docs-sync.py` validates `reference.md` against
   the lexer but never reads `CONTRIBUTING.md`.
3. **PR #39 is a regression risk**, not a fix: it widens
   `public static int size(Object, Span)` → `public static long size(...)`, breaking the
   published `dev.oqthara:oq-runtime` API. `main` solved this additively — `int size(...)`
   preserved (line 473), new `long length(...)` added (line 492).
4. **PR #38's test would fail if rebased** — it asserts `"cannot modify this list"`, which
   has **zero occurrences on `main`**. `main` classifies through
   `Errors.immutableRefusal` (line 78) and catches at `Interpreter.invoke` (line 481).
5. **Branch protection gap** — `main-protection` lacks a `pull_request` rule; the website
   repo has no protection at all. This is the precise mechanism that let PR #37 merge.
6. **Release tag mispointed** — `v0.1.0-alpha.3` → PR #37 merge, not the Alpha 3 squash.
7. **`CHANGELOG.md [Unreleased]` empty** despite two shipped fixes.

## 8. Alpha 3 specification and implementation baseline

Verified unimplemented — Alpha 4 scope:

| Feature | Evidence |
| --- | --- |
| `const` | `language.md:44` — "There is no `let`, no `var`, no `const`." |
| Overloading | 0 occurrences of `overload` in `language.md` |
| Destructuring | `language.md:330` — "**Deferred.** Tuple destructuring…" |
| `try` as expression | Statement only — `Ast.TryStmt`, `Parser.java:266`; "no `finally` and no typed catch" |
| Docstrings | 0 occurrences |
| Type aliases | 0 occurrences |
| Interfaces | Only Java's `List` interface (`language.md:326`), not an Oqthara construct |

Keyword budget: **20 of 30 used** — 10 slots remain, corroborated by the lexer.

## 9. Blockers to closure

Per directive §3.1, seven preconditions are required. Current state:

| # | Precondition | State |
| --- | --- | --- |
| 1 | Website content synchronization complete | **MET** — `sourceCommit` matches `main` |
| 2 | Sync mechanism reliable and tested, not manual | **MET with reserve** — dispatch failed twice on quota; schedule converged. Design is fail-closed and correct. |
| 3 | Integration debts resolved or explicitly accepted | **NOT MET** — the seven debt items in §7 are unresolved and unaccepted |
| 4 | Remaining PRs, regressions, outstanding work reviewed | **PARTIALLY MET** — #37 now reviewed; **#38 and #39 remain OPEN and undecided** |
| 5 | Documented final baseline | **MET** — `a104629…`, though the tag is mispointed (§1) |
| 6 | Formally closed by the maintainer | **NOT MET** — no closure artifact exists |
| 7 | Alpha 4 explicitly authorized | **NOT MET** |

**Exact blockers.** #38 and #39 are open and stale; a tag mispoints at a misdescribed CI PR;
no closure record exists; and Alpha 4 authorization has not been given.

## 10. Maintainer decision required

The maintainer asked for the PR #37 review **before** closing Alpha 3, and that review has now
produced a governance failure. The decision below is the maintainer's, not the agent's. The
directive forbids an agent from self-authorizing closure or a release, so this report stops
here rather than closing anything.

**Recommended path (not executed — requires authorization):**

1. Close #38 as superseded by `main`; close #39 rather than rebase it, because it widens a
   published API.
2. Amend `main-protection` to add a `pull_request` rule with at least one required approval,
   so a misdescribed `ci:` PR cannot merge unreviewed again.
3. Record the PR #37 review failure explicitly in the closure document rather than
   retroactively legitimizing the merge.
4. Fix the tag: re-point `v0.1.0-alpha.3` to `5b33f18`, or document why the release
   intentionally includes PR #37.
5. Repair the three documentation/tooling drifts in §7 (stale `verify-conformance.py`,
   `CONTRIBUTING.md` keyword count, empty `[Unreleased]`).
6. Only then write the closure record and authorize Alpha 4.

**No item above has been performed.** Every one changes repository, release, or governance
state and requires explicit maintainer authorization.
