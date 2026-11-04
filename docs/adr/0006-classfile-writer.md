# ADR-0006 — Write class files directly instead of using java.lang.classfile

- **Status:** accepted
- **Date:** 2026-11-04
- **Deciders:** oq-compiler, oq-architect
- **Supersedes in part:** ADR-0003

## Context

ADR-0003 planned to use `java.lang.classfile` only when running on JDK 24+, failing
otherwise. Two things changed during T-0038:

1. The API does not exist on JDK 21, the declared baseline, so half the users could never
   build a jar. `oq build` had to exit 3, which is honest but useless.
2. The first real deliverable turned out to need a very small part of the format: one class,
   one method, three instructions, a handful of constant-pool entries.

## Decision

Write the class file directly in `ClassFileWriter`, about 150 lines including the constant
pool. `JarBuilder` then bundles the generated class with the interpreter, the stdlib, the
source and the source name into an executable jar.

No `java.lang.classfile`, no ASM, no dependency. It works on JDK 21 and 25 identically.

## Consequences

- `oq build` always works, on every supported JDK, with no version gate.
- The generated jar reproduces `oq run` exactly, including diagnostics, because the original
  file name travels with the program.
- **The program is still interpreted inside the jar.** This is a launcher, not AOT
  compilation, and `docs/spec/backend.md` says so in those words. The P3 gate remains open.
- When real code generation arrives, `ClassFileWriter` is the natural place to grow: method
  bodies, more constant-pool entry kinds and a stack-map frame builder.
- The risk is that hand-written class files are easy to get subtly wrong. Mitigation:
  `BuildJarTest` executes every generated jar and compares output and exit code with the
  interpreter, and the JVM verifier rejects malformed classes loudly — which is exactly how
  the Code-attribute length bug was caught.
