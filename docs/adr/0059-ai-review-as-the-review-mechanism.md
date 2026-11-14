# ADR-0059 — AI review is the review mechanism

**Status:** Accepted
**Date:** 2026-11-14
**Related:** [`ai-review-policy.md`](../governance/ai-review-policy.md) (the shape a record must have), `scripts/check-ai-review.py` (how one is validated), the requirement matrix §4.2,
`docs/governance/ai-review-policy.md`, `docs/governance/remediation/solo-maintainer-exception.md`

## Context

`main-protection` required one approving review from the Alpha 3 remediation onward. The
repository has one human maintainer, so that requirement could be satisfied in exactly two
ways: nothing merged at all, or a second GitHub account belonging to the same person approved
the change and the audit trail read as though a second human had reviewed it.

Neither is acceptable. `solo-maintainer-exception.md` recorded the second outcome as
fabricated review and lowered `required_approving_review_count` to `0` instead. That decision
was honest but incomplete: it named what was *not* happening without establishing what would
happen instead. For the whole of Alpha 4, review was an obligation described in agent
instructions and enforced by nothing. No status check inspected it, and no record proved it
had occurred.

The gap is not "we need more reviewers". It is that the project had no review mechanism it
could satisfy honestly, so it satisfied none.

## Decision

**AI review is the review mechanism for this repository.** It is mandatory, it is recorded,
and the record is machine-validated in required CI.

Three parts, each of which can fail independently:

1. **A review record per pull request**, at
   `docs/governance/reviews/PR-<number>.md`, following the template below. A pull request
   with no record does not pass the gate.
2. **A validator**, `scripts/check-ai-review.py`, which parses the record and fails when it is
   missing, malformed, incomplete, or cites a commit that does not resolve. It is wired into
   `ci.yml` as a step of an existing required context.
3. **A stated independence limit**, in the record and in the policy, so that no reader can
   mistake model-driven review for an independent human opinion.

## What the record must contain

The validator enforces the structure rather than trusting the prose. Each record carries:

- the pull request number, matching its own filename;
- the categories reviewed, each with a verdict and a finding count;
- every finding marked `critical` or `high`, with its resolution and the evidence of
  resolution;
- a statement of which reviewer performed the review and what it reviewed;
- an explicit independence statement.

A record that says "reviewed, no findings" with no categories enumerated is rejected. A record
that omits the independence statement is rejected.

For a `critical` or `high` finding the resolution cell must name, in backticks, a commit that
resolves in this repository, and it may not be a deferral in any wording — `deferred`,
`won't fix`, `TBD` and `still open` are all rejected, because all of them mean the finding is
still open. A resolution that claims a fix without pointing at a commit has no evidence behind
it and is rejected. This is a bar on evidence, not on truthfulness: a record can still cite a
commit that does not contain the fix, and no check in this repository can tell the difference.

## Independence, stated honestly

Every agent in `.github/agents/` pins the same model. A reviewer agent asked to review a
change written by another agent running the same model is **not** independent in the sense a
second human would be. Confirmation bias and blind spots are shared, and a defect both models
find natural is a defect neither will flag.

So the record must say `independent: no` and name the shared model, or it must name a
different model *and* a mechanism that kept the reviewer from reading the implementer's
conclusions first. The validator requires one of those two statements, and the policy explains
which is being claimed.

"Names a mechanism" is enforced as a minimum length of thirteen words on the `yes` statement,
because the alternative was a check that accepted the bare word `yes` and therefore accepted
nothing. Thirteen is a floor on effort, not a measure of quality: a thirteen-word sentence can
still be wrong, and the policy says so. The number is in the validator rather than in prose so
that changing it requires changing the check.

What is gained is not independence. It is:

- **A real diff read.** The reviewer reads `git diff`, not the author's summary.
- **A recorded verdict.** "No findings" is a claim on the record, attributable to a run.
- **Mechanical enforcement.** The absence of a review stops a merge instead of being noticed
  months later.

## Consequences

**The gate is not tamper-proof against the change it is checking.** CI runs the pull request's
own copy of the validator, so a pull request that weakens it passes its own gate. This is
documented rather than solved: `ai-review-policy.md` names the limit, `scripts/check-docs-sync.py`
fails if the gate step leaves `ci.yml`, and eliminating it needs a check that runs outside the
pull request's control.


- A pull request now costs a review record. That is the point: the obligation existed before
  and cost nothing because it was enforced by nothing.
- The record lives in the repository, so it survives in the same history as the change it
  describes, and a future reader can check it against `git show`.
- The gate proves a review was *recorded and well-formed*. It cannot prove the review was
  *good*. A record with an empty findings list where defects existed is a valid record and a
  bad review; no automated check distinguishes the two. The policy says so rather than
  implying the gate measures review quality.
- If a second human maintainer joins, the approval count goes back to 1 and this mechanism
  becomes the *additional* AI gate rather than the only one. Nothing here is removed by that.
