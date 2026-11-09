# 0014 — `try` produces a value

Status: accepted (2026-11-09, Alpha 4 Phase 10)

## Context

Alpha 3 has `try` only as a statement. `x = try ... end` fails to parse: `try` is a keyword, and
`primaryInner` ends by rejecting every keyword that is not `true`, `false`, `nothing` or `fun`,
so the assignment reports `expected an expression` with the help "Found keyword 'try' instead".

The Alpha 4 directive (§13) asks for expression-position `try` while preserving the statement
form. It requires the successful and handler values to be specified, along with nested
behaviour, evaluation order, interaction with `return`/`break`/`continue`, and interpreter
against bytecode equivalence.

The directive also asks for "common result type and inference". Oqthara has no static type
system and no type checker: `type_of` is a run-time function, and there are no annotations.
There is therefore no result type to infer or unify, and the directive's own "do not assume
compile-time constant evaluation unless designed and justified separately" applies with the
same force here. This ADR says so explicitly rather than inventing a check the language cannot
support — an unenforceable "type" rule would be a diagnostic lie.

Two candidate value rules were considered, and the choice is not free.

The first was the `return` rule: the value is the first `return` leaving either branch. It is
rejected because it makes `return` mean two different things in the same position. In a `try`
statement a `return` leaves the enclosing function; inside a `try` expression it would instead
deliver a value to the expression, so the same word in the same block would do two jobs
depending on how the block was reached. It also cannot be compiled: compiled code emits a real
`ARETURN` for `Ir.Return`, and the enclosing method is the *program*, so the value would leave
`main` rather than the expression. Either behaviour would make the interpreter and the jar
disagree.

## Decision

`try` is both a statement and an expression, with a single rule for the value:

> A `try` expression evaluates to the value of the last statement of the branch that ran, when
> that statement is an expression. A branch that ends in a declaration, an assignment, a loop,
> `return`, `break` or `continue`, or that is empty, produces `nothing`.

This is the rule the directive's "behavior when the try block does not produce a value" clause
presupposes, and it is the only rule that both backends can implement without inventing a
second meaning for `return`. It is a *local* rule: it changes no existing program, because
expression `try` did not previously parse at all.

### Why "the last statement's value"

The competing rule — only `return` produces a value — was rejected above. The last-statement
rule also reads naturally in the cases that motivate the feature:

```
label = try
  describe(item)
catch e
  "unreadable: " + e["message"]
end
```

A branch produces a value only if its final statement is an expression statement. Ordinary
statements do not produce values: `x = 1` is a statement (assignment), not an expression, so a
branch ending in it yields `nothing`. This is deliberately narrow, and it means "the block's
last value" never accidentally leaks an assignment's right-hand side.

`return` inside a `try` expression keeps its existing, single meaning: it leaves the enclosing
function. `break` and `continue` likewise keep theirs. None of them deliver a value to the
expression.

### Why this does not change `if` or `fun`

Function bodies still yield `nothing` without an explicit `return`, and `if` is still a
statement. Those are unchanged decisions, and this ADR does not silently revise them. The
element of inconsistency is real and is recorded here rather than hidden: `try` is the first
block that yields a value, because it is the first block that has a use for one. If `if`
expressions or implicit function returns are added later, they should adopt the same
last-expression rule, at which point this ADR is superseded by one change that covers every
block.

### Grammar and precedence

`try` joins the other keyword-led primaries, before the infix operators:

```
tryExpr := 'try' NEWLINE block 'catch' IDENT NEWLINE block 'end'
```

Because it is a primary, it binds tighter than any infix operator and needs no parentheses
inside an expression: `1 + try ... end` parses as `1 + (try ... end)`. It is also allowed
where any other primary is allowed, including as a call argument and as the right side of an
assignment or `const` declaration.

`endStatement()` is consumed by the `try` form itself, so a `try` expression may be the last
thing on its line but is never followed by more tokens on that line.

### The statement form is unchanged

A `try` at the head of a statement where no value is wanted keeps its current behaviour. The
parser's statement path still produces the statement node, and the value is simply discarded.
No existing program changes meaning; `try` used for its effects prints nothing, as before.

### Both branches are ordinary blocks

The body and handler are blocks, not expression lists. Each still gets its own scope, the
handler still receives the error bound immutably (the "caught error of this `try`" binding),
and the existing diagnostics for `return`/`break`/`continue` outside their contexts are
unchanged.

Branch exit is uniform: `return` from inside a lambda or function, `break`, and `continue`
all unwind the `try` exactly as they do in the statement form. A `break` that leaves a loop
through a `try` expression produces no value, because the expression never completes; the
loop's own control flow wins. Nothing about a `try` expression changes what `return`, `break`
or `continue` mean.

### Evaluation order and side effects

The body runs first, in order. If it completes without an error, the handler does not run at
all — no side effect of the handler is observable. If the body raises an `OqError`, execution
jumps to the handler with the error bound, and any partial effects of the body before the
raise persist, exactly as for the statement form.

The final statement of a branch is evaluated exactly once, in its normal position. Nothing is
evaluated twice and nothing is evaluated early.

### Nesting

A `try` expression may appear inside a body or handler of another `try` expression. The
innermost handler that matches catches first, as with any nested `try`. Only `OqError` is
caught; `return`, `break`, `continue` and Java `Error`s are not, so a nested `try` cannot
swallow control flow.

### Exception mapping and lowering

Only `OqError` is caught, in both backends. Compiled code already routes `OqError` through
the exception table to `Ir.HandlerLabel`/`Ir.CatchValue`, so an expression `try` lowers to the
statement shape with one addition: a temporary local holds the value, each branch stores its
final expression there (or `null` when it has none), and the expression loads it afterwards.
`Ir.TryStart` requires an empty operand stack, and `Ir.Pop` discards a non-final expression
statement's value, so the stack discipline is unchanged. The interpreter evaluates the final
expression and returns its value. Because both consume the same `OqError` type and the same
final-expression rule, the values are identical.

A `try` expression nested inside another expression needs more than that: in
`total + (try ... end)` the left operand is already on the stack when `try_start` is reached,
which the verifier rejects because it cannot describe the frame. The lowerer therefore spools
operands into locals whenever an operand list mentions a `try` anywhere — each operand is
evaluated once, in source order, from an empty stack, stored, and only then reloaded. Programs
without a nested `try` keep the direct stack form, so this costs nothing elsewhere. The
alternative, teaching the framer to carry a non-empty stack across `try_start`, would have
weakened an invariant that every label and jump in the backend relies on.

Compiled code refuses rather than guesses when a `try` expression's handler is unreachable
(`'try' after an unconditional jump`), the same honest fallback the statement form already
uses.

### Interaction with other features

- **`const`:** `const x = try ... end` fixes `x` to the produced value. The `try` itself has
  no effect on mutability.
- **Overloads:** a callable produced by a branch is an ordinary value; overload resolution
  happens at the call site, unaffected by the `try`.
- **Lambdas:** a `return` inside a lambda body nested in a `try` expression returns from the
  *lambda*, not from the `try`. A lambda expression is itself an expression statement, so a
  branch ending in one produces the function value.
- **Collection transformations:** the value composes normally, e.g. `[try ... end, 2]`.

### Diagnostics

The existing `try` diagnostics are reused, so the accepted shapes stay narrow:

- a missing `catch` reports `expected 'catch' after the try block`;
- a handler without a name reports the existing name diagnostic;
- `try` with no `end` reports the existing block diagnostic.

There is deliberately no new "inconsistent branch value" diagnostic, because values are not
compared: each branch stands alone and `nothing` is always a valid result. Inventing one
would imply a static check that this language does not perform.

## Consequences

Expression `try` costs one AST node, one parser entry that shares its body with the statement
form, one temporary local in compiled code, and no new runtime. `return`, `break` and
`continue` keep exactly one meaning each, in both backends.

The cost is a real inconsistency: `try` yields its last expression while `if` and `fun` do
not. That is accepted because `try` is the construct that the directive asks to produce a
value, and because retrofitting block values onto `if` and implicit returns onto `fun` would
change the meaning of existing programs — which §11 and the project's own governance forbid
doing as a side effect of another feature. The inconsistency is recorded in the specification
so it cannot be discovered by surprise.

If block values are later introduced across the language, `try` already conforms; this ADR
should then be superseded rather than quietly reinterpreted.
