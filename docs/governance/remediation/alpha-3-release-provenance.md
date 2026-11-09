# Alpha 3 release provenance

**State:** RESOLVED — no tag was moved, deleted, or re-created.

This record answers a question raised during Alpha 3 closure: the tag
`v0.1.0-alpha.3` is a lightweight tag while `v0.1.0-alpha.1` and `v0.1.0-alpha.2` are
annotated, and the tag points at a CI commit rather than at the commit that set the
version. Both facts are real. Neither is a provenance defect, and the evidence below is
what settles it.

## What the tag points at

| Tag | Kind | Tag object / commit | Commit subject |
| --- | --- | --- | --- |
| `v0.1.0-alpha.1` | annotated | `9a9e8656` → `dbd86804` | `chore(ci): bump actions to current majors` |
| `v0.1.0-alpha.2` | annotated | `63c379d2` → `e1bf8680` | `bench: add JMH interpreter benchmark module (T-0047) (#33)` |
| `v0.1.0-alpha.3` | **lightweight** | commit `a104629e` | `ci: announce new content to the website (#37)` |

Each tag points **past** the commit that set its version in `build.gradle.kts`:

| Tag | Version-bump commit | Commits between the bump and the tag |
| --- | --- | --- |
| `v0.1.0-alpha.1` | `018c93c9` | 3 |
| `v0.1.0-alpha.2` | `3de97264` | 10 |
| `v0.1.0-alpha.3` | `5b33f18` | 1 |

So "the tag is not on the version-bump commit" describes all three releases equally. It is
the project's established practice — tag the tip of `main` that was actually built and
shipped — not a one-off error in alpha.3.

## The evidence that matters: the artifact is reproducible

A tag is only a provenance claim if the artifact can be tied to it. The released
`oq.jar` can be:

```
$ shasum -a 256 oq.jar.sha256 oq.jar   # downloaded from the GitHub release
oq.jar: OK

$ git worktree add /tmp/clean104 a104629e6adef727b31f2c6f06a28a0045cd7835
$ ./gradlew :oq-cli:standaloneJar
$ shasum -a 256 oq-cli/build/libs/oq.jar
3edfd8eff399cfc2bdb47cfb5f09f1bbdb96fc2a5d2b2a0b6c9b9d6ee9a065e6  oq-cli/build/libs/oq.jar
```

The released asset is `3edfd8ef…`, byte for byte. The build is reproducible from the
commit the tag names.

Built from the version-bump commit instead, the jar is **different**:

```
$ git worktree add /tmp/at5b 5b33f18
$ ./gradlew :oq-cli:standaloneJar
$ shasum -a 256 oq-cli/build/libs/oq.jar
7f80bdb9f35bc88554ff24b7aad12019e36368a5fd06d61f49b0986575071e1b
```

This is the decisive result. `a104629e` — the commit the tag actually names — is the
commit the released bytes came from, and `5b33f18` is not. The tag identifies its
artifact correctly. Moving it to `5b33f18` would **falsify** provenance by attaching the
release to a commit that does not reproduce it.

## Why the tag is lightweight, and why that stays

`v0.1.0-alpha.3` was created as a lightweight tag; the two earlier releases were
annotated. An annotated tag can carry a message and a tagger identity, so it is the
better default and worth using for future releases.

Converting this tag now would mean deleting a published tag reference and re-creating it
at the same commit. That is a rewrite of published history for a stylistic gain, with a
real cost: anyone who fetched the original ref would hold a different object, and the
release's `target_commitish` metadata would no longer match. The order for this
remediation permits such a rewrite only for documented technical necessity. Cosmetic
consistency does not meet that bar.

The correct outcome is the one adopted: **record the inconsistency, correct the practice
going forward, leave the published ref alone.**

## Correction carried forward

Future releases should:

1. Create the tag as an annotated tag, with the release name as its message — matching
   `v0.1.0-alpha.1` and `.2`.
2. Keep tagging the tip of `main` that is built and shipped. The release workflow already
   gates the tag against `build.gradle.kts`, `Main.java`, `CHANGELOG.md`, and the jar
   manifest, so a tag that does not match the shipped version cannot produce a release.

## A separate finding: where the stale conformance constants came from

While resolving this, the origin of the wrong numbers in `scripts/verify-conformance.py`
was identified. The annotated tag message for `v0.1.0-alpha.2` reads:

> 182 of 197 executable conformance programs compile to bytecode with zero measured
> divergences from the interpreter.

`182` and `197` are exactly the `FLOOR_COMPILED` and `EXPECTED_EXECUTABLE` constants the
verifier carried. They were accurate when alpha.2 was tagged, were copied into the script
as invariants, and were never re-measured as the corpus grew to 207 executable programs
with 192 compiled. The verifier's own constants were a fossil of a release note.

That is corrected in the same remediation (see the CHANGELOG): the verifier now pins the
measured baseline and states in a comment that the numbers come from a fresh measurement.
Tag messages are immutable records and are left as written.
