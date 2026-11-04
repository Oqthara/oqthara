# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Coverage-guided fuzzing with Jazzer 0.30.0: four targets covering the lexer, parser,
  nesting depth and the full compile pipeline, each in its own Gradle task and a dedicated
  `jazzer-fuzz` CircleCI job. A bounded run executes ~1.7 million cases.
- A curated regression corpus replayed by `CuratedCorpusTest` on every build.
- `oq build` compiles straight-line arithmetic, text concatenation and `print` to real JVM
  bytecode when the whole program is in that subset, embedding no source; anything else falls
  back to the launcher jar with a printed reason. Verified instruction-for-instruction with
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

[Unreleased]: https://github.com/Oqthara/oqthara/compare/v0.1.0-alpha.1...HEAD
[0.1.0-alpha.1]: https://github.com/Oqthara/oqthara/releases/tag/v0.1.0-alpha.1
