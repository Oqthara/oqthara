# ADR-0003 — Bytecode backend strategy

- **Status:** superseded in part by ADR-0006 (the class file is now written directly)
- **Date:** 2026-11-04
- **Deciders:** oq-compiler, oq-architect
- **Related:** prototype in `oq-compiler`

## Context

The roadmap wants `oq build file.oq` to emit a `.jar` whose behaviour matches the
interpreter case-for-case. There are three realistic ways to emit JVM bytecode:

1. **`java.lang.classfile`** — the standard API, finalised in JDK 24. No dependencies,
   but **not present in JDK 21**, which is our declared baseline. Verified on this
   machine:
   - JDK 21.0.12: `package java.lang.classfile does not exist`
   - JDK 25.0.4.1: `module java.base` (present)
2. **ASM** — works on every JDK, mature, tiny. It is an external dependency, and the
   project rule says `oq-core` must have **zero runtime dependencies**. `oq-compiler` is
   not `oq-core`, so a compile-time, non-exported dependency there is allowed by the
   letter of the rule.
3. **A generated Java source + `javac`** — simple, but slow and requires a full JDK at
   runtime, which breaks the "single jar" distribution promise.

The constraint that decides this is the baseline: **users on JDK 21 must be able to run
the interpreter**. Compiling to bytecode is an optional capability, not a prerequisite
for using the language.

## Decision

**Compile only when a JDK that has `java.lang.classfile` is the runtime JDK, and fail
with a clear message otherwise.** Concretely:

- `oq-compiler` targets Java 21 bytecode and has **zero dependencies**. The classfile API
  is reached reflectively, so the module still compiles and loads on JDK 21.
- `oq build file.oq` checks for `java.lang.classfile.ClassFile` at runtime.
  - If present (JDK 24+): lower the AST and emit a class file.
  - If absent (JDK 21–23): print a diagnostic that names the requirement and the exact
    JDK version to install, and exit with code 3. Never pretend to have built a jar.
- The interpreter stays the primary backend and the only one needed to run programs.
- The conformance suite is the contract: a case passes only when **both** backends agree,
  and the suite records which backend was used.

This is a deliberate reversal of the "prefer classfile if the target JDK supports it"
wording in the master prompt, because the prompt assumed the baseline and the classfile
API were compatible. They are not. A future ADR can raise the baseline to 24 and drop the
runtime check if that becomes the right tradeoff.

## Implementation plan

The first increment (T-0015) is intentionally small and verifiable:

1. A **lowering** from `Ast.Program` to a small stack IR (`oq-compiler`).
2. A **disassembler** for that IR, used in tests and by `oq build --emit-ir`.
3. A **backend interface** with one implementation, `ClassfileBackend`, loaded
   reflectively, and a `DiagnosticBackend` used when the API is missing.
4. `oq build` wires them and reports which backend ran.

Full code generation is a follow-up task; the contract for this increment is that the IR
round-trips the 137 conformance programs and that `oq build` behaves correctly on both a
classfile-capable and a classfile-incapable JDK.

## Consequences

- Users on JDK 21 get a precise message instead of a `NoClassDefFoundError`.
- The project keeps zero runtime dependencies.
- The bytecode backend cannot be exercised in the default CI matrix until a JDK 24+ leg
  is added; the matrix will add JDK 25 on Ubuntu for that purpose.
- Compiled artifacts are versioned separately from the interpreter; `oq --version` reports
  the backend that produced a jar in its manifest.
