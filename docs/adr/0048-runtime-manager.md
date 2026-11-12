# ADR-0048: Runtime Manager and distribution

- **Status:** Proposed (D1, D2, D4, D5, D6, D7, D8 and D9 implemented; provenance and
  signing are not)
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
is implemented; each numbered item below is a requirement, and the requirement matrix records the
workstream as `PARTIAL` overall, with `Managed runtime` specifically `NOT STARTED` until the
first item below passes its test.

**D1. The runtime is selected, never assumed.** A single resolution order is defined and
documented: an explicit `OQ_JAVA` override, then a project-local pin, then a managed runtime
installed by `oq`, then `java` on `PATH`. The first entry that exists *and* validates wins.
Resolution is a pure function of the environment plus the on-disk pin, so it is testable without
installing anything.

*Implemented, 2026-11-12.* `RuntimeResolver` in `oq-cli` realises D1 and nothing more than D1:
it reads the environment, walks up for `.oq/java`, and probes each candidate's `-version` banner
for a major version at or above the 21 floor. A candidate that exists but is below the floor is
recorded and skipped rather than terminating the search, because a wrong answer at a
higher-priority origin must not shadow a correct one below it. `oq java` reports the decision and
`oq java --json` reports it machine-readably. Because resolution touches no network and no
archive, it is complete and tested before any acquisition code exists — which is the reason this
decision was listed first.

**D2. A managed runtime is downloaded only when nothing suitable is found.** The manager
supports exactly the platforms it can test on, and the list is enumerated rather than implied:
Linux x86_64 and aarch64, macOS x86_64 and aarch64, Windows x86_64. A platform outside that list
gets a diagnostic naming what was detected, not a silent fallback to an incompatible JVM.

*Implemented, 2026-11-13.* `PlatformSupport` in `oq-cli` enumerates that list and
`oq java` reports it: `--json` always carries `platform` and `platformSupported`, and when nothing
resolves on an unsupported platform the failure names what was detected and says that `OQ_JAVA` or
a Java 21+ on `PATH` still works. What D2 asks for is the *enumeration and the diagnostic*, not the
download, so this is complete as stated. The normalisation is the substance rather than the table:
one processor is reported as `x86_64`, `amd64`, `x64` or `x86-64` depending on the vendor and the
operating system, and one as `aarch64` or `arm64`. Every spelling is folded to a canonical name
before it is compared, because matching the raw properties would make support depend on which JDK
the user happens to run — and a silent miss there produces exactly the silent fallback this item
forbids. An unrecognised value yields "unknown" rather than a guess, since a wrong platform name in
a diagnostic is worse than an admitted unknown. Windows on ARM is deliberately outside the list
because the ADR names Windows x86_64 only; adding it is a change to this ADR, not to the code.

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

`RuntimeInstaller` performs the sequence under the D6 lock: discard staging left by an earlier
run, extract into `$OQ_HOME/.staging-<uuid>`, locate `bin/java` inside the extracted tree, run it
and check the major version against the floor, then rename the located runtime to
`$OQ_HOME/runtime/java` with `ATOMIC_MOVE`. A refused or failed step deletes the staging
directory, so the only states are "nothing installed" and "a validated runtime installed".

There is deliberately no fallback when the filesystem refuses an atomic rename. Copying into
place instead would reintroduce the window this requirement removes, so the failure is reported
and installation does not happen.

The validation ask is about the exact bytes that will be activated, which is what makes the
ordering meaningful: `validationHappensBeforeActivation` records whether the live path exists at
the moment the version probe is called, and fails if it does.

**D1 correction.** `RuntimeResolver.managedJava` originally treated `$OQ_HOME/runtime/java` as
the Java executable. No JDK archive can satisfy that — a runtime is a tree of `bin`, `lib` and
`release` — and nothing could produce one before D5 existed, so the mistake was invisible. The
managed runtime is now a home directory and the executable is `bin/java` beneath it, which is the
same layout `JAVA_HOME` uses. The change is confined to the managed origin; the explicit override,
the project pin and `PATH` are unchanged.

**D6. Concurrent invocations serialise on a lock.** Two `oq` processes starting at once must not
extract into the same directory. The lock is acquired before any download, and a stale lock from
a killed process is detected rather than waited on forever.

The lock is an operating-system advisory lock on a file in the managed home, not a pid written
into a file. The difference decides what "stale" means: the kernel releases an advisory lock
however the holder dies, so a killed process leaves a lock file that is *unlocked*, and no reader
ever waits on a dead owner. A pid-based scheme has to guess, and its guess fails when the pid is
reused. The file's contents name the holding process for a human reading a timeout message, and
nothing parses them to make a decision.

Two levels are needed because `FileLock` belongs to a JVM rather than a thread: a second thread in
one process does not block on it, it throws `OverlappingFileLockException`. An in-process semaphore
per lock path serialises threads; the file lock serialises processes. Advisory locking is
unreliable on some network filesystems, which is recorded as a limit rather than mitigated.

**D7. Rollback is retention, not repair.** The manager keeps the previous runtime until the new
one has been validated and has run a program successfully. "Rollback" means selecting the
retained version; it never means undoing a filesystem operation.

`RuntimeRetention` keeps two directories in the managed home: `runtime/java` is active and
`runtime/java.prev` is the one it replaced. An upgrade validates the new runtime in staging (D5),
then renames the active one to `.prev` and the staged one to `.active`, so the previous runtime is
complete on disk before the new one is live and nothing is deleted to make room. Rolling back
renames the two, which is a selection and not a repair — no state is reconstructed from a
partial one.

Rotating *after* validation and not before is the load-bearing ordering: a refused upgrade leaves
the working runtime active and retains nothing, and `aRefusedUpgradeLeavesTheWorkingRuntimeActive`
fails if the two steps are swapped.

At most two runtimes are kept. A third generation replaces the retained one rather than
accumulating, which is a bound the requirement implies but does not state: no rule here decides
how many generations is enough, so one is retained and the choice is written down rather than
left to a cleanup that runs on a timer.

**D8. Offline operation is a first-class path.** With no network, an already-installed managed
runtime is used. If none exists, the diagnostic says which runtime was looked for, where it
would have been installed, and that the network was unreachable — not a generic failure.

*Implemented.* Resolution reads the environment and the filesystem and never reaches the
network, so an installed runtime is used offline with no special case; the test
`anInstalledRuntimeResolvesWithoutAnyNetwork` records that. The diagnostic gained a
`Reachability` seam: `explainFailure` consults it only when a managed home is known, because a
home that is not known would not be downloaded into. The real probe asks whether any acquisition
host answers, with a two-second bound per host, and reports "unreachable" for a host that throws.
A test supplies the answer instead, so both branches are asserted on a machine whose own network
state is irrelevant. The message states reachability; it never retries or falls back, because
nothing in this resolution path downloads anything to fall back from.

*Implemented.* `RuntimeCompatibility` runs the three checks against the candidate JVM and
returns a verdict rather than throwing. Each check has a sixty-second bound, so a JVM that hangs
is refused instead of stalling the install, and output is compared as well as exit status
because a JVM that exits zero after printing the wrong thing has not run the program. The
database check is reported and not enforced: a missing native SQLite library is a property of
the machine, and refusing an otherwise working JVM for it would reject a runtime on which every
other program runs. `DOCUMENTED_CHECK_COUNT` exists so the documented count is itself asserted
rather than drifting from the code. Eight tests in `RuntimeCompatibilityTest` cover a JVM that
prints the wrong output, one that hangs, one that passes, one without a database library, a
missing jar and an unstartable check; dropping the output comparison fails
`refusesAJvmThatPrintsTheWrongOutput`.

The installer runs this check only when it has the jar built from the release being installed,
which is the one case where the answer is about *this* Oqthara rather than a guess. That seal is
tested by `refusesAJvmThatReportsTheFloorAndCannotRunOqthara`: the version probe is told to
answer 21 while the extracted `bin/java` fails every program, and nothing is activated.

**An executable bit is part of the archive contract.** Writing these tests found a defect in D4
that no amount of reading had exposed. The Unix mode is read from the central directory to
identify symbolic links, and the execute bits in that same field were being discarded, so a real
JDK archive — which marks `bin/java` 0755 — installed a launcher that could not be executed.
The install would report success and every later command would fail with a permission error,
which is the worst place to discover the problem. `SafeArchive` now records which entries carry
any execute bit and applies owner-execute after the whole archive is written, so a tree that
failed partway is never left looking prepared. Only `0111` is honoured: setuid and setgid are
deliberately ignored, because honouring them would let an untrusted archive install a binary
that runs with the installer's privileges. Because a ZIP built without a Unix mode carries none
at all, the installer also makes the one path it is about to execute executable before probing
it, and refuses if that cannot be done.

**D9. Oqthara compatibility is a separate question from JDK availability.** Java 21 LTS is the
floor because `build.gradle.kts:51-52` targets it. A vendor's release date is not a compatibility
statement, so the manager validates a candidate by running the jar built from the release being
installed: `--version`, then a small fixed set of conformance programs chosen because they
exercise the bytecode backend, the standard library and the JNI database path. It is not the full
corpus — 212 executable files is too much to run before every install — and the ADR says so
rather than implying a stronger check than exists.

This is not hypothetical. [ADR-0002](0002-ci-split.md) records that the build failed on JDK 25
with `Unsupported class file major version 69`, because the Gradle release in use at the time
could not parse that class-file format. A JVM that was current, published and available from its
vendor could not build Oqthara. Any rule that ranks "newest" above "known-compatible" would have
selected it, which is why D1 validates rather than merely locates.

That specific failure has since been fixed by a Gradle upgrade, and CI builds and tests on JDK 25
today, so ADR-0002 is evidence of the *class* of failure and not of a current incompatibility.
The floor stays at 21 regardless: `build.gradle.kts:58` sets `options.release = 21`, so the
artefacts target 21 and must run on a 21 runtime even when built by a newer one. Compiling on a
JVM and targeting its floor are different questions, and the manager answers the second.

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
