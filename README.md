# Oqthara

**A small language for the JVM that reads like pseudocode.**

```
name = "World"
print "Hello, {name}!"
```

No classes. No `main`. No `new`. No semicolons. No getters. No checked exceptions.
Just code, on the JVM, with full access to every Java library.

```bash
oq hello.oq
```

## Install

Requires Java 21 or newer.

```bash
curl -fsSL https://raw.githubusercontent.com/Oqthara/oqthara/main/scripts/install.sh | bash
```

Or build from source:

```bash
git clone https://github.com/Oqthara/oqthara
cd oqthara
./gradlew :oq-cli:standaloneJar
java -jar oq-cli/build/libs/oq.jar examples/hello.oq
```

## A tour

```kotlin
data Point(x, y)

fun distance(a, b)
  dx = a.x - b.x
  dy = a.y - b.y
  return sqrt(dx * dx + dy * dy)
end

origin = Point(0, 0)
points = [Point(3, 4), Point(6, 8)]
for p in points
  print "distance: {distance(origin, p)}"
end
```

```kotlin
words = "the cat and the dog and the bird".split(" ")
counts = {}
for word in words
  counts[word] = counts.get(word, 0) + 1
end

for word in counts.keys().sort()
  print "{word}: {counts[word]}"
end
```

```kotlin
use java.time.LocalDate

launch = LocalDate(2026, 1, 1)
print "days until launch: {launch.toEpochDay() - today_epoch()}"

fun today_epoch()
  use java.time.LocalDate
  return LocalDate.now().toEpochDay()
end
```

## Why

Java is a good platform wrapped in a lot of ceremony. Oqthara keeps the platform and
removes the ceremony:

| Java | Oqthara |
|---|---|
| `public static void main(String[] args)` | nothing |
| `new ArrayList<>()` | `[]` |
| `list.stream().map(x -> x * 2).toList()` | `list.map(x -> x * 2)` |
| getters, setters, `equals`, `hashCode`, `toString` | `data Point(x, y)` |
| `String.format("Hi %s", name)` | `"Hi {name}"` |
| checked exceptions | none |
| `try { ... } catch` boilerplate | plain errors with source positions |

## Documentation

- [`docs/spec/language.md`](docs/spec/language.md) — complete language reference
- [`docs/spec/design.md`](docs/spec/design.md) — goals, non-goals and metrics
- [`docs/adr/`](docs/adr/) — architecture decisions
- [`examples/`](examples/) — 24 runnable programs, indexed by topic

## Development

```bash
./gradlew build      # compile, unit tests, conformance suite
./gradlew test       # tests only
./gradlew :oq-cli:run --args="examples/hello.oq"
oq test examples     # run the language-level test suite
```

The `conformance/` directory is the contract between the interpreter and the future
bytecode backend. Every `.oq` case has an expected `.out`, or an expected `.err`
fragment for programs that must fail.

## License

MIT.
