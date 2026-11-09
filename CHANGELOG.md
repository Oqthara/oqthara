# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Function overloading by parameter count.** A name may now carry several definitions as long
  as they differ in arity, and a call selects the definition whose parameter count matches. This
  replaces a silent data-loss defect: the second `fun area(...)` used to overwrite the first, so
  `area(2, 3)` failed with "area() expects 1 argument, got 2" and the one-parameter body was
  unrecoverable. Resolution is deterministic, independent of declaration order, and identical in
  the interpreter and in compiled bytecode. Two definitions with the same arity are now rejected
  where the second is declared, and a user definition replaces a built-in of the same name
  instead of colliding with it. Oqthara has no parameter type annotations, so arity is the only
  discriminator; the rule is specified in [ADR-0012](docs/adr/0012-overload-resolution.md).
- `arity()` on `OqCallable`, with `VARIADIC` for callables that accept no single parameter count,
  so a variadic built-in can never silently win an exact-arity resolution.
- **Destructuring assignment and binding.** `a, b = (1, 2)` binds or updates several names from
  one value, and `const c, d = ...` binds them read-only. The value may be a tuple, a list, a
  map (yielding keys), or text (yielding characters); the number of values must match the number
  of names exactly. All names are checked before any is written, so a failed destructuring leaves
  every target unchanged rather than half-updated. Destructuring is a binding operation, not
  pattern matching: there is no rest target, `_` is not a discard, and `(a, b) = x` remains a
  tuple literal on the left and is rejected. The rule is specified in
  [ADR-0013](docs/adr/0013-destructuring.md).

### Fixed

- **The conformance verifier now gates CI and tells the truth.** `scripts/verify-conformance.py`
  pinned a stale corpus size (197 executable programs) and a floor (182 compiled) that sat ten
  programs below reality, so it failed on a healthy tree while a ten-program bytecode
  regression would have passed. It pins the measured baseline (207 executable, 192 compiled)
  and now runs as a required CI step, which also covers the interpreter-against-jar comparison
  that previously ran nowhere. Its decision rules have regression tests in
  `scripts/tests/test_verify_conformance.py`.
- **`CONTRIBUTING.md` no longer understates the keyword count.** It claimed 18 keywords when
  the lexer defines 20; `try` and `catch` landed without the count being updated.
  `scripts/check-docs-sync.py` now reads `Lexer.KEYWORDS` directly instead of restating the
  names, and fails when `CONTRIBUTING.md` disagrees with the lexer in either direction.
- A range's length is documented as 64-bit. `docs/spec/language.md` now states that
  `length(r)` does not narrow through a 32-bit integer, and `StdlibCoverageTest` asserts
  `length(0 to 3000000000)` at the language level. Both were missing; the behavior itself was
  already correct.

## [0.1.0-alpha.3] - 2026-11-06

### Added

- **Tuples.** One comma makes a tuple: `(1, 2, 3)`, `(5,)` for a single value, and `((1, 2), 3)`
  to nest. `(a)` is still grouping, so parentheses keep their old meaning, and `()` is an error
  rather than an empty tuple. A tuple is immutable: indexing, `length`, iteration,
  `contains`, equality and lexicographic ordering all work, and every list mutator
  (`push`, `pop`, `sort`, `add`, `remove`, `remove_value`, `insert`, `reverse`, `clear`, `fill`,
  `shuffle`, `sort_by`, and assignment to an index) refuses with `a tuple is immutable` instead
  of silently copying. The names that only exist as `java.util.List` methods — `set`, `addAll`,
  `removeAll`, `retainAll`, `removeIf`, `replaceAll` — refuse the same way rather than leaking
  a Java exception. `.unique()` returns a tuple, `t.subList(0, 2)` returns a tuple slice, and a
  tuple can hold `nothing`. A tuple is never equal to a list, so `(1, 2) == [1, 2]` is `false`
  in both backends. Tuples compile to real bytecode, so `oq build` matches `oq run`, including
  a tuple passed as a function argument. The decision is
  recorded as `OQ-DEC-2026-006` in [the governance log](docs/governance/README.md).
- The specification now states operator precedence as a ten-level table read out of the
  parser, including the divergence that unary binds tighter than `**`, so `-2 ** 2` is `4`
  rather than Python's `-4`. It also specifies `format`, `write`, compound assignment and the
  `in`/`to` interaction that requires parentheses.

### Changed

- Host calls are bounded. `print`, `write` and `write_text` stop at 16 MiB of output, refused
  before anything is written; `read` and `read_lines` refuse a file over 16 MiB by capping the
  bytes read, so a pipe or `/dev/zero` cannot slip past; `input()` caps a line at 16 MiB
  instead of building it unbounded; `run_command` gives up after 30 seconds of wall clock in
  total and refuses output over 16 MiB, killing the whole process tree rather than leaking the
  children it started; `http_get` and `http_post` bound the body as well as the headers, so a
  server that stalls mid-body cannot hang the program, and stop at 16 MiB instead of
  buffering; `sleep` is capped at 10 seconds per call; `save` and `append` refuse more than
  16 MiB in one call; `list_files` caps a directory at 100,000 entries; `sleep` is bounded at
  60 seconds in total, because the per-call cap alone let a loop wait forever ten seconds at a
  time. Output has its own 16 MiB counter, and the newline `print` adds and the prompt `input`
  writes count against it, so neither is a way to emit unbounded bytes. That counter covers
  output only — reading a file does not draw on it, because reading is not writing output.
  The transfers that do bill the step budget are charged on top of their per-call limits, in
  the unit each one actually moves: bytes for `read`, `save`, `append`, HTTP and `run_command`,
  characters for `input`, directory entries for `list_files`. Output is the exception — `print`,
  `write` and `write_text` are bounded by the output counter and charge no steps. The older
  256-steps-per-byte rate made the documented 16 MiB unreachable, because `read` tripped the
  50,000,000-step budget at 195,312 bytes and reported `program ran for too long` instead of
  the size error.
  The specification states these limits and states plainly that they are resource controls,
  not a sandbox — and that a jar built as pure compiled bytecode has no step budget or
  deadlines, only the 16 MiB output cap, while a jar built as an interpreter launcher
  enforces every limit.

### Fixed

- `run_command` leaked the processes a timed-out command had started: only the direct child
  was destroyed. The whole tree is now killed and the program reports
  `command timed out after 30 seconds`.
- The conformance harness accepted an error message that merely contained the expected text,
  so an error that gained a prefix, lost a detail, or reported a different problem still
  passed. It now compares the whole first line and requires the diagnostic to carry a
  position.
- The compiled conformance count in `docs/spec/backend.md` drifted from what the suite
  measured; the documentation checker now compares it against both the test and the corpus.
- A jar built as compiled bytecode printed a raw `OutOfMemoryError` stack trace when the heap
  ran out, and compiled jars in general let any non-`OqError` failure escape the same way. The
  generated top-level handler now catches `Throwable` and routes the heap and stack cases
  through the renderer `oq run` already used, so both backends print `the program ran out of
  memory` and exit 1.
- `"ab" * 4611686018427387905` silently returned `ab`. The repetition product overflowed a
  `long`, slipped past the length check as a negative number, and repeated once. Both text and
  list repetition now use an exact product and refuse with `the repetition is too large to
  build`.
- The REPL never reset the step budget, so a long session eventually refused every command it
  had left. Each command now starts with the budget it would have on its own.
- `"" * 3000000000` failed with `internal error: IllegalArgumentException: count is negative`,
  and `[] * 4611686018427387905` looped over four quadrillion empty iterations. Repeating an
  empty text or list now returns empty immediately, without consulting the size guard that an
  empty value makes meaningless.
- `subList` was not part of the list API, so it fell through to `java.util.List.subList` and
  returned a live view of a JDK-private class. On a mutable list that view wrote through to the
  parent; on a tuple, mutating it leaked either `IllegalAccessException` or, for `sort`, a
  sorted list — a quietly mutable tuple. `subList` and `slice` are now real methods that return
  a copy, clamped to the sequence and keeping their type, so `t.subList(0, 2)` is a tuple and
  every mutator on it refuses with `a tuple is immutable`.

### Documentation

- `run_command` is documented as taking one executable path with no arguments and no shell,
  which is what it has always done and what its tests now assert.
- The version declaration agrees across the CLI, the build, the LSP server, the specification
  header and this changelog, and the documentation checker now compares the specification
  header and the LSP server so they cannot drift again.

## [0.1.0-alpha.2] - 2026-11-05

### Added

- `oq build` compiles lambdas that capture nothing, and passes them to stdlib methods:
  `double = x -> x * 2` and `xs.map(x -> x * x)` now produce real bytecode. Each lambda
  becomes one static method on the generated class plus a `runtime.CompiledFunction` value
  holding that method's name, so the call dispatches through the value rather than through a
  name, and a builtin with the same name cannot win. `Methods` now dispatches an
  `OqCallable` instead of refusing every callback. Conformance programs compiling to
  bytecode rose from 169 to 182 of 197 with zero divergences. A lambda that captures an
  enclosing variable is still refused: capturing would copy the value at creation time
  while the interpreter reads it at call time.
- `oq build` now dispatches deterministic stdlib builtins from generated bytecode:
  `abs`, `floor`, `ceil`, `round` (both arities), `sqrt`, `pow`, `min`, `max`, `clamp`,
  `number`, `text`, `type_of`, `is_nothing`, `is_number`, `is_text`, `upper`, `lower`,
  `trim`, `split`, `join`, `replace`, `contains`, `format`, `set`, `list`, `zip`, `keys`,
  `values`, `sum`, `sort`, `reverse`, `unique`, `push`, `pop`, `clone`, `range`, `assert`
  and the JSON builtins (`json_parse`, `json`, `json_write`, `json_pretty`). Generated code
  calls `stdlib.Builtins`, which installs the real `Stdlib` and invokes the same
  `NativeFunction` the interpreter uses, so no builtin is implemented twice. Variadic
  `print a, b` and `write a, b` also compile. Conformance programs compiling to bytecode
  rose from 145 to 169 of 197, with zero divergences from `oq run`. Builtins that depend on
  the host — files, clock, randomness, network, process state, `args`/`input`, and the
  callable-taking `spawn`/`await`/`par_map`/`map`/`filter` — are still refused by name and
  fall back to the launcher jar.
- A compiled jar now renders an uncaught `OqError` instead of a Java stack trace. The
  generated `main` carries one exception-table row over its whole body that hands the error
  to `EmbeddedMain.renderUncaught`, which uses the same `Details`/`Diagnostics` renderer as
  `oq run`; message, `file:line:column` and hint are byte-identical and the exit code is 1.
  The source line and caret are omitted because a compiled jar embeds no source. A program's
  own `try`/`catch` still wins for its range, so caught errors stay caught. Source position
  marks are now emitted for every operation that can raise, not only inside `try`, which is
  what makes an uncaught error report the interpreter's exact location.
- `oq build` compiles collections to real JVM bytecode: list, set and map literals,
  `xs[i]` reads, `xs[i] = v` writes, `length(x)` over text/list/set/map/range, `1 to 5` as
  a value, `x in collection`, and `for x in list / set / map / range / text` using a real
  iterator. Conformance programs compiling to bytecode rose from 52 to 72 of 197, still with
  zero divergences from `oq run`. Collections reuse the interpreter's own `Values` methods
  through `runtime.Collections`, so the backends cannot drift. Method calls (`xs.push(4)`),
  `data` and other builtins are refused by name and fall back to the launcher jar.
- `fuzzStdlibBuiltins` fuzzes the pure stdlib builtins, and `fuzzJsonParse` now round-trips
  through both JSON writers and bounds nesting. Bounded by `@FuzzTest(maxDuration)`, never run
  by `./gradlew build`.
- Fixed: `write "a"` produced bytecode that underflowed the operand stack, because the
  lowering popped a value the void call never pushed. Found while measuring the collections
  subset; now covered by a regression test.
- `oq build` compiles top-level `fun` declarations to real static JVM methods: parameters,
  return values, recursion and mutual recursion. Every parameter is one boxed `Object`, the
  descriptor is `(Ljava/lang/Object;...)Ljava/lang/Object;`, calls are `invokestatic` and
  `return` is `areturn`. Conformance programs compiling to bytecode rose from 41 to 52 of
  197, with zero divergences from `oq run`. A function that reads or writes a top-level
  variable, and a function named `print` or `write`, is refused by name and falls back to
  the launcher jar.
- Coverage-guided fuzzing with Jazzer 0.30.0: four targets covering the lexer, parser,
  nesting depth and the full compile pipeline, each in its own Gradle task and a dedicated
  `jazzer-fuzz` CircleCI job. A bounded run executes ~1.7 million cases.
- A curated regression corpus replayed by `CuratedCorpusTest` on every build.
- Fuzz targets for the JSON reader and writer, plus JSON nesting depth.
- Fixed: `json_parse(".")` escaped as a raw `NumberFormatException`; malformed numbers now
  produce a JSON syntax error. Found by fuzzing.
- Fixed: 390+ levels of nested parentheses, and 2000 nested blocks, crashed the checker with a raw
  `StackOverflowError` and exit 70. Found by fuzzing; now a normal diagnostic. The parser
  enforces a 128-level limit for both expressions and statement blocks.
- `oq build` compiles straight-line arithmetic, local variables (assignment, reassignment
  and reads), text concatenation and `print` to real JVM bytecode when the whole program is
  in that subset, embedding no source; anything else falls back to the launcher jar with a
  printed reason. Verified instruction-for-instruction with
  `javap` and by running the generated jars on JDK 21 and 25.
- `oq build` now produces an executable jar: a real class file written by Oqthara's own
  emitter, the interpreter and stdlib bundled in, the program source and its original name
  embedded. `java -jar app.jar` matches `oq run app.oq` exactly across all 28 deterministic
  examples. Works on JDK 21 and 25, with no external library.
- Relative file paths resolve against the program's directory, so runs are identical from
  any working directory.
- Generated jars pass program arguments through (`args()` works), encode the constant pool
  as modified UTF-8 so emoji survive, embed module sources, and reject unsafe jar entry
  names.
- `oq build` now lowers `break` and `continue` to real labeled jumps; nested loops target
  the innermost loop and the divergence warning is gone.
- `docs/spec/backend.md` records exactly what `oq build` does not do yet (no class files,
  placeholder lambdas, no interop or modules).
- `oq doc` generates `docs/stdlib/reference.md` from the implementation; CI fails when the
  checked-in copy is stale.
- Keyword count corrected from 18 to 20 in every document (the lexer is authoritative).
- `oq fmt` rewritten: canonical indentation and spacing, idempotent, output always parses,
  verified against every example.
- VS Code extension in `editors/vscode`: TextMate grammar with interpolation support,
  14 snippets, indentation rules, and tests that keep it in sync with the lexer.
- Distribution: `scripts/install.ps1`, a multi-stage `Dockerfile`, and
  `./gradlew :oq-cli:packageApp` producing a native launcher with an embedded JRE.
- `oq test` discovers `test_*.oq`, `*_test.oq` and anything under `tests/`, prints a
  pass/fail line per file, and exits non-zero when a test fails.
- `examples/tests/` contains the first language-level test suite.
- `try`/`catch` with an error value exposing `message`, `hint`, `file`, `line`, `column`.
- `round(value, places)` for rounding to decimal places.
- Lexicographic list comparison, so a sort key may be a list.
- 33 runnable examples, including a calculator, a BFS maze solver, a Sudoku validator,
  a Tower of Hanoi solver and a memoisation helper.
- Modules: `use "oq:name"` loads another `.oq` file, searched next to the program, then in
  `modules/`, then in `lib/`; loaded once, cycle-safe, with errors that name the module.
- `spawn`, `await`, `done` and `par_map` running on virtual threads, with task errors
  re-raised at the `await` site.
- Three more examples (anagrams, BST, queue) and a regression suite where every test names
  the real program that found the bug.
- Data field defaults are evaluated per instance; a mutable default such as `items = []`
  no longer aliases between objects.
- Interpolation now works when the expression starts with a quoted string, while JSON-like
  text such as `"{\"a\": 1}"` stays literal.
- `to` and `until` are contextual keywords, so they can be used as variable and parameter
  names (`fun hanoi(n, from, to, via)` now parses).
- `break` and `continue` outside a loop now produce a user error at the right line instead
  of an internal error with exit code 70.
- Clearer hints for comparison, negation and iteration type errors.
- Deterministic parser and lexer fuzzing: 7,000 random inputs must either parse or raise an
  `OqError`, never crash.
- `oq build` reports every known IR divergence (`break`, `continue`) as a warning instead
  of silently producing different behaviour from the interpreter.
- `oq build` with AST-to-IR lowering, an IR disassembler (`--emit-ir`), and runtime
  backend detection: on JDK 24+ it selects the classfile backend, and on JDK 21-23 it
  explains the requirement and exits with code 3.
- New `bench-jmh` Gradle module with JMH benchmarks for steady-state interpreter
  throughput (`numericLoop`, `fib`, `listOps`, `stringBuild`). Each benchmark parses its
  program once, runs it on a fresh CLI-equivalent interpreter writing to an in-memory
  sink, and asserts the exact expected output before measuring. The module is not wired
  into `check`/`build`, and a JUnit test re-checks every workload without running JMH.

### Fixed

- Numeric builtins now reject a non-number with the usual `error: ... needs a number`
  diagnostic instead of escaping as a raw `IllegalArgumentException`, which the CLI rendered
  as `internal error ... This is a bug in Oqthara` and exit 70. `range` was the worst case
  because it converted its arguments directly. `abs(Long.MIN_VALUE)` now widens to a decimal
  instead of returning a negative number, and `floor`/`ceil`/`round` keep a value beyond
  `Long.MAX_VALUE` as a decimal instead of saturating. The JSON writer refuses non-finite
  numbers, which it previously wrote as `Infinity`/`NaN` even though its own reader rejects
  them, and the reader rejects a number with no finite value such as `1e400`.
- `f(f(f(...)))` nested thousands deep crashed with a raw `StackOverflowError` instead of a
  diagnostic: the parser released its nesting counter before parsing a call's arguments, so
  this shape never accumulated depth. The counter is now held across the argument list.
- `print` and `write` now compile when used as expressions, yielding `nothing`, so
  `x = print(1)` and `print(1) + 1` behave exactly as under `oq run` instead of falling back
  to the interpreter launcher.

## [0.1.0-alpha.1] - 2026-11-04

### Added

- The Oqthara language: expressions, variables, conditionals, loops, functions,
  closures, lambdas and `data` records.
- `end`-terminated block syntax, newline statement separators, optional semicolons.
- Text interpolation with `{expression}`, triple-quoted multi-line text, and escaped
  braces.
- Collections: list, map and set literals with a rich method surface (`map`, `filter`,
  `reduce`, `sort`, `join`, `sum`, `unique`, `flatten`, set algebra).
- Ranges with `to` and `until`, usable in `for` loops and as values.
- First-class functions, hoisted declarations and lexical closures.
- Java interoperability: `use java.time.LocalDate`, constructor calls, instance
  methods, static methods, and automatic conversions for common types.
- Standard library: text, files, JSON, time, HTTP, processes, math and randomness.
- Diagnostics with file, line, column, source excerpt, caret and a human hint.
- CLI: `oq <file>`, `run`, `check`, `repl`, `fmt`, `new`, `version`, `help`.
- 130 conformance cases plus unit tests for the lexer, parser, values and interpreter.
- GitHub Actions CI across Linux, macOS and Windows on JDK 21 and 25.

[Unreleased]: https://github.com/Oqthara/oqthara/compare/v0.1.0-alpha.3...HEAD
[0.1.0-alpha.3]: https://github.com/Oqthara/oqthara/releases/tag/v0.1.0-alpha.3
[0.1.0-alpha.2]: https://github.com/Oqthara/oqthara/releases/tag/v0.1.0-alpha.2
[0.1.0-alpha.1]: https://github.com/Oqthara/oqthara/releases/tag/v0.1.0-alpha.1
