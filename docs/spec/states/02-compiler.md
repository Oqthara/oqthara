# 7.2 Compiler state machine

The model of how a source file becomes an executable artifact. This is a *build-time* model: it
runs once, before any program statement does, and its output is either an artifact or a
diagnostic. It is separate from §7.1 because a compiler state is not a program state — nothing
here is observable from inside a running Oqthara program, and every state in this model has
already exited by the time the first `print` executes.

The model has two properties that a conventional compiler model would not have:

- **A failed compilation is not a failed build.** When the compiler declines a program, the
  artifact is still produced, and it is an artifact that *interprets the source it embeds*. The
  user gets a working jar; what they lose is the bytecode path. §7.2.1 models this.
- **There is no optimiser.** The pipeline has no optimisation state at all, by design, because
  the compiler's contract is byte-for-byte agreement with the interpreter (§7.4), and every
  transformation that is not provably agreement-preserving is a source of divergence. The
  directive asks for "optimization and verification"; the honest answer is that optimisation is
  **absent** and verification is a byte-magic and run-the-jar check, both named below.

Every transition is `C<n>`, every invariant `K<n>`, and each invariant maps to an executable
test. Every diagnostic quoted here was measured by running the installed CLI.

## 7.2.0 States

| State | Meaning | Owner |
|---|---|---|
| `S0 Accept` | Source text has been read and the source name recorded. | `Main.build`, `EmbeddedMain` |
| `S1 Lex` | Text is split into tokens carrying spans. | `Lexer.lex` |
| `S2 Parse` | Tokens are recognised as a statement/expression grammar and an AST is built. | `Parser.parse` |
| `S3 Declare` | Top-level declarations and their names are collected; collisions between a `data` type name and an ordinary binding are looked for. | `Lowering.nameCollision` |
| `S4 Resolve` | Names are bound to the environments that will hold them; a call site is classified as builtin, user function or bound value. | `Lowering`, `Interpreter`/`BytecodeCompiler` |
| `S5 Lower` | The AST is translated to a flat `Ir.Instr` list with explicit stack effects. | `Lowering.lowerWithReport` |
| `S6 Refuse` | The pipeline has established that a construct cannot be represented faithfully. | `Ir.Refuse`, `NotSupportedException` |
| `S7 Emit` | IR is emitted as a JVM class file. | `BytecodeCompiler.compile` |
| `S8 Artifact` | A jar is assembled from the class and the runtime classes. | `JarBuilder.build` |
| `S9 Fallback` | A launcher class that embeds the source and interprets it is written instead. | `ClassFileWriter.programClass` |
| `SX Rejected` | The program is not valid Oqthara; a diagnostic is rendered and exit is 1. | `Details.render` |

`S6` and `S9` are the two states that make this model unlike a normal compiler. `S6` is an
ordinary "unsupported" signal; `S9` is the consequence — an unsupported construct does not stop
the build.

## 7.2.1 Transitions

### Front end

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `C1` | `S0` | source read | file exists and is UTF-8 | record source name | `S1` |
| `C2` | `S1` | character scan | — | emit identifier, keyword, number, text, operator, `NEWLINE`, `EOF` | `S2` |
| `C3` | `S1` | word scanned | the text appears in `Lexer.KEYWORDS` | emit `KEYWORD` instead of `IDENT` | `S2` |
| `C4` | `S2` | token stream starts | — | parse a statement list to `EOF` | `S3` |
| `C5` | `S3` | construct recognised | — | build the corresponding `Ast` node with its span | `S3` |
| `C6` | `S3` | construct does not match the grammar | — | raise `OqError` at the offending token | `SX` |
| `C7` | `S3` | expression nesting | depth `< MAX_NESTING` (128) | recurse | `S3` |
| `C8` | `S3` | expression nesting | depth would reach `MAX_NESTING` | raise `expression is nested too deeply` | `SX` |

`C7`/`C8` exist so that a hostile or accidental deeply-parenthesised file produces a diagnostic
instead of a `StackOverflowError`. The value 128 and the reason are recorded in
`docs/spec/fuzzing.md` row 17.

### Middle end

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `C9` | `S3` | AST accepted | — | collect `data` type names, function names and every bound name | `S4` |
| `C10` | `S4` | a `data` type name is also bound as a variable or function | — | prepend `Ir.Refuse` naming the collision | `S6` |
| `C11` | `S4` | any statement | — | record the statically-provable value kinds used for `data` construction and `for` element typing | `S5` |
| `C12` | `S5` | a call site | — | emit `Ir.Call(name, argc)` **without** deciding what the name means; that decision waits for `S7` | `S5` |
| `C13` | `S5` | `use` of a Java class or a module | — | emit `Ir.Refuse` | `S6` |
| `C14` | `S5` | `data` construction the backend cannot reproduce | — | emit `Ir.Refuse` (arity, missing default, a default that reads a name) | `S6` |
| `C15` | `S5` | any other construct the lowering cannot represent | — | emit `Ir.Refuse` rather than a placeholder instruction | `S6` |
| `C16` | `S5` | statement list exhausted | — | append `Ir.Halt`, wrap as `Ir.Function("main", [])` | `S7` |

There is **no transition for type inference**, because Oqthara has no static type system.
`type_of` is a runtime builtin, and `C11` records only the kinds the lowering can prove without
one. A directive clause phrased as "type resolution/inference" therefore has no implementation
to model; the honest state is "absent, by design", recorded as `K8`.

The deferral in `C12` is the reason `S4` and `S7` disagree about what a name means. `S4` knows
only the enclosing-scope rules; the real classification — user function first, builtin second,
variable never — happens at emit time where `functionArities` and the live binding table are
both available. That is why `K6` and `K7` are enforced in `BytecodeCompiler` and not in
`Lowering`.

### Back end

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `C17` | `S7` | IR walk begins | — | collect every function's arity into `functionArities` | `S7` |
| `C18` | `S7` | a function is declared twice with the same arity | — | refuse `function 'f' is declared twice with N parameter(s)` | `S6` |
| `C19` | `S7` | a function is named `print` or `write` | — | refuse — the name collides with the builtin command | `S6` |
| `C20` | `S7` | `Ir.Call` at emit time | the name has a known set of arities containing `argc` | emit a call to the user function | `S7` |
| `C21` | `S7` | `Ir.Call` at emit time | the name has no user function, and matches a builtin | emit a builtin call | `S7` |
| `C22` | `S7` | `Ir.Call` at emit time | the name has known arities but none equals `argc` | refuse `call to 'f' with N argument(s), but the function declares M parameter(s)` | `S6` |
| `C23` | `S7` | `Ir.Call` at emit time | the name is a variable binding here, not a function | refuse `'x' is a variable here, not a function, so this call is not compiled` | `S6` |
| `C24` | `S7` | a bare function name is used as a value | the name is a known function | emit a `runtime.CompiledFunction` for it, so it can be stored, passed and called | `S7` |
| `C25` | `S7` | a name is read | the name is a top-level variable of `main` and the reader is a function body | refuse `'x' is read from an enclosing scope; only functions that use their own parameters and locals are compiled` | `S6` |
| `C26` | `S7` | a variable is allocated a local slot | slot index stays `<= 255` | allocate | `S7` |
| `C27` | `S7` | a variable is allocated a local slot | slot index would exceed `255` | refuse `too many variables for one method` | `S6` |
| `C28` | `S7` | `Ir.Declare` for a name already declared in this scope | — | refuse, matching the interpreter's rule | `S6` |
| `C29` | `S7` | `Ir.Store` to a `fixed` binding | — | refuse `'x' is a fixed value and the interpreter would reject changing it` | `S6` |
| `C30` | `S7` | `try_start` or `Ir.Label` is placed | the operand stack is empty | proceed | `S7` |
| `C31` | `S7` | `try_start` or `Ir.Label` is placed | the operand stack is not empty | refuse — load-bearing for stack-map frames | `S6` |
| `C32` | `S7` | code generation would underflow the stack | — | refuse `generated code would underflow the stack` | `S6` |
| `C33` | `S7` | all instructions emitted | no refusal | serialise the constant pool, methods and stack-map table | `S8` |

`C30`/`C31` are the invariant that cost the most to keep: the framer needs a known stack depth
at every branch target, so a `try` that begins with operands still on the stack cannot be
described. The fix for nested `try` was to *spool* operands into locals before the region
(ADR-0014), never to weaken the check.

### Artifact

| ID | From | Event | Guard | Action | To |
|---|---|---|---|---|---|
| `C34` | `S8` | byte array produced | — | write `MANIFEST.MF` with `Main-Class` and `Oqthara-Backend` | `S8` |
| `C35` | `S8` | runtime classes enumerated | the entry name is safe, wanted and not already written | copy the class into the jar | `S8` |
| `C36` | `S8` | runtime classes enumerated | the entry name is unsafe, or the entry cannot be read | skip it and count it | `S8` |
| `C37` | `S8` | jar complete | — | report `wrote <jar> (N classes, N skipped)` | done |
| `C38` | `S9` | fallback chosen | — | embed the flattened source and a `programClass` launcher | `S8` |
| `C39` | `S6` | any refusal reached | a build was requested, not `oq check` | report `codepath: interpreter launcher (<reason>)` | `S9` |

`C39` is the transition that makes this model unusual, and it is deliberate. A refusal is
**diagnostic information, not a build failure**: `oq build` returns 0, writes a jar, and the jar
prints exactly what `oq run` prints. The cost is that a regression which pushes programs off the
bytecode path is invisible unless something counts. `scripts/verify-conformance.py` counts
(192 compiled of 207 executable), and `C39`'s reason string is the only per-program evidence.

## 7.2.2 Invariants

| ID | Invariant | Enforced by |
|---|---|---|
| `K1` | Every token and every AST node carries a span that maps back to a real source position. | `Span` on `Token`/`Ast`; `everyErrorHasAPositionOrAnExplicitReason` |
| `K2` | A refused program is still runnable: `oq build` returns 0 and the jar matches `oq run`. | `C39`, `BuildJarTest.jarErrorsUseTheOriginalFileName` |
| `K3` | The compiler never emits code it cannot justify: a construct it does not model becomes `Ir.Refuse`, never a placeholder instruction. | `C15` |
| `K4` | Refusals are semantic, not control-flow: an `Ir.Refuse` after an unconditional jump is still seen. | `BytecodeCompiler` refusal scan |
| `K5` | The operand stack is empty at every `try_start` and every `Ir.Label`. A `HandlerLabel` is the deliberate exception: the verifier pushes the caught error there, so its depth restarts at one. | `C30`/`C31`, `requireEmptyStack` |
| `K6` | A name that is a variable at a call site is never compiled to a call of the same-named function or builtin. | `C23` |
| `K7` | No function body reads or writes a top-level variable of `main`. | `C25` |
| `K8` | There is no static type system; no state in this model infers or checks types. | absence — see the note below |
| `K9` | The generated class file declares version 65 (Java 21) and starts with `CAFEBABE`. | `BuildJarTest.generatedClassIsAValidClassFile` |
| `K10` | A jar contains the program class and the runtime classes; a **launcher** jar additionally embeds `oq/main.oq` and `oq/source-name.txt`, and a **compiled** jar embeds neither. | `BuildJarTest.jarContainsManifestProgramAndSource`, `BytecodeCompilerTest.compiledJarContainsNoEmbeddedSource` |
| `K11` | A jar's error messages name the original source file, not `main.oq`. | `BuildJarTest.jarErrorsUseTheOriginalFileName` |
| `K12` | The number of programs that compile is recorded and cannot regress silently. | `C39`, `verify-conformance.py`, `check-docs-sync.py` backend claim |
| `K13` | `oq check` answers "does this parse?" and never "is this valid?". | `Main.check` — `Parser.parse` only |

`K8` is written as an invariant about an absence on purpose. A model that quietly omitted type
inference would read as an oversight; recording it as a stated property of the implementation
makes the directive's "type resolution/inference" clause answerable — there is nothing to
implement before a type system exists, and Alpha 4's §8 contract must decide that separately.

## 7.2.3 Invalid transitions

Every one of these was measured with the installed CLI, and each produces exit 1 with a rendered
diagnostic — never exit 70.

| Attempt | Diagnostic |
|---|---|
| `x = (` | `error: expected an expression`, help `Found the end of the line instead.` |
| 200 nested parentheses | `error: expression is nested too deeply` |
| `use java.time.LocalDate` under `oq build` | not an error: `codepath: interpreter launcher ('use' imports a Java class, which is not compiled yet)` |
| a top-level variable read inside a `fun` | `codepath: interpreter launcher ('x' is read from an enclosing scope; only functions that use their own parameters and locals are compiled)` |
| a function name used as a value | not a refusal: it compiles, and `f = double` then `f(4)` prints `8` in the jar and under `oq run` |
| a variable shadowing a function, then called | `error: 'abs' is not a function`, help naming the value's type |
| two `fun f(a)` | `error: 'f' is already defined with 1 parameter` |
| a name declared twice in one scope | `codepath: interpreter launcher ('x' is declared twice in the same scope, which the interpreter refuses, so this is not compiled)` |

The distinction in this table is the model: **the first two are source errors (`SX`), and the
rest are refusals (`S6` → `S9`)**. A source error stops the build; a refusal changes the backend.

`oq check` is deliberately shallow. It runs `S0`–`S3` and stops, so it answers "is this file
parseable?" and nothing else:

```
$ oq check break.oq      # contains only: break
OK break.oq
```

That program fails at run time with `error: 'break' outside a loop`. This is a known and
recorded limitation (`K13`), not a claim that `oq check` validates a program.

## 7.2.4 Where the states are observable

Four observation points exist, and a test that wants to see the compiler's state machine has to
use one of them:

| Observation | Command | What it shows |
|---|---|---|
| Backend chosen | `oq build f.oq` → `codepath: ...` | `S7` or `S9`, plus the `S6` reason |
| IR | `oq build f.oq --emit-ir` | `S5` output: `function <name>` then one line per instruction with its index |
| Diagnostics | `oq run f.oq` | `SX` rendering |
| Artifact metadata | `unzip -p f.jar META-INF/MANIFEST.MF` | `Oqthara-Backend: classfile-compiled` vs `classfile-launcher` |

`oq build` also prints `warning: <divergence> — the interpreter is authoritative` for each
entry in `Lowering.Result.divergences()`. That list is currently always empty, which is itself a
property worth pinning: the lowering refuses rather than diverges, so the warning channel is
reserved for a future lowering that can degrade gracefully.

## 7.2.5 Test mapping

| Invariant | Test |
|---|---|
| `K1` | `ErrorMessageContractTest.everyErrorHasAPositionOrAnExplicitReason`; `LexerTest.tracksLineAndColumn` |
| `K2` | `BuildJarTest.jarErrorsUseTheOriginalFileName`; `BytecodeCompilerTest` fallback cases |
| `K3`, `K4` | `LoweringTest` refusal cases; `BytecodeCompilerTest.aVariableShadowingABuiltinIsRefusedNotSilentlyCompiled`, `.anArityTheBuiltinRejectsIsRefusedNotCompiled` |
| `K5` | `BytecodeCompilerTest.aTryExpressionProducesTheSameValueInBothCodepaths`; nested-`try` cases |
| `K6` | `BytecodeCompilerTest.aVariableShadowingABuiltinIsRefusedNotSilentlyCompiled`, `.aCallBeforeTheShadowingAssignmentStillCompiles` |
| `K7` | `BytecodeCompilerTest.aCapturingLambdaFallsBackInsteadOfBindingAStaleValue` |
| `K8` | absence — `oq-core` contains no type checker; recorded in §8's contract |
| `K9` | `BuildJarTest.generatedClassIsAValidClassFile` |
| `K10` | `BuildJarTest.jarContainsManifestProgramAndSource`; `BytecodeCompilerTest.compiledJarContainsNoEmbeddedSource` |
| `K11` | `BuildJarTest.jarErrorsUseTheOriginalFileName` |
| `K12` | `scripts/verify-conformance.py`; `scripts/check-docs-sync.py` (`backend.md` claim) |
| `K13` | no test — recorded limitation; `oq check` runs `S1`–`S2` only |

## 7.2.6 Open defects in this model

Recorded here rather than only in the issue tracker, because a model that hides its own gaps is
worse than no model:

1. **`K13` — `oq check` does not check.** It parses and reports OK. A declaration that will be
   refused at run time passes. The fix is to run `S3`–`S6` and report the `Ir.Refuse` reason
   without building a jar; that makes `oq check` a real gate and gives editors a correct signal.
2. **No artifact verification beyond magic bytes.** `C33`–`C37` produce a jar that has never been
   verified by the JVM until someone runs it. `PipelineFuzzTest` asserts the magic in the fuzz
   profile; the default test suite has no `VerifyError`-catching step. A `ClassFile` load in
   `BuildJarTest` would catch a malformed constant pool before a user does.
3. **`Oqthara-Backend` is asserted for the compiled path but not required per feature.**
   `BytecodeCompilerTest.compiledJarContainsNoEmbeddedSource` does assert
   `classfile-compiled`, so the manifest value itself is covered. What is not covered is a
   per-feature requirement that a given construct compiles: only `verify-conformance.py`'s
   global count (192 of 207) would notice a feature quietly leaving the bytecode path.
