# ADR-0048: Runtime Manager and distribution

- **Status:** Proposed (plan only; no part of it is implemented)
- **Date:** 2026-11-11
- **Related:** [ADR-0040](0040-database-support.md) (the JNI platform limit), [ADR-0010](0010-release-gate.md) (release gating), [ADR-0002](0002-ci-split.md) (platform CI)

## Context

Workstream 9 was unowned until 2026-11-11, when this decision's authoring began and the
workstream moved to `oq-hive-testing-security` with `oq-hive-development` supporting. The
artefacts it covers already exist and already work, which is precisely why the missing ownership
mattered: nobody was accountable for them.

What the repository ships today:

- `scripts/install.sh` (202 lines) downloads `oq.jar` from a release, verifies a SHA-256 published
  beside it, and runs `--version` as a smoke test before installing.
- `scripts/install.ps1` (235 lines) is the Windows counterpart.
- `Dockerfile` and `.github/workflows/docker.yml` build a container image.
- `.github/workflows/release.yml` publishes the jar, its checksum, and the image.
- `packageApp` in `oq-cli/build.gradle.kts` builds a `jpackage` app-image.

What it does **not** ship, stated plainly because the gaps are the point of this ADR:

1. **No managed runtime.** Both installers call `check_java` and fail if `java` is absent or
   older than 21 (`scripts/install.sh:101-122`). There is no bundled JRE and no download of one.
2. **No signature.** Integrity is a SHA-256 in the same release as the jar. Anyone who can
   replace the jar can replace the checksum beside it, so this detects corruption and not
   tampering.
3. **No update or rollback.** Re-running the installer is the only upgrade. There is no
   previous-version retention and no way to return to one.
4. **No version pinning beyond `OQ_VERSION`.** There is no lockfile, no per-project runtime
   selection and no record of which JVM an installed `oq` will use.
5. **No documented platform matrix.** The installers behave differently on macOS, Linux and
   Windows, and that difference is in the shell rather than in a specification.

## Decision

This ADR records the architecture the Runtime Manager must have. It does **not** claim any of it
is implemented; each numbered item below is a requirement with an acceptance test, and the
requirement matrix tracks the workstream as `PARTIAL` until they pass.

**D1. The runtime is selected, never assumed.** A single resolution order is defined and
documented: an explicit `OQ_JAVA` override, then a project-local pin, then a managed runtime
installed by `oq`, then `java` on `PATH`. The first entry that exists *and* validates wins.
Resolution is a pure function of the environment plus the on-disk pin, so it is testable without
installing anything.

**D2. A managed runtime is downloaded only when nothing suitable is found.** The manager
supports exactly the platforms it can test on, and the list is enumerated rather than implied:
Linux x86_64 and aarch64, macOS x86_64 and aarch64, Windows x86_64. A platform outside that list
gets a diagnostic naming what was detected, not a silent fallback to an incompatible JVM.

**D3. Provenance is verified before extraction.** A runtime archive is accepted only when its
SHA-256 matches a value compiled into `oq` for that specific archive, and the download is
performed over TLS with certificate validation left to the platform. A checksum fetched from the
same place as the archive is not provenance; it detects corruption. This is the distinction the
current installer does not make, and D3 is why the ADR exists in this shape.

**D4. Extraction is a security boundary.** Archives are extracted by a routine that rejects an
entry whose resolved path escapes the destination, rejects absolute paths, and either refuses
symbolic links or creates them only when their target is inside the destination. The failure
mode is refusal, never a partial extract that leaves the cache in an unknown state.

**D5. Installation is atomic.** A runtime is unpacked into a temporary directory on the same
filesystem as its final location and moved into place with a single rename. An interrupted
download or extraction leaves a temporary directory that is discarded on the next run, never a
half-installed runtime that later validation has to detect. A validate-then-activate step runs
the extracted `java -version` before the rename, so a corrupt archive cannot be activated.

**D6. Concurrent invocations serialise on a lock.** Two `oq` processes starting at once must not
extract into the same directory. The lock is acquired before any download, and a stale lock from
a killed process is detected rather than waited on forever.

**D7. Rollback is retention, not repair.** The manager keeps the previous runtime until the new
one has been validated and has run a program successfully. "Rollback" means selecting the
retained version; it never means undoing a filesystem operation.

**D8. Offline operation is a first-class path.** With no network, an already-installed managed
runtime is used. If none exists, the diagnostic says which runtime was looked for, where it
would have been installed, and that the network was unreachable — not a generic failure.

**D9. Oqthara compatibility is a separate question from JDK availability.** Java 21 LTS is the
floor because `build.gradle.kts:51-52` targets it. The manager must prove that a candidate
runtime can run the language's own conformance corpus, not merely that the vendor published it.
A vendor's release date is not a compatibility statement.

This is not hypothetical. [ADR-0002](0002-ci-split.md) records that the build failed on JDK 25
with `Unsupported class file major version 69` because Gradle 8.x cannot parse that class-file
format. A newer JVM was present, available and published by its vendor, and Oqthara could not
build on it. Any resolution rule that ranks "newest LTS" above "known-compatible" would select
it and break the build, which is why D1 validates rather than merely locates.

**D10. Documented limitations are part of the contract.** R-006 (SQLite multiplies artifact size)
and R-007 (the JNI driver makes Alpine/musl unsupported) already record the platform consequence:
a managed runtime is a glibc/musl question before it is a JDK-version question. A limitation that
is written down is a supported configuration with a caveat; the same limitation undocumented is a
defect. D2's platform list is therefore a subset of the platforms R-007 leaves supportable, not a
separate opinion about them.

## Consequences

The installers gain a runtime-selection layer they do not have, which is a meaningful increase in
what they must get right. The alternative — continuing to require a pre-existing JDK — keeps the
installer small, and it is a defensible choice for a project whose users are already JVM
developers. It is not defensible for a project that wants a one-command install on a machine with
no JDK, which is what the workstream names.

No item here weakens a control in place. The SHA-256 check stays where it is; D3 adds a stronger
check beside it rather than replacing it.

## Open questions

The maintainer has not yet chosen between two shapes for the managed runtime:

- **A single pinned LTS per `oq` release.** Simple, one artifact per platform, and compatible
  with D8's offline story because there is exactly one runtime to have.
- **User-selectable LTS.** More flexible, but it multiplies the provenance matrix and turns
  D9 into a compatibility table that must be maintained per version.

This ADR proceeds with the first shape as the default and records the second as deferred. The
choice does not block the extraction, locking, atomicity or offline work, so implementation can
begin on the parts both shapes share.
