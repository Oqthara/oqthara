# Alpha 4 completion record

## Status: every requirement row is `DONE`

This record is written against `b7fb6c7`, the squash commit of the change that moved the
requirement matrix pin onto a commit on the default branch, and describes only what the
repository contains. It does not claim independent human review, and it does not claim CPython
parity.

That baseline is itself a correction. This record previously named `5015580`, an intermediate
commit on the branch behind pull request #221 — the sixth of its eight, not its tip, and not the
commit that added this document. Squash-merge rewrote all eight into one, so the reference
resolved nowhere on `main`. The figures were not invented: fetched by SHA they do reproduce at
`5015580` (77 tests OK, 20 reported checks, exit 0). But a reader doing what the record says
could not reach them, and the pin the matrix carried there, `c2b2b16`, was discarded the same
way. At `b7fb6c7` the identical figures reproduce on `main` itself, which is why the baseline
moved rather than the numbers.

Nothing here is a merge commit, and the earlier wording of this note said otherwise.
`b7fb6c7`, `f12719a`, `c2b2b16` and `deeb889` all have a single parent, because this repository
squash-merges; only three commits on `main` have two. What matters for a pin is not that it is a
merge but that squash-merge cannot erase it — a commit already on the default branch survives,
and a commit on a branch does not.

The gate table below keeps both readings, because the pair is the point: the script suite still
reads 77 tests at `b7fb6c7`, and the documentation gate reads 20 reported checks — the same
numbers on a commit a reader can actually check out.

The managed-runtime defect in §"Defects that only an audit could find" item 5 was found after
`b7fb6c7` and fixed in `28ef7b2`; the figures in §17.9 and the gate table were re-measured
against `28ef7b2`, where the documented global count is 205.

## What the last two states were, and how the second one closed

The requirement traceability matrix carries 16 rows. When this record was first written,
fifteen were `DONE` and one was `BLOCKED`; the status that used to dominate the matrix —
`PARTIAL` — was already empty.

The `BLOCKED` row was **§4.2 independent review roles**. Oqthara has one human maintainer.
Independent human review is unavailable, is not simulated, and is not claimed. The bounded
exception is recorded in [`solo-maintainer-exception.md`](solo-maintainer-exception.md) and
the compensating controls are mechanical: pull requests are mandatory, direct pushes are
rejected, required CI contexts are mandatory, review-thread resolution is required, merges are
squash-only, `bypass_actors` is empty and `current_user_can_bypass` is `never`. The one
lowered control is `required_approving_review_count`, and its reasoning is in that document.

That row is now `DONE`, and the wording above is kept because it is still true. Nothing
supplied the missing human reviewer. Under the maintainer's AI-led development order, the
requirement itself was revised: [ADR-0059](../../adr/0059-ai-review-as-the-review-mechanism.md)
replaces *independent human review* with a *mandatory, recorded AI review* that is enforced by
CI. [`ai-review-policy.md`](../ai-review-policy.md) defines the seven categories, the severity
rules and the independence limit; `scripts/check-ai-review.py` rejects a pull request whose
record is missing, malformed, incomplete, or leaves a critical or high finding open; and
`scripts/tests/test_check_ai_review.py` pins each of those rejection paths.

The substitution is narrower than the requirement it replaces, and the ADR says so. The gate
proves a review was **recorded and well-formed**. It cannot prove the review was thorough, and
it cannot make the reviewer independent of the author: every agent in this repository pins the
same model, so agreement between them is evidence of consistency, not of correctness.

## What "done" means here, and what it does not

Every row is closed on executed evidence, not on intent. Where a requirement could not be met
as written, the row says which part is missing rather than rounding up, and where a capability
is refused the refusal names its reason and the ADR that decided it.

It does **not** mean the standard library matches Python's. It does not. 21 of 231 Python
modules are covered, 37 partly, 55 absent and 118 deliberately excluded, and
[`alpha-4-stdlib-inventory.md`](../alpha-4-stdlib-inventory.md) measures all four with reasons.
What §17 and §3.1 require is a per-module decision rather than a count, and that is what
exists.

## How the last rows closed

**§17 broad standard library** was the last `PARTIAL` row and had been held open by a module
count. The count was the wrong test: §17 lists domains "including, but not limited to", and
§3.1 states that Alpha 4 is "not a promise of full CPython compatibility, a promise to
reproduce every Python standard-library module". The row now carries a disposition per
§17.1–§17.8 naming the decision record that settles each bullet, and §17.9 is measured rather
than asserted: all 205 documented globals have a specification or ADR mention and a test
reference, at zero gaps under word-boundary matching across every test tree.

**§16 signed distribution** closed on the published release rather than on a local build:
annotated tag at `bbb9024`, prerelease published 2026-11-14T02:13:16Z, three assets with their
byte sizes, `sha256sum -c` OK, keyless verification OK, and the jar printing its version and
running.

## Defects that only an audit could find

Four of the last round's findings were invisible to reading the code, because the code was
right and the *claim* about it was wrong or unexercised.

1. **The shipped verifier rejected its own release.** `verify-signature.sh` passed
   `refs/tags/*` to `--certificate-identity`, which is a literal subject, not a pattern. Every
   real user would have been told the signature did not match. The CI gate hid it by always
   supplying an exact ref. Fixed, and the regression test was itself mutation-tested: the first
   version used `assertRegex`, which searches, so restoring the broken glob still passed.
2. **Three spec claims were fabricated by inference.** `temp_dir` takes a required prefix and
   creates a directory; `toml_get` raises for an absent path rather than returning `nothing`;
   `stat` omits `mode` where the platform has no POSIX permissions. Each was written from the
   shape the builtins "should" have and corrected only by running them.
3. **Three builtins were shipped, documented and called by no test** — `json_write`,
   `log_warn`, `log_error`. Tests added and mutation-tested.
4. **A documentation promise nothing checked.** `examples/README.md` claimed to list every
   example and did not list `math.oq`; a new check now compares the directory and the table in
   both directions.

A fifth was found after this record was first written, and it is the same failure mode in the
governance itself.

5. **A `DONE` row whose acceptance path could not run.** The managed-runtime row was marked
   `DONE` on unit evidence: the resolver, the archive guard, the lock, the retention policy and
   the installer were each tested, and `RuntimeCompatibilityTest` was green. But the check that
   `RuntimeInstaller` runs against a candidate JVM executed an Oqthara program calling
   `list_sort`, a builtin that **had never existed** — `git log -S'list_sort' -- oq-stdlib
   oq-runtime` returns nothing. Every real `oq runtime install` therefore refused every JDK:
   `error: 'list_sort' is not defined`. No test could see it, because the failing string was an
   input to a seam whose genuine execution was only exercised by the install path, and the
   install tests stubbed the JVM rather than running the shipped program against a real one.

   The first attempt to close this renamed the call to `sort`, which made the check pass while
   deleting the requirement instead of meeting it. Reversed: `list_sort` is now implemented as a
   keyed sort that evaluates its key exactly once per item (decorate–sort–undecorate), because
   the existing `sort_by` evaluated its key once per *comparison* — measured at 46 calls for ten
   items on one ordering and 18 on others, and the ordering of a stateful key changes the answer
   outright. The defect is the dependence, not any particular count. [ADR-0060](../../adr/0060-keyed-sorting.md)
   records the decision. Twenty-two tests in `ListSortTest` covered it at that point, and they
   were mutation-tested; the last two of those came from the Alpha 5 Java-bridge audit, which
   accepted a Java functional value where a key is wanted and refused a two-argument comparator
   by count. [ADR-0067](../../adr/0067-sort-by-shares-the-decorate-path.md) has since moved
   `sort_by` onto the same decorate path, so the file now declares 24.

   An adversarial review of that change then found a **critical** defect in it: the keyed path
   built its callback arguments with `List.of(...)`, which rejects null, and `nothing` is null, so
   `list_sort([3, nothing, 1], x -> 0)` aborted the JVM with an uncatchable
   `internal error: NullPointerException` (exit 70). `sort_by` shared it. No test had a `nothing`
   in its input, which is why the change shipped green. Both now use a null-tolerant helper and
   two tests pin the case.

   The lesson is the record's own, applied to itself: the row was closed on evidence that never
   ran the path a user takes. `oq runtime install` was run end to end on a real JDK 21 after the
   fix, and that is the evidence this row should have carried from the start.

The pattern is consistent: an assertion that nothing executes is the failure mode. That is why
the §17.9 criteria were measured with a script rather than read off the reference.

## Gates

Run against `b7fb6c7`, then re-run against `28ef7b2` on `main` after the managed-runtime fix.
Where the two differ the re-run is listed second.

| Gate | Command | Result |
|---|---|---|
| Script unit tests | `python3 -m unittest discover -s scripts/tests` | 77 tests OK at `b7fb6c7`; **198 OK (1 skipped)** at `28ef7b2` |
| Documentation drift | `python3 scripts/check-docs-sync.py` | exit 0, 20 reported checks at `b7fb6c7`; exit 0, 24 reported checks at `28ef7b2` |
| Conformance | `python3 scripts/verify-conformance.py` | **205 compiled, 9 fallback (214 of 214 executable), 0 divergences across 237 programs** at `28ef7b2` |
| Builtin coverage audit | word-boundary match of 205 globals against specs, ADRs and five test trees | 0 undocumented, 0 untested |
| Full build | `./gradlew build` | 52 tasks SUCCESSFUL, `oq-core` coverage 91.8% (gate 80%) at `28ef7b2` |
| Full build and required CI | 12 contexts required by `main-protection` | recorded on the pull request |

In a shallow clone four `test_check_docs_sync_pin.py` cases could not run, including
`test_it_says_what_it_read` — the pin `deeb889` is not among a depth-1 clone's objects, and
those cases ask about a commit other than the tip, which a one-commit history cannot answer.
The check itself already discriminates "not fetched" from "does not exist" with
`git rev-parse --is-shallow-repository`, so the production path was correct and only the tests
were wrong to assume history.

The count is four rather than three because fixing the first three exposed a fourth:
`test_a_branch_only_commit_is_not_an_acceptable_pin` was failing in a single-branch clone all
along, behind the others. That clone has no `refs/remotes/origin/main`, so the check falls back
to `HEAD`, the pin becomes an ancestor of itself, and a case that expects a rejection gets a
green result. Its own skip only fired when `merge-base` returned 0; in that clone it returns
128, so the premise was never tested.

Each guard now asks the question its case actually depends on, rather than reading the
repository-wide shallow flag. Two need the pin commit to be present, one needs
`refs/remotes/origin/main`, and only `test_a_sha_that_matches_no_commit_is_rejected` depends on
the flag, because there the check legitimately reports a `note` instead of a problem. A
flag-based guard would have been cheaper and wrong: a depth-20 clone contains the pin, and it
now skips two cases rather than six. A full clone is unaffected — `git fetch --unshallow` gives
198 tests OK — and CI checks out `fetch-depth: 0`, so the extra coverage is always present where
it counts.

The documentation gate enforces the matrix figures against the inventory and the generated
reference, so a row cannot drift from the measurement it cites.

## Residual risks

- **No independent human review.** Stated above and not mitigated by any repository change.
- **The standard library is narrow.** 25% of the Python reference index is covered or partly
  covered. The absent 55 modules are cheap and ranked, not blocked; the eight highest-value
  ones are ordered in the inventory for a future round.
- **The language has no `close`.** Every builtin returns a completed value, so nothing can leak,
  but domains that need a lifetime — sockets, servers, held handles — cannot be delivered until
  the construct exists. Each refusal cites that reason rather than deferring the question.
- **Alpine/musl is unsupported** because the SQLite driver is JNI-bound.
- **A built program carries the database driver**, so each jar is 13.5 MB regardless of whether
  it queries a database.
- **The review gate's ancestry rule is PR-time only, so merged records are not re-auditable.**
  `check-ai-review.py` requires a critical or high finding's resolution to name a commit that is
  an ancestor of the branch, so a resolution cannot cite an unrelated fix. Squash merge then
  erases every branch commit — the same defect this record was corrected for. Run against `main`,
  the gate fails on every merged review record. In a fresh clone the cited commits are not merely
  off-branch, they are absent objects: `951cf12` (PR-224), `7ae7fb7` (PR-225), `36a145e` (PR-227),
  `164749f` (PR-228) and `039053b` (PR-226's reviewed commit) all fail
  `git cat-file -t`. This is measured in a `git clone` of `main`, not inferred. It matters because
  evidence that cannot be checked out is exactly what the Alpha 3 closure work was about, and
  because a reviewer re-running the gate on `main` sees a failure that is not a defect in the
  change. CI is unaffected: the gate step exits 0 on a push event and runs only for pull requests,
  so it enforces as designed at the moment it can act. A proper fix has the record cite the merge
  commit, or adds a post-merge mode resolving findings against the merge diff; both change the
  policy in ADR-0059 and were not done here. Recorded rather than claimed.
