# ADR-0064 — `use` binds a class value, resolved when compiling

**Status:** Accepted
**Date:** 2026-11-16
**Related:** ADR-0062 (a diagnostic names the construct, which the old fallback reason
violated), [`backend.md`](../spec/backend.md) §The current boundary (item 2, which this record
closes), [`alpha-5-java-bridge-capability-inventory.md`](../governance/alpha-5-java-bridge-capability-inventory.md)
A6.1, `Interpreter.loadClass`, `Lowering`, `BytecodeCompiler.emitConstant`

## Context

`oq run` and `oq build` disagreed about the same program, and said so in a way that blamed the
writer:

```
$ cat m.oq
use java.lang.Math
print Math.abs(-3)

$ java -jar oq.jar run m.oq
3

$ java -jar oq.jar build m.oq
...
codepath: interpreter launcher (variable 'Math' is used before assignment)
...
```

The answer was right because the jar fell back to the interpreter. The reason was wrong twice
over. `use` never assigned anything, so there was no unassigned variable to blame; and the
message is the same one a genuine `print xyz` produces for a name that was never mentioned
before, where the interpreter says `'xyz' is not defined`. A reader who trusts the `codepath:`
line goes looking for the missing assignment that does not exist. That is exactly the failure
ADR-0062 forbids.

Underneath the wrong word was a real gap: `Lowering` refused `Ast.UseStmt` outright, so no
compiled program could call Java. `docs/spec/backend.md` recorded it as boundary item 2.

## Decision

**`use` binds a class value, and the class is resolved once, while compiling.**

`Ast.UseStmt` is lowered to `Ir.Const(theClass)`, and a `Ast.Var` naming a `use`-bound simple
name lowers to the same constant instead of `Ir.Load`. `BytecodeCompiler.emitConstant` gains a
`Class<?>` case that emits `LDC_W` of a `CONSTANT_Class` through the `emitClassConstant` and
`pool.classRef` path that already existed for other class references.

Four things follow, and three of them needed no new code at all, because the runtime already
handled a `Class<?>` receiver:

| Capability | Path | New code |
|---|---|---|
| Static method `Math.abs(-3)` | `Ir.MethodCall` → `Methods.interop` → reflective `JavaBridge.callStatic` | none |
| Static field `Integer.MAX_VALUE` | `Ir.GetField` → `DataValues.get` → `JavaBridge.readField` | none |
| `print Math` | `Ir.Call("print")` → `Builtins` → `Values.display` | none |
| Construct `ArrayList()` | `Ir.CallValue` → `Callables.call` | **one branch** |

Only `Callables.call` was missing the interpreter's rule. It accepted an `OqCallable` and said
`'ArrayList' is not a function` for anything else, where `Interpreter.invoke` constructs a
`Class<?>`. The new branch delegates reflectively to `JavaBridge.construct`, mirroring how
`Methods.interop` already reaches `JavaBridge` from a module that cannot depend on it.

### Why the class is resolved while compiling

The interpreter calls `Class.forName` at run time. A compiled jar could too, but then a typo in
a `use` path becomes a run-time failure in a program that built successfully, and the compiler
has no chance to report it. Resolving in `Lowering` means `use java.lang.Mathh` fails the build
with the interpreter's own wording:

```
error: cannot find Java class 'java.lang.Mathh'
help: Check the package name, for example: use java.time.LocalDate
```

The candidate order is copied from `Interpreter.loadClass`, including the rule that
`java.lang.` and `java.util.` are only tried when the path contains no dot. A `use` whose path
has a dot is used exactly as written.

Emitting `CONSTANT_Class` rather than a string also means the jar resolves the class from
whatever JDK runs it. `unzip -l m.jar | grep java/lang/Math` is empty: the JDK's own classes are
never packaged, and never need to be.

### `use` is sequential, and the lowering registry is sequential with it

The interpreter registers a class as the statement executes, so a name used before its `use` is
`'Math' is not defined`, and a `use` inside a block is visible at top level afterwards if that
block ran.
The registry that `Lowering` consults is therefore a single ordered set filled as statements are
lowered, deliberately **not** the hoisting `localScopes` deque used for `fun` and `data` names.
The two notions look alike and behave differently; conflating them would make a program compile
that the interpreter refuses.

Any variable binding of the same name shadows the class, matching the interpreter's
`env.hasLocal` → `javaTypes` order.

### A `use` below the top level is refused

The interpreter registers a class as the statement executes, and `javaTypes` is
interpreter-wide, so a `use` that runs inside a block is visible afterwards at top level. The
registration is conditional on the path being taken, not on scoping: of

```
if true
  use java.lang.Math
end
print Math
```

prints `class java.lang.Math`, while the same program with `if false` fails with
`'Math' is not defined`. So the leak is not a scope rule that can be modelled statically —
whether the name exists depends on a value the compiler cannot read.

`Lowering.block()`, `Lowering.function(...)` and `Lowering.lambda(...)` each lower into a
different place than `main`, and none of them has a way to publish a name into the enclosing
method's constant table on the path that reached them. Modelling the leak would need a registry
shared across generated methods and initialised on whichever path ran first, which is a second
mechanism for one statement that no program in the corpus uses: the only `use` in `examples/`
is `examples/interop.oq` line 1, and `conformance/` contains none at all, top level or
otherwise.

So a `use` at depth greater than zero is **registered, then refused**:

```
'use' only compiles at the top level
```

and the jar falls back to the interpreter, which runs the program correctly. This is the same
choice ADR-0031 made for other constructs: refuse truthfully rather than compile something
subtly different.

Registering before refusing is load-bearing, not tidiness. `BytecodeCompiler.plan()` throws
`variable 'x' is not defined in this scope` when it sees a load of a name that was never
registered, and it reaches that load before it reaches the refusal opcode. Refusing without
registering therefore masked the real reason behind a message blaming the reader's variable,
which is exactly the misdirection ADR-0062 forbids. A refusal forces interpreter fallback, and
the interpreter resolves the name itself, so a registration that is never used costs nothing.

The depth counter increments in all three of `block()`, `function(...)` and `lambda(...)`
because each lowers its body somewhere other than `main`, and none of them goes through the
others.

### The `used before assignment` message is rewritten

`BytecodeCompiler.lookup` raised `variable 'x' is used before assignment` for any name not
visible in the current method, including names that were never assigned anywhere. With `use`
compiling, that message is no longer reachable for Java classes, but it was still wrong for
`print xyz`. It now reads

```
variable 'xyz' is not defined in this scope
```

which is true of both a name that was never declared and one that is out of scope here, and
does not claim an assignment the writer never wrote.

## Consequences

Compiled programs call Java. `Math.abs`, `Integer.MAX_VALUE`, `ArrayList()` and `print Math`
produce byte-identical output under `oq run` and `oq build`, and `docs/spec/backend.md`
boundary item 2 is closed.

Overload selection, argument conversion, and error hints are the interpreter's, reached
reflectively, so the two backends cannot drift.

A `use` statement still costs nothing at run time beyond one `LDC`, and the class it names is
never copied into the jar.

`use` inside a function body is refused by name, so a program that does it keeps working through
the fallback while the reason on the `codepath:` line says what is actually unsupported.

`JarBuilder.needsDatabaseDriver` decides whether to bundle a JDBC driver by matching source text
for `use`, not IR, so it stays sound: it is now conservative in the other direction too, since a
compiled `use java.sql.DriverManager` does reach the class. Its comment claiming `use` compiles
to `Class.forName` is corrected in the same change.

Reflection off a class value (`d2_reflection_refused.oq`) is untouched and still refused, since
that is A6.2's separate question.
