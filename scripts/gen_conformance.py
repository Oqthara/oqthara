import pathlib, textwrap

CASES = {}

def c(name, src):
    CASES[name] = textwrap.dedent(src).strip() + "\n"

c("001-hello", 'print "Hello, World!"')
c("002-hello-interpolation", '''
name = "World"
print "Hello, {name}!"
''')
c("003-numbers", '''
print 1 + 2
print 10 - 4
print 3 * 4
print 10 / 2
print 7 % 3
print 2 ** 10
''')
c("004-decimals", '''
print 1.5 + 2.25
print 10 / 4
print 3.0 * 2
''')
c("005-negatives", '''
print -5 + 3
print -(4 * 2)
print 3 - -2
''')
c("006-precedence", '''
print 2 + 3 * 4
print (2 + 3) * 4
print 2 ** 3 ** 2
''')
c("007-comparison", '''
print 1 < 2
print 2 <= 2
print 3 > 4
print 5 >= 5
print 1 == 1
print 1 != 2
''')
c("008-booleans", '''
print true
print false
print not true
print true and false
print true or false
''')
c("009-if", '''
x = 10
if x > 5
  print "big"
else
  print "small"
end
''')
c("010-if-else-if", '''
score = 75
if score >= 90
  print "A"
else if score >= 80
  print "B"
else if score >= 70
  print "C"
else
  print "F"
end
''')
c("011-while", '''
i = 0
while i < 5
  print i
  i = i + 1
end
''')
c("012-for-range", '''
for n in 1 to 3
  print n
end
''')
c("013-for-until", '''
for n in 0 until 3
  print n
end
''')
c("014-for-list", '''
for item in ["a", "b", "c"]
  print item
end
''')
c("015-break-continue", '''
for n in 1 to 10
  if n == 4
    break
  end
  if n == 2
    continue
  end
  print n
end
''')
c("016-functions", '''
fun add(a, b)
  return a + b
end

print add(2, 3)
print add("x", "y")
''')
c("017-recursion", '''
fun fact(n)
  if n <= 1
    return 1
  end
  return n * fact(n - 1)
end

print fact(10)
''')
c("018-fibonacci", '''
fun fib(n)
  if n < 2
    return n
  end
  return fib(n - 1) + fib(n - 2)
end

for i in 0 to 10
  print fib(i)
end
''')
c("019-closures", '''
fun make_counter()
  count = 0
  return fun()
    count = count + 1
    return count
  end
end

c1 = make_counter()
print c1()
print c1()
print c1()
''')
c("020-lambdas", '''
double = x -> x * 2
print double(21)
add = (a, b) -> a + b
print add(3, 4)
''')
c("021-list-basics", '''
xs = [1, 2, 3]
print xs
print xs[0]
print xs[2]
print xs[-1]
print length(xs)
''')
c("022-list-mutation", '''
xs = [1, 2, 3]
xs[1] = 20
print xs
xs.push(4)
print xs
print xs.pop()
print xs
''')
c("023-list-map-filter", '''
xs = [1, 2, 3, 4, 5]
print xs.map(x -> x * x)
print xs.filter(x -> x % 2 == 0)
print xs.sum()
''')
c("024-list-reduce", '''
xs = [1, 2, 3, 4]
print xs.reduce((a, b) -> a + b)
print xs.reduce((a, b) -> a + b, 100)
''')
c("025-list-sort", '''
print [3, 1, 2].sort()
print [3, 1, 2].sort(x -> -x)
print ["pear", "apple", "fig"].sort()
''')
c("026-list-join", '''
print [1, 2, 3].join(", ")
print ["a", "b"].join("")
''')
c("027-list-nested", '''
grid = [[1, 2], [3, 4]]
print grid[1][0]
print grid
''')
c("028-map-basics", '''
m = {"one": 1, "two": 2}
print m["one"]
m["three"] = 3
print m["three"]
print m.size()
print m.has("two")
''')
c("029-map-keys", '''
m = {"a": 1, "b": 2}
print m.keys().sort()
print m.values()
''')
c("030-set-basics", '''
s = {3, 1, 2, 1}
print s.size()
print s.has(2)
s.add(4)
print s.size()
''')
c("031-data", '''
data Point(x, y)

p = Point(3, 4)
print p
print p.x
print p.y
p.x = 10
print p.x
''')
c("032-data-defaults", '''
data Person(name, age = 0)

print Person("Ada")
print Person("Bob", 30)
''')
c("033-data-equal", '''
data Point(x, y)

print Point(1, 2) == Point(1, 2)
print Point(1, 2) == Point(1, 3)
''')
c("034-string-methods", '''
s = "Hello"
print s.upper()
print s.lower()
print s.length()
print s.contains("ell")
print s.starts_with("He")
''')
c("035-string-split", '''
print "a,b,c".split(",")
print "one two  three".words()
''')
c("036-string-interpolation-expr", '''
x = 5
print "{x} squared is {x * x}"
print "sum: {1 + 2 + 3}"
''')
c("037-string-escape", '''
print "line1\\nline2"
print "quote: \\"hi\\""
print "tab:\\there"
''')
c("038-string-repeat", '''
print "ab" * 3
print "-" * 10
''')
c("039-fizzbuzz", '''
for n in 1 to 15
  if n % 15 == 0
    print "FizzBuzz"
  else if n % 3 == 0
    print "Fizz"
  else if n % 5 == 0
    print "Buzz"
  else
    print n
  end
end
''')
c("040-range-iteration", '''
total = 0
for n in 1 to 100
  total = total + n
end
print total
''')
c("041-nested-loops", '''
for i in 1 to 3
  for j in 1 to 3
    print "{i},{j}"
  end
end
''')
c("042-return-none", '''
fun greet(name)
  print "Hello, {name}"
end

result = greet("Ada")
print result == nothing
''')
c("043-multiple-returns", '''
fun sign(n)
  if n > 0
    return "positive"
  end
  if n < 0
    return "negative"
  end
  return "zero"
end

print sign(5)
print sign(-5)
print sign(0)
''')
c("044-higher-order", '''
fun apply(f, value)
  return f(value)
end

print apply(x -> x + 1, 10)
print apply(x -> x * x, 6)
''')
c("045-string-number", '''
print number("42") + 1
print number("3.5") * 2
print text(42) + "!"
''')
c("046-math", '''
print abs(-5)
print floor(3.7)
print ceil(3.2)
print round(3.5)
print sqrt(16)
print min(3, 7)
print max(3, 7)
''')
c("047-json", '''
data = json_parse("{\\"name\\": \\"Ada\\", \\"n\\": 3}")
print data["name"]
print data["n"]
''')
c("048-json-write", '''
print json({"a": 1})
''')
c("049-json-array", '''
items = json_parse("[1, 2, 3]")
print items
print length(items)
''')
c("050-word-count", '''
text = "the cat and the dog and the bird"
counts = {}
for word in text.split(" ")
  counts[word] = counts.get(word, 0) + 1
end
for key in counts.keys().sort()
  print "{key}: {counts[key]}"
end
''')
c("051-list-of-data", '''
data Item(name, price)

items = [Item("apple", 3), Item("pear", 5)]
for item in items
  print "{item.name}: {item.price}"
end
''')
c("052-data-map", '''
data User(name)

users = [User("Ada"), User("Bob")]
names = users.map(u -> u.name)
print names
''')
c("053-assert", '''
assert 1 + 1 == 2
assert true, "arithmetic is broken"
print "ok"
''')
c("054-string-index", '''
print "abc"[0]
print "abc"[-1]
print "abc".slice(1)
print "abc".slice(0, 2)
''')
c("055-list-contains", '''
xs = [1, 2, 3]
print xs.contains(2)
print xs.contains(9)
print 2 in xs
''')
c("056-list-unique", '''
print [1, 2, 2, 3, 1].unique()
''')
c("057-list-reverse", '''
print [1, 2, 3].reverse()
''')
c("058-list-take-skip", '''
xs = [1, 2, 3, 4, 5]
print xs.take(2)
print xs.skip(3)
''')
c("059-map-each", '''
m = {"a": 1, "b": 2}
keys = []
m.each((k, v) -> push(keys, k))
print keys.sort()
''')
c("060-set-operations", '''
a = {1, 2, 3}
b = {2, 3, 4}
print a.union(b).items().sort()
print a.intersection(b).items().sort()
''')
c("061-boolean-functions", '''
fun is_even(n)
  return n % 2 == 0
end

print [1, 2, 3, 4].filter(is_even)
''')
c("062-any-all", '''
xs = [1, 2, 3]
print xs.any(x -> x > 2)
print xs.all(x -> x > 0)
print xs.find(x -> x > 1)
''')
c("063-nested-data", '''
data Address(city)
data Person(name, address)

p = Person("Ada", Address("London"))
print p.address.city
''')
c("064-list-in-data", '''
data Team(name, members)

t = Team("Core", ["Ada", "Bob"])
print t.members.length()
for m in t.members
  print m
end
''')
c("065-multiple-assign", '''
x = 1
y = x
x = 2
print x
print y
''')
c("066-string-build", '''
parts = []
for i in 1 to 5
  parts.push("{i}-")
end
print parts.join("")
''')
c("067-fib-memo", '''
memo = {}
fun fib(n)
  key = text(n)
  if memo.has(key)
    return memo[key]
  end
  if n < 2
    return n
  end
  result = fib(n - 1) + fib(n - 2)
  memo[key] = result
  return result
end

print fib(30)
''')
c("068-prime-check", '''
fun is_prime(n)
  if n < 2
    return false
  end
  i = 2
  while i * i <= n
    if n % i == 0
      return false
    end
    i = i + 1
  end
  return true
end

primes = []
for n in 2 to 30
  if is_prime(n)
    primes.push(n)
  end
end
print primes
''')
c("069-binary-search", '''
fun search(items, target)
  low = 0
  high = items.length() - 1
  while low <= high
    mid = (low + high) / 2
    mid = floor(mid)
    if items[mid] == target
      return mid
    end
    if items[mid] < target
      low = mid + 1
    else
      high = mid - 1
    end
  end
  return -1
end

xs = [1, 3, 5, 7, 9]
print search(xs, 7)
print search(xs, 4)
''')
c("070-bubble-sort", '''
fun bubble(xs)
  items = xs.clone()
  n = items.length()
  for i in 0 until n
    for j in 0 until n - i - 1
      if items[j] > items[j + 1]
        temp = items[j]
        items[j] = items[j + 1]
        items[j + 1] = temp
      end
    end
  end
  return items
end

print bubble([5, 2, 9, 1, 5, 6])
''')
c("071-string-palindrome", '''
fun is_palindrome(s)
  clean = s.lower().replace(" ", "")
  return clean == clean.chars().reverse().join("")
end

print is_palindrome("racecar")
print is_palindrome("hello")
print is_palindrome("A man a plan a canal Panama")
''')
c("072-count-vowels", '''
fun vowels(s)
  count = 0
  for ch in s.lower()
    if "aeiou".contains(ch)
      count = count + 1
    end
  end
  return count
end

print vowels("hello world")
''')
c("073-reverse-words", '''
words = "the quick brown fox".split(" ")
print words.reverse().join(" ")
''')
c("074-caesar", '''
fun shift(s, n)
  result = []
  for ch in s.chars()
    code = ch.chars().first()
    if code == " "
      result.push(" ")
      continue
    end
    shifted = (ch.code_at(0) - 97 + n) % 26 + 97
  end
  return result
end
''')
c("075-nested-condition", '''
for n in 1 to 20
  if n % 2 == 0
    if n % 4 == 0
      print "{n} divisible by 4"
    else
      print "{n} even"
    end
  end
end
''')
c("076-accumulate", '''
sum = 0
product = 1
for n in 1 to 5
  sum = sum + n
  product = product * n
end
print sum
print product
''')
c("077-string-format", '''
data Point(x, y)
p = Point(1, 2)
print "(x={p.x}, y={p.y})"
''')
c("078-list-index-error", '''
print [1, 2, 3][5]
''')
c("079-undefined-name", '''
print missing
''')
c("080-type-error", '''
print 1 + {}
''')
c("081-arity-error", '''
fun f(a)
  return a
end
f(1, 2)
''')
c("082-division-by-zero", '''
print 1 / 0
''')
c("083-assert-error", '''
assert 1 == 2, "one is not two"
''')
c("084-call-nothing", '''
x = nothing
x.foo()
''')
c("085-parse-error", '''
if true
print "no end"
''')
c("086-map-missing-key", '''
m = {"a": 1}
print m["b"]
''')
c("087-nested-function-scope", '''
x = "outer"
fun show()
  print x
end
show()
''')
c("088-block-scope", '''
x = 1
if true
  x = 2
  y = 3
end
print x
print y
''')
c("089-while-sum", '''
total = 0
n = 1
while n <= 10
  total = total + n
  n = n + 1
end
print total
''')
c("090-multi-line-list", '''
xs = [
  1,
  2,
  3,
]
print xs
''')
c("091-multi-line-map", '''
m = {
  "a": 1,
  "b": 2,
}
print m.keys().sort()
''')
c("092-comments", '''
# This is a comment
print "after comment" # trailing comment
''')
c("093-semicolons", '''
x = 1; y = 2; print x + y
''')
c("094-string-literal-single", """
print 'single quotes work'
""")
c("095-nested-interpolation", '''
name = "Ada"
print "Hello, {name.upper()}!"
''')
c("096-interpolation-call", '''
fun square(n)
  return n * n
end
print "square: {square(7)}"
''')
c("097-list-of-lists-sum", '''
grid = [[1, 2, 3], [4, 5, 6]]
total = 0
for row in grid
  for cell in row
    total = total + cell
  end
end
print total
''')
c("098-flatten", '''
print [[1, 2], [3], [4, 5, 6]].flatten()
''')
c("099-zip", '''
print zip([1, 2], ["a", "b"])
''')
c("100-clamp", '''
print clamp(5, 1, 10)
print clamp(-5, 1, 10)
print clamp(50, 1, 10)
''')
c("101-string-pad", '''
print "7".pad_start(3, "0")
print "ab".pad_end(5, ".")
''')
c("102-tuple-like", '''
data Pair(a, b)
p = Pair(1, "one")
print p.a
print p.b
''')
c("103-early-exit", '''
fun first_negative(xs)
  result = nothing
  for x in xs
    if x < 0
      result = x
      break
    end
  end
  return result
end

print first_negative([1, 2, -3, 4])
print first_negative([1, 2, 3])
''')
c("104-gcd", '''
fun gcd(a, b)
  while b != 0
    t = b
    b = a % b
    a = t
  end
  return a
end

print gcd(48, 18)
print gcd(17, 5)
''')
c("105-nested-closures", '''
fun adder(x)
  return y -> x + y
end

add5 = adder(5)
print add5(10)
''')
c("106-list-sort-data", '''
data Person(name, age)
people = [Person("C", 30), Person("A", 25), Person("B", 35)]
sorted = people.sort(p -> p.age)
print sorted.map(p -> p.name)
''')
c("107-map-iteration", '''
m = {"x": 1, "y": 2, "z": 3}
total = 0
for key in m.keys()
  total = total + m[key]
end
print total
''')
c("108-print-variants", '''
print 1, 2, 3
print "a", true, nothing
''')
c("109-deep-recursion", '''
fun count_down(n)
  if n == 0
    return 0
  end
  return count_down(n - 1) + 1
end
print count_down(100)
''')
c("110-modulo-negative", '''
print -7 % 3
print 7 % -3
''')
c("111-exponent-negative", '''
print 2 ** -1
''')
c("112-string-comparison", '''
print "apple" < "banana"
print "zebra" > "apple"
''')
c("113-range-value", '''
r = 1 to 5
print r
print length(r)
''')
c("114-list-empty", '''
xs = []
print xs
print length(xs)
print xs.sum()
''')
c("115-nothing", '''
x = nothing
print x
print x == nothing
print is_nothing(x)
''')
c("116-boolean-coercion", '''
if 0
  print "zero truthy"
else
  print "zero falsy"
end
if ""
  print "empty truthy"
else
  print "empty falsy"
end
''')
c("117-write", '''
write "a"
write "b"
print ""
''')
c("118-argument-type", '''
print type_of(1)
print type_of(1.5)
print type_of("x")
print type_of(true)
print type_of(nothing)
print type_of([1])
print type_of({"a": 1})
''')
c("119-string-lines", '''
multi = "one\\ntwo\\nthree"
for line in multi.lines()
  print "> {line}"
end
''')
c("120-const-function", '''
fun make_adder(n)
  return x -> x + n
end
a = make_adder(10)
b = make_adder(100)
print a(1)
print b(1)
''')

root = pathlib.Path("conformance")
root.mkdir(exist_ok=True)
for name, src in CASES.items():
    (root / f"{name}.oq").write_text(src)
print(f"wrote {len(CASES)} cases")
