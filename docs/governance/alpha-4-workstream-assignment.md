# Alpha 4 workstream assignment

Directive §4.1 defines twelve workstreams. The repository defines six agent roles in
`.github/agents/*.agent.md`. This document maps one onto the other, names the artefacts each
role actually produces, and states plainly where the mapping does not reach.

The mapping is descriptive, not aspirational. A table row that names a role means that role's
scope, as written in its own agent file, covers the work — not that the work is finished. A row
marked **Unowned** means no role's scope names the work at all, and a proposal follows in
[Where the mapping does not reach](#where-the-mapping-does-not-reach).

This closes the `§4.1 workstream assignment` row of
[`alpha-4-requirement-matrix.md`](alpha-4-requirement-matrix.md).

## The twelve workstreams and their owners

| # | Workstream | Primary owner | Supporting roles | Artefacts the role produces | Coverage |
|---|---|---|---|---|---|
| 1 | Language Architecture & Syntax | `oq-hive-language-syntax` | `oq-hive-architecture-governance`, `oq-hive-development` | `oq-core/src/main/java/dev/oqthara/core/Lexer.java`, `Parser.java`, `Ast.java`; `docs/spec/language.md`; `conformance/*.oq`; `docs/adr/0001-syntax.md`, `0013-destructuring.md`, `0014-try-as-an-expression.md`, `0026-range-membership-follows-the-walk.md` | Covered |
| 2 | Type System & Values | `oq-hive-development` (de facto) | `oq-hive-language-syntax` | `oq-runtime/src/main/java/dev/oqthara/runtime/Values.java`, `Value.java`; `docs/adr/0015-type-families.md`, `0017-equality-and-hashing.md`, `0007-boxed-values.md`; `docs/spec/language.md` "Types" | **Under-covered** — no role names types |
| 3 | Collections & Iteration | `oq-hive-development` | `oq-hive-testing-security` | `oq-stdlib/src/main/java/dev/oqthara/stdlib/MethodResolverImpl.java`; `oq-runtime/src/main/java/dev/oqthara/runtime/{OqTuple,OqSet,OqFrozenSet,OqRange,OqIterator}.java`; `docs/adr/0020-iterator-protocol.md` through `0024-text-is-a-sequence-of-code-points.md`; `docs/governance/alpha-4-9-7-collection-evidence.md`; `bench-jmh/`; `scripts/bench-backends.py` | Covered |
| 4 | Compiler & Bytecode | `oq-hive-development` | `oq-hive-testing-security` | `oq-compiler/src/main/java/dev/oqthara/compiler/{Lowering,BytecodeCompiler,ClassFileWriter,JarBuilder,Ir,Backends}.java`; `docs/spec/backend.md`; `docs/adr/0003-bytecode.md`, `0006-classfile-writer.md`; `oq-cli/src/test/java/dev/oqthara/cli/BytecodeCompilerTest.java`; `oq-compiler/src/test/java/dev/oqthara/compiler/LoweringTest.java`; `oq-compiler/src/fuzz/java/dev/oqthara/compiler/PipelineFuzzTest.java` | Covered |
| 5 | Interpreter & JVM Runtime | `oq-hive-development` | `oq-hive-testing-security` | `oq-core/src/main/java/dev/oqthara/core/Interpreter.java`; `oq-runtime/src/main/java/dev/oqthara/runtime/{OqTask,OqMutex,OutputBudget,WaitedBudget,OqHost}.java`; `oq-core/src/test/java/dev/oqthara/core/NestingLimitTest.java`; `docs/adr/0039-executors-and-cancellation.md` | Covered |
| 6 | JavaBridge | `oq-hive-development` | `oq-hive-testing-security`, `oq-hive-architecture-governance` | `oq-core/src/main/java/dev/oqthara/core/JavaBridge.java`; `oq-core/src/test/java/dev/oqthara/core/JavaBridgeVarargsTest.java`, `JavaBridgeFieldTest.java`; `docs/adr/0032-static-fields-and-varargs.md`; `docs/spec/language.md` "Interoperability with Java" | Covered |
| 7 | Core Language Features | `oq-hive-development` | `oq-hive-language-syntax` | `docs/adr/0027-classes-and-contracts.md`, `0028-data-evolution.md`, `0029-closures-and-modules.md`, `0030-docstrings.md`; `oq-cli/src/test/java/dev/oqthara/cli/ClassTest.java`, `DataTest.java`; `conformance/228-classes-and-contracts.oq`, `conformance/229-immutable-data.oq` | Covered |
| 8 | Standard Library | `oq-hive-development` | `oq-hive-testing-security`, `oq-hive-architecture-governance` | `oq-stdlib/src/main/java/dev/oqthara/stdlib/{Stdlib,Builtins,DatabaseModule,Json,MethodResolverImpl}.java`; `docs/stdlib/reference.md`; `oq-cli/src/main/java/dev/oqthara/cli/DocGenerator.java`; `docs/adr/0033-encoding-and-hashing.md` through `0040-database-support.md`; `examples/urls.oq`, `hashing.oq`, `concurrency.oq`, `database.oq` | Covered |
| 9 | Runtime Manager & Distribution | **Unowned** | — | Artefacts exist with no owning role: `Dockerfile`, `.github/workflows/docker.yml`, `.github/workflows/release.yml`, `scripts/install.sh`, `scripts/install.ps1`, `scripts/tests/`, `packageApp` in `oq-cli/build.gradle.kts` | **Unowned** |
| 10 | Security Engineering | `oq-hive-testing-security` | `oq-hive-architecture-governance` | `SECURITY.md`; `docs/spec/fuzzing.md`; `oq-core/src/fuzz/java/dev/oqthara/core/`, `oq-stdlib/src/fuzz/java/dev/oqthara/stdlib/`; `oq-runtime/src/main/java/dev/oqthara/runtime/OqHost.java`, `OutputBudget.java`, `WaitedBudget.java`; `.github/workflows/codeql.yml`; `docs/governance/alpha-4-threat-model.md` | Covered |
| 11 | QA & Conformance | `oq-hive-testing-security` | `oq-hive-review-escalation` | `conformance/`; `scripts/verify-conformance.py`; `scripts/gen_conformance.py`; `oq-cli/src/test/java/dev/oqthara/cli/ConformanceTest.java`, `ExamplesRunTest.java`; `.github/workflows/ci.yml`, `.github/workflows/nightly.yml`; `.circleci/config.yml` | Covered |
| 12 | Developer Experience & Website | `oq-hive-development` (CLI/LSP only) | — | `oq-cli/src/main/java/dev/oqthara/cli/{Main,OqRunner,Formatter,Repl,DocGenerator,Scaffold,Modules}.java`; `oq-lsp/src/main/java/dev/oqthara/lsp/{Main,Server,Hover,Formatter}.java`; `editors/vscode/`; `docs/reference/cli.md`; **no owner** for `scripts/dispatch-site-content.mjs`, `.github/workflows/site-content-dispatch.yml`, `docs/adr/0008-react-website.md`, `0011-standalone-website-repository.md` | **Partly unowned** |

Twelve rows, four verdicts: nine `Covered`, one `Under-covered`, one `Unowned`, one `Partly
unowned`. The matrix's `§4.2 independent review roles` row is separate and stays `BLOCKED`; it is
a governance requirement, not one of the twelve workstreams.

## Where the mapping does not reach

The six roles were written for the code, not for the shipping. Two workstreams fall outside every
one of them, and one is split between two roles without either owning it.

### Workstream 9 — Runtime Manager & Distribution is unowned

No agent file mentions JDK runtime selection, platform installers, update, rollback, or artifact
integrity. `oq-hive-development` names `oq-cli` and the Gradle build, and its scope therefore
brushes against `packageApp` in `oq-cli/build.gradle.kts` — the `jpackage` task that builds a
native app-image — but packaging a launcher is not the same as owning a distribution. The
artefacts that make up the workstream exist and are maintained by nobody in particular:
`scripts/install.sh` (SHA-256 verified download, then a `--version` smoke test),
`scripts/install.ps1`, `scripts/tests/`, `Dockerfile`, `.github/workflows/docker.yml`, and the
Publish/Checksums steps in `.github/workflows/release.yml`.

What the workstream names and the repository does not have: JDK runtime selection (there is no
bundled JRE — both installers require a pre-existing `java`), a signed artifact (integrity is a
SHA-256 published next to the jar in the same release, never a signature), an update or rollback
path (re-running the installer is the only upgrade), and version pinning beyond `OQ_VERSION`.

**Proposal.** Give workstream 9 to `oq-hive-testing-security`, with `oq-hive-development`
supporting. Supply chain is already the security role's remit — it owns `.github/workflows/` and
the host-access posture — and an installer is a supply-chain artefact before it is a packaging
convenience. `oq-hive-development` keeps `packageApp` and the jar layout because they are Gradle
work.

### Workstream 12 — Developer Experience & Website is split and partly unowned

The developer-experience half is genuinely covered. `oq-hive-development` lists `oq-cli` and
`oq-lsp`, and `oq-hive-language-syntax` lists `editors/`. The CLI, the formatter, the REPL, `oq
doc`, the scaffolder, the language server and the VS Code extension all have an owner.

The website half has none. `scripts/dispatch-site-content.mjs` and
`.github/workflows/site-content-dispatch.yml` announce content changes to
`Oqthara/Oqthara.github.io`; `docs/adr/0008-react-website.md` and
`docs/adr/0011-standalone-website-repository.md` record why the site lives in another repository.
Nothing in the six agent files claims any of it, and the site repository is outside every role's
working checkout.

**Proposal.** Split the workstream explicitly. CLI, LSP, formatter, `oq doc`, the scaffolder and
`editors/` stay with `oq-hive-development` and `oq-hive-language-syntax` as today. The dispatch
path and the documentation pipeline go to `oq-hive-architecture-governance`, because the dispatch
is a publication contract between two repositories and that role already owns cross-record
coherence; `oq-hive-testing-security` reviews the token scope. If the directive prefers a single
accountable role, `oq-hive-orchestrator` is the alternative — it is the only role with `agent`
and `web` tools and it already owns integration and reporting.

### Workstream 2 — Type System & Values is under-covered

No role names the type system. It survives because `oq-hive-development` owns `oq-runtime`, where
`Values.java` and `Value.java` live, and because `oq-hive-language-syntax` owns
`docs/spec/language.md`, where the type families are specified. That is two halves of one
workstream with no role accountable for the whole. The language has no static types, so there is
less to cover than the workstream's name suggests — assignability and inference have nothing to
attach to — but "there is less" is not the same as "someone owns it".

**Proposal.** Add the type system to `oq-hive-language-syntax`, which already owns the
specification of the type families, and keep the runtime representation with `oq-hive-development`
as it is today. This costs no new role.

## Accountability rule

A workstream owner is accountable for the correctness of that area: for the tests that pin its
behaviour, for the ADR that records its decisions, and for telling the truth about what is
missing. Accountability here means "the role that must answer for this", not "the role that has
the authority to approve it".

It does not mean independent review. This repository has **one human maintainer**, recorded in
[`remediation/solo-maintainer-exception.md`](remediation/solo-maintainer-exception.md), which
lowered the required approval count from 1 to 0 on ruleset `26650412` precisely because a second
approver does not exist. Every Hive role runs on the same custom-endpoint model,
`deepseek-ai/deepseek-v4.1-flash-1017`, pinned in each agent file and recorded in
[`README.md`](README.md) as OQ-DEC-2026-005. Two role names in one table are two prompts against
one model endpoint, not two reviewers. A `oq-hive-review-escalation` verdict is therefore
supplemental assurance about a specific diff; it is never independent human review, and no
document in this repository may describe it as such.

The consequence for this table is direct: the nine `Covered` verdicts above assert that a named
role's scope includes the work. They do not assert that anyone other than the maintainer has
looked at it.

## Keeping this document current

A workstream may change owner only when the agent file that defines the role changes in the same
pull request, or when this document records why the mapping is being read differently. Adding an
agent adds a column of accountability and requires re-reading all twelve rows. The
[`alpha-4-risk-register.md`](alpha-4-risk-register.md) cites workstream numbers from this table,
so renumbering a workstream is a change to two documents.

**Last verified:** `b5813d1` (merge of PR #84), 2026-11-10.
