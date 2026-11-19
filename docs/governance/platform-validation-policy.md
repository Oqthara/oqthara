# Cross-platform validation policy

**Status:** active · **Decision:** [ADR-0074](../adr/0074-platform-validation-requires-the-platform.md)
**Required-context list verified:** 2026-11-19 against the `main-protection` ruleset (id `26650412`)

Finding 20 of [`reviews/PR-272.md`](reviews/PR-272.md) was a Windows-only defect that survived a
green local suite, nine green gates and a green conformance corpus. This policy is the enforcement
that finding asked for. It states which environments a platform-dependent test must run in, and
`scripts/check-platform-coverage.py` fails the build when the statement and the code disagree.

A platform is validated only by a run on that platform. A green local suite is a statement about
one of six legs.

## Forbidden by this policy

| Anti-pattern | Why it is a defect | Detected by |
| --- | --- | --- |
| Declaring merge-ready while a platform leg is pending | The merge set is the platform set; a pending leg is not evidence | ruleset `strict_required_status_checks_policy` |
| A platform-conditional test absent from this table | The cheap way out is to leave the table empty | check 1, check 2 |
| A test declaring `run` on a platform whose body then skips it | The row claims coverage the run did not produce | check 3 |
| A row naming a test that no longer exists | A stale row protects nothing while reading as coverage | check 4 |
| A required context that no job produces | An unsatisfiable requirement blocks every merge silently | check 5 |
| `assumeTrue(false, …)` guarding a platform difference | It skips everywhere, so the green tick proves nothing anywhere | check 3 |

## Dispositions

Every test below carries exactly one, and both are legitimate.

- **`run` on an environment** — the test executes to a real assertion there. `skipped=0` is required
  for that suite on that environment.
- **`skip` on an environment** — the test is not applicable there and says why. A skip is *not* a
  pass, and it is *not* coverage. It must never be counted as validated.

`FileOperationsTest`'s FIFO tests are the model: a FIFO cannot be created reliably on Windows, so
they skip there and run on Linux and macOS. Driving them to "pass" on Windows would mean faking the
capability, which is the defect finding 20's sibling already recorded.

## Declared environment guards

One row per test that carries an `assumeTrue` / `assumeFalse` guard. `Runs on` and `Skips on` are
the environments the test must execute in and must not. The gate proves the guard and the row agree.

| Test | File | Runs on | Skips on | Why |
| --- | --- | --- | --- | --- |
| `copyStopsAtTheLimitEvenWhenTheReportedSizeIsSmall` | `FileOperationsTest` | linux, macos | windows | a FIFO cannot be created reliably on Windows |
| `aRefusedCopyIsChargedForTheBytesItWrote` | `FileOperationsTest` | linux, macos | windows | a FIFO cannot be created reliably on Windows |
| `copyRefusesADanglingSymlinkTargetAndLeavesItAlone` | `FileOperationsTest` | linux, macos, windows | — | skips only on a filesystem without symbolic links |
| `aCopyThatFailsBeforeOpeningTheTargetLeavesAnExistingLinkIntact` | `FileOperationsTest` | linux, macos, windows | — | skips only on a filesystem without symbolic links |
| `saveAndAppendWriteThroughASymbolicLinkRatherThanReplacingIt` | `FileOperationsTest` | linux, macos, windows | — | skips only on a filesystem without symbolic links |
| `anUnreadableDirectoryMakesWalkAUserErrorNotAnInternalOne` | `FileOperationsTest` | linux, macos | windows | needs POSIX attribute views to make a directory unreadable |
| `copyTreeRefusesMidCopyWhenAFileYieldsMoreThanItReported` | `FileSystemModuleTest` | linux, macos | windows | a FIFO cannot be created reliably on Windows |
| `anUnreadableDirectoryIsAUserErrorNotAnInternalOne` | `GlobTest` | linux, macos | windows | needs POSIX attribute views to make a directory unreadable |
| `aProbeThatNeverExitsIsRefused` | `RuntimeResolverTest` | linux, macos | windows | the fake JVM is a POSIX shell script with an execute bit |
| `aProbeThatFloodsItsOutputIsRefused` | `RuntimeResolverTest` | linux, macos | windows | the fake JVM is a POSIX shell script with an execute bit |
| `aWellBehavedProbeStillReportsItsMajor` | `RuntimeResolverTest` | linux, macos | windows | the fake JVM is a POSIX shell script with an execute bit |

## Platform-branching suites

A suite that branches on the operating system and passes on **every** branch is the most deceptive
shape in the repository: the green tick does not say which branch ran. Both outcomes are correct, so
the run is only evidence for the branch that executed. These suites must therefore run on **every**
platform, and the gate refuses a file that branches without being listed here.

| Suite | Runs on | What differs |
| --- | --- | --- |
| `RuntimeCompatibilityTest` | linux, macos, windows | the fake JVM is a batch file on Windows and a shell script elsewhere |
| `RuntimeInstallerTest` | linux, macos, windows | the executable name is `java.exe` on Windows and `java` elsewhere |
| `RuntimeRetentionTest` | linux, macos, windows | the executable name is `java.exe` on Windows and `java` elsewhere |
| `SafeArchiveTest` | linux, macos, windows | case-insensitive filesystems reject the duplicate; others accept both files |
| `HostBuiltinLimitsTest` | linux, macos, windows | fixtures are `.bat` under `cmd.exe` on Windows and `#!/bin/sh` elsewhere |

## Required merge contexts

`main-protection` requires these contexts with `strict_required_status_checks_policy: true`, so a
pull request merges only when every one reports success against its current head. The set is a
repository setting, which no gate in this tree can read; the gate checks the half it can see, that
each documented context is a job the workflow actually produces.

| Context | Workflow | Proves |
| --- | --- | --- |
| `ubuntu-latest / JDK 21` | `ci.yml` | full suite on Linux, and every documentation and governance gate |
| `ubuntu-latest / JDK 25` | `ci.yml` | full suite on Linux under the newer LTS |
| `windows-latest / JDK 21` | `ci.yml` | path, filesystem and process semantics on Windows |
| `windows-latest / JDK 25` | `ci.yml` | the same, under the newer LTS |
| `macos-latest / JDK 21` | `ci.yml` | case-insensitive filesystem and BSD userland |
| `macos-latest / JDK 25` | `ci.yml` | the same, under the newer LTS |
| `ubuntu-latest / installer tests` | `ci.yml` | the POSIX installer path |
| `windows-latest / installer tests` | `ci.yml` | the PowerShell installer path |
| `Bytecode backend (JDK 25)` | `ci.yml` | the classfile backend is selected |
| `Standalone jar` | `ci.yml` | the shipped artifact runs a program |
| `analyze` | `codeql.yml` | the security analysis |
| `ci/circleci: jazzer-fuzz` | `.circleci/config.yml` | the fuzz targets |

### Not required, and therefore not evidence

Two jobs run on every pull request and are **not** in the required set:

| Job | What it proves | Why this is a gap |
| --- | --- | --- |
| `Display-backed Swing` | a frame is realized under a real X server | no merge-blocking job proves it, so a broken change cannot fail a required context |
| `ci/circleci: macOS / JDK 21`, `JDK 25` | a second opinion on macOS | the GitHub `macos-latest` legs already hold that requirement |

The `display-backed` gap is recorded rather than fixed here, because changing the required set is a
repository-settings change that this policy cannot perform or verify. It is listed so the gap is
stated where the rest of the evidence is stated.

## What this policy does not claim

- **It does not claim Windows, macOS and Linux behave the same.** They do not, which is the whole
  reason the matrix has six legs.
- **It does not claim Xvfb proves desktop compositing.** The display-backed job maps a window and
  reports it displayable; it runs no window manager, so compositor behaviour stays a desktop
  property.
- **It does not claim a skipped test passed.** A skip is recorded as `skipped` in the JUnit XML and
  is absent from the pass count by design.
- **It does not replace running the platform job.** Nothing in this tree can; a local green suite
  remains one platform's evidence.
