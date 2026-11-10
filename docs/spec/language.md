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

Assignment creates the variable if it does not exist yet. There is no `let` and no `var`.

```
const MAX = 100
MAX = 200        # error: cannot change 'MAX'
```

`const` declares a name that is bound once and cannot be reassigned. It needs a value at
the point of declaration. A `const` fixes the *name*, not the object it refers to: a list
held by a `const` can still be mutated through methods such as `.push`, because rebinding
and mutation are separate operations.

Values are snapshotted on assignment: assigning a list to another variable does not alias
it for math purposes; both names still refer to the same list, and methods that mutate a
list (`.push`, index assignment) are visible through both.

### Destructuring

```
a, b = (1, 2)
print a + b                 # 3

const c, d = [10, 20]
x, y = "hi"                 # x is "h", y is "i"
first, second = {"a": 1, "b": 2}   # keys, in the map's own order
```

One value supplies several names. The number of values must equal the number of
names; a mismatch is an error, never a partial binding. The source may be a tuple, a
list, a set, a range, a map (which yields its keys, like iterating it) or text (which
yields its characters, one per code point, as described in
[What one character is](#what-one-character-is)). `data` instances and every number,
boolean or `nothing` value are rejected.

Destructuring is a *binding* operation, not pattern matching. The left side is a plain
list of names; there are no nested patterns, no rest target and no discard. `_` is an
ordinary identifier, so `_ , b = v` binds `_` rather than discarding the first element.
`(a, b) = v` is not destructuring either: parentheses on the left make a tuple literal,
which cannot be assigned to.

Names that already exist are updated; names that do not are created by the assignment,
exactly as in single assignment. `const` makes all of the names read-only. Every name is
resolved and the source is measured before any name is written, so a destructuring that
fails on a count mismatch or on an immutable target leaves every target untouched. The
source expression is evaluated exactly once. This is specified normatively in
[ADR-0013](../adr/0013-destructuring.md).

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

### What one character is

A text value is a sequence of **code points** ([ADR-0024](../adr/0024-text-is-a-sequence-of-code-points.md)).
Every operation that measures, indexes, walks or slices text counts code points, not
bytes and not UTF-16 units, so all of them agree on the same value:

| Expression | Result | Why |
|---|---|---|
| `"a😀b".length()` | `3` | three characters |
| `"a😀b"[1]` | `"😀"` | the second character |
| `"a😀b".to_list()` | `["a", "😀", "b"]` | three characters |
| `"a😀b".chars()` | `["a", "😀", "b"]` | same three |
| `"a😀b".reverse()` | `"b😀a"` | characters reversed |
| `"a😀b".find("b")` | `2` | a character offset |
| `"a😀b".substring(1, 2)` | `"😀"` | character bounds |
| `for c in "a😀b"` | three iterations | one character each |

This matches Python 3, which counts code points. Text is stored as UTF-16 internally, so
indexing is O(n) in the position; that is an implementation cost, not something the
language exposes. `.length()` also scans, so hoist it out of a loop that repeats it.

Grapheme clusters are *not* the unit: `"é"` written as `e` plus a combining accent has
length `2`, because it is two code points. [ADR-0024](../adr/0024-text-is-a-sequence-of-code-points.md)
records why code points were chosen over clusters.

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

### Changing a collection while looping over it

A loop walks a **snapshot** taken when it starts, so the body may change the
collection it is looping over. The loop still visits exactly the items that were
present when it began:

```
xs = [1, 2, 3]
for x in xs
  xs.add(x * 10)
end
print xs
```

```
[1, 2, 3, 10, 20, 30]
```

The same applies to sets, to a map's keys, and to the collection methods that take
a callback — `map`, `filter`, `each`, `any`, `all`, `find`, `find_index`, `reduce`
and `flatten` all walk a snapshot too. Ranges and text were already unaffected
because both are converted to a list before the loop starts.

Mutation is neither forbidden nor dangerous, and it is not an error, so a program
that removes items in the loop simply finishes with those items removed. Writing to
a collection from two tasks at once is still a data race: a snapshot fixes what a
loop visits, not concurrent access.

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

### Overloading

A name may carry several definitions as long as they differ in the number of
parameters. Oqthara has no parameter type annotations, so arity is the only
discriminator; resolution never inspects argument values.

```
fun area(side)
  return side * side
end

fun area(width, height)
  return width * height
end

print area(3)      # 9
print area(3, 4)   # 12
```

A call selects the definition whose parameter count equals the number of
arguments, and the result is the same in the interpreter and in compiled
bytecode. Two definitions with the same arity are rejected where the second is
declared. A user definition replaces a built-in of the same name rather than
clashing with it. Defaults, named arguments, variadic parameters and generics do
not participate in resolution. See [ADR-0012](../adr/0012-overload-resolution.md).

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
`.take(n)`, `.skip(n)`, `.subList(from, to)`, `.sum()`, `.min()`, `.max()`, `.clone()`,
`.clear()`, `.remove_value(x)`.

Map methods: `.length()`, `.keys()`, `.values()`, `.entries()`, `.get(k, fallback)`,
`.set(k, v)`, `.has(k)`, `.has_value(v)`, `.remove(k)`, `.clear()`, `.each(f)`,
`.map(f)`, `.clone()`.

Set methods: `.length()`, `.add(x)`, `.remove(x)`, `.has(x)`, `.items()`,
`.union(other)`, `.intersection(other)`, `.difference(other)`.

### An index is a position

`l[i]`, `l.get(i)`, `l.insert(i, x)` and `l.remove(i)` take an index. All four agree on
what it means, and they agree in two ways that a language can easily get wrong.

**A negative index counts from the end.** `l[-1]` is the last element, and `-1` means the
same thing to `insert` and `remove` as it does to a read. A rule that held for reads but
not for mutations would make `l.remove(-1)` a silent no-op, which is worse than refusing
it.

**An index that names no position is an error.** `l[99]`, `l.get(99)`, `l.remove(99)` and
`l.insert(99, x)` all raise, naming the index and the length. `insert` accepts `l.length()`
because appending names the position after the last element; it does not accept
`l.length() + 1`, and it does not clamp. Removal by value is `.remove_value(x)`, never
`.remove(x)`, so `l.remove(1)` is unambiguously position `1`.

The range-shaped methods are different on purpose. `take(n)`, `skip(n)` and
`subList(from, to)` take a count or a bound rather than a position, so they clamp: asking
for ten items from a three-item list is a meaningful request with an obvious answer, and
has no position that could be wrong.

```
xs = [1, 2, 3]
print xs.remove(-1)     # 3 -- the last element
print xs.insert(-1, 9)  # [1, 2, 9, 3] -- before the last element
xs.insert(3, 4)         # appending at length is allowed
```

See [ADR-0018](../adr/0018-index-semantics.md).

### Slicing

`xs[from:to]` takes a run of items, and `xs[from:to:step]` takes every `step`-th. Either
bound may be omitted, in which case it means the corresponding end, so `xs[:3]` is the first
three items, `xs[2:]` is everything from the third, and `xs[:]` is a copy of the whole
sequence. A negative bound counts from the end, so `xs[-2:]` is the last two items.

A **bound** is resolved differently from an index, and the difference matters. An index
names exactly one element, so an index that names nothing is an error. A bound names the
edge of a range, so a bound past the end means the end: `xs[0:999]` is the whole list, not
an error. That is what makes `xs[i:i + 10]` safe on a short list.

A step of `1` walks forwards. A negative step walks backwards and reverses the result, so
`xs[::-1]` is the reversal and `xs[4:1:-1]` is `[5, 4, 3]`. A step of `0` is an error
because it names no traversal.

The result is always a new value, never a view of the original, and it keeps the family it
came from: a list slice is a list, a tuple slice is a tuple, and a text slice is text because
a text slice is a substring. A range slice is a list, since a sub-range with a step is not a
range. `xs[i:j]` is not assignable -- only an index is.

```
xs = [1, 2, 3, 4, 5]
print xs[1:3]     # [2, 3]
print xs[-2:]     # [4, 5]
print xs[::2]     # [1, 3, 5]
print xs[::-1]    # [5, 4, 3, 2, 1]
print "abcdef"[1:3]   # bc
```

See [ADR-0019](../adr/0019-slicing.md).

### Iteration

`for x in xs` walks a collection. It takes a **snapshot** first, so changing the collection
inside the loop cannot disturb the walk:

```
xs = [1, 2, 3]
for x in xs
    if x == 1
        xs.add(99)
    end
    print x      # 1 2 3, not 1 2 3 99
end
```

To walk with a position, ask for an **iterator**. An iterable *can produce* an iterator and
can be walked as often as you like; an iterator *tracks a position* and is consumed once.

```
it = [1, 2].iter()
print it.has_next()   # true
print it.next()       # 1
print it.next()       # 2
print it.has_next()   # false
print it.next()       # error: this iterator is exhausted
```

There is no `nothing` answer for an exhausted iterator, because a list may contain
`nothing` and the two would be indistinguishable. `has_next()` asks the question instead,
so a correct loop can always be written.

`.iter()` is **lazy**: it reads the live collection rather than copying, which is the reason
to use it instead of `for` on a large collection. The trade is that you must not change the
collection while iterating it — `for` is the safe choice when you might. Breaking the rule
is an ordinary error that says so, not an internal failure:

```
xs = [1, 2]
it = xs.iter()
it.next()
xs.add(3)
it.next()     # error: the collection changed while this iterator was walking it
```

An iterator is a position, not a collection, so `length`, indexing, slicing and `in` all
refuse it rather than consuming it. `xs.next()` is an error too; call `iter()` first, and the
message says so. Lists, tuples, sets, maps (keys), ranges and text all have `.iter()`, and a
`data` type joins the protocol by defining `iter`.

`enumerate(xs)` pairs each item with its position, and `range(from, to, step)` counts with a
step. Ranges also gained a method surface: `.to_list()`, `.to_set()`, `.first()`, `.last()`,
`.contains(x)`, `.sum()`, `.min()`, `.max()`, `.sorted()` and `.reverse()`.

`.reverse()` has no single answer for every family, so the rule is stated per family. For
`text` and `list` it returns the receiver's family, because those are ordered sequences whose
reversal is still a sequence. For `range` it returns the `range` `2 to 0 by -1`, because a
counting sequence reverses into a counting sequence. For `set` and `map` it returns a `list`
of the reversed walk order, because the reversed order of a set is not a set — the family's
defining property is unordered membership, and no `set` can hold "the reversed order".
`frozenset` has no `reverse` at all. `.sorted()` deliberately widens to a `list` for every
family, since only a sequence has an order to preserve.

Reversing a range is constant-time and keeps a step: `range(0, 9, 3)` walks `0, 3, 6`, so it
reverses to `6 to 0 by -3`. The new start is the last value the walk reaches, not `end`, which
is why `range(0, 9, 3).reverse()` is not `8 to 0 by -3` — `8` was never in the range.

The unifying rule is narrower than "always keep the family": **an operation may not change
whether a value is mutable, and it may not claim an order its family does not have.** That
is why `range` cannot widen (it is immutable, and a list would be a lie) and why `set`
cannot stay a `set` (a set has no order to show).

See [ADR-0020](../adr/0020-iterator-protocol.md) and
[ADR-0023](../adr/0023-collection-protocols.md).

### Aggregation and conversion

`sum`, `min`, `max` and `sorted` read **any** collection, not just a list:

```
print sum(set([1, 2, 3]))    # 6
print sum(range(0, 4))       # 6
print min([3, 1, 2])         # 1
print sorted("cba")          # ["a", "b", "c"]
```

`min` and `max` also take several values: `min(3, 1, 2)` is `1`. The arity decides the
reading, so `min(xs)` means the extreme of one collection and `min(a, b)` means the smaller
of two values.

`sorted` returns a **new list** and never reorders its argument.

Every family that holds members converts to a list and to a set. A map converts to its
**keys**, because that is what a map iterates as.

```
print [1, 1, 2].to_set()     # {1, 2}
print "ab".to_list()         # ["a", "b"]
print {"a": 1}.to_list()     # ["a"]
```

`.clone()` returns the same family as the receiver, so `type_of((1, 2).clone())` is `tuple`.

Sets compare and combine without changing either operand: `.union`, `.intersection`,
`.difference`, `.symmetric_difference`, `.issubset` and `.issuperset`.

A map merges in place with `.merge(other)` or `.update(other)` — the argument's entries win
and the receiver changes. `.items()` yields `[key, value]` pairs and `.entries()` yields
`Entry` values with `.key` and `.value`; both are ordinary lists, so a loop reaches the parts
by index (`p[0]`, `p[1]`) or by field (`e.key`, `e.value`). `.pop(key, default)` returns the
default when the key is absent.

See [ADR-0021](../adr/0021-collection-family-operations.md).

### Which operations are eager, and which are lazy

Exactly two things are lazy: `for` and `.iter()`. Everything that takes a function is eager
and returns a `list`.

| | `for` | `.iter()` | `map` / `filter` |
|---|---|---|---|
| Reads | a snapshot | the live collection | a snapshot taken by the operation |
| Body runs | at the loop | at each `next()` | immediately |
| Result | nothing | an `iterator` | a `list` |
| Safe to mutate during | yes | no | yes |

`map`, `filter`, `enumerate`, `zip` and `sorted` all return a `list`, so a program that wants
a set or tuple converts at the end. A program that wants to walk without materialising uses
`.iter()` and advances it explicitly.

Views follow the same rule: `keys()`, `values()` and `entries()` return new lists, not live
views, so a map cannot be changed behind a list you are holding.

### What each family guarantees

| Family | Length | Index | `in` | Mutable | Order |
|---|---|---|---|---|---|
| `list` | yes | yes | yes | yes | insertion |
| `tuple` | yes | yes | yes | no | position |
| `map` | yes | no | keys | yes | insertion |
| `set` | yes | no | yes | yes | insertion |
| `frozenset` | yes | no | yes | no | insertion |
| `range` | yes | yes | yes | no | counting |
| `text` | yes | yes | substring | no | position |
| `iterator` | no | no | no | position | walk order |

An `iterator` has no length, no index and no membership test, because each of those would
consume or depend on the walk's position. Sets and maps are **insertion-ordered and
deterministic**: the same program prints the same order on every platform and every run.

See [ADR-0023](../adr/0023-collection-protocols.md).

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

Mutation is refused; tuples are immutable. Every mutator raises `a tuple is immutable`, and
`t[0] = 9` is rejected. That includes a mutator called on a *slice* of a tuple: `t.subList(0, 2)` yields another tuple,
so changing one is refused with the same error rather than a Java exception. `take` and `skip`
are the exception among the readers — they always return a list, even on a tuple, so the result
is mutable. To change a tuple, build a new one.

`subList(from, to)` and its one-argument form `subList(from)` return a **new** list or tuple,
never a live view. Mutating the result leaves the original untouched. Indices are clamped to
the sequence, so `l.subList(0, 99)` is the whole list rather than an error. `slice` is an
alias. `take(n)` and `skip(n)` are the prefix and suffix forms.

A tuple satisfies Java's `List` interface for reading. Mutating it from Java throws
`UnsupportedOperationException`; reaching a tuple's `List` methods through Oqthara reports
`a tuple is immutable` instead.

**Deferred.** Tuple destructuring (`a, b = pair`), named or typed tuple fields, and tuple
arithmetic (`+`, `*`) are not part of Alpha 3. See `docs/governance/roadmap-alpha-3.md`.

## Equality and hashing

`==` is one definition applied at every depth, and hashing agrees with it.

```
print 1 == 1.0                 # true: one number, two representations
print [1] == [1.0]             # true
print (1,) == (1.0,)           # true
print {1: "a"} == {1.0: "a"}   # true
print set([1]) == set([1.0])   # true
```

A whole number and a whole decimal that denote the same value are equal, and they hash
alike, so a set cannot hold both and a map finds the entry whichever representation wrote it:

```
print length(set([1, 1.0]))    # 1
print 1 in set([1.0])          # true
m = {1: "a"}
print m[1.0]                   # a
```

The rules per family:

| Values | Equal when |
|---|---|
| numbers | they denote the same value, whether whole or decimal |
| text | the characters are the same, case sensitively |
| booleans | both are the same boolean |
| lists | same length, and each position equal |
| tuples | same length, and each position equal |
| maps | same size, and every key of one finds an equal value in the other; order is irrelevant |
| sets | same size, and every member of one is in the other; order is irrelevant |
| frozensets | same as sets, and never equal to a `set` |
| `data` | same type name and equal fields, compared by the same rule |
| `nothing` | only against another `nothing` |

A tuple never equals a list, even with the same items and order. A list never equals a set.
Comparing values of unrelated families with `==` is `false` rather than an error, so
`1 == "1"` is `false`.

`unique`, `union`, `intersection`, `difference`, `has_value` and membership all use this same
rule, so nothing disagrees with `==`.

**Only immutable values may be keys.** Hashing is structural, so a mutable value's hash would
change the moment it changed, and the entry would stay in the collection while becoming
impossible to find again. Oqthara refuses the value instead of allowing that:

```
print {[1]: "a"}
# error: a list cannot be used as a map key

print set([[1]])
# error: a list cannot be used as a set member
```

| Family | Usable as a key or member |
|---|---|
| number, decimal, text, boolean, `nothing`, range | yes |
| tuple | yes, when every item is |
| `frozenset` | yes, when every member is |
| function | yes, by identity |
| list, set, map, `data` | no, because they can change |

The check is deep, so a tuple is only as usable as what it holds: `{([1],): "a"}` is refused
because the list inside can change. A `set` that must be a key becomes a `frozenset`, and a
list becomes a tuple of fixed length. See [ADR-0022](../adr/0022-hashability-and-frozenset.md).

**A collection that contains itself cannot be hashed or compared**, and raises
`this collection contains itself, so it cannot be hashed or compared` rather than crashing.

See [ADR-0017](../adr/0017-equality-and-hashing.md).

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
`length(r)` returns the range count without narrowing it through a 32-bit integer; for
example, `length(0 to 3000000000)` returns `3000000001`.

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

`try` is also an expression, so it can produce a value where it is used:

```
n = try
  number(text)
catch err
  0
end
```

The value is the value of the branch that ran, taken from that branch's **final
statement** when the final statement is an expression; otherwise the value is `nothing`.
A branch that ends in a declaration, a loop or an `if` therefore produces `nothing`
rather than the last value computed inside it:

```
a = try
  x = 1        # a declaration, so the branch has no value
catch err
  0
end
print a        # nothing
```

This differs from `fun`, where a value must be named with `return`. `return` inside a `try`
expression still returns from the enclosing function, so it does not select the `try`'s
value — use the final expression instead. A `try` at the start of a statement is the
statement form and produces no value; parenthesize it to use it as an expression in that
position.

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

Processes: `run_command(path)` returns `.code` and `.output`. `env(name)`,
`cpu_count()`, `memory_mb()`.

Program: `args()`, `input()`, `input(prompt)`, `exit(code)`.

### Host calls are bounded

The language restricts the host calls that can consume unbounded time or memory, so a
program cannot exhaust the machine by accident. These limits are part of the contract:

| Call | Limit | On breach |
| ---- | ----- | --------- |
| `print`, `write`, `write_text` | 16 MiB of output, total | `the program wrote too much output` |
| `read`, `read_lines` | 16 MiB per file | `the file is too large to read: <path>` |
| `input()` | 16,777,216 characters per line | `input line is too large` |
| `run_command` | 30 seconds total, 16 MiB of output | `command timed out after 30 seconds: <path>`, `command output is too large: <path>` |
| `http_get`, `http_post` | 30 seconds for the body, 16 MiB per body | `the response body did not finish in 30 seconds`, `response is too large: <url>` |
| `save`, `append` | 16 MiB per call | `cannot save <N> bytes in one call`, `cannot append <N> bytes in one call` |
| `sleep` | 10 seconds per call, 60 seconds total | `cannot sleep for more than 10 seconds`, `the program waited for too long` |
| `list_files` | 100,000 entries | `directory has too many entries: <path>` |

A limit of 16 MiB means 16,777,216 bytes; a payload of exactly that size is allowed and one
byte more is refused. `input` is the exception: its 16,777,216 ceiling counts characters, so
that sentence reads "character" for `input` and "byte" everywhere else. Output is refused
**before** it is written, so a program that exceeds the limit prints nothing from the call that
crossed it. The newline `print` adds counts towards the total, as do the bytes of an
`input` prompt: a prompt is written output and is billed like one, so a multibyte prompt costs
its UTF-8 size and not its character count.

Output has its own 16 MiB counter rather than draining the step budget. Charging output as
steps made the documented ceiling unreachable: at 256 steps per byte a program could print
only 50,000,000 / 256 = 195,312 bytes before the budget stopped it. That counter covers output
only. Reading a file does not draw on it, because reading is not writing output.

Every host transfer other than output also bills the step budget, on top of its own per-call
size limit. Output is the exception: `print`, `write` and `write_text` draw on the output
counter only and charge no steps at all, because that counter already bounds them. For the
calls that do bill steps, the per-call limit is what binds; the step charge only stops a
program from moving data for free. Where a step charge exists it is always the same figure the
size check used, never a re-derived one:

| Call | Step charge | Unit |
| ---- | ----------- | ---- |
| `read`, `read_lines` | bytes read | UTF-8 bytes |
| `save`, `append` | bytes written | UTF-8 bytes |
| `http_get`, `http_post` | body length | UTF-8 bytes |
| `run_command` | output length | UTF-8 bytes |
| `input()` | line length | Java `String` characters |
| `list_files` | entry count | directory entries |
| `print`, `write`, `write_text` | none | — |

The blank row is deliberate: those three calls are bounded by the output counter and are never
charged against the step budget, so they have no billing unit to state.

`input` is the one call that bills characters rather than bytes, because it accumulates into a
`StringBuilder` and charges `sb.length()`. Its 16 MiB ceiling is also counted in characters, so
a line of astral characters is billed and refused by character count, not byte count.
`list_files` bills one unit per directory entry, so its 100,000-entry cap and its step charge
are the same number.

The per-byte rate is not itself a bound. At one step per byte the 50,000,000-step budget would
allow 50,000,000 bytes — about 47.68 MiB, or 2.98 calls of 16,777,216 bytes — so two full
per-call transfers fit and a third does not. No single transfer can approach that total,
because the per-call limit refuses it first. Billing at 256 steps per byte failed the other
way and made the per-call figure unreachable, since `read` refused any file past 195,312 bytes
with `program ran for too long`, whatever the table said.

`sleep` is bounded separately again. The 10-second per-call cap alone would let a program drip
ten seconds at a time forever, so a program may wait 60 seconds in total and is refused with
`the program waited for too long`.

`run_command(path)` runs **one executable path** with no arguments and no shell. There is no
command-line splitting and no interpolation, so `run_command("/bin/ls -l")` is a program
error rather than a directory listing. To pass arguments, write a script and run the script:

```
result = run_command("/bin/ls")
print result.code, result.output
```

A non-zero exit status is an ordinary result, not an error; the caller inspects `.code`. A
missing executable, a timeout, or excessive output raises a program error.

These bounds are enforced by the interpreter, so they always apply to `oq run`. A jar produced
by `oq build` takes one of two shapes, and `oq build` prints which:

```
codepath: compiled bytecode
codepath: interpreter launcher (call to 'read' with 1 argument(s) is not compiled yet)
```

A jar built as an **interpreter launcher** runs the program in the embedded interpreter, so
every limit in the table applies to it exactly as it does to `oq run`. That is what happens
whenever the program calls a builtin the compiler cannot lower, such as `read` or
`run_command`.

A jar built as **compiled bytecode** is real bytecode, not an embedded interpreter, so it has
no step budget and no host-call deadlines: only its output is capped, at the same shared
16 MiB. A compiled program that loops runs until something outside stops it, and runaway
allocation is stopped only by the JVM heap. When that happens the jar reports
`the program ran out of memory`, the same wording `oq run` uses, instead of dumping a stack
trace — but it is still the heap that stops it, not a budget. Run untrusted programs through
`oq run`, or use a jar built as an interpreter launcher. See [backend.md](backend.md).

Oqthara is **not** a sandbox. A program that is allowed to run can read the files the user can
read, start the processes the user can start, and reach the network the user can reach. The
limits above prevent accidental exhaustion; they do not contain hostile code. See
[SECURITY.md](../../SECURITY.md).

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

`oq build` writes an executable jar next to the program. This output was captured from the
current build:

```
$ oq build hello.oq
compiled hello.oq (0 functions, 5 main instructions)
backend=classfile (self-contained writer, JDK 21)
codepath: compiled bytecode
wrote hello.jar (103 classes, 0 skipped)
run it with: java -jar hello.jar

$ java -jar hello.jar
Hello, World!
```

The instruction and class counts move as the compiler and runtime grow; the shape of the
transcript — the five lines, in that order — is what the contract fixes, not those numbers.

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
