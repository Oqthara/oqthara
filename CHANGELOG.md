# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
