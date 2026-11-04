# Oqthara

Oqthara is a small programming language for the JVM that reads like pseudocode. It is
implemented in Java, compiles to nothing but runs everything, and gives full access to
Java libraries through `use`.

Repository: https://github.com/Oqthara/oqthara
CLI: `oq`. Extension: `.oq`. Maven group: `dev.oqthara`. License: MIT. Java baseline: 21.

## Golden rules

1. Never say "done". Close a task, pick the next one, continue.
2. Never ask the user. Decide, record an ADR, keep going.
3. State lives on disk (`.oqthara-ai/`), never only in context.
4. Nothing is done without executed evidence: paste the command and its result.
5. `main` is always green. Short branches, small PRs, squash merges.
6. Steps are small: at most ~30 minutes and ~400 changed lines per task.
7. Simplicity is the product. Apply the simplicity test before adding anything.
8. Status must be honest. Failures are recorded as failures.
9. Non-destructive: no `sudo`, no force-push to `main`, no deleting tags or releases,
   no working outside the project directory, no secrets in the repository.

## Simplicity test

Before a feature: can a beginner explain it in one sentence? Does it add a keyword
(budget: 30, currently 20)? Does an existing feature already solve it? Is there exactly
one obvious way? Does it look like Rust (then redesign)? Does hello world stay one line?

## Never do Rust

No lifetimes, ownership, `&`/`&mut`, `let mut`, `fn`, `->` return types, `::` paths,
`=>` in matches, `#[...]`, `impl`/`trait`, `unsafe`, `Option`/`Result` everywhere,
mandatory semicolons, or the words crate/borrow/move. Memory belongs to the JVM GC.

## Layout

`oq-core` (lexer, parser, AST, interpreter, diagnostics), `oq-runtime` (values, host),
`oq-stdlib` (built-ins, JSON, HTTP, files), `oq-compiler` (bytecode backend, later),
`oq-cli` (the `oq` command), `conformance` (the backend contract), `examples`,
`docs` (spec, design, ADRs), `site`, `scripts`.

## Commands

```bash
./gradlew build                                   # everything CI runs
./gradlew test                                    # unit + conformance
./gradlew :oq-cli:run --args="examples/hello.oq"  # run an example
./gradlew :oq-cli:standaloneJar                   # build oq.jar
```

## Language, in one screen

```
print "Hello, World!"

data Point(x, y)

fun distance(a, b)
  dx = a.x - b.x
  dy = a.y - b.y
  return sqrt(dx * dx + dy * dy)
end

for n in 1 to 5
  print n
end
```

Blocks close with `end`. Statements end at a newline (semicolons optional). Collections
are literals. Every collection has `.map`, `.filter`, `.sort` and friends. `use` imports
any Java class.

## To resume

Read `.oqthara-ai/RESUME.md` first, then `.oqthara-ai/MASTER_PROMPT.md`,
`state.json`, `STATUS.md` and the newest `checkpoints/CP-*.md`.
