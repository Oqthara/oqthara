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

Tracked in `STATUS.md` on every cycle:

| Metric | Target | Current |
|---|---|---|
| Keywords | ≤ 30 | 20 |
| Hello world | 1 line | 1 line |
| Tutorial concepts before first useful program | ≤ 3 | 2 (`print`, text) |
| Ways to declare a variable | 1 | 1 |
| Ways to write a function | 2 (`fun` declaration, `->` lambda) | 2 |

The keyword list is checked by a test in `oq-core`. Adding a keyword requires an ADR
and a line in the "Simplicity" table of `STATUS.md`, so the count cannot drift
silently.

## Architecture

```
source (.oq)
   |
   v
 Lexer  -> tokens (with spans)
   |
   v
 Parser -> AST
   |
   v
 Interpreter (tree-walking, todays backend)
   |
   +-> Runtime values (List, Map, Set, OqData, OqRange, Java objects)
   +-> Stdlib (text, files, JSON, time, HTTP, processes)
   +-> JavaBridge (reflection for `use`, constructors, methods, static calls)
```

The bytecode backend (`oq-compiler`) will consume the same AST and must pass the same
conformance suite. `conformance/` is the contract between backends.

## Error philosophy

An error message is a product surface. It must answer three questions:

1. **What went wrong?** `index 5 is out of range for a list of length 3`
2. **Where?** file, line, column, source line, caret.
3. **What do I do now?** `Valid indexes are 0 to 2, or negative from the end.`

Internal errors are prefixed with `internal error:` and say that it is a bug in
Oqthara, never blaming the user.

## Compatibility policy

- Before 1.0, minor versions may change behaviour; patch versions do not.
- After 1.0, SemVer applies to the language surface, the CLI flags and the public
  stdlib. Removing or changing a public function requires a major version.
- The conformance suite is append-only. A case may be corrected, but a passing case
  may not be deleted.
