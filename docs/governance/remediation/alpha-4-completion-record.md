# Alpha 4 completion record

## Status: every requirement row is `DONE` or documented as externally blocked

This record is written against `5015580` and describes only what the repository contains. It
does not claim independent human review, and it does not claim CPython parity.

## The two states that remain, and why neither is open work

The requirement traceability matrix carries 16 rows. Fifteen are `DONE`. One is `BLOCKED`, and
the status that used to dominate the matrix — `PARTIAL` — is now empty.

The single `BLOCKED` row is **§4.2 independent review roles**. Oqthara has one human
maintainer. Independent human review is unavailable, is not simulated, and is not claimed. The
bounded exception is recorded in [`solo-maintainer-exception.md`](solo-maintainer-exception.md)
and the compensating controls are mechanical: pull requests are mandatory, direct pushes are
rejected, required CI contexts are mandatory, review-thread resolution is required, merges are
squash-only, `bypass_actors` is empty and `current_user_can_bypass` is `never`. The one
lowered control is `required_approving_review_count`, and its reasoning is in that document.

No amount of work in this repository converts that row to `DONE`. It stays blocked until a
second human reviewer exists.

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
than asserted: all 204 documented globals have a specification or ADR mention and a test
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

The pattern is consistent: an assertion that nothing executes is the failure mode. That is why
the §17.9 criteria were measured with a script rather than read off the reference.

## Gates

Run against `5015580` on branch `fix-verify-signature-identity`:

| Gate | Command | Result |
|---|---|---|
| Script unit tests | `python3 -m unittest discover -s scripts/tests` | 77 tests OK |
| Documentation drift | `python3 scripts/check-docs-sync.py` | exit 0, 20 reported checks |
| Builtin coverage audit | word-boundary match of 204 globals against specs, ADRs and five test trees | 0 undocumented, 0 untested |
| Full build and required CI | 12 contexts required by `main-protection` | recorded on the pull request |

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
