# Architecture decisions

Every structural decision in this repository has a stable identifier, a state, and a
rationale. The state machine is fixed:

`PROPOSED` → `UNDER_REVIEW` → `APPROVED` | `REJECTED` | `DEFERRED`, and an approved
decision may advance to `IMPLEMENTED` and then `VERIFIED`.

Records are immutable once `APPROVED`. A later decision supersedes an earlier one rather
than editing it, exactly as in [`docs/adr/`](../adr/).

## Hive decision records

| ID | Title | State |
| --- | --- | --- |
| OQ-DEC-2026-001 | Agent architecture uses the native custom agent format | APPROVED |
| OQ-DEC-2026-002 | Per-agent model pinning is not configured | DEFERRED |
| OQ-DEC-2026-003 | Governance records live in `docs/governance/` | APPROVED |
| OQ-DEC-2026-004 | Baseline audit is read-only and evidence-bound | APPROVED |

## OQ-DEC-2026-001 — Agent architecture uses the native custom agent format

**State:** APPROVED

**Context.** The repository already describes an orchestrator and eleven specialists in
`.kilo/agents/*.md`. Those files use a different product's frontmatter (`mode:`, `steps:`,
`permission:`, a `robin/` model prefix) and are not read by the agent runtime used here.
Leaving the described architecture unloadable would mean the repository documents an
orchestration that cannot run.

**Decision.** The Hive is defined as six custom agents in `.github/agents/*.agent.md`,
which is the format this environment loads from the repository root. The six roles are:

| Agent | Mirrors `.kilo/agents/` roles |
| --- | --- |
| `oq-hive-orchestrator` | `oq-orchestrator` |
| `oq-hive-language-syntax` | `oq-compiler` (syntax surface) |
| `oq-hive-development` | `oq-runtime`, `oq-tooling`, `oq-compiler` (implementation) |
| `oq-hive-testing-security` | `oq-qa`, `oq-dogfood`, `oq-ci-release` |
| `oq-hive-review-escalation` | `oq-reviewer`, `oq-debugger` |
| `oq-hive-architecture-governance` | `oq-architect`, `oq-scribe`, `oq-docs` |

**Alternatives rejected.** (a) Keep only `.kilo/agents/`: rejected because those files do
not load, so the architecture would exist on paper only. (b) Delete `.kilo/agents/`:
rejected as an unrelated destructive change. (c) Eleven agents instead of six: rejected
because several `.kilo` roles overlap and the six-role split maps cleanly onto the audit
areas that actually need separation.

**Consequences.** The two directories now coexist and describe the same intent in two
formats. `.kilo/agents/` remains authoritative for the Kilo product; `.github/agents/` is
authoritative here. A future decision should merge or retire one of them.

**Evidence.** `.kilo/agents/oq-orchestrator.md:1-25` (the unloadable frontmatter);
`.github/agents/oq-hive-orchestrator.agent.md` (the loadable replacement).

## OQ-DEC-2026-002 — Per-agent model pinning is not configured

**State:** DEFERRED

**Context.** The intended design pins a DeepSeek model per agent. The `model` field in
custom-agent frontmatter is only honoured when the runtime can resolve the named model.
The CLI's own model enumeration does not include any DeepSeek identifier, and
`copilot --model 0c48b4ee-e62f-4e6a-ab1f-c8d2c83b6177/deepseek-ai/deepseek-v4.1-flash-1017`
fails with `Model "..." from --model flag is not available.` The `.kilo` files use a
`robin/deepseek-ai/deepseek-v4.1-flash-1017` identifier that belongs to the other product.

**Decision.** No `model` field is set on any Hive agent. Every agent inherits the session
model. This is recorded as a limitation, not presented as a configured capability.

**Why deferred rather than rejected.** The runtime does serve a DeepSeek model to this
session, so per-agent pinning may become available without any change to this repository.
It is not available today.

**Consequences.** Agents cannot be tuned individually by model. Anything that depends on a
specific model's behaviour is unreliable and must not be designed around.

**Evidence.** `copilot --model <deepseek-id>` → `Model "..." from --model flag is not
available.`; `copilot help config` model enumeration contains no DeepSeek entry.

## OQ-DEC-2026-003 — Governance records live in `docs/governance/`

**State:** APPROVED

**Context.** `AGENTS.md` places working state in `.oqthara-ai/`, which is gitignored.
Baseline audits and decision records are not ephemeral working state: they are the
institutional memory a reviewer needs six months from now. Putting them in a gitignored
directory would discard exactly the artifact the task exists to produce.

**Decision.** Decision records and audit reports live in `docs/governance/`, tracked in
git, and are registered in `docs/catalog.json` like every other document.

**Alternatives rejected.** `.oqthara-ai/` only: rejected because the content would never
be published or reviewed. Repository root: rejected because `docs/` is already the single
place documentation lives, and `docs/README.md` says so.

**Consequences.** Adding a governance document requires regenerating the catalog in the
same change; CI enforces that through `scripts/gen-docs-catalog.py --check`. The single
`# ` heading is a convention the catalog generator relies on for titles, not an assertion
it makes — a missing heading silently falls back to the file name.

**Evidence.** `docs/README.md:25-33` (the adding-a-document procedure); `.gitignore:9`
(`.oqthara-ai/` is ignored).

## OQ-DEC-2026-004 — Baseline audit is read-only and evidence-bound

**State:** APPROVED

**Context.** A baseline audit that changes the thing it measures produces a baseline of
its own edits. An audit that reports claims without command output produces a document
nobody can check.

**Decision.** The baseline audit changes no implementation file. Every finding cites a
`path:line` or a pasted command result. Anything a specialist could not inspect is marked
`NOT INSPECTED` rather than assumed.

**Consequences.** The audit can report gaps honestly, including gaps in its own coverage.
Coverage is quoted only where the build measures it: JaCoCo is configured and `coverageGate`
enforces an 80% line floor on `dev/oqthara/core`. No other module has a floor, so this audit
never generalises the one number it can prove.

**Evidence.** `docs/governance/audit/baseline-2026-11.md`.
