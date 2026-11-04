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
codepath: interpreter launcher (instruction Store is not compiled yet)
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
| integer, decimal, text and boolean constants | variables (`Store`/`Load`) |
| `+ - * / %` and unary `-` | comparisons and boolean operators |
| `print`/`write` of one expression | calls, functions, closures |
| multiple statements in sequence | `if`, loops, `data`, collections, interpolation, modules |

Refusal is deliberate and visible: the compiler returns the name of the instruction it
cannot lower, `oq build` prints it, and the launcher jar is used instead. A jar that behaved
differently from `oq run` would be worse than no jar.

## What "compiled" does **not** mean yet

The compiled mode is real bytecode, but the subset is small. Remaining work for the P3 gate:

1. **Variables and control flow.** `Store`/`Load`, branches and loops need local slots and
   `StackMapTable` frames; today the compiler is straight-line only, which is why no stack
   maps are required.
2. **Lambdas.** `Lowering` still emits a placeholder constant for a lambda.
3. **Java interop.** `use` is not lowered; compiled code cannot call Java directly.
4. **Modules as separate artifacts.** Module sources are flattened into one program.
5. **Benchmarks.** Nobody has measured interpreter versus compiled, so the "no regression
   above 10%" gate cannot be evaluated.

Until items 1–5 are done, the project stays on `0.x` and `STATUS.md` says the P3 gate is
open.

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
3. **T-0050** — variables, branches and loops with `StackMapTable` generation.
4. **T-0037b** — closures/lambdas as generated classes with a captured environment.
5. **T-0049** — Java interop from generated code.
6. **T-0047** — benchmarks: interpreter versus generated versus hand-written Java.

Every step must keep the conformance suite green and delete the corresponding limitation
from this page. If a step cannot, the page changes, not the claim.
