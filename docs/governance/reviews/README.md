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
- `**Reviewed at:**` names a commit that exists in this repository (in a shallow clone the
  check degrades to well-formedness rather than reporting a false failure);
- `**Independent:**` starts with `yes` or `no`, and `no` names the shared model;
- all seven categories appear, each with `pass` or `fail`, and only `release integrity` may be
  `not applicable`;
- the number of findings listed equals the number the category table claims;
- no `critical` or `high` finding has an empty resolution;
- no category has a `fail` verdict.
