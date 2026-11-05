# ADR-0009 — Kilo chat-native orchestration replaces the external supervisor

- **Status:** accepted
- **Date:** 2026-11-05
- **Deciders:** oq-orchestrator, oq-architect
- **Related:** ADR-0002

## Context

Until now, `scripts/ai-watchdog.py` was a launchd job (`dev.oqthara.supervisor`) that spawned
one headless `kilo run --agent oq-orchestrator --auto` per cycle. The model worked, but it had
structural costs:

1. **Two control planes.** The chat and the headless cycle could not see each other. A running
   headless cycle held a lock, refused a second writer, and could edit the same checkout the
   user was inspecting.
2. **No real parallelism.** One invocation owned the tree, so work was sequential by
   construction. The master prompt asked for delegation, but concurrent writers were forbidden
   and there were no isolated worktrees.
3. **Opaque recovery.** When an invocation hit its step limit, the only durable record was
   whatever it happened to write before stopping.
4. **A background process the user did not ask for**, restarting itself through launchd.

## Decision

**The Kilo chat is the single control plane.** There is no external supervisor, no watchdog,
no launchd or systemd unit, and no detached autonomous loop.

- `oq-orchestrator` (primary, in `.kilo/agents/`) owns the continuous loop inside the chat.
- Independent workstreams are dispatched to specialist agents with the real `task` tool,
  concurrently, each in its own git worktree under `.worktrees/<task>/`.
- The primary keeps the main checkout for review, integration and validation.
- Every workstream has explicit file ownership, acceptance criteria, required tests and a
  report format.
- The primary reviews every diff, merges one workstream at a time, reruns the build after each
  merge, then pushes branches and opens PRs.
- `scripts/ai-watchdog.py` is retired. It stays in the tree as historical code only; it must
  not be installed or started. `.oqthara-ai/STOP` remains as an emergency file so the
  operator can still halt work explicitly.

## Consequences

**Better:** one control plane, real parallelism, isolated writers, reviewable diffs, an
auditable conversation, and no surprise background process.

**Accepted costs:** the chat must stay open for work to continue; long builds block the
conversation; more branches and worktrees to clean up; the orchestrator must be disciplined
about file ownership to avoid merge conflicts.

**Rejected alternative:** keep the watchdog and add worktrees inside each headless cycle. That
preserves the second control plane and the restart loop, which is exactly what this ADR
removes.
