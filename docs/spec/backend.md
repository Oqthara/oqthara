# Backend status and limitations

This page is the honest state of `oq-compiler`. It exists so nobody discovers these by
being surprised at runtime, and so the gate for a stable release is unambiguous.

## Two backends, one contract

| Backend | Where | Status |
|---|---|---|
| Interpreter | `oq-core` (`Interpreter`) | **Authoritative.** Runs every conformance case. |
| Bytecode | `oq-compiler` | **Incomplete.** Lowers to IR, does not emit class files yet. |

The conformance suite is the contract. Until both backends pass it, the interpreter is the
only backend that may be described as "supported".

## What works today

```
$ oq build examples/hello.oq
compiled examples/hello.oq (0 functions, 10 main instructions)
backend=classfile jdk=25
classfile emission is not implemented yet; IR lowering only
```

- AST → stack IR lowering for every construct the parser accepts.
- `oq build --emit-ir` prints the IR, which is how the lowering is tested.
- Runtime backend detection: JDK 24+ selects `classfile`, JDK 21–23 selects `diagnostic`.
- On JDK 21–23, `oq build` exits with code **3** and explains the requirement. It never
  pretends to have produced a jar.
- Loop-aware IR: `for`/`while` bodies carry `loopN_head`, `loopN_step` and `loopN_end`
  labels, and `break`/`continue` compile to jumps instead of diverging.
- Known divergences are reported as warnings, naming the line, so a future gap cannot be
  silent.

## What does not work

1. **Class file emission.** `oq-compiler` does not write a `.class` file. `oq build`
   produces IR and a report, not an executable artifact.
2. ~~**`break` and `continue` in IR.**~~ **Fixed.** The IR now has named labels and
   explicit `break`/`continue` instructions targeting a loop id, so nested loops redirect
   correctly and the disassembler shows `break loop0` / `jump loop0_head`.
3. **Lambdas.** `Lowering` emits a placeholder constant instead of a closure. Any program
   that passes a lambda to a collection method will not behave identically under a future
   bytecode backend. Tracked with the closure work in T-0038.
4. **Modules.** Module resolution happens in `oq-cli` before execution; `oq build` sees the
   merged program only when invoked through the CLI. Compiling a module graph to separate
   class files is not designed yet.
5. **Java interop in IR.** `use` is not lowered. A compiled program cannot yet call Java.

## Why `java.lang.classfile` is not used on JDK 21

The classfile API was finalised in **JDK 24**. It does not exist in JDK 21, which is the
declared baseline. Verified on this machine:

```
$ jshell   # JDK 21.0.12
|  Error: package java.lang.classfile does not exist

$ jshell   # JDK 25.0.4.1
module java.base
```

Because the API is absent, linking against it at compile time would make the whole CLI
fail to load on the baseline. `Backends` therefore resolves it reflectively at runtime.
The decision and the alternatives are in `docs/adr/0003-bytecode.md`.

## What the P3 gate requires

From the master prompt:

1. Two backends with identical conformance results — **not met**.
2. No performance regression above 10% — **not measurable yet**.

Therefore the project stays on `0.x`. A stable release is impossible while the gate is
open, and `STATUS.md` says so.

## Order of work

1. ~~**T-0037** — loop addresses for `break`/`continue`.~~ Done.
2. **T-0038** — emit a real class file for programs without lambdas, Java interop or
   modules; add a `--run-compiled` path that replays the conformance suite.
3. **T-0039** — closures as generated classes with a captured-environment field.
4. **T-0040** — Java interop from compiled code, reusing `JavaBridge` semantics.
5. **T-0041** — benchmarks: interpreter versus compiled versus hand-written Java.

Each step must keep the conformance suite green and must delete the corresponding warning
from `Lowering`, or the divergence will silently come back.

## How to check the current state yourself

```bash
./gradlew build
java -jar oq-cli/build/libs/oq.jar build examples/hello.oq --emit-ir
java -jar oq-cli/build/libs/oq.jar build examples/hello.oq        # exit 3 on JDK 21
JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4.1+1-tem" \
  java -jar oq-cli/build/libs/oq.jar build examples/hello.oq      # backend=classfile
```

If any of these stop matching this page, the page is wrong and must be updated in the same
change.
