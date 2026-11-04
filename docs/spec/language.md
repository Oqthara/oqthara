# Oqthara language reference

Version: 0.1.0-alpha.1

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

Operators: `==`, `!=`, `<`, `<=`, `>`, `>=`, `and`, `or`, `not`.

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

`oq build` lowers the program to a small stack IR shared by every backend. Emitting a
`.jar` requires `java.lang.classfile`, which exists only on **JDK 24+**. On JDK 21-23 the
command prints an explicit diagnostic and exits with code 3 instead of crashing; `oq run`
always works, because the interpreter needs nothing extra.

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
