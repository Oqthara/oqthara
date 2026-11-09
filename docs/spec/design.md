# Oqthara design

## Goals

1. **Read like pseudocode.** A reader who knows any programming language should be
   able to skim an Oqthara file and follow it.
2. **Make the boring parts of Java disappear.** No `main`, no `new`, no getters, no
   checked exceptions, no type ceremony, no stream pipelines.
3. **Stay on the JVM.** Any Java library is reachable. Any Oqthara code can call Java.
4. **Excellent errors.** Every diagnostic has a file, line, column, the source line
   with a caret, a plain-language explanation and a suggestion.
5. **Small surface.** At most 30 keywords. One obvious way to do each thing.
6. **Fast feedback.** `oq run file.oq` works with no build file, no daemon, no
   configuration.

## Non-goals

- Not a systems language. No manual memory management, no pointers, no ownership.
- Not a replacement for Java in large legacy codebases. Interop is the bridge.
- Not an FP-first language. Functions are values, but there is no monad vocabulary.
- Not a Rust lookalike. No lifetimes, borrow markers, `Option`/`Result` envelopes,
  traits or macros with `!`.
- Not a dynamic language by accident. Types are inferred, but the long-term plan is
  an optional type checker, not a permanently untyped runtime.

## The shape of the language

```
name = "World"
print "Hello, {name}!"

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

- Statements end at a newline. Semicolons are optional and never required.
- Blocks open with a keyword and close with `end`.
- `else if` chains without a closing `end` between branches.
- `print` is a command: `print a, b, c` prints them space-separated.
- Collections are literals: `[1, 2]`, `{"a": 1}`, `{1, 2}`.
- Every collection has methods: `.map`, `.filter`, `.sort`, `.sum`, `.join`, and so on.
- `data` generates a constructor, field access, `equals`, `hashCode` and `toString`.
- `use java.time.LocalDate` imports a Java class. `LocalDate(2026, 1, 1)` constructs
  it and `.plusDays(3)` calls a method on it, using the same dot syntax as Oqthara
  values.
- Errors are exceptions. `nothing` is the absent value, and the runtime reports the
  exact source position when something is used on it incorrectly.

## Simplicity metrics

| Metric | Target | Current |
|---|---|---|
| Keywords | ≤ 30 | 21 |
| Hello world | 1 line | 1 line (`print "Hello, World!"`) |
| Tutorial concepts before first useful program | ≤ 3 | 2 (`print`, text) |
| Ways to declare a variable | 2 (`x = ...`, `const x = ...`) | 2 |
| Ways to write a function | 2 (`fun` declaration, `->` lambda) | 2 |

The keyword count is enforced by `scripts/check-docs-sync.py`, which reads
`Lexer.KEYWORDS` and `CONTRIBUTING.md` together and fails CI when either the count or
the documented list drifts. Adding a keyword requires an ADR and an edit to both, so the
count cannot change silently.

## Architecture

There are two execution paths, built from one front end.

```
source (.oq)
   |
   v
 Lexer  -> tokens (with spans)
   |
   v
 Parser -> AST
   |
   +---------------------------+
   |                           |
   v                           v
 Interpreter                 Lowering -> IR (flat instructions)
 (tree-walking)                 |
   |                            v
   |                         BytecodeCompiler -> class file
   |                            |
   +-------------+--------------+
                 |
                 v
       Runtime values (List, Map, Set, OqData, OqRange, Java objects)
       Stdlib (text, files, JSON, time, HTTP, processes)
       JavaBridge (reflection for `use`, constructors, methods, static calls)
```

`oq run` uses the interpreter. `oq build` lowers to IR and compiles it, and falls back
to a launcher that interprets the embedded source when the compiler refuses a construct
(the reason is printed as `codepath: interpreter launcher (<reason>)`). Both paths must
pass the same conformance suite: `conformance/` is the contract between them.

The state models are in `docs/spec/states/`. `01-language.md` covers the interpreter's language
states, `02-compiler.md` covers the build pipeline, `03-runtime.md` covers a running program and
`04-unified.md` states the contract between the two execution paths, including the four places
where they are allowed to differ.

## Error philosophy

An error message is a product surface. It must answer three questions:

1. **What went wrong?** `index 5 is out of range for a list of length 3`
2. **Where?** file, line, column, source line, caret. A compiled jar carries no program text,
   so it gives the position without the quoted line (`04-unified.md`, `U4`).
3. **What do I do now?** `Valid indexes are 0 to 2, or negative from the end.`

Internal errors are prefixed with `internal error:` and say that it is a bug in
Oqthara, never blaming the user.

### What the conformance harness enforces

A `*.err` file holds the **exact** message, without the `error: ` prefix the CLI adds. The
harness compares the whole first line of the diagnostic, not a substring, so an error that
gains a prefix, drops a detail, or reports a different problem no longer passes. It also
requires the diagnostic to carry a position (`--> <case>.oq:<line>:<column>`), because the
second question above is part of the contract rather than a nicety.

Assertion on the first line rather than on the whole stderr leaves the excerpt and the hint
free to improve without rewriting expectations, while pinning the part a user reads first.

## Compatibility policy

- Before 1.0, minor versions may change behaviour; patch versions do not.
- After 1.0, SemVer applies to the language surface, the CLI flags and the public
  stdlib. Removing or changing a public function requires a major version.
- The conformance suite is append-only. A case may be corrected, but a passing case
  may not be deleted.
