# Oqthara language reference

Version: 0.1.0-alpha.3

## Running a program

```
oq hello.oq
```

A file is a script. It runs from the top. There is no `main`, no class, no package.

```
print "Hello, World!"
```

## Values

| Type | Example | Notes |
|---|---|---|
| number | `42`, `-7`, `1000` | 64-bit whole numbers |
| decimal | `3.14`, `1e9` | 64-bit floating point |
| text | `"hi"`, `'hi'` | UTF-8, immutable |
| boolean | `true`, `false` | |
| nothing | `nothing` | the absent value |
| list | `[1, 2, 3]` | ordered, mutable |
| tuple | `(1, 2)` | ordered, immutable, fixed length |
| map | `{"a": 1}` | key to value, insertion ordered |
| set | `{1, 2, 3}` | unique values |
| range | `1 to 5`, `0 until 5` | produced by range expressions |
| data | `Point(1, 2)` | declared with `data` |
| function | `x -> x + 1` | first-class values |
| Java object | `Path("a")` | anything from the JVM |

## Variables

```
name = "Ada"
count = 0
count = count + 1
```

Assignment creates the variable if it does not exist yet. There is no `let`, no `var`,
no `const`. Values are snapshotted on assignment: assigning a list to another variable
does not alias it for math purposes; both names still refer to the same list, and
methods that mutate a list (`.push`, index assignment) are visible through both.

## Text

```
greeting = "Hello, {name}!"
print "2 + 2 = {2 + 2}"
print "a tab:\tand a newline:\n"
print "literal braces: {{like this}}"
```

`{...}` interpolates any expression. `{{` produces a literal `{`.

Useful methods: `.upper()`, `.lower()`, `.trim()`, `.split(",")`, `.lines()`,
`.words()`, `.chars()`, `.replace(a, b)`, `.contains(s)`, `.starts_with(s)`,
`.ends_with(s)`, `.slice(from, to)`, `.repeat(n)`, `.pad_start(n, fill)`,
`.pad_end(n, fill)`, `.number()`.

## Numbers

```
print 2 + 3        # 5
print 10 / 4       # 2.5
print 10 / 2       # 5
print 7 % 3        # 1
print 2 ** 10      # 1024
```

Division produces a whole number when it is exact and a decimal otherwise. There is
no separate integer division operator.

Built-in functions: `abs`, `floor`, `ceil`, `round`, `round(value, places)`, `sqrt`,
`pow`, `min`, `max`, `clamp`, `random`.

Lists compare lexicographically, so a sort key can itself be a list:

```
# newest first, then alphabetical
items.sort(item -> [-item.count, item.name])
```

## Booleans and conditions

```
if score >= 90
  print "A"
else if score >= 80
  print "B"
else
  print "C"
end
```

Operators, from the parser itself, listed highest precedence first. Operators on the same
row associate as shown.

| Level | Operators | Associativity | Meaning |
|---|---|---|---|
| 1 | `f(x)`, `xs[i]`, `obj.field` | left | call, index, field |
| 2 | `-x`, `+x`, `not x` | right | unary minus, unary plus, logical not |
| 3 | `**` | right | exponentiation |
| 4 | `*`, `/`, `%` | left | multiply, divide, remainder |
| 5 | `+`, `-` | left | add, subtract |
| 6 | `<`, `<=`, `>`, `>=`, `in` | left | comparison, membership |
| 7 | `==`, `!=` | left | equality |
| 8 | `and` | left | short-circuit and |
| 9 | `or` | left | short-circuit or |
| 10 | `to`, `until` | none | range, only in `for` headers and range expressions |

Because unary minus binds tighter than `**`, `-2 ** 2` is `(-2) ** 2` and prints `4`.
This differs from Python, where it would be `-4`. Because `and` binds tighter than
`or`, `a or b and c` is `a or (b and c)`. Comparison operators share one level and
evaluate left to right, so `1 < 2 < 3` compares `1 < 2` with `3` and fails rather than
chaining.

`in` tests membership. For a list, map, set, range or text it reports whether the right
side contains the left side; text uses substring membership. It is not a `for` loop.

```
print 3 in [1, 2, 3]        # true
print "ell" in "hello"      # true
print "a" in {"a": 1}       # true
print 3 in (1 to 5)         # true
```

The parentheses in the last line are required. `in` binds tighter than `to`, so
`3 in 1 to 5` is read as `(3 in 1) to 5` and fails. Write `3 in (1 to 5)`.

### Assignment and compound assignment

```
count = 0
count += 1                  # count = count + 1
count -= 1
count *= 4
count /= 2
```

`=`, `+=`, `-=`, `*=` and `/=` are statements, not expressions. They return `nothing`
and cannot be nested inside an expression. The left side of a compound assignment must
be a plain variable; `xs[0] += 1` is rejected with "compound assignment needs a variable
on the left". Unlike `x = x + 1`, a compound assignment evaluates its target once.

Truthiness: `nothing`, `false`, `0`, `0.0`, `""`, empty collections and empty ranges
are falsy. Everything else is truthy.

## Loops

```
for n in 1 to 5
  print n
end

for n in 0 until 5
  print n
end

for item in ["a", "b"]
  print item
end

while running
  running = step()
end
```

`to` includes the end value. `until` excludes it. Loop variables are local to the
loop body. `break` and `continue` work inside all loops.

`to` and `until` are **contextual**: they only act as range operators between two
expressions, so they are still valid variable and parameter names.

```
fun count(from, until)
  total = 0
  for n in from until until
    total = total + 1
  end
  return total
end
```

## Functions

```
fun add(a, b)
  return a + b
end

print add(2, 3)
```

A function without an explicit `return` returns `nothing`.

Anonymous functions:

```
double = x -> x * 2
add = (a, b) -> a + b
```

Anonymous functions with a body:

```
handler = fun(x)
  y = x * 2
  return y + 1
end
```

Closures capture their environment:

```
fun counter()
  n = 0
  return fun()
    n = n + 1
    return n
  end
end
```

## Collections

```
xs = [1, 2, 3]
xs.push(4)
print xs[0]
print xs[-1]
xs[1] = 20

m = {"a": 1}
m["b"] = 2
print m.keys()

s = {1, 2, 3}
s.add(4)
```

List methods: `.length()`, `.push(x)`, `.pop()`, `.first()`, `.last()`, `.get(i)`,
`.insert(i, x)`, `.remove(i)`, `.map(f)`, `.filter(f)`, `.each(f)`, `.any(f)`,
`.all(f)`, `.find(f)`, `.find_index(f)`, `.reduce(f, start)`, `.sort()`,
`.sort(key)`, `.reverse()`, `.join(sep)`, `.contains(x)`, `.unique()`, `.flatten()`,
`.take(n)`, `.skip(n)`, `.sum()`, `.min()`, `.max()`, `.clone()`.

Map methods: `.length()`, `.keys()`, `.values()`, `.entries()`, `.get(k, fallback)`,
`.set(k, v)`, `.has(k)`, `.has_value(v)`, `.remove(k)`, `.clear()`, `.each(f)`,
`.map(f)`, `.clone()`.

Set methods: `.length()`, `.add(x)`, `.remove(x)`, `.has(x)`, `.items()`,
`.union(other)`, `.intersection(other)`, `.difference(other)`.

## Tuples

A tuple is a fixed-length, ordered, immutable group of values. It is not a list: a list
can grow and shrink, a tuple cannot, and `type_of` tells them apart.

```
point = (3, 4)
print point[0]        # 3
print point[1]        # 4
print type_of(point)  # tuple
print point           # (3, 4)
```

Syntax rules, exactly:

| You write | You get |
|---|---|
| `(a)` | grouping, not a tuple; `(1)` is the number `1` |
| `(a,)` | one-item tuple; the trailing comma is required |
| `(a, b)` | two-item tuple |
| `(a, b,)` | same tuple; a trailing comma is allowed |
| `()` | an error: an empty tuple is not a value |
| `((a, b), c)` | nested tuple |
| `(a, b) -> ...` | a lambda, not a tuple |

One comma makes a tuple; two or more values make a tuple. `(1)` is grouping because a
one-item tuple would otherwise be indistinguishable from a parenthesised expression, and
`()` is an error because there is nothing to hold.

Tuples support:

```
t = (1, 2, 3)
print t.length()          # 3
print t[0]                # 1
print t[-1]               # 3
print 2 in t              # true
print (1, 2) == (1, 2)    # true
print (1, 2) == (2, 1)    # false
print (1, 2) < (1, 3)     # true, compared left to right
for v in (1, 2)
  print v
end
```

Equality is structural and order sensitive. Comparison is lexicographic, item by item.
A tuple never equals a list, even with the same items and order, so `(1, 2) == [1, 2]`
is `false`. `for` iterates over a tuple in order. A function may take and return tuples:

```
fun swap(pair)
  return (pair[1], pair[0])
end

print swap((1, 2))        # (2, 1)
```

Mutation is refused; tuples are immutable. `.push`, `.pop`, `.insert`, `.remove` and
`.sort` raise an error naming the tuple, and `t[0] = 9` is rejected. To change a tuple,
build a new one.

Tuples are read-only from Oqthara and from Java-interop methods that require a `List`.
A tuple satisfies Java's `List` interface for reading, but mutating it through Java
throws `UnsupportedOperationException`.

**Deferred.** Tuple destructuring (`a, b = pair`), named or typed tuple fields, and tuple
arithmetic (`+`, `*`) are not part of Alpha 3. See `docs/governance/roadmap-alpha-3.md`.

## Data types

```
data Point(x, y)
data Person(name, age = 0)

p = Point(3, 4)
print p.x
p.y = 5
print p
```

`data` generates a constructor, field access and mutation, structural equality, a
hash, and a readable text form. Fields may have defaults. There is no inheritance.

Default expressions are evaluated **for each new instance**, so a mutable default does not
leak between objects:

```
data Bag(items = [])
a = Bag()
b = Bag()
a.items.push(1)
print b.items      # [], not [1]
```

## Ranges

```
r = 1 to 5
print length(r)
for n in r
  print n
end
```

`a to b` includes `b`. `a until b` excludes it. If `a > b`, the range counts down.

## Errors

Any runtime error raises an exception with a precise position. Catch it with `try`:

```
try
  risky()
catch err
  print "failed: {err.message} at line {err.line}"
end
```

`err` is a data value with `message`, `hint`, `file`, `line` and `column`. If the handler
itself raises, the new error propagates; `try` does not swallow silently. There is no
`finally` and no typed catch: one handler per `try` keeps the surface small.

Uncaught errors stop the program with status 1 and a diagnostic like this:

```
error: index 5 is out of range for a list of length 3
  --> app.oq:3:10
   3 | print xs[5]
              ^

  help: Valid indexes are 0 to 2, or negative from the end.
```

Raise your own with `error("message")`. Guard with `assert(condition, "message")`.
`exit(n)` stops the program with status `n`.

## The standard library

Text: `upper`, `lower`, `trim`, `split`, `join`, `replace`, `contains`, `regex`,
`matches`, `format`.

`format(value)` takes exactly one argument and returns the same text `print` would show,
without a newline. It is the display form, not a template language: there are no format
specifiers and no placeholders. Use string interpolation for that.

Output: `print` writes its arguments separated by single spaces and ends the line.
`write` writes its arguments with no separator and no newline. Both accept any number
of arguments and return `nothing`.

```
print "a", "b"     # a b
write "a", "b"     # ab, cursor stays on the line
write_text "x"     # same as write
```

Files: `read`, `read_lines`, `save`, `append`, `exists`, `list_files`, `delete`,
`make_dir`, `file_size`, `path_join`, `path_name`, `path_parent`, `cwd`.

JSON: `json_parse(text)`, `json(value)`.

Time: `now()`, `today()`, `datetime()`, `timestamp()`, `sleep(seconds)`.

HTTP: `http_get(url)`, `http_post(url, body)`. Both return a value with `.status`,
`.body` and `.ok`.

Processes: `run_command(line)` returns `.code` and `.output`. `env(name)`,
`cpu_count()`, `memory_mb()`.

Program: `args()`, `input()`, `input(prompt)`, `exit(code)`.

## Concurrency

Oqthara runs concurrent work on **virtual threads**, so blocking is cheap and there is no
pool to configure.

```
task = spawn(fun()
  return expensive()
end)

print await(task)        # blocks until done, returns the value
print done(task)         # true when finished
```

Map in parallel when order matters but speed does too:

```
squares = par_map([1, 2, 3, 4], x -> x * x)
print squares            # [1, 4, 9, 16]
```

Errors inside a task are re-raised by `await`, so `try`/`catch` works across the
boundary. There is no shared-memory model to learn: values are ordinary Oqthara values and
the JVM's memory model applies. For shared mutation, use a synchronized Java structure from
the standard library.

## Building

```bash
oq build hello.oq            # lower to the intermediate representation
oq build hello.oq --emit-ir  # print the IR
```

`oq build` writes an executable jar next to the program:

```
$ oq build hello.oq
compiled hello.oq (0 functions, 3 main instructions)
backend=classfile (self-contained writer, JDK 21)
wrote hello.jar (89 classes, 0 skipped)
run it with: java -jar hello.jar

$ java -jar hello.jar
Hello, World!
```

The jar contains a real class file with a real `main`, the Oqthara runtime, the program
source and its original file name. The class file is written by Oqthara itself, so it works
on **every** supported JDK, including 21; no `java.lang.classfile` and no external library
are involved.

Relative paths in `read`, `save`, `read_lines` and friends resolve against the program's
directory, so `oq run` and `java -jar` behave identically from any working directory. See
[backend.md](backend.md) for the exact boundary: the embedded program is still executed by
the interpreter.

The current state, including exactly which constructs still diverge, is in
[backend.md](backend.md). Read it before relying on `oq build` for anything.

## Security and fuzzing

The lexer, parser and compiler pipeline are fuzzed with Jazzer on every change. The contract
is that any input produces either a result or a diagnostic, never an internal crash. See
[fuzzing.md](fuzzing.md) for the targets, how to run them, and the regression corpus.

## Documentation

```bash
oq doc            # regenerate docs/stdlib/reference.md from the implementation
```

The generated page lists every global function and every method, read directly from the
standard library sources, plus the real keyword list with its count. CI regenerates it and
fails if the checked-in copy is stale, so the reference cannot drift.

## Formatting

```bash
oq fmt hello.oq           # rewrite in canonical style
```

The formatter re-indents blocks, normalises spacing around operators and commas, collapses
runs of blank lines, and leaves text literals untouched. It is **idempotent** and the output
always parses; both properties are enforced by tests over every example.

```bash
# before
if x>0
      print"positive"
  else
   print"other"
end

# after
if x > 0
  print "positive"
else
  print "other"
end
```

## Editor support

The `editors/vscode` directory contains a VS Code extension with syntax highlighting,
snippets and indentation rules:

```bash
cd editors/vscode
npx --yes @vscode/vsce package
code --install-extension oqthara-0.1.0.vsix
```

`EditorSupportTest` checks that the grammar covers every keyword and that every snippet
parses, so the extension cannot drift from the language.

### Language server

`oq-lsp` is a minimal stdio Language Server (JSON-RPC 2.0 with `Content-Length` framing).
It publishes parse diagnostics on open and change, and returns whole-document formatting
edits in the same canonical style as `oq fmt`. It supports `initialize`,
`textDocument/didOpen`, `textDocument/didChange`, `textDocument/didClose`,
`textDocument/formatting`, `shutdown` and `exit`; unknown requests get a JSON-RPC
"method not found" error and unknown notifications are ignored.

```bash
./gradlew :oq-lsp:standaloneJar
java -jar oq-lsp/build/libs/oq-lsp.jar   # speak LSP on stdin/stdout
```

Editors only need a command that starts that process; capabilities are `textDocumentSync`
(full) and `documentFormattingProvider`.

## Installation

```bash
# macOS and Linux
curl -fsSL https://raw.githubusercontent.com/Oqthara/oqthara/main/scripts/install.sh | bash

# Windows PowerShell
irm https://raw.githubusercontent.com/Oqthara/oqthara/main/scripts/install.ps1 | iex

# Docker
docker run --rm -v "$PWD:/work" oqthara/oq:latest hello.oq
```

From source, the native launcher is built with `jpackage`:

```bash
./gradlew :oq-cli:packageApp
# macOS:   oq-cli/build/package/oq.app/Contents/MacOS/oq
# Windows: oq-cli/build/package/oq/oq.exe
# Linux:   oq-cli/build/package/oq/bin/oq
```

The launcher embeds a Java runtime, so the machine it is copied to does not need a JDK.

## Testing

Any file named `test_*.oq`, `*_test.oq`, or any `.oq` file inside a `tests/` directory is
a test. A test passes when it runs to completion without an error. Use `assert` for
checks:

```
# tests/test_math.oq
assert 1 + 1 == 2, "addition"
assert 9.sqrt() == 3, "square root"
print "math ok"
```

```bash
oq test              # scan the current directory
oq test examples     # scan a specific directory
```

Output is one `PASS`/`FAIL` line per file, a summary, and the full diagnostic for each
failure. The command exits with `1` if anything failed, so it works in CI unchanged.

## Interoperability with Java

```
use java.time.LocalDate
use java.util.ArrayList

today = LocalDate(2026, 1, 1)
print today.plusDays(30)

list = ArrayList()
list.add("a")
print list.size()
```

`use` imports a class by fully qualified name. Short names resolve against
`java.lang` and `java.util`. Constructors, instance methods and static methods are
called with the same dot syntax used for Oqthara values. Common Java types are
converted automatically: `int`, `long`, `double`, `boolean`, `char`, `String`,
`List`, `Map`, `Path` and enums.
