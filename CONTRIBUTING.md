# Contributing to Oqthara

Thanks for helping. Oqthara is a small language, and that is the point: every change
must make it smaller or clearer, not just bigger.

## Ground rules

1. **Simplicity is the feature.** Before adding a keyword, ask whether an existing
   construct already solves it. The keyword budget is 30; we are at 18.
2. **No Rust.** No lifetimes, ownership, `&`, `Option`/`Result` wrappers, `impl`,
   `trait`, macros with `!`, or `=>` in matches. If it looks like Rust, redesign it.
3. **Every user-visible change needs**: code, a test, documentation, and a
   `CHANGELOG.md` entry.
4. **Executed evidence.** Do not mark something as working without pasting the command
   and its output in the pull request.

## Getting started

```bash
git clone https://github.com/JoaoValentimTheo/oqthara
cd oqthara
./gradlew build
./gradlew :oq-cli:run --args="examples/hello.oq"
```

Java 21 is the baseline. JDK 25 is also supported and tested in CI.

## Where things live

| Directory | Contents |
|---|---|
| `oq-core` | lexer, parser, AST, interpreter, diagnostics |
| `oq-runtime` | value model and host interfaces (no external dependencies) |
| `oq-stdlib` | built-in functions, JSON, HTTP, files, Java bridge helpers |
| `oq-cli` | the `oq` command: run, repl, check, fmt, new |
| `oq-compiler` | reserved for the bytecode backend |
| `conformance` | `.oq` programs with expected `.out` or `.err` |
| `examples` | runnable programs used in docs and tests |
| `docs` | reference, design, ADRs |

## Adding a conformance case

Create `conformance/NNN-short-name.oq` and either:

- an `.out` file with the exact expected standard output, or
- an `.err` file with a fragment that the diagnostic must contain.

The number must be the next unused one. Do not delete a passing case.

## Testing

```bash
./gradlew test            # unit tests + conformance
./gradlew build           # everything CI runs
```

## Commit messages

Conventional Commits: `feat(parser): ...`, `fix(cli): ...`, `docs: ...`,
`test: ...`, `chore: ...`.

## Reporting bugs

Include a minimal `.oq` program, the full output, and `oq --version`.
