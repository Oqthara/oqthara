# Oqthara language reference

Oqthara is a small language for the JVM that reads like pseudocode. A file is a script: it
runs from the top, and there is no `main`, no class and no package to declare.

This page is the reader's introduction. The normative reference — every rule, every edge case
and the full standard library — lives in the
[language specification](https://github.com/Oqthara/oqthara/blob/main/docs/spec/language.md).

## Install and run

```bash
oq hello.oq
```

```oq
print "Hello, World!"
```

| Command | What it does |
|---|---|
| `oq file.oq` | run a script |
| `oq run file.oq` | the same, spelled out |
| `oq check file.oq` | parse and type-check without running |
| `oq repl` | an interactive session |
| `oq fmt file.oq` | format a file in place |
| `oq test` | run the tests in the project |
| `oq build file.oq --emit-ir` | emit intermediate representation |

## Values

| Type | Example | Notes |
|---|---|---|
| number | `42`, `-7` | 64-bit whole numbers |
| decimal | `3.14`, `1e9` | 64-bit floating point |
| fraction | `fraction(1, 2)` | exact rational, from the standard library |
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

```oq
name = "Ada"
count = 0
count = count + 1
```

Assignment creates the variable if it does not exist yet. There is no `let` and no `var`.

```oq
const MAX = 100
MAX = 200        # error: cannot change 'MAX'
```

A `const` fixes the *name*, not the object. A list held by a `const` can still be mutated
through `.push`, because rebinding and mutation are separate operations.

## Text

Text is immutable and an index is a position, not a byte offset.

```oq
name = "Ada"
greeting = "Hello, {name}!"
print upper(greeting)
```

A `{` inside a string starts interpolation; write `\{` for a literal brace.

## Lists, maps and sets

```oq
xs = [1, 2, 3]
xs.push(4)
print length(xs)

person = {"name": "Ada", "born": 1815}
print person["name"]

unique = set([1, 2, 2, 3])
```

## Control flow

```oq
count = 12

if count > 10
  print "many"
else if count > 0
  print "some"
else
  print "none"
end

for n in 1 to 5
  print n
end

while count > 0
  count = count - 1
end
```

`1 to 5` includes 5. `0 until 5` excludes it.

## Functions

```oq
fun add(a, b)
  return a + b
end

print add(2, 3)
```

A function without an explicit `return` returns `nothing`. Functions are first-class values,
so they can be passed, returned and stored:

```oq
double = x -> x * 2
print double(21)
```

```oq
fun counter()
  n = 0
  return fun()
    n = n + 1
    return n
  end
end
```

## Data types

`data` declares a value with named fields and generated equality:

```oq
data Point(x, y)

p = Point(1, 2)
print p.x
```

## Classes and contracts

```oq
class Counter(start)
  init
    this.n = start
  end

  fun bump()
    this.n = this.n + 1
  end
end
```

A `contract` names the operations a class must provide.

## Errors

Any runtime error raises an exception with a precise position.

```oq
try
  risky()
catch err
  print "failed: {err.message} at line {err.line}"
end
```

`err` carries `message`, `hint`, `file`, `line` and `column`. A handler that raises
propagates the new error; `try` never swallows silently.

`try` is also an expression, so it can produce a value:

```oq
n = try
  number(text)
catch err
  0
end
```

## The standard library

Text, files, JSON, time, HTTP, processes, math, regular expressions, hashing and the
filesystem, all reachable as global functions:

```oq
print upper("hello")
print json_parse("{\"a\": 1}")
```

Reading a file that is not there raises, like any other error, rather than returning
`nothing`:

```oq
print read("notes.txt")
```

The [complete reference](https://github.com/Oqthara/oqthara/blob/main/docs/stdlib/reference.md)
is generated from the implementation, so it cannot drift from what ships.

## Where to go next

- [Language specification](https://github.com/Oqthara/oqthara/blob/main/docs/spec/language.md) —
  the normative rules.
- [Standard library reference](https://github.com/Oqthara/oqthara/blob/main/docs/stdlib/reference.md) —
  every function and method.
- [Examples](https://github.com/Oqthara/oqthara/tree/main/examples) — runnable programs.
