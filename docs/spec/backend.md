# Backend status and limitations

This page is the honest state of `oq-compiler`. It exists so nobody discovers these by
being surprised at runtime, and so the boundary of the compiled backend is unambiguous.

## Two backends, one contract

| Backend | Where | Status |
|---|---|---|
| Interpreter | `oq-core` (`Interpreter`) | **Authoritative.** Runs every conformance case. |
| Class file emitter | `oq-compiler` (`ClassFileWriter`, `JarBuilder`) | **Works.** Emits a real class file and jar. |
| Bytecode compiler | `oq-compiler` (`BytecodeCompiler`) | **Partial by design.** Real JVM instructions for the compiled subset below; everything outside it is refused by name and falls back to the interpreter. |

`oq build file.oq` produces `file.jar`. Measured on this tree:

```
$ java -jar oq-cli/build/libs/oq.jar build build/oqdoc/hello.oq
compiled build/oqdoc/hello.oq (0 functions, 5 main instructions)
backend=classfile (self-contained writer, JDK 21)
codepath: compiled bytecode
wrote build/oqdoc/hello.jar (99 classes, 0 skipped)
run it with: java -jar build/oqdoc/hello.jar

$ java -jar build/oqdoc/hello.jar
Hello, World!
```

The class file is written byte by byte by
`ClassFileWriter` — no `java.lang.classfile`, no ASM, no dependency. It therefore works on
**JDK 21**, the project baseline, and on every newer JDK. `java.lang.classfile` is not used
anywhere.

## What the generated jar contains

| Entry | Purpose |
|---|---|
| `META-INF/MANIFEST.MF` | `Main-Class: oq.generated.Program`, `Oqthara-Backend: classfile-launcher` or `classfile-compiled` |
| `oq/generated/Program.class` | real class file with a real `public static void main` |
| `dev/oqthara/**` | the interpreter, stdlib and embedded runner |
| `oq/main.oq` | the program source (launcher mode only) |
| `oq/source-name.txt` | the original file name, so diagnostics match `oq run` |

In launcher mode `Program.main` loads the source string, loads its own `String[] args`, calls
`EmbeddedMain.runWithArgs` and returns, so program arguments, `args()`, exit codes and
diagnostics all behave exactly as under `oq run`. In compiled mode the class holds real
bytecode and embeds no source.

The constant pool uses **modified UTF-8** (JVMS 4.4.7), so emoji and other supplementary
characters survive. Jar entry names are validated before being copied, rejecting absolute
paths, drive letters and `..` segments.

## Two jar modes, chosen automatically

`oq build` reports which mode it used and why:

```
$ java -jar oq-cli/build/libs/oq.jar build build/oqdoc/arith.oq
compiled build/oqdoc/arith.oq (1 functions, 8 main instructions)
backend=classfile (self-contained writer, JDK 21)
codepath: compiled bytecode
wrote build/oqdoc/arith.jar (99 classes, 0 skipped)
run it with: java -jar build/oqdoc/arith.jar
```

**Compiled mode** (`Oqthara-Backend: classfile-compiled`) emits real JVM instructions for
the whole program and embeds **no source**. `javap` on the generated class shows the user
function and the interpreter's shared `Arithmetic.print`:

```
$ javap -c -p -classpath build/oqdoc/arith.jar oq.generated.Program | grep invokestatic
      5: invokestatic  #8   // Method java/lang/Long.valueOf:(J)Ljava/lang/Long;
     32: invokestatic  #28  // Method oq/generated/Program.multiply:(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
     53: invokestatic  #38  // Method dev/oqthara/runtime/Arithmetic.print:(Ljava/lang/Object;)V
```

When a program is outside the subset, the same command prints the refusal and builds the
launcher instead:

```
$ java -jar oq-cli/build/libs/oq.jar build build/oqdoc/cap.oq
...
codepath: interpreter launcher ('factor' is read from an enclosing scope; only functions that use their own parameters and locals are compiled)
...
```

**Interpreter launcher** (`Oqthara-Backend: classfile-launcher`) embeds the source and the
interpreter. It is the fallback for everything the compiler refuses, and it is why refusing
is safe: a program either compiles to equivalent bytecode or runs exactly as `oq run` does.

## The compiled subset, measured

As of Alpha 3, **192 of the 207 executable conformance programs** compile to real bytecode
and produce byte-identical output to the interpreter. That number is asserted by
`BytecodeCompilerTest.everyCompilableConformanceProgramMatchesTheInterpreter`
(`assertEquals(192, compiled, ...)`), so it cannot silently go down. 207 is the number of
executable programs in `conformance/`: every `*.oq` without a sibling `*.err`. Every refusal
is reported by name in the `oq build` output.

| Supported | Refused by name (falls back to the interpreter) |
|---|---|
| integer, decimal, text and boolean constants | functions that read or write a top-level variable |
| variables: assignment, reassignment, scoping, shadowing | lambdas that capture an enclosing variable |
| top-level `fun` with parameters, `return`, recursion, mutual recursion | `use`, modules |
| `+ - * / % **` and unary `-`, unary `+` | Java constructors, Java instance methods and static methods reached through `use` |
| `try` / `catch`, nested, with rethrow | `try` with an empty body |
| comparisons `== != < <= > >=` | `spawn`, `await`, `done`, `par_map`, `sort_by`, `map`, `filter` and other callable-taking builtins |
| `and`, `or`, `not` with short-circuiting | `args()`, `input()`, `exit()` |
| `if` / `else`, including `else if` chains | the filesystem, clock, randomness, network and process builtins |
| `while`, `for ... to`, `for ... until`, descending ranges | |
| `break`, `continue`, nested loops | |
| **non-capturing lambdas**, called directly or through a stdlib method (`xs.map(x -> x * x)`) | |
| list, set and map literals; `xs[i]`; `xs[i] = v` | |
| `length(x)` over text, list, set, map and range | |
| `1 to 5` as a value | |
| `for x in list / set / map / range / text` | |
| `x in collection` | |
| string interpolation | |
| `print` / `write` of one or more expressions | |
| `data` declarations, construction with defaults, field read and write | |
| method calls: `.push()`, `.add()`, `.pop()`, `.keys()`, `.values()`, `.upper()` ... | |
| deterministic stdlib builtins: `abs`, `floor`, `ceil`, `round`, `sqrt`, `pow`, `min`, `max`, `clamp`, `number`, `text`, `type_of`, `is_nothing`, `is_number`, `is_text`, `upper`, `lower`, `trim`, `split`, `join`, `replace`, `contains`, `format`, `set`, `list`, `zip`, `keys`, `values`, `sum`, `sort`, `reverse`, `unique`, `push`, `pop`, `clone`, `range`, `assert`, `json_parse`, `json`, `json_write`, `json_pretty` | |

Non-capturing lambdas compile: `xs.map(x -> x * x)` is measured to build a compiled jar and
print `[1, 4, 9]`:

```
$ java -jar oq-cli/build/libs/oq.jar build build/oqdoc/map.oq
...
codepath: compiled bytecode
...
$ java -jar build/oqdoc/map.jar
[1, 4, 9]
```

A lambda becomes one extra static method on the generated class plus a
`runtime.CompiledFunction` value holding the method name, so a call goes through the value.
A lambda that reads an enclosing variable is **deliberately refused**, because capturing
would copy that variable's value at creation time while the interpreter reads it at call
time; binding the stale value silently would be a wrong answer. The refusal names the
variable:

```
codepath: interpreter launcher ('factor' is read from an enclosing scope; only functions that use their own parameters and locals are compiled)
```

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
`Builtins.supports` returns false for every name in `HOST_DEPENDENT`: `args`, `input`,
`exit`, the filesystem calls (`read`, `read_lines`, `save`, `append`, `exists`,
`list_files`, `delete`, `make_dir`, `file_size`, `path_join`, `path_name`, `path_parent`,
`cwd`), time (`now`, `today`, `datetime`, `timestamp`, `sleep`), randomness (`random`,
`random_item`), HTTP (`http_get`, `http_post`), process state (`env`, `run_command`,
`cpu_count`, `memory_mb`) and the concurrency helpers (`spawn`, `await`, `done`, `par_map`,
`sort_by`, `map`, `filter`). Those stay on the interpreter, because a generated class would
either block on a stream it does not own or produce output that `oq run` cannot reproduce.
Measured refusal:

```
$ java -jar oq-cli/build/libs/oq.jar build build/oqdoc/now.oq
...
codepath: interpreter launcher (call to 'now' with 0 argument(s) is not compiled yet)
...
```

Variadic `print a, b` and `write a, b` build an argument list and call the interpreter's
variadic builtin, so the separator rule does not get a second implementation.

An uncaught `OqError` in a compiled jar renders the same diagnostic `oq run` prints, minus
the caret block. `main` owns one exception-table row covering its whole body, so an error
escaping the program reaches `EmbeddedMain.renderUncaught`, which calls the same
`Details`/`Diagnostics` renderer the interpreter uses and exits 1. The message, the
`file:line:column` location and the hint are byte-identical; the source line and caret are
omitted because a compiled jar deliberately embeds no source. Each `try` in the program
contributes its own row, and because rows sort by end offset ascending an inner region
always wins for its own range, so a caught error is never mistaken for an uncaught one.

`print` and `write` also compile when used as expressions. The interpreter's builtin returns
`nothing`, so the generated call performs the output and then leaves `nothing` on the stack;
the statement form discards it with the same `Pop` every other call uses. There is therefore
no void special case keyed on the name, and `x = print(1)` and `print(1) + 1` behave exactly
as they do under `oq run`, including the arithmetic failure the latter raises.

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

## The current boundary

The compiled backend is real bytecode, not a launcher in disguise, but it is intentionally
smaller than the language. Today's boundary, in the order it would be widened:

1. **Capturing closures.** Non-capturing lambdas already compile. A lambda that reads an
   enclosing variable, and a function that touches a top-level variable, are refused because
   capture would copy at creation time while the interpreter reads at call time.
2. **Java interop.** `use` is refused by name; compiled code cannot call Java classes
   directly. Method calls on Java objects that arrive from a compiled call still work,
   because they go through `Methods` -> `JavaBridge`, exactly as under the interpreter.
3. **Modules as separate artifacts.** Module sources are flattened into one program.
4. **Host-dependent builtins.** Files, time, randomness, HTTP, process state, `args`,
   `input`, `exit` and the concurrency helpers stay on the interpreter, for the reasons
   above. They are refused by name, not silently miscompiled.

The interpreter remains authoritative for every program and is the fallback for everything
refused. There is no roadmap item to remove the interpreter; the two backends are one
contract with one fallback.

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

# the compiled subset is exactly what the interpreter produces
./gradlew :oq-cli:test --tests 'dev.oqthara.cli.BytecodeCompilerTest'

# every deterministic example matches the interpreter through a jar
./gradlew :oq-cli:test --tests 'dev.oqthara.cli.BuildJarTest'
```

`BytecodeCompilerTest.everyCompilableConformanceProgramMatchesTheInterpreter` compiles every
compilable conformance program, runs it, and asserts that stdout equals `oq run`'s, then
asserts the compiled count is 192 of the 207 executable programs. `BuildJarTest` builds a
jar for every deterministic example, runs it, and asserts that stdout and exit code equal
the interpreter's; any divergence fails the build. `parallel.oq` is excluded because it
prints elapsed milliseconds, and `guess.oq`/`grep.oq` because they are interactive or need
arguments.

Benchmarks, when needed, are produced by `scripts/bench-backends.py`, which builds each
program in `bench/programs`, keeps only those that report `codepath: compiled bytecode`,
checks that both backends print the same bytes, then measures whole CLI invocations over a
warm-up and several runs. It prints a table; the repository does not commit a performance
claim that it cannot reproduce.

## How values are represented

The compiled backend emits JVM operations against the same runtime classes the interpreter
uses, so the two cannot disagree about a value's representation. This section records what
those classes are, so a reader debugging a compiled program knows what they are looking at.

### Scalars

`number` is `java.lang.Long` when whole and `java.lang.Double` when not; `text` is
`String`; `boolean` is `Boolean`; `nothing` is a single `null` sentinel. All values cross the
boundary as `Object`, which is why `docs/adr/0007-boxed-values.md` exists: the compiler emits
no unboxing it cannot prove safe, and the runtime helpers accept `Object` throughout.

Integer arithmetic is `long` arithmetic and wraps (ADR-0015). Decimal arithmetic is `double`
arithmetic and has the usual precision limits, which the language does not hide.

### `map` and `set` are insertion-ordered

Both are `LinkedHashMap` and `LinkedHashSet` underneath:

```
print {"b": 1, "a": 2}.keys()      # ["b", "a"]
```

The order is the order of first insertion, and removing then re-adding a key moves it to the
end. This is a language guarantee, not an accident of `java.util`: a `HashMap` would reorder
on resize and make `for k in m` non-reproducible between runs, which no program should have
to defend against.

`OqMap` and `OqSet` hold `Value` boxes rather than raw objects, so their `hashCode` and
`equals` are Oqthara's rather than Java's. That is what makes `{1: "a"}[1.0]` find its entry
(ADR-0017): the boxes of `1` and `1.0` are equal and hash alike, while `Long` and `Double`
are not.

### `for` walks a snapshot; `.iter()` does not

`for` captures the collection's contents when the loop starts, so a program may add to or
remove from a list while looping over it, and the loop sees the state at entry:

```
xs = [1, 2, 3]
seen = []
for x in xs
  seen = seen + [x]
  xs = xs + [9]
end
print seen      # [1, 2, 3]
print xs        # [1, 2, 3, 9, 9, 9]
```

`docs/adr/0016-iteration-snapshots.md` records why: the alternative is the
`ConcurrentModificationException`-style failure Python has, which is a harder thing to
explain than a defined result. The cost is one copy per loop entry, which for the sizes
Oqthara programs work with is not measurable.

An explicit `.iter()` does **not** copy. It reads the collection it came from, per
ADR-0020, because an explicit iterator is the caller asking to walk the real thing. Changing
the collection mid-walk is therefore invalid, and the runtime translates the JDK's
`ConcurrentModificationException` into a program error naming `for` as the fix rather than
letting it escape as an internal error.

Text iterates by code point either way, and text is immutable, so neither path can produce a
lone surrogate.

### `set` methods mutate through an unchecked cast

`MethodResolverImpl.asMutableSet` casts `Set<?>` to `Set<Object>`. This is the one unchecked
cast on a hot path, and it is deliberate: `OqSet` implements `Set<Object>` and unwraps boxes
on the way out, so a method that receives a `Set<?>` view of one can still call `add`.
Replacing it with a copying wrapper would make `s.add(x)` silently fail to affect `s`.

### Text stores UTF-16 and exposes code points

`String` is the storage type; the language's unit is the code point. `"a😀b".length()` is `3`,
`"a😀b"[1]` is `"😀"`, and no operation can produce a lone surrogate.
`docs/adr/0024-text-is-a-sequence-of-code-points.md` records the decision and its accepted
cost: indexing text is O(n) in the position, because locating the i-th code point in a
UTF-16 buffer requires a scan. Storing an `int[]` would make indexing O(1) and double or
quadruple memory for ASCII, which is the common case.

### The current error position is per thread

`Errors.current` is a `ThreadLocal`. Compiled code is single-threaded, because every
concurrency builtin is `HOST_DEPENDENT` and a program that calls one is compiled to a
launcher rather than to bytecode. The interpreter is not: `spawn` and `par_map` run user code
on virtual threads, and a process-wide slot would let two callbacks report each other's
positions. ADR-0025 records the change and the measurement that showed it to be about 15%
faster on a hot arithmetic loop, because a `ThreadLocal` read is not a memory barrier.

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
9. ~~**T-0037b** — lambdas~~ Done for non-capturing lambdas; captured environments are
   refused by name.
10. **T-0049** — Java interop from generated code.
11. **T-0047** — benchmarks: interpreter versus generated versus hand-written Java.

Every step must keep the conformance suite green and delete the corresponding limitation
from this page. If a step cannot, the page changes, not the claim.
