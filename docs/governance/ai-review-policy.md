# AI review policy

**State:** ACTIVE — authorized by the maintainer on 2026-11-14.
**Applies to:** every pull request against `main` in `Oqthara/oqthara`.
**Decided in:** [ADR-0059](../adr/0059-ai-review-as-the-review-mechanism.md).
**Enforced by:** `scripts/check-ai-review.py`, run in required CI.

## What changed, and why

Until this policy, review was required by nothing. `main-protection` asked for zero approving
reviews under the [solo-maintainer exception](remediation/solo-maintainer-exception.md), because
the only alternative was a self-approval from a second account belonging to the same person.
The exception was honest about what was *not* happening. It did not establish what would happen
instead, so for the whole of Alpha 4 the requirement that changes be reviewed existed in prose
and was enforced by no check at all.

AI review is now the review mechanism. It is not a placeholder for a human reviewer who might
arrive later, and it is not described as one.

## The requirement

Every pull request carries a review record at `docs/governance/reviews/PR-<number>.md`, written
in the same pull request it reviews. The record is a file in the repository, so it lives in the
same history as the change and can be checked against `git show`.

A pull request without a well-formed record fails `scripts/check-ai-review.py`, which runs in
required CI. The merge button is blocked by a failing required check, so the record is not
advisory.

## What a review must evaluate

Seven areas, each with a verdict and a finding count. The validator rejects a record that skips
one:

| Category | What the reviewer must establish |
| --- | --- |
| functional correctness | the change does what the pull request says, for the stated inputs |
| architecture and maintainability | it fits the design decisions already made, and does not add an abstraction that fails to pay for itself |
| security and privacy | no new exposure, no unsafe default, no secret in the tree |
| regression and compatibility | existing conformance cases, tests, and published behaviour still hold |
| test coverage and validation | the tests would fail without the change, and edge cases are covered |
| documentation accuracy | the documents describe what the code does, not what it was meant to do |
| release integrity | signatures, provenance, and distribution are unaffected — or, for a change that touches none of them, explicitly marked not applicable |

The record must also name the reviewer, the implementer, and the commit that was reviewed.

## Independence, stated honestly

**No agent in this repository is independent of any other.** Every file in `.github/agents/`
pins the same model. A reviewer agent examining a change written by another agent on the same
model shares its blind spots: a defect both find natural is one neither will flag.

The record therefore states `**Independent:** no — <shared model>` in the ordinary case. It may
say `yes` only when the reviewer ran on a different model *and* was given the diff and evidence
rather than the implementer's conclusions.

The validator enforces that distinction rather than accepting the first word:

| Statement | What the validator requires |
| --- | --- |
| `no — …` | that a model is named at all, so a reader can see what the reviewer ran |
| `yes — …` | a model that is **not** one the agents pin, plus a description of the mechanism that kept the reviewer from inheriting the implementer's framing |

A bare `yes` is rejected, and so is `yes` naming the shared model with no mechanism, because
both are the claim the whole mechanism exists to stop. The mechanism bar is a word count, not a
judgement of quality: it rules out `yes` and `yes, trust me`, and nothing more. A reviewer that
writes a plausible sentence about a mechanism it did not use still passes, and no check in this
repository can detect that.

What the mechanism actually buys:

- the reviewer reads `git diff`, not the author's summary;
- a verdict is recorded and attributable to a run;
- the absence of review blocks a merge instead of being discovered months later.

What it does not buy is independence, and this policy does not claim it. Every earlier statement
in this repository that a human reviewed a change remains false, and none is retroactively
upgraded by this document.

## Severity handling

Findings are recorded with a severity, and the rule differs by severity.

A `critical` or `high` finding must be resolved **in the pull request that found it**, with the
resolution recorded in the finding row. Its resolution cell must name a commit, in backticks,
that resolves in this repository. A cell that is empty, or that merely says `deferred`,
`won't fix`, `TBD` or `still open`, does not satisfy that, and the validator
fails the record. Evidence is required, not a sentence saying the fix happened.

Medium and low findings may instead be fixed or accepted with a stated rationale. Accepting one
is a recorded decision rather than a silent omission, which is the difference this policy is
trying to make. They may also be deferred, and the validator allows it: a medium or low finding
does not block a merge. Deferring instead of accepting is a weaker record, because it states no
reason, so a reviewer who means to accept a finding should say so.

## What the gate cannot do

It proves a review was recorded, addressed all seven categories, and left no critical or high
finding open. It cannot prove the review was thorough. A record that reports an empty findings
list where defects existed is a valid record and a bad review, and no automated check tells the
two apart.

This is stated here because the alternative — letting a green check stand for review quality —
is exactly the failure mode the repo has already had once: a review requirement that was
recorded as satisfied while nothing had been reviewed.

Two specific shapes of that failure are worth naming, because the validator accepts both.

**A record can be copied forward.** The heading must name the pull request the run belongs to,
and `Reviewed at` must name a commit on that branch, ahead of its branch point. Both are
satisfiable by copying a previous record, editing two lines, and writing no review. Nothing in
the record distinguishes a considered review from a transcribed one, so review of the review —
a human or agent reading the findings against the diff — remains the only check on this.

**A record can be thorough and wrong.** Enumerating seven categories with a plausible count in
each is a formatting exercise. The count is checked against the findings table; neither is
checked against reality.

### The gate runs the pull request's own copy of itself

CI checks out the pull request's head and runs `scripts/check-ai-review.py` from that checkout.
A pull request that edits the validator therefore runs its own edited gate, and an edit that
makes the validator exit 0 passes. This is not a defect that can be fixed from inside the
script: any check a pull request can execute, that pull request can alter.

Three things reduce the exposure, and none of them eliminates it:

- The change to `scripts/check-ai-review.py` is visible in the diff that reviewers are required
  to examine. A validator edit that weakens the gate is the first thing a review should catch.
- `scripts/check-docs-sync.py` fails if the gate step disappears from `ci.yml`, if its command
  line is altered, if it is moved out of the job whose name carries the required
  `ubuntu-latest / JDK 21` context, or if the step or that job is made non-blocking with
  `continue-on-error` or a constant-false `if:`. That check parses the workflow rather than
  matching a string, so the gate cannot be removed by a comment. It is, however, itself a
  script in the same pull request: a diff that weakens both files is only caught by a reviewer
  reading it.
- The ruleset requires the status check and forbids bypass, so an edit that makes the gate fail
  cannot be merged. This is a real constraint but a narrow one: it stops the gate *failing*, and
  says nothing about the gate *being right*.

The honest summary of these three: nothing here prevents a pull request from neutering the gate,
because every mechanism that would is itself checked into the branch under review. The ruleset
stops the required context from going red or being skipped by the ruleset's own configuration;
it cannot tell whether the code producing that context still does anything.

Removing the limit entirely needs a check that runs outside the pull request's control: a
separate workflow on `pull_request_target` reading the base branch's copy of the validator, or a
GitHub App. Both are larger changes than this policy, and neither is implemented here. **A
reader should treat "the gate passed" as "the validator in this diff passed", not as "the
validator was not modified".**

## Reviewer and implementer

Separation is preferred wherever practical: the implementer writes the change and the record's
raw material, and a separate agent run examines the diff and writes the findings. The validator
requires `**Reviewer:**` and `**Implementer:**` to name different parties, so one agent may not
sign both.

When one agent genuinely performs both roles, the record must say so in the `**Independent:**`
line — `no`, naming the model and stating that a single agent did the work and called it review.
That is an accurate, mergeable record that describes a weak review. Writing the same name in
both party fields is rejected, because it records a separation that did not happen.

## When this policy is superseded

If a second genuine human maintainer joins, `required_approving_review_count` returns to 1
(`REQUIRED_APPROVALS=1 scripts/apply-branch-protection.sh`). The AI review gate is not removed
when that happens: it becomes the additional mechanical gate alongside the human one. Nothing in
this policy depends on being the only review.
