# AI review records

One file per pull request: `PR-<number>.md`. See
[the AI review policy](../ai-review-policy.md) and
[ADR-0059](../../adr/0059-ai-review-as-the-review-mechanism.md).

`scripts/check-ai-review.py` fails the build when a pull request's record is missing,
malformed, does not address all seven categories, or leaves a critical or high finding
unresolved.

## Template

```markdown
# AI review — PR #<number>

**Pull request:** #<number>
**Reviewer:** <agent or model that performed the review>
**Implementer:** <agent or model that wrote the change>
**Reviewed at:** `<sha>`
**Independent:** no — <shared model>, and why

## Categories

| Category | Verdict | Findings |
| --- | --- | --- |
| functional correctness | pass | 0 |
| architecture and maintainability | pass | 0 |
| security and privacy | pass | 0 |
| regression and compatibility | pass | 0 |
| test coverage and validation | pass | 0 |
| documentation accuracy | pass | 0 |
| release integrity | not applicable | 0 |

## Findings

| # | Severity | Location | Finding | Resolution |
| --- | --- | --- | --- | --- |
| 1 | medium | `path/to/file:42` | what is wrong | fixed in `<sha>` |

## What was examined

Which commands were run, which files were read, and what the reviewer did **not** cover.
```

## Rules the validator enforces

- the heading and the filename name the same pull request number;
- `**Pull request:**`, `**Reviewer:**`, `**Implementer:**`, `**Reviewed at:**` and
  `**Independent:**` are all present;
- `**Reviewed at:**` names a commit that exists in this repository. A shallow clone cannot
  confirm this, so it fails rather than silently passing; pass `--allow-shallow` to accept the
  degradation knowingly;
- `**Independent:**` starts with `yes` or `no`. `no` must name a model, so a reader can see what
  the reviewer ran. `yes` must name a model that is **not** one the agents pin, and must describe
  the mechanism that kept the reviewer from inheriting the implementer's framing — a bare `yes`
  is the claim this check exists to stop;
- all seven categories appear, each with `pass` or `fail`, and only `release integrity` may be
  `not applicable`;
- the number of findings listed equals the number the category table claims, and no count may be
  negative;
- no category has a `fail` verdict;
- a `critical` or `high` finding is unresolved if its resolution cell is empty, is a deferral in
  any wording (`deferred`, `won't fix`, `TBD`, `still open`, …), or does not name in backticks a
  commit that resolves in this repository. Evidence is required, not a sentence saying the fix
  happened;
- the gate reads the pull request number from `--pr`, then `GH_PR_NUMBER`, then the event
  payload, then `GITHUB_REF`.

A pull request opened by an automated author — `dependabot[bot]`, `renovate[bot]`,
`github-actions[bot]` — is exempt from carrying a record, because it cannot review its own
change. Every other required context still applies, and the exemption is a login check rather
than a label anyone can set.

## Adding a record

Records are excluded from `docs/catalog.json`, so adding one does not require regenerating the
catalog. Write `docs/governance/reviews/PR-<number>.md` in the same pull request it describes,
against the commit you reviewed, and push. The gate fails until it exists and is well-formed,
which is the intended order: the pull request demonstrates the gate it introduces.
