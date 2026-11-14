# ADR-0052: Keyless signing of release artefacts

## Status

Accepted.

## Context

A release publishes `oq.jar` and, beside it, `oq.jar.sha256`. `scripts/install.sh`
and `scripts/install.ps1` download both and refuse to install a jar whose digest
does not match the published one.

That check is honest about a limit it cannot cross. The jar and its checksum are
published by the same release, from the same account, over the same channel. Any
party who can replace the jar can replace the checksum beside it, and the
installer will report a match. ADR-0048 states the consequence directly:

> Integrity is a SHA-256 in the same release as the jar. Anyone who can replace
> the jar can replace the checksum beside it, so this detects corruption and not
> tampering.

The distinction matters more than the check does. "Corruption" is a truncated
download or a bad disk. "Tampering" is a substituted binary. A user who reads
"checksum verified" and concludes the second has been ruled out believes
something the project never established. The wording of the installer is
therefore part of the security property, not decoration.

Three ways to close the gap were available.

**A long-lived signing key in the repository.** Simple and offline-verifiable,
but the private key must live somewhere. A repository secret is an operational
liability: it can be exfiltrated from CI logs, copied by a maintainer, or lost.
For a single-maintainer project with no hardware token and no key ceremony, the
key would most likely end up in a CI secret, which relocates the trust problem
rather than solving it. Key rotation and revocation would also need to be
designed and documented.

**A long-lived key held outside the repository, published for verification.**
The installer would embed the public half. This is a real improvement over a
checksum, and it survives offline. But it still depends on one key that must
never leak, and the project has no process for protecting it.

**Keyless signing.** The release workflow obtains a short-lived certificate from
the Sigstore public-good instance, bound to the workflow's own OIDC identity, and
records the signature in a transparency log. There is no long-lived private key
anywhere. Verification succeeds only if the signature was produced by that
workflow, in this repository, and was logged publicly.

## Decision

**Releases are signed with Sigstore cosign in keyless mode, and the release
workflow refuses to publish a jar whose signature does not verify.**

1. **Checksum for corruption, signature for tampering.** Both ship. The checksum
   stays because it is cheap, works with no network and no tools, and catches the
   common failure. The signature is what makes a substitution detectable.
   Documentation and installer output must keep the two claims separate. Neither
   may be described as the other.

2. **Keyless, so there is no key to protect.** The private key exists for minutes
   inside the runner and is never written anywhere persistent. The property this
   buys is not convenience; it is the removal of a long-lived secret that a
   single-maintainer project has no way to guard well.

3. **The verifier names the signer.** `scripts/verify-signature.sh` accepts a
   signature only when the certificate identity is
   `https://github.com/Oqthara/oqthara/.github/workflows/release.yml@refs/tags/.*`
   and the issuer is `https://token.actions.githubusercontent.com`. A signature by
   *someone* is not enough; the check is that *this repository's release
   workflow* signed it. Both values are overridable for forks and for local
   testing, and the overrides are visible in the command line rather than hidden
   in configuration.

   The identity default is a **regexp**, matched with
   `--certificate-identity-regexp`. It must be: `--certificate-identity` compares
   its argument literally, so the glob `refs/tags/*` that this ADR originally
   specified matched no real certificate. The script shipped that glob as its
   default in `0.1.0-alpha.4` and reported "signature verification FAILED" for the
   published release, which is the worst possible direction for this control to
   fail on a user's machine. The gate in the release workflow passed throughout
   because it supplies the exact `${GITHUB_REF}` it just built, which is a valid
   literal — a test that only ever exercises the exact path cannot detect a broken
   default. `OQ_COSIGN_IDENTITY_EXACT=1` selects the literal flag for callers that
   hold an exact subject; the workflow uses it, and the default path is covered by
   a test that full-matches the pattern against a realistic subject.

4. **Signing is verified inside the release, before publishing.** The workflow
   signs, immediately verifies its own signature, and then tampers with a copy of
   the jar and asserts that verification *fails*. A signing step whose output is
   never checked is a step that silently stops working — the failure mode is a
   release that looks signed and is not. The negative control is what
   distinguishes "the tool exited 0" from "the tool checks something".

5. **A release with no signature is reported as such, never as verified.** The
   releases published before this decision have no bundle. The installer detects
   the absence, says that only a checksum was available and what that does and
   does not prove, and continues. It must not fail silently, and it must not
   imply a check it did not perform.

6. **Strictness is available and opt-in.** `OQ_REQUIRE_SIGNATURE=1` turns either
   "signed but cosign is not installed" or "no signature published" into a hard
   failure. The default stays permissive because making it strict by default
   would break every existing installation on a machine without cosign, and a
   security control that users disable to get their tool working is worse than
   one that is honest about what it checked.

## Consequences

**The claim the project can make is narrow and true.** For releases published
after this decision, a jar that verifies was produced by this repository's
release workflow. That is a stronger statement than "the digest matched", and it
is the strongest statement available without a key ceremony.

**Installing now optionally depends on cosign.** Verification needs the tool, or
a reimplementation of the bundle format. The installer therefore treats cosign as
optional, states the consequence when it is absent, and lets the user demand it.
`scripts/verify-signature.sh` reports exit code 2 — distinct from a failed
verification — when the tool is missing, so a caller can tell "not checked" from
"checked and rejected".

**Older releases remain unsigned.** This is append-only: nothing is re-tagged and
no published artefact is replaced. A signature added retroactively to an existing
tag would assert that the jar was signed when it was built, which would be false.

**Keyless verification wants the transparency log.** Verification against the
public-good instance needs a tlog entry, which needs network access. This is
acceptable for a release path and is why an offline mode exists: given a public
key with `OQ_COSIGN_KEY`, `verify-signature.sh` verifies from the bundle alone
with `--offline` semantics and no log lookup. Projects that mirror the jar can
mirror the public key with it.

**Rotation is not a problem this design has.** There is no long-lived key to
rotate. The trust anchor is the OIDC issuer and the repository identity, both of
which are already the things that would have to be compromised for an attacker
to publish a release at all.

**A compromise of the repository workflow is still a compromise.** Keyless
signing proves the artefact came from this workflow. It does not prove the
workflow was not itself altered. The protection against that is branch
protection, required review and a protected release path — which is why this ADR
does not replace any of them, and why signing alone is not described as supply
chain security.

## Alternatives rejected

- **GPG signing with a repository key.** Rejected: relocates the trust problem to
  a secret that a single-maintainer project cannot guard better than the release
  channel itself, and adds a key-distribution problem with no transparency log.
- **Signing with a key checked into the repository.** Rejected outright: a
  published private key signs nothing.
- **Publishing only a checksum and describing it accurately.** This is the status
  quo, and it was rejected because the accurate description is "this does not
  detect tampering" — which is a gap, not a decision. ADR-0048 lists it as gap 2
  for exactly this reason.
- **Signing by default and requiring cosign to install.** Rejected: it would break
  installation on machines without cosign for a project whose install path is
  `curl | bash`. `OQ_REQUIRE_SIGNATURE=1` gives that strictness to those who want
  it without imposing a new hard dependency on everyone.
