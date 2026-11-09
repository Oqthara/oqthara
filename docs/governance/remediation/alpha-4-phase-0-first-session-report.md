# Alpha 4 Phase 0 — First-Session Report (Directive §66, A–G)

**Verdict at time of writing: the Phase 0 gate was NOT satisfied. Alpha 4 was BLOCKED.**

> **This is a historical audit record, preserved as written.** It was produced during the
> read-only Alpha 3 audit, before remediation was authorized. Its findings describe the state at
> `a104629e` and are kept unedited so the delta is visible.
>
> **Current Phase 0 status is assessed separately in
> [`../../alpha-4-phase-0-assessment.md`](../../alpha-4-phase-0-assessment.md)**, written after
> remediation completed at `e32215f5`. Several of the blockers recorded below were subsequently
> fixed; others changed form. Read that document for the current gate, not this one.
>
> Two findings below were later overturned by evidence:
>
> - **§A "tag misdirected"** — the released `oq.jar` is byte-reproducible from `a104629e` and not
>   from `5b33f18`, so the tag names the commit the artifact came from. See
>   [`alpha-3-release-provenance.md`](alpha-3-release-provenance.md).
> - **§B's "no review requirement"** — the `pull_request` rule now exists and is enforced, under
>   the documented solo-maintainer exception recorded in
>   [`solo-maintainer-exception.md`](solo-maintainer-exception.md).

Read-only audit. No repository files created, modified, or deleted. Every fact below was
re-verified against `origin/main` via `git show origin/main:<path>` — **not** from the local
worktree, which is two commits stale. Corrections to the earlier verbal report are collected
in §B.1 and are authoritative where they conflict with anything said before.

Baseline: `Oqthara/oqthara` @ `a104629e6adef727b31f2c6f06a28a0045cd7835`.

## Solo-maintainer governance exception — recorded against this report

This report's review-related findings assume a project that can supply an independent reviewer.
It cannot.

- There is currently **one human maintainer** of Oqthara.
- The GitHub account `JoaoGaValentim` belongs to **that same person**. It is not a second human
  and is not an independent reviewer. An approval from it is a self-approval.
- **Independent human review was not performed** for Alpha 3 or for the Phase 0 assessment.
- The maintainer **explicitly authorized and accepted** this bounded governance exception, as
  recorded in [`solo-maintainer-exception.md`](solo-maintainer-exception.md).
- **Required CI, pull requests and protected-branch enforcement remain active.** The
  `pull_request` rule is enforced, direct pushes are rejected, all 12 required CI contexts are
  mandatory, and review-thread resolution is required.
- **Independent review requirements must be reconsidered when another genuine human maintainer
  joins.** The restoration command is recorded in the exception document.
- Any review slot in this report that names an agent — such as `oq-hive-review-escalation` — is
  **tool separation, not model independence**. All Hive agents pin the same model endpoint, so
  their review is correlated with the author's. AI review here is supplemental assurance only.

---

## A. Repository state

| Item | Value |
| --- | --- |
| Repository | `Oqthara/oqthara` (+ `Oqthara/Oqthara.github.io`) |
| Branch | `main` |
| HEAD (remote) | `a104629e6adef727b31f2c6f06a28a0045cd7835` — `ci: announce new content to the website (#37)` |
| Working tree (local clone) | `7ffb6755…` — **two commits behind**; clean but stale |
| Existing changes | None in the repository. Session artifacts live only in the session workspace. |

**Stale-clone hazard.** The maintainer's clone lacks `5b33f18` (Alpha 3 squash) and `a104629`.
Three earlier claims were wrong for this exact reason (§B.1). Any future audit must read
`origin/main` through git, never the filesystem.

Tag/release: `v0.1.0-alpha.3` is a lightweight tag pointing at `a104629` — **the PR #37 merge
commit, not the Alpha 3 squash `5b33f18`**. Release published 2026-11-09T02:45:12Z,
`prerelease: true`.

## B. Alpha 3 closure

- **Website synchronization — MET.** `https://oqthara.github.io/` HTTP 200; Firestore
  `metadata/catalog.sourceCommit` = `a104629e…` (matches `main`), `status: published`.
- **Outstanding PRs — NOT MET.** #38 and #39 are **OPEN**, both based on `5b33f18`, both stale
  forks whose content already landed via PR #37 by a different implementation.
- **CI — MET.** All 12 required contexts satisfied at `a104629`.
- **Reviews — FAIL.** PR #37 merged with `reviews: 0`, `reviewDecision: ""`, by `Oqthara`,
  against its own body's demand for "an independent review of the exact final PR head."
- **Remaining blockers.** Tag mispointed; #38/#39 undecided; seven debt items in §E.
- **Formal closure status — NOT MET.** No closure artifact exists: no issue, no milestone, no
  report, no signed decision. `docs/governance/roadmap-alpha-3.md` is a **plan**, not a closure.

**PR #37 retrospective review — FAIL.** Title `ci: announce new content to the website`; body
claims "No Java source, runtime, compiler, standard library, or `gradlew.bat` content is
intentionally changed." **False** — 12 of 15 changed files are Java (+564/−27). It absorbed
PR #38's immutability fix and PR #39's 64-bit length fix. Root cause: ruleset `main-protection`
(`26650412`) has `required_status_checks` but **no `pull_request` rule and no required reviews**.

### B.1 Claims retracted

| Earlier claim | Correct finding |
| --- | --- |
| `docs/governance/` does not exist | **Exists**: `README.md`, `audit/baseline-2026-11.md`, `roadmap-alpha-3.md` |
| Conformance 209 / 12 / 197 | **223 `.oq` / 16 `.err` / 207 executable** |
| Keyword list from outdated spec | **20 of 30 used** — reconfirmed against the lexer |

## C. Alpha 4 readiness

- **Confirmed baseline.** `a104629`, all checks green, version `0.1.0-alpha.3` consistent in
  five locations including `oq-lsp/.../Server.java:136`.
- **Requirement registry — NOT STARTED.** No Alpha 4 requirement register, traceability
  matrix, risk register, or threat model exists. Directive §4.1 mandates all of them.
- **Architecture readiness — PARTIAL.** `docs/governance/` and ADR index exist; the four
  Alpha 4 language features are verifiably absent (§E).
- **Dependencies.** The stale/unwired `verify-conformance.py`, the review-gate gap, and the
  mispointed tag all sit upstream of Alpha 4 work.
- **Risks.** A ruleset with no review requirement means the only gate is CI, and CI cannot
  read a PR description. The PR #37 failure is reproducible by design until that is fixed.

## D. Team organization

Six Hive agents are defined in `.github/agents/*.agent.md`, mirrored in
`docs/governance/README.md` (OQ-DEC-2026-001, APPROVED): `oq-hive-orchestrator`,
`oq-hive-language-syntax`, `oq-hive-development`, `oq-hive-testing-security`,
`oq-hive-architecture-governance`, `oq-hive-review-escalation`.

**Independence limitation — material, and narrower than it looks.** Tool separation is real:
`oq-hive-review-escalation` is granted `read, search, execute, todo` and **not `edit`**, so it
cannot modify the checkout; `oq-hive-development` has `read, edit, search, execute, todo`. That
is a genuine structural boundary.

What is **not** separated is the model. All six agents pin the identical endpoint:

```
model: deepseek-ai/deepseek-v4.1-flash-1017
```

appearing verbatim in all six `.github/agents/*.agent.md` files. So "independent review" means
a differently-scoped role running the **same model** that produced the change — it shares the
same blind spots, and shares them on exactly the questions that matter most here (whether a
description matches a diff, whether a claim is overstated). `docs/governance/README.md:201`
verifies permission separation from the same inventory but does not address model
homogeneity.

The PR #37 merge is direct evidence that role separation alone did not hold in practice: the
review-slot existed, was assigned, and was never invoked. An Alpha 4 plan must state this
constraint plainly — and must not treat "independent review" as satisfied by a same-model
re-review.

## E. Initial technical findings

| Gap area | Finding |
| --- | --- |
| Type system | `const` explicitly absent (`language.md:44`); no type aliases; no interfaces |
| State machines | 20 keywords of a 30 budget; `try` is a statement (`Ast.TryStmt`, `Parser.java:266`), not an expression; no `finally`, no typed catch |
| Compiler | 192 of 207 executable conformance programs compile (`BytecodeCompilerTest:1056`) |
| Runtime | `Values.size(Object,Span)` returns `int` and narrows ranges at line 478 — the deliberate compatibility constraint that makes PR #39 a regression risk |
| JavaBridge | Refusals route through `Errors.immutableRefusal` (line 78), caught at `Interpreter.invoke:481` |
| Collections | Immutable JDK collections report a program error, not an internal fault — already covered end-to-end |
| Standard library | No destructuring (`language.md:330`); no docstrings; no overloading |
| Tooling | `verify-conformance.py` stale and **not in CI**; `CONTRIBUTING.md:9` says 18 keywords when 20 are used and is unchecked; `CHANGELOG.md [Unreleased]` empty despite two shipped fixes |

## F. Proposed next actions

Each is unexecuted and requires authorization (§G).

| # | Task | Responsible | Depends on | Deliverable | Review | Acceptance |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Close #38 as superseded; close #39 (do not rebase — it widens a published API) | maintainer | — | PR state change | human | Both closed with rationale |
| 2 | Add `pull_request` rule + required approval to `main-protection` | maintainer | — | Ruleset amendment | human | A `ci:` PR cannot merge unreviewed |
| 3 | Re-point `v0.1.0-alpha.3` to `5b33f18`, or document the inclusion of PR #37 | maintainer | #1 | Tag/release fix | human | Tag matches the reviewed deliverable |
| 4 | Fix the three tooling/docs drifts | `oq-hive-development` | — | Patch + CI wiring | `oq-hive-review-escalation` | `verify-conformance.py` passes and runs in CI; `CONTRIBUTING.md` corrected |
| 5 | Write the Alpha 3 closure record, disclosing the PR #37 failure | `oq-hive-architecture-governance` | #1–#4 | Closure document | `oq-hive-review-escalation` | All §3.1 preconditions evidenced |
| 6 | Build the §4.1 Alpha 4 baseline artifacts | all six agents | #5 | Requirement register, matrices, risk register | `oq-hive-review-escalation` | Every §4.1 artifact present |

## G. Authorization requirements

No decision below has been made. Nothing is presented as approved.

1. **Close PR #38 as superseded** and **PR #39 as superseded rather than rebased.**
2. **Amend `main-protection`** to require at least one approving review.
3. **Re-point or re-document the `v0.1.0-alpha.3` tag.** Re-pointing a published tag is itself a
   release-integrity decision; documenting the inclusion is the lower-risk alternative.
4. **Decide whether PR #37's merge stands as-is or is recorded as a governance failure** in the
   closure document.
5. **Formally close Alpha 3** (directive §3.1 precondition 6) — only the maintainer may do this.
6. **Explicitly authorize Alpha 4 to begin** (precondition 7).
7. **Accept or reject the seven debt items** in §E, which precondition 3 requires be "resolved
   or explicitly accepted by the maintainer."

**Alpha 4 must not begin until items 5 and 6 are given explicitly.**
