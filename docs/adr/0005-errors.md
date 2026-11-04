# ADR-0005 — Error message contract

- **Status:** accepted
- **Date:** 2026-11-04
- **Deciders:** oq-architect, oq-debugger

## Context

An audit of 27 representative failures found:

- one **P0 bug**: `break` at top level escaped as `BreakSignal` and the CLI reported
  `internal error: BreakSignal: null` with exit code 70, blaming Oqthara for a user mistake;
- several messages that named the operation but not the fix (`cannot compare text with
  number` with no hint, a bare `invalid value` for bad JSON);
- one message that pointed at `<unknown>` instead of the real source line, because the
  control-flow signal carried no span.

## Decision

Every user error must satisfy a contract that is enforced by tests
(`ErrorMessageContractTest`):

1. **Exit code 1**, never 70. Exit 70 is reserved for "this is our bug", and the message
   must say `internal error`.
2. **A position**: file, line, column, source line and caret, whenever the AST has one.
3. **A `help:` line** when the fix is not obvious from the message alone.
4. **No internal vocabulary** in user-facing messages. `BreakSignal`, `ReturnSignal` and
   Java class names must not leak.
5. **The type is named** when a value has the wrong type, and the message suggests the
   conversion if one exists (`number(...)`, `text(...)`).

Control-flow signals carry the span of the statement that raised them, so a top-level
`break` can be reported at the `break` keyword instead of at `<unknown>`.

## Consequences

- `OqRunner`, the test harness, returns the raw message without the `error:` prefix because
  it has no source to render; the CLI is the component that guarantees the contract.
  Tests therefore assert on content and position, not on the prefix.
- Adding a new stdlib function means adding it to the audit set when its failure is
  user-triggerable.
- The audit list is the seed of a future documentation page: "common errors and fixes".
