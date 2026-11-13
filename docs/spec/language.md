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
| `"𐐨x".capitalize()` | `"𐐀x"` | the case mapping of the first character |
| `for c in "a😀b"` | three iterations | one character each |

This matches Python 3, which counts code points. Text is stored as UTF-16 internally, so
indexing is O(n) in the position; that is an implementation cost, not something the
language exposes. `.length()` also scans, so hoist it out of a loop that repeats it.

Grapheme clusters are *not* the unit: `"é"` written as `e` plus a combining accent has
length `2`, because it is two code points. [ADR-0024](../adr/0024-text-is-a-sequence-of-code-points.md)
records why code points were chosen over clusters.

`capitalize()` upper-cases the first character and leaves the rest alone. Because the unit
is a code point, a letter outside the basic multilingual plane is mapped as one character:
`"𐐨x"` is `"𐐀x"`, where an implementation working in UTF-16 units would leave the letter
unchanged. A first character with no upper case — a digit, an emoji, a space — is kept as it
is.

### Counts and bounds

Every method that takes a count or an index — `.take(n)`, `.skip(n)`, `.slice(from, to)`,
`.subList(from, to)`, `.substring(from, to)`, `.repeat(n)`, `.pad_start(n, fill)`,
`.pad_end(n, fill)` — follows the same two rules as `xs[i]`.

A value that is not a whole number is refused rather than truncated: `xs.take(1.5)` raises
`a count for take must be a whole number, got decimal`, and `xs.take("a")` raises
`a count for take must be a whole number, got text`. Rounding `1.5` down to `1` would answer
a question the program did not ask, and a wrong element is harder to notice than an error.

A whole number written as a decimal is still a whole number, because `1 == 1.0`
([ADR-0017](../adr/0017-equality-and-hashing.md)). `xs.take(2.0)` is `[10, 20]`, the same
as `xs.take(2)`. A decimal past the 64-bit range, or an infinity, is refused as out of range
rather than narrowed to the largest whole number: `xs[1e300]` raises
`an index is out of range: <the value as the language prints it>`, quoting the number the
program wrote rather than the saturated index it would otherwise have used.

`.repeat(n)` needs a count of zero or more, so `"x".repeat(-1)` raises
`repeat() needs a count of zero or more, got -1` rather than failing inside the runtime. A
count whose product with the text length would not fit in memory is refused before anything
is allocated.

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

A whole number is a 64-bit signed value, so the range is `-9223372036854775808` to
`9223372036854775807`. Both ends are writable directly. The lowest value's magnitude
is one past the highest, so it is the one whole number whose magnitude would overflow
on its own; it is accepted when a minus applies to it and refused otherwise. Arithmetic
that leaves the range wraps rather than raising an error, so `9223372036854775807 + 1`
is `-9223372036854775808`.

Built-in functions: `abs`, `floor`, `ceil`, `round`, `round(value, places)`, `sqrt`,
`pow`, `min`, `max`, `clamp`, `random`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`,
`atan2`, `hypot`, `ln`, `log10`, `log2`, `exp`, `degrees`, `radians`, `pi()`, `e()`,
`tau()`.

A number's method form and the builtin of the same name are the same operation, and
they answer identically for every input: `x.floor()` is `floor(x)`, and a method is
never narrowed to a whole number where the builtin keeps the decimal. The one case
with no whole-number answer is `abs` of the lowest whole number, whose magnitude is
one past the highest; both forms return the decimal rather than a negative number.

An argument that counts something — a range bound or step, a decimal-place count, a
random bound — must be a whole number. A decimal with a fractional part is refused
rather than truncated, because truncating `range(0, 5, 1.5)` and walking by `1`
answers a question the program did not ask. A decimal outside the 64-bit range is
refused for the same reason, and a whole decimal such as `2.0` is accepted, because it
is the same value as the whole number `2`.

`is_even` and `is_odd` answer the parity the number actually has, never the parity of
a narrowed `long`: `1e20.is_even()` is `true`. A number with a fractional part has no
parity and is refused.

`round` breaks a tie *upward*, toward positive infinity: `round(0.5)` is `1`,
`round(2.5)` is `3`, and `round(-1.5)` is `-1` rather than `-2`. It is not banker's
rounding, which would give `2` and `-2`, and it is not rounding away from zero, which
would make `round(-1.5)` equal `-2`.

The tie rule is stated for the whole number, but the underlying value is a binary
double, so `round(1.005, 2)` is `1.0` and not `1.01`: the stored double is a little
below `1.005`, and a tie is never reached. A result that must not depend on how a
double is stored needs exact arithmetic, which the `fraction` family provides (below);
an exact *decimal* type is deliberately absent, because `type_of(3.14)` already answers
`"decimal"` for a `Double` and an exact type taking that name would change what every
existing program reports.

A number outside the representable range is not the same thing as an input the
function cannot accept, and the two are treated differently. An input with no
real-number result is an error: `sqrt(-1)`, `ln(0)`, `ln(-1)`, `asin(2)` and
`pow(-8, 0.5)` all raise, as `1 / 0` already did. A finite input whose true answer is
merely too large or too small is not an error: `exp(1000)` and `1.0e308 * 10` are
`infinity`, and `1.0e-308 / 10.0` is `0.0`. `pow(0, -1)` is `infinity` for the same
reason — the input is in the domain and the function grows without bound.

`NaN` therefore never comes from an in-domain input. It is reachable only by
combining infinities, as in `infinity - infinity`, `infinity / infinity` or
`0.0 * infinity`. There is no `infinity` literal, so an infinity is reached by
overflowing a finite one, as in `1.0e308 * 10`. Because `NaN` equals nothing, `n != n`
is the test for it:

```oq
big = 1.0e308 * 10
n = big - big
print n == n   # false
print n != n   # true
```

The transcendental functions use `java.lang.StrictMath`, so a given input yields the
same bits on every platform and JVM. `log2` is exact on every power of two, so
`log2(1024)` is `10.0` and `log2(5e-324)` is `-1074.0`.

### Exact fractions

A `fraction` is a rational number held as a `BigInteger` numerator and denominator, always
in lowest terms with a positive denominator, so `fraction(2, 4)` and `fraction(1, 2)` are the
same value and print as `1/2`. Every operation on it is exact, including comparison:

```oq
a = fraction_of("9999999999999999/30000000000000000")
b = fraction_of("10000000000000001/30000000000000000")
print fraction_compare(a, b)   # -1 — both are 0.333… as doubles, but a < 1/3 < b
```

`fraction_compare` cross-multiplies rather than converting to a double, because converting
would reintroduce the rounding the type exists to avoid. The one conversion outward is
`fraction_to_decimal(value, places)`, which states its precision; there is no implicit one.

`fraction_round` breaks a tie the way `round` does, upward toward positive infinity, so
`fraction_round(fraction(-1, 2))` is `0`. `fraction_pow` takes an integer exponent only.

Two bounds keep an exact rational a result rather than a memory request. An exponent whose
magnitude exceeds `10000` is refused. Each part is also limited to `32768` bits, about 9,865
decimal digits, and that second bound is the one that holds: it is checked on every
construction, so a loop that squares a fraction without ever calling `fraction_pow` is refused
at the same limit, with a message naming the part that grew. A value whose parts reduce back
below the limit is accepted, however large the intermediates were. When a program needs more
precision than that, `fraction_to_decimal` states how many places it wants instead. See
[ADR-0053](../adr/0053-exact-rational-arithmetic.md).

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

Truthiness: `nothing`, `false`, `0`, `0.0`, an empty fraction, `""`, empty collections and
empty ranges are falsy. Everything else is truthy.

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

Membership in a range means **reachable by the walk**, not merely inside the bounds. A range
that steps by three holds only every third number: `3 in range(0, 10, 3)` is true and
`2 in range(0, 10, 3)` is false, even though `2` lies between the ends. A range that counts
down holds only the numbers it counts down to, so `range(5, 1, -1)` contains `3` and not `9`.
`x in r` and `r.contains(x)` always agree, and both agree with `r.to_list()`:

```
print 2 in range(0, 10, 3)      # false
print 3 in range(0, 10, 3)      # true
print 3 in range(5, 1, -1)      # true
print 9 in range(5, 1, -1)      # false
```

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
| `iterator` | no | no | no | no | walk order |

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
| fractions | same numerator and same denominator, so `fraction(1, 2) == fraction(2, 4)` |
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
| number, decimal, fraction, text, boolean, `nothing`, range | yes |
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

### Immutable data

A `data` declaration may end with `immutable`. The fields are then fixed at construction,
and the value may be used where a stable hash is required — as a map key or a set member,
which reject a mutable `data` value:

```
data Point(x, y) immutable

m = {Point(3, 4): "a"}
print m[Point(3, 4)]        # a — structural lookup finds the entry
print length(set([Point(1, 2), Point(1, 2)]))   # 1

p = Point(3, 4)
p.x = 9                     # error: Point is immutable, so field 'x' cannot be changed
```

The refusal names the field and shows how to rebuild the value, because building a new one
is the only way to change an immutable record:

```
error: Point is immutable, so field 'x' cannot be changed
  help: Build a new value instead: Point(..., 2)
```

The hint is written in positional form because that is the only form the constructor
accepts, and every value is escaped so the line can be pasted back as source.

`immutable` fixes the **field**, not its contents. A field may still hold a mutable value,
and changing that value changes the record's rendering — and makes the record unusable as a
key, because it is only as hashable as what it holds:

```
data Box(items) immutable
b = Box([])
b.items.push(1)
print b          # Box(items: [1])
```

This is the rule tuples already follow — `(list, 1)` is an immutable tuple holding a mutable
list. Deep freezing would have to reach every nested value, and no family in the language
does that. An immutable record that holds a mutable collection is therefore refused as a map
key or set member, for the same reason a tuple holding one is refused.

`immutable` is not a reserved word; `immutable = 7` still names a variable. See
[ADR-0028](../adr/0028-data-evolution.md).

## Classes and contracts

`data` is for values. A `class` is for objects that have identity and behaviour:

```
class Counter(value = 0)
  fun bump()
    this.value = this.value + 1
    return this.value
  end
end

c = Counter()
print c.bump()      # 1
print c.bump()      # 2
```

Fields are listed in the header exactly as they are for `data`, including defaults, and
defaults are evaluated per construction for the same reason. Methods are declared with `fun`
inside the class body and reach the object as `this`. An optional `init` block runs once per
construction, after every field is filled, so it can read and adjust any of them:

```
class Scaled(value)
  init
    this.value = this.value * 10
  end
end
print Scaled(2).value     # 20
```

An instance is a **reference**, so equality is identity, not structure: two objects with
equal fields are never `==`. `type_of` returns the class name. `init` cannot introduce a
field; every name it writes must already be in the header.

### Privacy

A field whose name begins with `_` is private to its class. Reading or writing it from
outside is an error that names the class:

```
class Account(_balance = 0)
  fun deposit(n)
    this._balance = this._balance + n
    return this
  end
  fun balance()
    return this._balance
  end
end

print Account().deposit(50).balance()    # 50
print Account()._balance                 # error: field '_balance' of Account is private
```

Privacy is per class, not per object: a method of `A` may read `other._v` when `other` is
also an `A`, and may not when it is a `B`.

The check covers every way a field can be named, and an object has no index form, so
`account["_balance"] = 99` is rejected rather than reaching the field.

A class declared inside a function or a block is subject to the same declaration checks as
one at the top level. It also does not disturb instances of a class of the same name declared
outside: each object keeps the declaration it was built from.

### Contracts

A `contract` lists the method names and arities a class promises to provide. It is checked
when the class is declared, so a missing method is reported even if no object is ever
built. A contract is a checked promise, not a type: it does not restrict what a variable may
hold, and it does not enable dispatch to a method the object does not have.

```
contract Shape
  fun area()
  fun scale(factor)
end

class Square(side) implements Shape
  fun area()
    return this.side * this.side
  end
  fun scale(factor)
    this.side = this.side * factor
    return this
  end
end
```

A class cannot be declared twice with the same method name, with the same field twice, or
with a field and a method sharing one name. `class`, `contract`, `implements`, `init` and
`this` are contextual: each remains usable as an ordinary variable name, so no existing
program changes meaning.

There is no inheritance. Polymorphism comes from contracts and from values that carry their
own methods, not from a class hierarchy. See [ADR-0027](../adr/0027-classes-and-contracts.md)
for why, and for the note that classes currently run on the interpreter rather than the
bytecode backend.

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
of arguments and return `nothing`. `write_text` is an alias of `write`: it writes the
same output, takes the same arguments and returns `nothing` too.

`print`, `write` and `assert` are the only three names that may be called as a command
statement, without parentheses. Every other builtin requires them, `write_text`
included. A command statement ends at the end of the line, so `write_text "x"` reports
`unexpected text after a statement`; it is `write_text("x")`.

```
print "a", "b"     # a b
write "a", "b"     # ab, cursor stays on the line
write_text("x")    # x, cursor stays on the line
```

Files: `read`, `read_lines`, `save`, `append`, `exists`, `list_files`, `delete`,
`make_dir`, `file_size`, `path_join`, `path_name`, `path_parent`, `cwd`.

Encoding: `hex_encode`, `hex_decode`, `base64_encode`, `base64_decode`. There is no `bytes`
type, so binary data is spelled as text: lowercase hex or standard base64 with padding. Both
are ASCII and round-trip, so a digest or a payload survives a file, a JSON document or a
terminal. Decoding refuses anything that is not canonically that spelling and reports the
offset it refused, because a value pasted from a log is the usual source.

```
print hex_encode("abc")          # 616263
print hex_decode("616263")       # abc
print base64_encode("abc")       # YWJj
print base64_decode("YWJj")      # abc
```

Hashing: `sha256(text)`, `sha1(text)`, `md5(text)`, `hmac_sha256(key, text)`. Each hashes the
UTF-8 bytes of the text and returns lowercase hex, so the result matches what `sha256sum`
prints for a UTF-8 file with the same content. `sha1` and `md5` exist because protocols and
file formats still name them; they are not safe for a secret. `secure_random_hex(n)` returns
`n` bytes from the platform's `SecureRandom`, as hex, for a nonce or a token; `random()`
remains the one for games and shuffling.

None of the digests is constant-time, and none is claimed to be. Comparison is the one
operation that is, because that is the one where a fast answer leaks where the inputs differ:

```oq
print secure_equal("aaaaaaab", "aaaaaaac")   # false
```

`secure_equal(a, b)` compares two texts through `MessageDigest.isEqual`, whose running time does
not depend on where the inputs first differ, unlike a `==` that stops at the first mismatched
character. Use it for a MAC or a token, not `==`. It is deliberately not what `==` does: most
text comparisons are not secrets, and paying the cost everywhere to defend the few would be a
poor trade. Non-text is refused, so `secure_equal(1, 2)` is an error rather than `false`.

Password storage needs a slow, salted function, and `sha256` is the wrong tool for it:

```
print pbkdf2_sha256("password", "salt", 1)
# 120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b
```

`pbkdf2_sha256(password, salt, iterations)` is `PBKDF2WithHmacSHA256` from the JVM, deriving
32 bytes as lowercase hex. The salt and the iteration count are both required, and the count
must be between 1 and 10,000,000. The salt is a parameter rather than generated, because a
generated one would have to be returned with the count for the stored value to be verifiable;
build the record yourself:

```oq
const salt = secure_random_hex(16)
const stored = salt + ":" + pbkdf2_sha256(password, salt, 600000)
```

```
print sha256("abc")     # ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
print secure_random_hex(8).length()   # 16
```

There is no API for loading a private key or a custom trust store. Certificate validation is
not declined — it happens on every `https` request with the JVM defaults and cannot be turned
off. What is absent is a key *value*, because a key needs a lifetime and this language has no
`close`: a key would live until the garbage collector decided otherwise, with no way to write
"use it, then be sure it is gone".

Exact fractions: `fraction(numerator, denominator)`, `fraction_of(text)`, `fraction_text(f)`,
`fraction_num(f)`, `fraction_den(f)`, `fraction_add(a, b)`, `fraction_sub(a, b)`,
`fraction_mul(a, b)`, `fraction_div(a, b)`, `fraction_neg(a)`, `fraction_abs(a)`,
`fraction_compare(a, b)`, `fraction_floor(f)`, `fraction_ceil(f)`, `fraction_round(f)`,
`fraction_pow(f, exponent)`, `fraction_to_decimal(f, places)`.

A `fraction` is the language's only exact non-integer number. The constructor takes two whole
numbers and normalises: the denominator is made positive and the two are reduced, so
`fraction(2, -4)` and `fraction_of("-1/2")` are the same value. A denominator of zero raises,
and a non-integral input such as `fraction(0.1, 2)` raises rather than rounding, because a
fraction built from a float would inherit the float's error and defeat the point.

```
print fraction_add(fraction(1, 3), fraction(1, 6))   # 1/2
print fraction_text(fraction(6, 4))                  # 3/2
print fraction_num(fraction(6, 4))                   # 3
print fraction_den(fraction(6, 4))                   # 2
print fraction_to_decimal(fraction(1, 3), 4)         # 0.3333
```

`fraction_of` reads `"3/4"`, `"-2/5"` and `"7"` and refuses anything else. `fraction_text`
writes the canonical `"n/d"`, or `"n"` when the denominator is `1`. Comparisons and every
arithmetic operation stay exact, including `fraction_pow`, whose exponent must be a whole
number from `-10000` to `10000` and whose result must keep both parts within `32768` bits, the
limit every operation is held to; `fraction_to_decimal` is the only way out to an inexact value
and it requires the caller to state the number of places (0 to 15), so the rounding is never
silent. `fraction_round` follows `round`: a tie goes upward, toward positive infinity.

Statistics: `mean`, `median`, `variance`, `stdev`, `mode`, `percentile(items, p)`.
Each accepts any iterable of numbers, so a list, a set or a range works.

```
print mean([1, 2, 3])              # 2
print median([4, 1, 3, 2])         # 2.5
print mode([3, 1, 1, 3])           # 1
print percentile([1, 2, 3, 4], 50) # 2.5
```

`variance` and `stdev` are the **sample** forms: they divide by `n - 1`, not `n`. The
difference is invisible on large inputs and material on small ones, so the two are not
interchangeable and the language offers only one:

```
print variance([2, 4, 4, 4, 5, 5, 7, 9])   # 4.571428571429
print stdev([2, 4, 4, 4, 5, 5, 7, 9])      # 2.138089935299
```

A sample variance of one item is undefined, so `variance([5])` is an error rather than `0`.
Returning zero would be the wrong answer stated confidently; the population variance of a
single value is genuinely zero, and that is a different question.

Ties and boundaries are decided rather than left to chance. An even-length `median` is the mean
of the two middle values. `mode` returns the **smallest** most-frequent value when several tie,
so the call always has an answer. `percentile` interpolates linearly between closest ranks, so
`percentile([1, 2, 3, 4], 50)` is `2.5` rather than `2` or `3`, and a `p` outside `0..100` is an
error rather than being clamped into range — a clamp would turn a typo into a plausible answer.
Both are computed so that the ends of the double range are not a special case:
`median([1e308, 1e308])` is `1e308`, not `infinity`, and two equal subnormals average to
themselves rather than to `0.0`.

Identifiers: `uuid()` and `is_uuid(text)`. `uuid()` mints a version 4 (random) identifier in
canonical lowercase `8-4-4-4-12` form. Version 1 is not offered because it embeds the host's
MAC address and a timestamp; version 7 is not offered because a time-ordered identifier is
guessable from the clock.

```
print uuid()                                  # ea2631a9-95a8-4304-829d-060fd002af6f
print is_uuid("ea2631a9-95a8-4304-829d-060fd002af6f")   # true
print is_uuid("{ea2631a9-95a8-4304-829d-060fd002af6f}") # false
```

`is_uuid` accepts the canonical hyphenated form and nothing else. Among the spellings it
refuses are the three that `UUID.fromString` in Java would take: braces, a `urn:uuid:` prefix,
and the 32-character form with no hyphens. A validator that accepted strings other systems
reject would be worse than no validator.

Ordered search: `heap_push(list, value)`, `heap_pop(list)`, `bisect_left(list, value)`,
`bisect_right(list, value)`. These are functions over ordinary lists, not a heap type, so no
second collection family was needed and the array stays inspectable.

`heap_push` and `heap_pop` keep a binary min-heap in place and mutate the list, matching
`push` and `pop`. The array is a heap, not a sorted list: `[1, 5, 3]` is a valid state and only
repeated `heap_pop` yields sorted order.

```
items = []
heap_push(items, 5)
heap_push(items, 1)
print heap_pop(items)   # 1
print heap_pop(items)   # 5
```

`bisect_left` and `bisect_right` return the index where a value is or should be, differing only
on duplicates: on `[1, 2, 2, 3]` the answer for `2` is `1` or `3`. Both assume the list is
sorted and do not check, because verifying would turn an `O(log n)` primitive into `O(n)` and
charge the correct caller for the incorrect one. An empty list is valid input and returns `0`.

JSON: `json_parse(text)`, `json(value)`.

CSV: `csv_parse(text)`, `csv_parse(text, delimiter)`, `csv_parse(text, delimiter, comments)`
return a list of rows of text, following RFC 4180 quoting. `csv_write(rows)` writes the same
shape back. A quoted field may contain the delimiter, a line break, or a doubled quote. A
malformed row raises an error rather than being repaired, because a table with the wrong shape
and no warning is worse than a program that stops. The delimiter is exactly one character.
Comment lines beginning with `#` are dropped only when the third argument is `true`; a quoted
`"#field"` is always data.

```oq
print csv_parse("a,\"b,c\"")            # [["a", "b,c"]]
print csv_parse(csv_write([["a", "b,c"]])) # [["a", "b,c"]]
```

XML is not provided. A parser for it needs namespace resolution, entity rules and a decision
about external entities, and the last of those is the XXE vulnerability class; an unhardened
implementation would be a security regression rather than a feature. Recorded as absent in the
requirement matrix.

Dates: `date_parse(text)` accepts `YYYY-MM-DD` and returns the same shape `today()` produces.
`instant_parse(text)` accepts an ISO-8601 instant with an explicit offset and returns the same
`...Z` shape `now()` produces, converting to UTC. A date with no offset is never given one,
because that would make the result depend on which machine ran the program.

Time: `now()`, `today()`, `datetime()`, `timestamp()`, `sleep(seconds)`.

`now()` and `timestamp()` are UTC. `today()` and `datetime()` are **local**, so the same
program prints different strings on machines in different zones — measured, not inferred:

```
$ TZ=Pacific/Kiritimati oq run t.oq
2026-11-11
$ TZ=UTC oq run t.oq
2026-11-10
```

That is a trap worth naming. When a result has to be the same everywhere, use `now()` and
`instant_parse`, not `today()` and `datetime()`.

Time zones: `tz_convert(instant, zone)` converts an ISO-8601 instant into wall-clock text
carrying an explicit numeric offset, for an IANA zone name.

```oq
print tz_convert("2026-11-10T14:04:24Z", "Asia/Tokyo")   # 2026-11-10T23:04:24+09:00
print tz_convert("2026-11-10T14:04:24Z", "UTC")          # 2026-11-10T14:04:24Z
```

The output carries an offset rather than a zone name, because a timestamp that requires a
zone-database lookup to read is not a timestamp. An abbreviation such as `JST` is an error, not
a guess: `CST` is three different zones. A bare date is an error too — use `date_parse` first.

HTTP: `http_get(url)`, `http_post(url, body)`. Both return a value with `.status`,
`.body` and `.ok`.

A failed request names the phase that failed and the cause, instead of ending in `null`:

```
print http_get("http://127.0.0.1:1/")
# error: HTTP request failed for 'http://127.0.0.1:1/': cannot reach the host: connection refused

print http_get("http://no-such-host.invalid/")
# error: HTTP request failed for 'http://no-such-host.invalid/': cannot resolve the host: the name did not resolve to an address
```

The two are distinguished because a name that does not resolve is a different problem from a
host that resolved and refused. Neither case is retried: `http_post` is not idempotent, so an
automatic retry could create a duplicate the server never acknowledged. Three timeouts apply —
15 seconds to connect, 30 seconds for headers, 30 seconds for the body — and each reports its
own phase, so a stalled server is never described as the subprocess timeout. TLS keeps the JVM
defaults: certificates and hostnames are validated, and there is no option to turn that off.

URLs: `url_parse(url)` returns `.scheme`, `.host`, `.port`, `.path`, `.query` and `.fragment`.
`url_encode(text)` and `url_decode(text)` are **form** encoding: a space becomes `+`, and a
literal `+` becomes `%2B`, so the two round-trip. `query_parse(query)` returns a list of
`{name, value}` pairs in order — a repeated name is kept, which a map could not do — and
`query_encode(pairs)` accepts either those pairs or a plain map. `url_join(base, reference)`
resolves a reference against a base and escapes it per path segment, which `url_encode` cannot
do correctly because it turns `/` into `%2F` and a space into `+`.

```oq
const u = url_parse("https://example.com/a/b?x=1&y=2#frag")
print u.scheme      # https
print u.port        # nothing
print u.path        # /a/b
print query_parse("a=1&a=2")   # [QueryPair(name: "a", value: "1"), QueryPair(name: "a", value: "2")]
print url_join("https://example.com/a/", "a b/c")
# https://example.com/a/a%20b/c
```

`port` is `nothing` when the URL does not write one; the scheme default is never invented, since
that would make `https://h` and `https://h:443` indistinguishable. Text that cannot be parsed as
a URL is an error rather than a value with empty fields.

Files: `read(path)`, `read_lines(path)`, `save(path, text)`, `append(path, text)`,
`exists(path)`, `delete(path)`, `make_dir(path)`, `file_size(path)`, `list_files(directory)`,
`path_join(a, b)`, `path_name(path)`, `path_parent(path)`, `cwd()`.

Also `is_file(path)` and `is_dir(path)`, because `exists` answers true for a directory and
`file_size` reports a directory's own size rather than failing. `walk(directory)` returns every
path under a directory as relative text, sorted, and does **not** follow symbolic links:
following one can revisit a directory already walked, and no entry cap stops a short cycle for
the right reason.

`save` replaces the file's whole contents, so it truncates an existing file; that is the
difference from `copy_file` and `move_file`, which never overwrite a target and refuse one that
is already there. `append` adds to the end, creating the file if it is missing. Both create
missing parent directories. Because they open the path rather than the file behind it, `save`,
`append` and `write_bytes` follow a symbolic link and write to what it points at, unlike `walk`
and `glob`, which do not follow links. Writing through a link is what a shell's `>` does; it is
stated here because it is otherwise indistinguishable from writing the link itself.

```oq
print walk("src")   # ["main", "main/Main.java", "test", "test/MainTest.java"]
```

Patterns: `glob(directory, pattern)` returns every matching path under a directory as relative
text, sorted, and `fnmatch(name, pattern)` tests one file name against one pattern. The dialect
is Python's glob: `*` and `?` stay inside one path segment, `**` crosses segments, and `[abc]`
is a character class.

```oq
# src is a.oq, x.txt, deep/b.oq
print glob("src", "**/*.oq")   # ["a.oq", "deep/b.oq"]
print glob("src", "*.oq")      # ["a.oq"]
print fnmatch("main.oq", "*.oq")   # true
```

Paths come back relative to the argument directory, with `/` separators even on Windows, so a
result can be joined back onto it and comparisons do not depend on the host. Recursion follows
the pattern rather than a flag: only a pattern that can reach past one segment — one containing
`**` or a separator — walks the tree, so `*.oq` deliberately lists one level and does not find
`deep/b.oq`. Write `**/*.oq` for that.

`**/` matches **zero or more** directories, so `**/*.oq` finds a top-level `a.oq`. Java's own
`PathMatcher` requires at least one directory there and would silently skip that file;
inheriting it would be a wrong answer rather than an error, so the pattern is translated to a
regex here instead. Symbolic links are not followed, for the reason `walk` gives: following one
can revisit a directory, and an entry cap would then trip for the wrong reason. A pattern that
matches more entries than a list may hold is an error rather than a truncated result.

Inside a class, only a leading `!` negates. Every other member is literal, so `[^a]` is "a caret
or an a" and not a negated class, and `[a&&b]` is three characters with a repeated `&` rather
than an intersection. Both spellings have a meaning in Java's regex engine that this dialect does
not have, so they are quoted before the pattern reaches it:

```oq
print fnmatch("^", "[^a]")   # true
print fnmatch("&", "[a&&b]") # true
```

A class the dialect accepts but a regex cannot express is a program error, not a fault in
Oqthara. Ranges run low to high, so `[z-a]` reports `bad pattern:` and exits 1 like any other
bad argument, rather than escaping as an internal error.

Copying: `copy_file(source, target)` and `move_file(source, target)` refuse an existing target
rather than overwriting it. Moving copies and then deletes the source, so a failure leaves the
source intact rather than a half-written target behind. `copy_tree(from, to)` copies a
directory recursively, refuses a target that exists and is not empty rather than merging into
it, refuses to follow a symbolic link anywhere in the tree, and counts the whole tree against
the same 16 MiB bound `copy_file` gives one file: an entry cap alone would allow a hundred
thousand files of sixteen megabytes each, and a per-file bound inside a tree that size would
allow the same 1.6 TiB. A tree refused partway leaves the partial target in place, because
removing it needs a recursive delete the language does not have; the error names the target
and says to delete it before retrying.

Binary: `read_bytes(path)` returns lowercase hex text and `write_bytes(path, hex)` accepts it.
There is no bytes type; hex round-trips through files, JSON and the encoders above, and a
program doing real binary arithmetic should not be parsing hex in the first place.

```oq
print read_bytes("a.bin")      # 00ff10
write_bytes("b.bin", "00ff10")
```

Compression: `zlib_compress(hex)` and `zlib_decompress(hex)` for raw DEFLATE,
`gzip_compress(text)` and `gzip_decompress(hex)` for the gzip container, and
`zip_compress(files)` with `zip_decompress(hex)` for an archive, where `files` is a map of entry
names to hex content. Every input and output is lowercase hex, the same spelling `read_bytes`
uses, and every call consumes a complete value and returns a complete value — there is no stream
and no handle.

```oq
packed = gzip_compress("hello hello")
print gzip_decompress(packed) == hex_encode("hello hello")   # true
bundle = zip_compress({"a.txt": hex_encode("aaa")})
print zip_decompress(bundle)      # {"a.txt": "616161"}
```

Decompression is capped at 16 MiB of output, and the cap is enforced by counting the bytes
actually produced rather than by reading a length from the input, because a hostile producer
writes whatever length it likes. Input that expands past the cap raises
`zlib_decompress() exceeded 16777216 bytes of output` — a named error, not an out-of-memory
failure — and the whole of a zip archive counts against one total, so a thousand entries just
under the limit cannot add up to an attack. Compressed input that ends early raises
`zlib_decompress() needs more input` rather than returning a partial value.

Entry names given to `zip_compress` may not be absolute, may not contain a `..` segment, a
backslash or a NUL, and are refused with `zip_compress() refused entry name: <name>`. The name
is refused rather than rewritten: a name that had to be changed to be safe is not the name the
caller asked for, and storing a quietly different one is the wrong-answer failure mode this
language treats as a defect.

`zip_decompress` applies the identical rule to the archive it is given, and refuses with
`zip_decompress() refused entry name: <name>` rather than returning a map holding a name the
writer would have refused. Reading is the direction that matters: a program does not choose the
names in an archive it is handed, and the obvious use of the result — writing each entry's
content to the path named by its key — would otherwise turn a hostile archive into an
arbitrary-file-write. Nothing is returned when any name is refused, so the failure is a refusal
and not a partial map the caller might act on. Names that merely contain dots, such as `..txt`
or `a/b..c`, are not traversal and are accepted in both directions. `tar` is not provided; zip covers the archive case and needs no
separate reader.

Text layout: `wrap_text(text, width)` returns the text as a list of lines no longer than
`width`, `fill_text(text, width)` returns the same thing joined by `\n`, and `shorten_text(text,
width)` collapses whitespace and cuts at a word boundary with a ` [...]` marker. Wrapping is
greedy and breaks only at whitespace: a run of spaces between two words becomes one space, and
leading whitespace is kept so an already-indented string stays indented.

```oq
print wrap_text("The quick brown fox jumps", 10)   # ["The quick", "brown fox", "jumps"]
print fill_text("The quick brown fox jumps", 10)
print shorten_text("Hello  world  foo bar", 12)    # Hello [...]
```

A word longer than the width is split at exactly the width, so `wrap_text("abcdefghij", 4)` is
`["abcd", "efgh", "ij"]`. A hyphen is not a break opportunity — `wrap_text("co-operate", 6)` is
`["co-ope", "rate"]` and not `["co-", "operate"]`. Python's `textwrap` breaks on hyphens by
default because it guesses at syllable boundaries; that guess is deliberately not made here, so
porting code that relies on it will produce different lines.

A width below 1 raises `wrap_text() needs a width of at least 1, got 0`, and a width that is not
a whole number raises `wrap_text() needs a whole number for width, got 2.5`. `shorten_text`
raises `shorten_text() cannot fit 6 characters of placeholder in a width of 4` rather than
returning a string longer than the width it was asked to respect.

```oq
print dedent_text("  a\n    b")   # a\n  b
print indent_text("a\n\nb", "> ")  # > a\n\n> b
```

`dedent_text(text)` removes the longest whitespace prefix every non-blank line shares, leaving
blank lines blank; whitespace-only text becomes `""`. `indent_text(text, prefix)` prefixes every
non-blank line and leaves blank lines empty, because prefixing a blank line adds trailing
whitespace that a diff then reports.

`unified_diff(before, after)` compares two lists of lines and returns the diff in unified format,
with optional third and fourth arguments naming the two sides. It shows three lines of context
and returns an empty list when nothing changed, so the result can be used as a condition.

```oq
print unified_diff(["a", "b", "c"], ["a", "x", "c"], "before", "after")
# ["--- before", "+++ after", "@@ -1,3 +1,3 @@", " a", "-b", "+x", " c"]
```

An empty side of a hunk is written `-0,0` or `+0,0`, as in every unified diff. Both arguments
must be lists of lines: passing text raises `unified_diff() needs a list of lines, got text`,
because text is iterable as characters and diffing characters is never what the caller meant.
The alignment is a longest common subsequence, so where several edits are equally minimal it may
choose a different one than Python's `difflib`; both reproduce the after-side exactly.

Neither copying nor reading is confined to the working directory. Oqthara is not a sandbox —
see the note below.

Processes: `run_command(path)` returns `.code` and `.output`. `env(name)`,
`cpu_count()`, `memory_mb()`.

Program: `args()`, `input()`, `input(prompt)`, `exit(code)`.

Logging: `log_debug`, `log_info`, `log_warn`, `log_error` write `LEVEL message` to the error
stream, never to the output stream, so a program's output stays pipeable. The threshold is
read from `OQ_LOG_LEVEL` once per process and defaults to `info`; `log_level(name)` changes it
at run time and `log_enabled(name)` asks whether a level would be written. There are no
timestamps, because a program that prints a clock is not reproducible.

Arguments: `arg_parse(arguments, options)` turns `args()` into `{flags, values, rest}` from a
declaration like `["verbose", "port:int", "output:text?"]`. A trailing `?` marks an option
optional and an absent optional is absent from `values`, not null. A malformed command line
raises an error naming the offending token and listing what was declared.

```oq
parsed = arg_parse(args(), ["verbose", "port:int", "name:text?"])
print parsed.flags      # ["verbose"]
print parsed.values.port
print parsed.rest       # ["file.txt"]
```

Logging and argument parsing are specified by
[ADR-0034](../adr/0034-logging-and-argument-parsing.md); CSV and date parsing by
[ADR-0035](../adr/0035-csv-and-structured-data.md); time zones and the filesystem additions by
[ADR-0036](../adr/0036-time-zones-and-filesystem.md).

### Host calls are bounded

The language restricts the host calls that can consume unbounded time or memory, so a
program cannot exhaust the machine by accident. These limits are part of the contract:

| Call | Limit | On breach |
| ---- | ----- | --------- |
| `print`, `write`, `write_text` | 16 MiB of output, total | `the program wrote too much output` |
| `read`, `read_lines` | 16 MiB per file | `the file is too large to read: <path>` |
| `input()` | 16,777,216 characters per line | `input line is too large` |
| `run_command` | 30 seconds total, 16 MiB of output | `command timed out after 30 seconds: <path>`, `command output is too large: <path>` |
| `http_get`, `http_post` | 15 s to connect, 30 s for headers, 30 s for the body, 16 MiB per body | `cannot reach the host: connection refused`, `no response headers within 30 seconds`, `the response body did not finish in 30 seconds`, `response is too large: <url>` |
| `save`, `append` | 16 MiB per call | `cannot save <N> bytes in one call`, `cannot append <N> bytes in one call` |
| `copy_file`, `move_file` | 16 MiB per file | `file is too large to copy: <path>` |
| `copy_tree` | 16 MiB per tree, 100,000 entries | `directory tree is too large to copy: <path>`, `directory tree has too many entries: <path>` |
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
| `copy_file`, `move_file`, `copy_tree` | bytes copied | bytes |
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

That includes the environment. `env(name)` reads any variable the interpreter process holds, and
a process started by `run_command` inherits the whole environment, because its
`ProcessBuilder` sets no environment of its own. Tokens and cloud keys live in exactly those
variables, so `env("GITHUB_TOKEN")` and `run_command` to a script that prints its environment
both disclose them. There is no allow-list on either path; the trust statement above is what
covers them, and adding a supervisor mode for untrusted programs would have to add one.

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

`await(task, seconds)` waits at most that long and then raises
`the task did not finish within N seconds`. The task is **not** cancelled — it keeps running,
and any work it does still happens. The reason is that nothing in the interpreter checks for
interruption, so a cancel would be reported without being performed, and a caller who was told
a task had stopped while it kept mutating shared state would be worse off than one who was told
the wait ended. `sleep` and `await` share one ceiling: ten seconds per call.

Errors inside a task are re-raised by `await`, so `try`/`catch` works across the boundary. An
error in a task that nothing awaits is **not** reported: the process still exits successfully,
because `spawn` deliberately does not turn an unobserved failure into a fatal one.

### Shared state

Values are ordinary Oqthara values, and the JVM's memory model applies. Two tasks that both
run `n = n + 1` can read the same `n` and both write the same successor, so the total is short.
`mutex()` gives one primitive for that:

```
n = 0
const m = mutex()

fun bump()
  n = n + 1
  return n
end

fun twice()
  i = 0
  while i < 2000
    m.lock(bump)
    i = i + 1
  end
end

const t = spawn(twice)
const other = spawn(twice)
await(t)
await(other)
print n                  # 4000, every run
```

`lock(body)` takes a function rather than being a pair of `lock`/`unlock` calls, because the
language has no `finally` and a raising body would otherwise leave the mutex held forever. The
lock is released however the body ends. It is **not reentrant**: locking it twice from the same
task raises `this mutex is already held by this task` instead of deadlocking.

`m.is_free()` reports whether the lock is held at that instant. It is for diagnostics — another
task can take the lock before the caller acts on the answer.

The mutex protects a convention, not the data. Nothing stops a program from writing `n = n + 1`
outside the lock; `lock` is how you say "these steps belong together", and the language does not
enforce it. There is no cancellation and no `close`, and there are no channels: a channel would
be another lifetime, and a blocking receive with no timeout would be a hang with no escape.

## Databases

SQLite is supported through three functions. There is no connection value and no `open`/`close`
pair, because the language has no `close`: every call opens the database, does its work, and
closes it before returning or raising.

```oq
db_execute("app.db", "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT)", [])

saved = db_execute("app.db", "INSERT INTO users (name) VALUES (?)", ["ana"])
print saved["last_id"]

rows = db_query("app.db", "SELECT id, name FROM users WHERE name = ?", ["ana"])
for row in rows
    print "{row["id"]} {row["name"]}"
end
```

`db_execute(path, sql, params)` runs a statement that does not return rows and gives back
`{"affected": n, "last_id": n}`. `db_query(path, sql, params)` runs a statement that does and
gives back a list of maps, one per row, keyed by column name.

The path is repeated on every call. That redundancy is the design: a value you cannot hold is a
value you cannot leak or forget to close.

### Parameters are always bound

Every function takes its values in the `params` list and binds them with a prepared statement.
Values are never spliced into the SQL text, so text that looks like SQL is stored as text:

```oq
payload = "'; DROP TABLE users--"
db_execute("app.db", "INSERT INTO users (name) VALUES (?)", [payload])

print db_query("app.db", "SELECT name FROM users", [])[0]["name"] == payload   # true
print db_query("app.db", "SELECT count(*) AS n FROM users", [])[0]["n"]       # the table is intact
```

Pass `[]` when the statement has no placeholders. The number of `?` placeholders must equal the
number of parameters; a mismatch raises rather than writing NULL:

```
error: db_execute() has 1 placeholder but got 0 parameters
```

That check exists because SQLite treats an unbound placeholder as NULL. Without it, a forgotten
parameter would store `nothing` and exit successfully.

### Transactions

`db_transaction(path, body)` runs `body` with an open transaction: it commits when the body
returns and rolls back when the body raises. Calls to `db_query` and `db_execute` made inside the
body join that transaction, even though they name the path again.

```oq
fun transfer()
    db_execute("bank.db", "UPDATE accounts SET balance = balance - ? WHERE id = ?", [50, 1])
    db_execute("bank.db", "UPDATE accounts SET balance = balance + ? WHERE id = ?", [50, 2])
    return "ok"
end

db_transaction("bank.db", transfer)
```

If the body raises, both statements are discarded and the original error reaches the caller
unchanged. A transaction cannot be nested; a nested call raises rather than silently joining the
outer one, because a joined inner transaction would turn an inner rollback into an outer commit.

### Types

| SQLite | Oqthara |
|---|---|
| `INTEGER` | number (whole) |
| `REAL` | number (decimal) |
| `TEXT` | text |
| `BLOB` | bytes |
| `NULL` | `nothing` |
| anything else | text |

`nothing` round-trips: a NULL column reads back as `nothing`, and passing `nothing` as a parameter
writes NULL. Boolean parameters are stored as `1` and `0`, and read back as numbers, because
SQLite has no boolean type.

### Errors

A malformed statement, a failed constraint, or a path whose directory does not exist raises an
error whose message begins with `database error:` and includes the driver's explanation. The
prefix is the stable part; the text after it is the driver's and may change between versions.

`:memory:` is accepted as a path and gives a scratch database. Because the connection closes at
the end of every call, an in-memory database does not persist between calls; use
`db_transaction` to keep one alive across several statements.

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
wrote hello.jar (343 classes, 0 skipped)
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
It publishes parse diagnostics on open and change, returns whole-document formatting
edits in the same canonical style as `oq fmt`, answers hover with a declaration's
signature and its documentation comment, and answers signature help with the parameters of
the call being typed. It supports `initialize`, `textDocument/didOpen`,
`textDocument/didChange`, `textDocument/didClose`, `textDocument/formatting`,
`textDocument/hover`, `textDocument/signatureHelp`, `shutdown` and `exit`; unknown requests
get a JSON-RPC "method not found" error and unknown notifications are ignored.

A hover returns markdown holding the declaration's signature in an `oq` code block,
followed by its summary, a bullet per parameter the comment describes, and one line per
remaining tag. A builtin resolves through the interpreter's own table, so the arity hint
is the one the call site is checked against rather than a second copy of the signature.
A position that names nothing declared returns `null`, which is how the protocol says
"show nothing"; an empty string would open an empty popup.

Resolution is lexical: the identifier under the cursor is matched against declarations in
the same file first, and shadowing is not modelled, so a local declaration wins and the
answer describes the declaration the reader can see rather than the one the program would
call. A name the file does not declare is then looked for in the modules it imports with
`use`, following those transitively through the same resolver the interpreter uses and
stopping at its depth limit of fifty. Following needs a document the editor saved to disk,
because the search order starts at the importing file's directory. A name nothing declares
still returns `null`, including when a module has moved or no longer parses.

Signature help resolves the call the cursor is inside, in the same lexical order as hover:
declarations in the file, then `use` imports, then builtins. Each candidate is rendered as a
label holding its parameter names, together with the offset range of each name inside that
label, and the reply names the active parameter by index — the two together are what let an
editor bold the argument being written rather than the whole signature. The active parameter
counts the top-level commas already typed and is clamped to the widest candidate, so it never
runs off the end when a call is half-written. A builtin that publishes no names renders its
arity alone, as `now()` or `write_text(...)`, rather than inventing labels. Opening
parentheses in text, in comments and inside an interpolation are not calls and get no answer.

Two forms deliberately get no answer. A method call (`x.slice(`) is not answered because the
receiver's type is not knowable from the text before the dot and the same method name means
different things to different receivers, so a signature there would be a guess. A declaration
header (`fun add(`) is not answered because it describes parameters being defined rather than
arguments being passed.

Unlike hover, signature help does not require the document to parse. Typing an opening
parenthesis leaves the file unbalanced, so when parsing fails the server falls back to
reading `fun` headers textually, skipping headers whose parameter list has not closed. That
fallback supplies the signatures a call is matched against; the call itself is read from the
text either way, so `print add(` is answered from a half-written file. Every answer is still
taken from the source rather than inferred from an argument count.

```bash
./gradlew :oq-lsp:standaloneJar
java -jar oq-lsp/build/libs/oq-lsp.jar   # speak LSP on stdin/stdout
```

Editors only need a command that starts that process; capabilities are `textDocumentSync`
(full), `documentFormattingProvider`, `hoverProvider`, and `signatureHelpProvider` with
`(` and `,` as both trigger and retrigger characters.

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

## Documentation comments

A multi-line text literal standing on its own before a declaration documents that
declaration:

```
"""Adds two numbers.

@param a the first addend
@param b the second addend
@return the sum
"""
fun add(a, b)
  return a + b
end
```

The literal attaches to the `fun`, `data`, `class` or `contract` that follows it, and inside a
class body to the `fun` that follows it. It is not evaluated and does not become a statement of
its own.

Only the multi-line form documents anything. A single-quoted string on the line before a
declaration is a value, exactly as it always was, so nothing that used to work changes
meaning:

```
print 1
"""a value nobody reads"""
print 2
```

That prints `1` then `2`, and the literal in the middle is an ordinary expression. A
literal after an assignment is likewise a value, not a comment on the assignment.

### Tags

A tag begins a line inside the literal with `@name` and runs to the next tag:

| Tag | Use |
|---|---|
| `@param name text` | describes one parameter, or a `data` field |
| `@return text` | what the call produces |
| `@throws Error text` | the failure it raises |
| `@since version` | when it appeared |
| `@author text` | who wrote it |
| `@deprecated text` | what to use instead |
| `@see other` | a related declaration |

Everything before the first tag is the summary. A line that mentions an `@` but does not
start with one is prose, so `Sends mail to @home when idle.` has no tags.

A tag the language does not recognise is kept rather than refused. `@example add(1, 2)`
is documentation under a name this version does not define, and writing it is not an
error.

A `@param` names its parameter explicitly. A `@param` whose name is not in the parameter
list is ignored rather than reported, because the documentation is not the authority on
the signature.

### Reading it back

`oq doc --source <path>` renders the declarations of a file, or of every `.oq` file under
a directory, from the comments they carry:

```
oq doc --source src/
oq doc --source src/ --out api.md
```

A class's methods are rendered one heading level below the class, each from its own
comment, so a method stays with the class it belongs to and a method name shared by two
classes cannot be confused for one declaration:

```
"""A counter.

@param value the starting value
"""
class Counter(value)
  """Adds one.

  @return the new value
  """
  fun increment()
    return value + 1
  end
end
```

A declaration without a comment appears as `_Undocumented._`, and the footer counts
documented and undocumented declarations — methods included — so the page shows what is
missing rather than only what is present. See [ADR-0030](../adr/0030-docstrings.md).

## Modules

A program can be split across files. `use "oq:name"` loads `name.oq` and makes its
top-level functions and `data` types available:

```
# helper.oq
fun twice(n)
  return n * 2
end

data Point(x, y)
```

```
# main.oq
use "oq:helper"
print twice(3)          # 6
print Point(1, 2)       # Point(x: 1, y: 2)
```

`name.oq` is looked for next to the program, then in `modules/`, then in `lib/`. The
`.oq` suffix is optional in the import.

A module's top-level statements run before the importing program's, once, however many
times it is imported. An import cycle terminates, because a file is loaded at most once:

```
# a.oq
use "oq:b"
fun af()
  return 1
end

# b.oq
use "oq:a"
fun bf()
  return 2
end
```

Importing `a` gives a program with both `af` and `bf`. Nothing recurses forever.

There is one flat namespace. A module contributes its functions and types and nothing
else: its locals stay local, and there is no `private`, no export list and no qualified
name such as `helper.twice`. A top-level name declared twice is an error where the
importer sees it:

```
error: 'twice' is already defined with 1 parameter
```

A failure inside a module reports that module's file, line and column, not a position in
the program that imported it.

`oq build` splices modules into the built program, so the jar runs without the `.oq` files
beside it. The trade-off is that a compiled jar reports a module failure against the
spliced source's line numbers rather than the module's own; `oq run` reports the module's
file and line.

`use` with a bare name such as `use java.time.LocalDate` is a Java import, described under
[Interoperability with Java](#interoperability-with-java). The `"oq:"` prefix is what
selects a module.

## Closures

A `fun` declared inside a `fun` body captures the enclosing bindings, by reference:

```
fun make_adder(n)
  fun add(x)
    return x + n
  end
  return add
end

a = make_adder(10)
print a(5)      # 15
```

Capturing by reference means the closure sees later writes, from either side:

```
n = 0
fun bump()
  n = n + 1
end
bump()
bump()
print n         # 2
```

A `const` can be captured too; it simply cannot be reassigned, so a closure over one
cannot change it. A captured parameter outlives the call that created it, because the
closure holds the binding rather than a copy:

```
fun box(v)
  fun get()
    return v
  end
  return get
end

print box("kept")()     # kept
```

Nested functions can be mutually recursive, because both names are hoisted inside the body
that declares them.

A closure keeps its captured bindings alive for as long as the closure itself is
reachable. Nothing frees them early and there is no way to free them by hand.

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

The collection families need no conversion at all, because they are already Java
collections: a `tuple` extends `AbstractList`, a `frozenset` extends `AbstractSet`, a
`map` extends `AbstractMap`, and a `list` is an `ArrayList`. Passing one to a Java method
that takes a `Collection` therefore hands over the real object rather than a copy, and
mutating it through the Java side is visible to Oqthara. That is the same aliasing rule
the language states elsewhere; it is not a new exception at the boundary.

A `range` is the one family that does not cross. It is a computed interval rather than a
stored collection, so it cannot satisfy a `Collection` parameter:

```text
error: cannot create ArrayList with 1 argument
```

Materialise it first if that is what you want, which also makes the allocation explicit:

```
use java.util.ArrayList

xs = []
for i in 1 to 3
  xs.push(i)
end
print ArrayList(xs).size()   # 3
```

### Static fields

A static field is read with the same dot syntax as a static method, on the imported class:

```
use java.lang.Integer
use java.lang.Math

print Integer.MAX_VALUE   # 2147483647
print Math.PI             # 3.14159265359
```

Only a field declared `static` is reachable this way. Naming an instance field on the class
itself is an error, because there is no instance to read it from:

```text
error: cannot read field 'size' on java Class
```

### Varargs

A Java method declared `Object...` — such as `String.format`, `String.join` or
`Arrays.asList` — accepts any number of trailing arguments, including none, and the bridge
packs them into the array the method actually declares:

```
use java.lang.String

print String.format("%s-%s", "a", "b")   # a-b
print String.format("no arguments")      # no arguments
print String.join("-", "x", "y", "z")    # x-y-z
```

When several overloads match, the one whose parameter types fit the arguments most exactly is
chosen, so `String.valueOf(5)` reaches the `int` overload rather than the `Object` one.
Arguments are converted by the table above before the choice is made.

### Exceptions crossing the boundary

A Java throwable does not reach the program as a Java object. It is translated into an
ordinary Oqthara error whose message is the throwable's class name and its message, and
whose span is the call that threw it. `try` catches it like any other error:

```
use java.lang.Integer

try
  print Integer.parseInt("not a number")
catch e
  print e.message   # NumberFormatException: For input string: "not a number"
  print e.line      # 4, the call that threw
end
```

The message reports the throwing class rather than wrapping it, so the text names what
failed in the JDK's own words. There is no separate `catch` clause for Java exceptions:
they are errors, and the span carries the position, which is what a stack trace would
otherwise be consulted for.

### Exact Java numbers

`BigDecimal` and a `BigInteger` wider than 64 bits stay Java objects. They are not
narrowed to `decimal` or `number`, because narrowing loses the thing they exist for — a
`BigDecimal` reports the scale it was given, and a `BigInteger` reports digits that a
`long` cannot hold:

```
use java.math.BigDecimal

tenth = BigDecimal("0.1")
fifth = BigDecimal("0.2")
print tenth.add(fifth).toString()                             # 0.3
print BigDecimal("1").divide(BigDecimal("3"), 10, 4).toString()  # 0.3333333333
```

Because they stay Java objects they keep their methods. Mixing one into Oqthara
arithmetic is an error rather than a silent conversion to `decimal`, which would discard
the exactness that made it worth importing.

### The classpath

`use` resolves against the classloader the program runs on, so any class on the server's
classpath is reachable, not only the JDK's:

```
use dev.oqthara.core.Lexer

print Lexer.lex("<probe>", "1 + 2").size()   # 5
```

This is the same mechanism the JDK classes above use. There is no separate import path,
no manifest and no allowlist; a class that is present is importable and one that is
absent is refused at `use`.

### Callbacks

A Java method that wants a functional interface accepts a `fun`. The parameter is the
interface's own single abstract method, so `forEach` wants a `Consumer` and `sort` wants a
`Comparator`:

```
use java.util.Collections

xs = [3, 1, 2]
xs.forEach(fun(x)
  print x
end)
Collections.sort(xs, (a, b) -> a - b)
print xs                 # [1, 2, 3]
```

The function keeps its own captures: it is called back in the environment it was declared in,
not in a copy of it, so a callback can read and write the enclosing bindings exactly as any
other `fun` can. Values Java passes in arrive as Oqthara values, so a callback over a list of
`Integer` is handed a `number`.

The arity has to fit. A function taking two parameters cannot stand where a `Consumer` — one
parameter — is wanted:

```text
error: 'forEach' wants a Consumer, which takes 1 argument, but '<lambda>' takes 2
```

A value a callback returns must also fit the interface's return type, and returning nothing
where a value is required is refused rather than passed as `null`:

```text
error: '<lambda>' returned nothing, which boolean cannot hold
```

### Objects Java returns

A value Java hands back is an ordinary Java object, and its methods are called the same way as
any other receiver's. Most of the JDK's own function objects live in package-private classes,
which reflection reports as inaccessible even though the interface is public; the bridge resolves
the method on the public supertype so the call works:

```
use java.util.Comparator

ascending = Comparator.naturalOrder()
descending = Comparator.reverseOrder()
print ascending.compare(1, 2)    # -1
print descending.compare(1, 2)   # 1
```

A Java value whose class declares a single abstract method is callable as a function, so the
direction back out of Java is complete for functional interfaces. Detection is by the same rule the
forward conversion applies — one abstract method, not the `@FunctionalInterface` annotation, which
`Comparator` and friends do not retain — and the call reuses the argument conversion and overload
selection of an explicit method call, so the two forms cannot diverge:

```
use java.util.Comparator
use java.util.function.Function

ascending = Comparator.naturalOrder()
print ascending(1, 2)            # -1, the same as ascending.compare(1, 2)
print Function.identity()("x")   # x
```

A class may satisfy more than one functional interface, and the interfaces the class *declares*
are considered before ones it merely inherits: `Comparator.naturalOrder()` is an enum, so it
inherits `Comparable` from `Enum` as well as declaring `Comparator`, and preferring the inherited
one made a one-argument call reach `compareTo` and die inside Java with a `ClassCastException`
casting a number to `Enum`. With nothing else to choose between them, the argument count decides
which method a bare call means. Two different methods at the same count would be genuinely
ambiguous and are refused rather than resolved by declaration order.

Declaring an interface only counts when that interface has a single abstract method. A class that
declares a marker such as `Serializable` while inheriting its callable interface from a superclass
is still callable: `class Saved extends Thread implements Serializable` is called as a `Runnable`,
because a marker makes no claim about how the value is used and must not hide one that does.
Declaring a *functional* interface is such a claim, so the same value at another argument count is
a wrong count rather than a reason to look further up the hierarchy.

A functional Java value called with the wrong number of arguments is not described as a non-function,
because it is one at its own count. The refusal names the count that would have worked:

```text
error: wrong number of arguments

  help: It is java NaturalOrderComparator, a Java object with one method of 2 arguments, so it is called with 2 rather than 1. Call its methods with '.' for anything else.
```

A Java object with no single abstract method is still refused as `this value is not a function`, and
the help carries the type. That refusal cannot name the caller's identifier, because a
standard-library higher-order method knows only the value it was handed, so it says `this value`
rather than quoting a placeholder that reads like one of the program's own names.

Such an object is still a Java reference, so it can be handed back to Java anywhere that type is
wanted — which is what makes the reverse comparator useful at all:

```
use java.util.Comparator
use java.util.Collections

descending = Comparator.reverseOrder()
xs = [1, 3, 2]
Collections.sort(xs, descending)
print xs                          # [3, 2, 1]
```
