# Backend status and limitations

This page is the honest state of `oq-compiler`. It exists so nobody discovers these by
being surprised at runtime, and so the gate for a stable release is unambiguous.

## Two backends, one contract

| Backend | Where | Status |
|---|---|---|
| Interpreter | `oq-core` (`Interpreter`) | **Authoritative.** Runs every conformance case. |
| Class file emitter | `oq-compiler` (`ClassFileWriter`, `JarBuilder`) | **Works.** Emits a real class file and jar. |
| Bytecode compiler | `oq-compiler` (`BytecodeCompiler`) | **Partial.** Real JVM instructions for a small straight-line subset; everything else falls back explicitly. |

`oq build file.oq` produces `file.jar`:

```
$ oq build hello.oq
compiled hello.oq (0 functions, 3 main instructions)
backend=classfile (self-contained writer, JDK 21)
wrote hello.jar (89 classes, 0 skipped)
run it with: java -jar hello.jar

$ java -jar hello.jar
Hello, World!
```

The class file is written byte by byte by
`ClassFileWriter` — no `java.lang.classfile`, no ASM, no dependency. It therefore works on
**JDK 21**, the project baseline, and on every newer JDK. `java.lang.classfile` is not used
anywhere.

## What the generated jar contains

| Entry | Purpose |
|---|---|
| `META-INF/MANIFEST.MF` | `Main-Class: oq.generated.Program`, `Oqthara-Backend: classfile` |
| `oq/generated/Program.class` | real class file with a real `public static void main` |
| `dev/oqthara/**` | the interpreter, stdlib and embedded runner |
| `oq/main.oq` | the program source |
| `oq/source-name.txt` | the original file name, so diagnostics match `oq run` |

`Program.main` loads the source string, loads its own `String[] args`, calls
`EmbeddedMain.runWithArgs` and returns, so program arguments, `args()`, exit codes and
diagnostics all behave exactly as under `oq run`.

The constant pool uses **modified UTF-8** (JVMS 4.4.7), so emoji and other supplementary
characters survive. Jar entry names are validated before being copied, rejecting absolute
paths, drive letters and `..` segments.

## Two jar modes, chosen automatically

`oq build` reports which mode it used and why:

```
$ oq build arith.oq
codepath: compiled bytecode
wrote arith.jar (90 classes, 0 skipped)

$ oq build fizzbuzz.oq
codepath: compiled bytecode
wrote fizzbuzz.jar (90 classes, 0 skipped)
```

**Compiled mode** (`Oqthara-Backend: classfile-compiled`) emits real JVM instructions for
the whole program and embeds **no source**. `javap` on the generated class shows
`Arithmetic.multiply`, `Long.valueOf` and `Arithmetic.print` calls:

```
3: invokestatic  Long.valueOf:(J)Ljava/lang/Long;
18: invokestatic  Arithmetic.multiply:(Object;Object;)Object
24: invokestatic  Arithmetic.print:(Object;)V
```

The compiled subset today:

| Supported | Refused (falls back) |
|---|---|
| integer, decimal, text and boolean constants | functions that read or write a top-level variable |
| variables: assignment, reassignment, scoping, shadowing | lambdas, callbacks, closures |
| top-level `fun` with parameters, `return`, recursion, mutual recursion | `use`, modules |
| `+ - * / % **` and unary `-`, unary `+` | `try` / `catch` |
| comparisons `== != < <= > >=` | `spawn` |
| `and`, `or`, `not` with short-circuiting | builtins other than `print`, `write`, `length` |
| `if` / `else`, including `else if` chains | Java interop |
| `while`, `for ... to`, `for ... until`, descending ranges | |
| `break`, `continue`, nested loops | |
| list, set and map literals; `xs[i]`; `xs[i] = v` | |
| `length(x)` over text, list, set, map and range | |
| `1 to 5` as a value | |
| `for x in list / set / map / range / text` | |
| `x in collection` | |
| string interpolation | |
| `print` / `write` of one expression | |
| `data` declarations, construction with defaults, field read and write | |
| method calls: `.push()`, `.add()`, `.pop()`, `.keys()`, `.values()`, `.upper()` ... | |

As of T-0060b, **139 of the 197 executable conformance programs** compile to real bytecode
and produce byte-identical output to the interpreter. That number is asserted by
`BytecodeCompilerTest.everyCompilableConformanceProgramMatchesTheInterpreter`, so it cannot
silently go down. Every refusal is reported by name in the `oq build` output.

Collections reuse the interpreter's own semantics instead of reimplementing them: literals,
`xs[i]`, `xs[i] = v`, `length`, `x in xs` and `for x in xs` all call the same
`Values` methods through `runtime.Collections`, so the two backends cannot drift. Iteration
uses a real `java.util.Iterator`, not `length` plus indexing, because maps iterate keys and
text iterates characters. A user `fun length` still wins over the builtin, matching the
interpreter's hoisting rules.

`data` types are a compile-time table in the lowering, because the interpreter hoists every
declaration in a block before running it. A construction pushes the given values, evaluates
the declaration's defaults for the omitted ones, and calls `DataValues.make`, which builds the
same `OqData` with the same ordered field map. Field reads and writes go through
`DataValues.get` / `DataValues.set`.

Two construction shapes are refused by name instead of being compiled. Too many arguments, or
a missing field with no default, is a call the interpreter would reject anyway. A default that
reads a name or calls a function is the subtle one: the interpreter closes over the scope where
the *type* was declared, while a compiled constructor can only evaluate at the *call site*, and
the two differ as soon as a variable is reassigned in between. Only literal defaults are
compiled; the rest keep the program on the interpreter. A `data` type whose name is also a
variable, parameter or function is refused for the same reason: the interpreter resolves that
shadowing through its environment chain.

Method calls compile to `runtime.Methods`, which does **not** reimplement dispatch. It calls
the same `MethodResolver` the interpreter uses for lists, maps, sets, text, numbers and data
records, and delegates unknown names to the interpreter's own `JavaBridge` by reflection, so
overload selection and argument conversion are the same code on both backends. `Methods`
lives in `oq-runtime`, below both, which is why those calls are reflective: the alternative
would be a second copy of the resolver, and a second copy is exactly how a compiled jar starts
diverging from `oq run`.

Functions become real static methods: every parameter is one boxed `java/lang/Object`, the
descriptor is `(Ljava/lang/Object;...)Ljava/lang/Object;`, calls are `invokestatic` against
the generated class, and `return` is `areturn`. A function may only use its own parameters
and locals; reading or assigning a variable of the top-level program is refused by name,
because that would be a closure or a global mutation and the interpreter handles those with
an environment chain. Recursion and mutual recursion work because the functions are all
methods of the same class.

Refusal is deliberate and visible: the compiler returns the name of the instruction it
cannot lower, `oq build` prints it, and the launcher jar is used instead. A jar that behaved
differently from `oq run` would be worse than no jar.

## What "compiled" does **not** mean yet

The compiled mode is real bytecode, but the subset is small. Remaining work for the P3 gate:

1. **Closures and lambdas.** A function that touches an enclosing variable is refused, and a
   lambda is refused by name (`lambdas are not compiled yet`). Captured environments need a
   generated environment class, which is the next stage of the backend. Every conformance
   program that still falls back does so because of a lambda, a closure, a builtin, or a
   `try`, so this is the single biggest remaining tier.
2. **Java interop.** `use` is refused by name; compiled code cannot call Java classes directly.
   Method calls on Java objects that arrive from a compiled call still work, because they go
   through `Methods` -> `JavaBridge`, exactly as under the interpreter.
3. **Modules as separate artifacts.** Module sources are flattened into one program.
4. **The rest of the stdlib.** Builtins beyond `print`, `write` and `length` (`json`,
   `json_parse`, `zip`, `type_of`, `read`, ...) are refused by name.
5. **Benchmarks.** Nobody has measured interpreter versus compiled, so the "no regression
   above 10%" gate cannot be evaluated.

Until items 1–5 are done, the project stays on `0.x` and `STATUS.md` says the P3 gate is
open.

## How control flow is emitted

Branches require a `StackMapTable` on class file version 50 and above, because those
versions are verified by type checking rather than by type inference. The backend keeps that
difficult in exactly one place:

- Every Oqthara value is a reference (`Long`, `Double`, `Boolean`, `String` or `null`), so
  every local is an `astore`/`aload` slot and every frame names `java/lang/Object`.
- Labels exist only where the operand stack is empty, so every frame is a `full_frame` with
  the same locals and an empty stack.
- All locals are initialized to `null` in a prologue. An uninitialized slot is `top`, and no
  frame may claim `top` is a reference; starting from `null` makes one frame shape valid at
  every join point.
- There is no code generation for exception handlers yet, so no frame ever needs an
  exception on the stack.

Descending ranges are the clearest example of why the IR is not simplified: the interpreter
computes `step = from <= to ? 1 : -1` and walks downwards, so a compiled `i <= to` loop would
print nothing for `for n in 5 to 1`. The lowering materializes the step and emits both
direction tests.

## Why `java.lang.classfile` is not used

It was the original plan, and `docs/adr/0003-bytecode.md` records the change. The API was
finalised in JDK 24 and does not exist in JDK 21:

```
$ jshell   # JDK 21.0.12
|  Error: package java.lang.classfile does not exist

$ jshell   # JDK 25.0.4.1
module java.base
```

Writing the class file directly turned out to be smaller than managing two backends: about
150 lines for the constant pool and the `Code` attribute, with no version gate and no
reflection. The tradeoff is that complex code generation is now our job; for the current
"package the interpreter" use case it is the simplest thing that works.

## Verification you can run yourself

```bash
./gradlew build
java -jar oq-cli/build/libs/oq.jar build examples/hello.oq
java -jar examples/hello.jar

# parity between interpreter and jar over the whole example corpus
./gradlew :oq-cli:test --tests 'dev.oqthara.cli.BuildJarTest'
```

`BuildJarTest` builds a jar for every deterministic example, runs it, and asserts that
stdout and exit code equal the interpreter's. Any divergence fails the build. `parallel.oq`
is excluded because it prints elapsed milliseconds, and `guess.oq`/`grep.oq` because they
are interactive or need arguments.

Current result: **28 of 28 deterministic examples match exactly**, plus targeted tests for
non-BMP text, program arguments, module embedding and jar-entry safety.

## Order of work

1. ~~**T-0038** — emit a real class file and jar.~~ Done (launcher model).
2. ~~**T-0048** — straight-line arithmetic bytecode.~~ Done.
3. ~~**T-0050a** — local variables in compiled bytecode.~~ Done.
4. ~~**T-0050b** — branches and loops.~~ Done.
5. ~~**T-0060b** — `data` declarations, constructors, field access and method calls.~~ Done.
6. **T-0037b** — closures/lambdas as generated classes with a captured environment.
7. **T-0049** — Java interop from generated code.
8. **T-0047** — benchmarks: interpreter versus generated versus hand-written Java.

Every step must keep the conformance suite green and delete the corresponding limitation
from this page. If a step cannot, the page changes, not the claim.
