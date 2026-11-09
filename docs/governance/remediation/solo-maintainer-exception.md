# Solo-maintainer governance exception

**State:** ACTIVE — explicitly authorized by the maintainer, bounded, and reversible.
**Scope:** the `required_approving_review_count` parameter of `main-protection` on `Oqthara/oqthara`.
**Applied:** the count went from `1` to `0` on ruleset `26650412`, and was read back afterwards.
**Restore with:** `REQUIRED_APPROVALS=1 scripts/apply-branch-protection.sh`

This record exists so that a future reader, including a future maintainer, cannot mistake a
narrowed review requirement for a satisfied one. It states what was reduced, what was not, who
authorized it, and what would end it.

## The condition

Oqthara has **one human maintainer**.

The GitHub account `JoaoGaValentim` is a second account belonging to that same person. It is
**not** another human and **not** an independent reviewer. It has `write` permission on the
repository, and it is capable of submitting an approving review, but an approval from it carries
exactly as much independence as an approval from the maintainer — which is to say none.

There was never a second person available to perform the review that `main-protection` began
requiring in the Alpha 3 remediation.

## What was reduced, and why

`required_approving_review_count` was set from **1** to **0**.

The reason is not convenience. With a count of 1 and one human maintainer, exactly two outcomes
are possible, and both are worse than the exception:

1. **Nothing merges.** Every change, including urgent security fixes, stalls indefinitely on a
   review that cannot exist.
2. **A self-approval is dressed up as a second opinion.** The maintainer approves from the
   second account, the ruleset records a satisfied approval, and the audit trail reads as though
   a second person reviewed the change. That is fabricated review, and it is precisely what the
   remediation order forbids.

Lowering the count removes the incentive for the second outcome. It does not remove the
requirement to be honest about it.

## What was not reduced

Every control that a single maintainer **can** satisfy honestly remains active and enforced.
Read back from ruleset `26650412` after the change:

| Control | State |
| --- | --- |
| `deletion` | active — the branch cannot be deleted |
| `non_fast_forward` | active — force-push is rejected |
| `pull_request` | active — changes must arrive as a pull request |
| `allowed_merge_methods` | `["squash"]` only |
| `required_review_thread_resolution` | **true** — every review thread must be resolved |
| `dismiss_stale_reviews_on_push` | **true** |
| `required_status_checks` | active — 12 contexts, `strict_required_status_checks_policy: true` |
| `bypass_actors` | `[]` |
| `current_user_can_bypass` | `never` |
| Administrative bypass | none — the merge button obeys the same rules as anyone else's |

Direct pushes to `main` are still rejected. Required CI is still required. Thread resolution is
still required. The only thing removed is the approval count.

The enforcement was tested rather than assumed. With the count already at `0`, a probe commit was
pushed directly at `refs/heads/main` and the server refused it:

```
$ git push origin <probe>:refs/heads/main
remote: - Changes must be made through a pull request.
 ! [remote rejected] <probe> -> main (push declined due to repository rule violations)
```

The probe commit was never created on the remote; it exists only as a discarded local object.
This is the control that protects the branch, and it does not depend on the approval count.

## What this exception does not claim

- It does **not** claim that independent human review occurred. It did not.
- It does **not** convert any AI or agent review into independent review. All Hive agents pin
  the same model, so their review is correlated with, not independent of, the author's model.
  AI review here is supplemental assurance.
- It does **not** close the PR #37 retrospective's independence gap. That gap is carried as
  blocked, and the retrospective says so in its own words.

## Why the record lives in the repository

The ruleset is a repository-level setting and can be changed in the GitHub UI by anyone with
admin rights. A change made there would leave no trace in the audit trail. Keeping the policy in
`scripts/apply-branch-protection.sh`, with the exception documented here, means the exception is
reviewable in the same pull request that depends on it, and that a future reader can see the
parameter and its justification together.

## When this exception must be revisited

The exception ends when **either** of the following becomes true:

1. **A second genuine human maintainer joins.** Raise the count back to 1 immediately:

   ```
   REQUIRED_APPROVALS=1 scripts/apply-branch-protection.sh
   ```

   The script parameterizes the count for exactly this reason. No edit to the script is needed.

2. **The project is transferred to an organization or a foundation** with more than one
   committer. The same command applies.

Until one of those happens, this file should be read alongside the Alpha 3 closure report's
review status, so that "review required: 0" is never read as "review performed: yes".
