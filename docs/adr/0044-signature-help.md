# ADR-0044: Signature help in the language server

- **Status:** Accepted
- **Date:** 2026-11-11
- **Related:** [ADR-0031](0031-hover.md), [ADR-0012](0012-overload-resolution.md)

## Context

ADR-0031 gave the language server a position-aware request. `textDocument/hover` answers what a
name is, but nothing answered the other half of the question a user asks while typing a call:
what does this function take, and where am I in its argument list.

The obstacle was not the protocol. It was that the parameter names did not exist. Every builtin
was a name, an arity range and a lambda, and the names of its parameters lived only in the
lambda's body as `args.get(0)`. A signature help that rendered those would have to invent
names, and an invented name is worse than a number: `region` suggests which argument it is and
`arg1` does not, but a wrong `region` is actively misleading.

So the work was in two parts, and the order mattered. Names had to exist before anything could
display them.

## Decision

**D1. Parameter names live beside the function body, not in a separate table.** A builtin is
built with `NativeFunction.of(...)` or one of the two `variadic` overloads and may then be
annotated with `.named("a", "b")`. The names are a field of the same object as the body, so a
builtin cannot carry a name list its own code contradicts — they are edited in one place and
reviewed in one diff. Properties that follow from this:

- Naming is optional. A builtin with nothing to name — zero arity, or unbounded variadic like
  `print` — publishes no names, and signature help shows only its arity.
- The list is immutable and `named` returns a new instance, matching the existing style;
  nothing mutates a registered builtin.
- Names are display-only. `call` never reads them, so a wrong name cannot change what a
  program does. Only what a user reads.

**D2. Every builtin is named or explicitly has nothing to name.** Of 167 global builtins, 150
carry names. The other 17 are three unbounded variadics (`print`, `write`, `write_text`),
thirteen zero-arity functions (`now`, `cwd`, `random`, `mutex` and so on), and one internal
wrapper that is not user-facing. Method builtins are out of scope for the count and for the
feature, per D4. `ParameterNamesTest` enforces this structurally rather than by inspection: a
name list must match the arity it labels, names must be distinct, and an unnamed builtin must
be one whose arity explains the absence. A fixed-arity builtin cannot quietly ship unnamed, and
a name list that shifts by one fails the build.

**D3. Signature help resolves lexically, in the same order as hover.** Declarations in the
document first, then `use` imports through the interpreter's resolver, then the builtin table.
The builtin table and the module walk were extracted from `Hover` into `Symbols`, so the two
requests share one table rather than each building their own: they cannot disagree, and the
standard library is installed once per server rather than once per handler.

**D4. Methods are not answered.** The receiver's type is not knowable from the text before the
dot — `x.slice(` says nothing about whether `x` is text or a list — and the same method name
means different things to different receivers. A signature there would be a guess presented as
an answer, so `.name(` returns null.

**D5. A declaration header is not a call.** `fun add(` describes parameters being defined, not
a call being made, so it returns null. A parenthesis after a control-flow keyword (`if (`,
`while (`) is likewise not a call.

**D6. A document that does not parse still answers.** This is the single most important
property, and the least obvious. The moment a user types an opening parenthesis their file is
unbalanced, so a strict parse fails — and a handler that required one would decline to help at
exactly the moment help was asked for. When the parse fails, a textual scan reads `fun` headers
only, skipping text and comments, and skipping any header whose parameter list has not closed
yet. Every answer it produces is still a name list taken from the source; nothing is inferred.

**D8. The reply names the active parameter by index and marks each label's range.** The
protocol lets an editor either bold one parameter or leave the choice to the server, and doing
both is what makes the reply usable: the index says which argument is being written, and the
offset ranges say where each name sits inside the label, so an editor that renders labels
richly does not have to re-parse the string it was given.

**D7. The cursor scan is one forward pass, not a backward rescan.** Deciding which comma the
cursor is past requires knowing which parentheses are code and which are text or comment. The
first implementation asked that question per character and was quadratic; it now precomputes a
code/text mask in one pass. A plain string ends at a newline, matching the lexer, so an
unbalanced quote cannot swallow the rest of the file.

## Consequences

Signature help and hover can now never disagree about a builtin's shape, because they read the
same table. Adding a builtin without names is caught at build time rather than reviewed by eye.

The cost is that `NativeFunction` carries display data. It is one immutable list and one
builder method, it cannot affect execution, and the structural tests make the failure mode a
red build rather than a wrong label.

Two limitations are accepted and recorded here rather than hidden:

- **Interpolation is not scanned.** `"value {add(1, 2)}"` is a real call and evaluates,
  but a text literal is masked whole, so no help is offered inside one. Scanning interpolation
  would also mean describing parentheses in ordinary prose, which is worse.
- **Overloads are all offered, not filtered by argument count.** An argument count that
  matches no signature usually means the call is half-written, and hiding alternatives at that
  moment removes the help the user is asking for. The active parameter is still reported
  against the widest signature so the cursor stays on a real parameter.
