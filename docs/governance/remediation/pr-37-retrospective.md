# PR #37 retrospective review

**PR:** [#37 — ci: announce new content to the website](https://github.com/Oqthara/oqthara/pull/37)
**Merge commit:** `a104629e6adef727b31f2c6f06a28a0045cd7835` (squash)
**Reviewer independence:** **NOT SATISFIED** — see "Independence" below.

This retrospective was required before Alpha 3 could be closed. It records what the
change contained, whether it was correct, and the process failures it exposed.

## What was actually merged

The PR title and body describe a CI change: announce new site content. The merged diff
touched **15 paths**:

| Area | Paths |
| --- | --- |
| Runtime | `Interpreter.java`, `JavaBridge.java`, `Collections.java`, `Errors.java`, `Methods.java`, `Values.java` |
| Stdlib | `Stdlib.java` |
| Tests | `ErrorMessageContractTest.java`, `ImmutableRefusalTest.java` (new), `ValuesTest.java`, `HostBuiltinLimitsTest.java`, `TupleMethodRefusalTest.java` |
| CI / tooling | `release.yml`, `site-content-dispatch.yml`, `scripts/dispatch-site-content.mjs` (new) |

**12 of the 15 paths are Java.** The PR body did not mention the Java changes, and the
squash commit subject (`ci: announce new content to the website`) does not either.

## Why this matters: it absorbed two other PRs

The two behavioral fixes in that diff are exactly the fixes proposed by the two PRs that
were sitting open at the time:

| Fix now on `main` | Proposed by |
| --- | --- |
| `Methods.java` catches `UnsupportedOperationException` → `Errors.immutableRefusal(...)` | [#38](https://github.com/Oqthara/oqthara/pull/38) |
| `Values.length(...)` counts ranges in `long` | [#39](https://github.com/Oqthara/oqthara/pull/39) |

Confirmed by `git log -S`: both `immutableRefusal` and `public static long length(Object
value, Span span)` first appear in `a104629e`, the squash commit of #37.

The consequence was that #38 and #39 stayed open against a `main` that already contained
their fixes, and #39 in particular drifted into conflict. Both are closed in this
remediation with the evidence recorded on each PR.

## Was the merged code correct?

Yes, on the evidence available:

- Full build green after the merge: **452 tests, 0 failures, 0 errors, 0 skipped**.
- oq-core coverage **85.1%** against an 80% gate.
- Independent conformance comparison: **223 programs, 207 executable, 192 compiled,
  19 fallback, 0 divergences**.
- The immutable-collection path behaves correctly end to end:

  ```
  $ java -jar oq.jar jlistmut.oq
  error: cannot change an unchangeable collection: the operation is not supported
    --> jlistmut.oq:3:6
  exit 1
  ```

  A program error, not an `internal error` blaming Oqthara.
- The range-length path is correct: `length(0 to 3000000000)` prints `3000000001`.

The behavioral outcome was right. The problem is entirely one of process and disclosure.

## Process failures

1. **The change was reviewed zero times.** `reviews: 0` on the PR. A 564-insertion,
   27-deletion change spanning runtime, stdlib, tests, and release CI merged with no
   approval.
2. **The description was incomplete in a material way.** It described CI work while
   carrying language-runtime semantics changes. A reader could not have known what they
   were approving.
3. **The commit subject mislabels the content.** `ci: announce new content to the website`
   is not a truthful summary of a squash that also changed runtime error handling and
   numeric semantics. This is why the Alpha 3 baseline audit initially read the tree
   incorrectly — the content had to be found by diffing, not by reading history.
4. **Test expectations were never reconciled in the open.** #38 and #39 were left to
   conflict against a tree that had silently taken their work.

## Independence

The order authorizing this remediation requires a **genuinely independent reviewer** for
this retrospective and for the governance changes that follow.

**That requirement is not met, and cannot be met in this repository as configured:**

- `repos/Oqthara/oqthara/collaborators` returns exactly one account: `Oqthara` (admin).
- `repos/Oqthara/Oqthara.github.io/collaborators` returns the same single account.
- Every agent in `.github/agents/` pins the same model endpoint, so agent-level review is
  the same model reviewing its own family's output. The order explicitly states that is
  not sufficient for high-impact governance and release changes.

This review was therefore performed by the same maintainer account, assisted by an agent.
It is an honest technical reconstruction, and it is **not** independent sign-off. Recording
it as independent would be fabrication.

**What would clear it:** add at least one collaborator with write access who is not the
release author, require their approval on the closure pull request, and have them review
this document. Until then, the PR #37 sign-off gate is **BLOCKED**, and Alpha 3 closure is
conditional on that gate, as recorded in the closure report.

## Verdict

The code that landed through #37 is correct and is kept. The review that should have
preceded it never happened, the description and commit subject did not disclose what it
contained, and two open PRs were superseded without being closed.

The remedy is not to revert working code. It is to make the next change impossible to
merge this way — which is what the ruleset repair in this remediation does — and to record
plainly that this one merged without review.
