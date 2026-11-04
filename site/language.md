# Oqthara language reference

See the canonical source: [docs/spec/language.md](https://github.com/Oqthara/oqthara/blob/main/docs/spec/language.md).

## Quick tour

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

## Commands

```bash
oq hello.oq
oq run hello.oq
oq check hello.oq
oq repl
oq fmt hello.oq
oq test
oq build hello.oq --emit-ir
```

## Types

`number`, `decimal`, `text`, `boolean`, `nothing`, `list`, `map`, `set`, `range`, `data`
and any Java object.

## Standard library

Text, files, JSON, time, HTTP, processes and math. See the reference for the full list.
