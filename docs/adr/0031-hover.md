# ADR-0031: Hover in the language server

- **Status:** Accepted
- **Date:** 2026-11-10
- **Related:** [ADR-0030](0030-docstrings.md)

## Context

ADR-0030 gave declarations a documentation comment and `oq doc --source` a way to render
them. The editor still showed nothing: `oq-lsp` handled `initialize`,
`textDocument/didOpen`, `textDocument/didChange`, `textDocument/didClose`,
`textDocument/formatting`, `shutdown` and `exit`, and the string `hover` did not appear
anywhere in `oq-lsp` — not in a handler, not in the advertised capabilities.

So this was not an extension of existing behaviour. The server had no position-aware
request at all, and nothing in it read the AST for anything other than diagnostics.

The question was how much semantic analysis to build. The plausible answers were a real
resolver over scopes, imports and modules, or a lexical match against the declarations in
the file. The second is smaller and describes what the reader can see; the first is
correct about which declaration a call reaches.

## Decision

**D1. Hover resolves lexically.** The identifier under the cursor is matched against
declaration names in the same file, at top level and inside class and contract bodies.
Shadowing, `use "oq:name"` imports and module declarations are not followed. The answer
therefore describes the declaration the reader can see rather than the one the program
would call, and the specification says so rather than leaving it to be discovered.

**D2. Declarations and builtins are described from different sources.** A declaration is
rendered from its `Docstring` in the same shape `oq doc --source` uses, so the editor and
the generated page cannot disagree. A builtin is described from
`Interpreter.builtins()`, after `Stdlib.install` — the same table the interpreter checks
calls against. The arity hint is found by probing `NativeFunction.accepts(int)`, because
`minArity`/`maxArity` are private and adding accessors for one caller would widen the
runtime API for a display concern.

**D3. "Nothing here" is `null`, not `""`.** The protocol treats a null result as "show
nothing"; an empty string opens an empty popup. A position on whitespace, a name nothing
declares, and a document that does not parse all return null.

**D4. An unparseable document resolves nothing.** Falling through to the builtin table
would describe a name the file does not define and that the user may be halfway through
typing, so the parse failure ends resolution.

**D5. The reply is markdown with an `oq` code block.** The signature goes in a fenced
block so editors highlight it; the summary, parameter bullets and tag lines follow as
prose. Unknown tags render under their capitalised name, matching `oq doc --source`, so a
tag this version does not define is still visible in the editor.

**D6. The range is the identifier's, not the declaration's.** A hover reports the word
under the cursor, so an editor underlines only what it is describing.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| Full scope resolution | Correct about which declaration a call reaches, but needs name resolution over imports, modules and shadowing — a resolver the language does not yet have anywhere, including the interpreter's own tooling |
| Describe only declarations, not builtins | The most common hover in real code is over a standard-library call; leaving it unanswered makes the feature feel broken |
| Add `minArity()`/`maxArity()` accessors | Widens the runtime API for a display concern; `accepts(int)` already answers the question |
| Return `""` when nothing resolves | Opens an empty popup in most editors |
| Reuse `oq doc`'s markdown renderer by importing the CLI | `oq-lsp` would depend on `oq-cli`, which carries the whole command surface |
| HTML instead of markdown | Markdown is what the editors this targets render; HTML is a larger surface for no gain |

## Consequences

- Hovering a local function shows its own comment; hovering a call to a module function
  shows the *importer's* declaration if one exists, or nothing. `use` is not followed.
- A `data` or `class` field described with `@param` renders as a bullet, because the field
  list is passed as the parameter list — the same rule `oq doc --source` uses.
- The first hover in a process installs the standard library into a throwaway interpreter
  and copies its builtin table. That is a one-off cost of a few milliseconds and has no
  effect on a running program, because it happens in the server process.
- `Hover.wordAt` clamps a position past the end of a line or of the file instead of
  failing, because editors do send those.
- Hover is not tested against a real editor in CI. The tests drive the JSON-RPC surface and
  assert on the payload; whether a given editor renders it well is outside what this
  repository can check.
- Signature help — the parameter list shown while typing arguments — remains absent. It is
  a separate request with its own payload shape, and is not implied by hover.
