# Backend status and limitations

This page is the honest state of `oq-compiler`. It exists so nobody discovers these by
being surprised at runtime, and so the gate for a stable release is unambiguous.

## Two backends, one contract

| Backend | Where | Status |
|---|---|---|
| Interpreter | `oq-core` (`Interpreter`) | **Authoritative.** Runs every conformance case. |
| Class file emitter | `oq-compiler` (`ClassFileWriter`, `JarBuilder`) | **Works.** Emits a real class file and jar. |
| Bytecode compiler | `oq-compiler` (`BytecodeCompiler`) | **Partial.** Real JVM instructions for most of the language; lambdas, closures, Java interop and host-dependent builtins still fall back explicitly. |

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
| `+ - * / % **` and unary `-`, unary `+` | `try` with an empty body |
| `try` / `catch`, nested, with rethrow | |
| comparisons `== != < <= > >=` | `spawn` |
| `and`, `or`, `not` with short-circuiting | `args()`, `input()`, `exit()` |
| `if` / `else`, including `else if` chains | the filesystem, clock, randomness, network and process builtins |
| `while`, `for ... to`, `for ... until`, descending ranges | `spawn`, `await`, `done`, `par_map` and other callable-taking builtins |
| `break`, `continue`, nested loops | lambdas and function values |
| list, set and map literals; `xs[i]`; `xs[i] = v` | Java interop |
| `length(x)` over text, list, set, map and range | |
| `1 to 5` as a value | |
| `for x in list / set / map / range / text` | |
| `x in collection` | |
| string interpolation | |
| `print` / `write` of one or more expressions | |
| `data` declarations, construction with defaults, field read and write | |
| method calls: `.push()`, `.add()`, `.pop()`, `.keys()`, `.values()`, `.upper()` ... | |
| deterministic stdlib builtins: `abs`, `floor`, `ceil`, `round`, `sqrt`, `pow`, `min`, `max`, `clamp`, `number`, `text`, `type_of`, `is_nothing`, `is_number`, `is_text`, `upper`, `lower`, `trim`, `split`, `join`, `replace`, `contains`, `format`, `set`, `list`, `zip`, `keys`, `values`, `sum`, `sort`, `reverse`, `unique`, `push`, `pop`, `clone`, `range`, `assert`, `json_parse`, `json`, `json_write`, `json_pretty` | |

As of T-0071, **169 of the 197 executable conformance programs** compile to real bytecode
and produce byte-identical output to the interpreter. That number is asserted by
`BytecodeCompilerTest.everyCompilableConformanceProgramMatchesTheInterpreter`, so it cannot
silently go down. Every refusal is reported by name in the `oq build` output.

Collections reuse the interpreter's own semantics instead of reimplementing them: literals,
`xs[i]`, `xs[i] = v`, `length`, `x in xs` and `for x in xs` all call the same
`Values` methods through `runtime.Collections`, so the two backends cannot drift. Iteration
uses a real `java.util.Iterator`, not `length` plus indexing, because maps iterate keys and
text iterates characters. A user `fun length` still wins over the builtin, matching the
interpreter's hoisting rules.

Ordinary stdlib builtins are not reimplemented either. A compiled call such as
`clamp(12, 1, 10)` becomes an `invokestatic` to `stdlib.Builtins`, which installs the real
`Stdlib` into one headless interpreter and calls the same `NativeFunction` the interpreter
calls, so there is exactly one implementation of every builtin. The compiler asks
`Builtins.supports(name, argc)` before emitting, which is why an unknown name, a wrong arity
or a host-dependent builtin is still refused by name instead of failing inside a jar.
Host-dependent means files (`read`, `save`, `exists`, ...), time (`now`, `today`, `sleep`),
randomness, HTTP, process state (`env`, `run_command`, `cpu_count`) and the concurrency
helpers (`spawn`, `await`, `par_map`); those stay on the interpreter, because a generated
class would either block on a stream it does not own or produce output that `oq run` cannot
reproduce. `args()` and `input()` are refused for the same reason. Variadic `print a, b` and
`write a, b` build an argument list and call the interpreter's variadic builtin, so the
separator rule does not get a second implementation.

Two known gaps remain and are tracked as **T-0072** and **T-0073**. A builtin that throws
inside a compiled jar escapes as an uncaught `OqError` stack trace instead of `oq run`'s
rendered diagnostic (same message and exit code 1, different presentation); this predates
the builtin work and affects arithmetic errors too. And `print(1, 2)` written as an
expression is refused rather than compiled, because the statement lowering pops a value the
void call never pushes. Both fall back to the launcher jar, so no program changes meaning.

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

### Why data fields are still boxed `Object`

`data` records are not JVM fields. The generated class has **no field at all**; `javap -p` on
a compiled program shows only `main`, and every record is one `OqData` from `oq-runtime`
backed by a `LinkedHashMap<String, Object>`. Reads and writes go through `DataValues.get` /
`DataValues.set`, which index that map. Two facts make a precise descriptor unsafe today:

1. **There is no declared type to encode.** `Ast.FieldDef` carries a name and an optional
   default expression, nothing else. A `data` field is dynamically typed, and the language
   has no type annotations to derive a descriptor from.
2. **The interpreter accepts heterogeneous values in one field.** This program prints
   `0`, `text`, `[1, 2]` under both `oq run` and the compiled jar:

   ```
   data Slot(value = 0)
   s = Slot()
   print s.value
   s.value = "text"
   print s.value
   s.value = [1, 2]
   print s.value
   ```

A precise descriptor would have to reject the second write, which the interpreter allows, so
it would be a behavioural divergence, not an optimisation. It is also not reachable by a
`getfield`/`putfield` rewrite alone: `OqData.fields()` is read directly by `Values.repr`,
`Json.writeValue`, `Stdlib.deepCopy` and `DataValues`, so a typed layout would break JSON,
`text(record)`, equality and deep copies, or need a second parallel representation.

Making fields precise therefore needs, in order: optional type annotations on `data` fields
(T-0069), a language rule for a write whose value does not match the annotation (the
interpreter must reject it too), generated accessors or typed locals with matching stack map
frames, and a way to serialise a typed record through the same `Map`-shaped APIs. Until all
four exist, every data field stays a boxed `java/lang/Object` inside the shared `OqData`
map, which is exactly the interpreter's value model. This is a decision, not a pending
rewrite: changing it requires the language change first.

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
   program that still falls back does so because of a lambda, a closure, or a builtin, so
   this is the single biggest remaining tier.
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
- A handler label is the one frame that is not empty-stack: it is entered with the caught
  `OqError` on the stack, so its frame is a `full_frame` with one stack item of that class.
  It carries the same locals as every other frame, which is sound because the handler runs
  with the try body's locals still in place.
- Exception-table rows are emitted inner-region first. JVMS 4.7.3 picks the first row whose
  range contains the throw, so an enclosing region listed first would swallow a nested
  handler's throw. `patch()` sorts by end offset ascending, which puts every inner region
  ahead of the region that encloses it.

## How `try` / `catch` is emitted

The interpreter's rule is exact and narrow (see `Interpreter`'s `TryStmt` case): the body
runs in a child environment, a thrown `OqError` is caught, `error.toValue()` becomes the
binding, and the handler runs in another child environment with that binding fixed. No other
throwable is caught.

The backend reproduces that literally:

- `Ir.TryStart(handler)` / `Ir.TryEnd(handler)` mark a protected region. The lowering emits
  them around the body only, so a handler cannot catch its own rethrow; that is why the
  nested rethrow case works.
- `Ir.HandlerLabel` opens the handler and `Ir.CatchValue` calls `Errors.catchValue`, which is
  `OqError.toValue()`. The binding is an `Ir.Declare(name, fixed)`, so assigning to it is
  refused exactly as the interpreter refuses to change a `defineConst`.
- The exception table names `dev/oqthara/runtime/OqError` and **only** that class. A
  `NullPointerException` or any other `RuntimeException` escapes the handler on both
  backends; naming `RuntimeException` would make a compiled jar catch what `oq run` does not.
- The body and handler are `try`-scoped, so a `catch` variable cannot leak into the code
  after `end`, matching the child environment.
- A `return`, `break` or `continue` inside the body still closes the region: `TryEnd` is
  processed even when it is unreachable, so a body that always returns leaves a valid table.
- An **empty** body is refused by name (`an empty 'try' body is not compiled yet`) rather
  than emitted as a zero-length row, which the verifier rejects.

Error spans are the subtle part. A caught error exposes `message`, `hint`, `line`, `column`
and `file`, and `133-try-error-fields.oq` prints three of them. Generated code therefore
emits `Errors.mark(file, line, column)` immediately before every operation inside a `try`
(or inside any function, since its caller may catch) that can raise. The runtime helpers
`Arithmetic`, `Collections`, `DataValues` and `Methods` read that position instead of
`Span.synthetic()`. `Errors` reduces the source to its file name because that is what
`oq run` reports, so `oq build dir/prog.oq` and `oq run dir/prog.oq` produce the same
`err.file` in the jar.

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
6. ~~**T-0061** — `try` / `catch` with a real exception table and handler frames.~~ Done.
7. ~~**T-0060c** — precise field descriptors for `data`.~~ Refused with evidence: fields stay
   boxed `Object` because the language has no field type and allows a field to change type.
   See "Why data fields are still boxed `Object`".
8. ~~**T-0071** — stdlib builtins dispatched from generated code.~~ Done: deterministic
   builtins go through `stdlib.Builtins`, which calls the interpreter's own `NativeFunction`.
9. **T-0037b** — closures/lambdas as generated classes with a captured environment.
10. **T-0049** — Java interop from generated code.
11. **T-0047** — benchmarks: interpreter versus generated versus hand-written Java.

Every step must keep the conformance suite green and delete the corresponding limitation
from this page. If a step cannot, the page changes, not the claim.
