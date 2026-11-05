# Oqthara

A small JVM language that reads like pseudocode. Java implementation, full Java interop
through `use`. CLI `oq`, extension `.oq`, Maven group `dev.oqthara`, MIT, Java 21 baseline.
Repo: https://github.com/Oqthara/oqthara

## Golden rules

1. The Kilo chat is the control plane: dispatch parallel specialist agents, integrate,
   validate, checkpoint, then start the next task. Never say the project is done.
2. Never ask the user: decide, record an ADR, continue.
3. State lives on disk in `.oqthara-ai/`, never only in context.
4. Nothing is done without executed evidence: paste the command and its result.
5. `main` stays green; short branches, squash merges, no force-push.
6. ~30 minutes and ~400 changed lines per task.
7. Simplicity is the product. 8. Honest status. 9. Non-destructive: no `sudo`, no
   deleting tags/releases, no secrets, stay inside the repo.

## Simplicity test

One sentence? Adds a keyword (budget 30, now 20)? Already solved? Exactly one obvious way?
Looks like Rust? Hello world still one line?

## Never Rust

No lifetimes, ownership, `&`/`&mut`, `let mut`, `fn`, `->` return types, `::` paths,
`=>` in matches, `#[...]`, `impl`/`trait`, `unsafe`, mandatory `Option`/`Result`, mandatory
semicolons, or the words crate/borrow/move. Memory belongs to the JVM GC.

## Layout and commands

`oq-core` (lexer, parser, AST, interpreter), `oq-runtime` (values, host), `oq-stdlib`
(built-ins, JSON, HTTP, files), `oq-compiler` (IR), `oq-cli` (`oq`), `conformance`,
`examples`, `docs`, `site`, `scripts`, `editors`.

```bash
./gradlew build                          # everything CI runs
./gradlew test                           # unit + conformance
./gradlew :oq-cli:standaloneJar

```

## Language in one screen

```
print "Hello, World!"

data Point(x, y)
fun distance(a, b)
  return sqrt((a.x - b.x) ** 2 + (a.y - b.y) ** 2)
end
for n in 1 to 5
  print n
end
```

Blocks close with `end`; statements end at a newline; `use` imports any Java class.

## Operation

Read `.oqthara-ai/MASTER_PROMPT.md` and `RESUME.md`; pick the next task; checkpoint.
Orchestration is chat-native (ADR-0009): no watchdog, launchd unit, or detached loop. Agents
live in `.kilo/agents/` (`oq-orchestrator` primary); delegate via the real `task` tool, one
git worktree per concurrent writer under `.worktrees/`. `.oqthara-ai/STOP` is an emergency
halt; never remove it automatically.
