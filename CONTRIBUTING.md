# Contributing to Oqthara

Thanks for helping. Oqthara is a small language, and that is the point: every change
must make it smaller or clearer, not just bigger.

## Ground rules

1. **Simplicity is the feature.** Before adding a keyword, ask whether an existing
   construct already solves it. The keyword budget is 30; we are at 22.
2. **No Rust.** No lifetimes, ownership, `&`, `Option`/`Result` wrappers, `impl`,
   `trait`, macros with `!`, or `=>` in matches. If it looks like Rust, redesign it.
3. **Every user-visible change needs**: code, a test, documentation, and a
   `CHANGELOG.md` entry.
4. **Executed evidence.** Do not mark something as working without pasting the command
   and its output in the pull request.

## Getting started

```bash
git clone https://github.com/Oqthara/oqthara
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

A full `./gradlew build` takes about three minutes with a warm cache and no up-to-date tasks.
Most of that is one test class: `oq-cli`'s conformance test builds and runs a jar per compilable
program, and `HostBuiltinLimitsTest` deliberately waits out two 30-second command timeouts. A
long build is not a hang. `--profile` writes a per-task report under `build/reports/profile/`.

## Review

Every pull request needs a review record at `docs/governance/reviews/PR-<number>.md` before it
can merge. The required `ubuntu-latest / JDK 21` context runs `scripts/check-ai-review.py`,
which fails the pull request if the record is missing, does not cover all seven categories, or
leaves a critical or high finding unresolved. The record format and the category list are in
[`docs/governance/reviews/README.md`](docs/governance/reviews/README.md); the policy behind it
is [`docs/governance/ai-review-policy.md`](docs/governance/ai-review-policy.md).

The review is not an approval gate with a human behind it. Oqthara has one maintainer, so
`required_approving_review_count` is 0 — the reasoning is in
[`docs/governance/remediation/solo-maintainer-exception.md`](docs/governance/remediation/solo-maintainer-exception.md).
Do not describe an AI review as independent human review; it is neither.

## Commit messages

Conventional Commits: `feat(parser): ...`, `fix(cli): ...`, `docs: ...`,
`test: ...`, `chore: ...`.

## Reporting bugs

Include a minimal `.oq` program, the full output, and `oq --version`.
