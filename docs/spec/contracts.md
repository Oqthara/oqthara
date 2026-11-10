# 8 Semantic and runtime contracts

This page is the register of the contracts that define what Oqthara means. Each row names one
area, the document that governs it, the version it is at, and how much of it is actually
enforced by a test. It exists because the directive asks for versioned contracts before major
feature work, and because "the behaviour is whatever the code does" is not a contract.

A contract is listed as **enforced** only when a test fails if the contract is broken. A contract
listed as **documented** is stated in prose with no test standing behind it. A contract listed as
**absent** does not exist yet, and the consequence of its absence is written in the row.

The version is the level of change a future edit would represent: `1` means agreed and
implemented, `0.x` means implemented but not yet reconciled against the other contracts, and
`—` means absent.

## 8.1 Contract register

| # | Area | Contract | Version | Enforced by |
|---|---|---|---|---|
| 1 | Type and value representation | [ADR-0007](../adr/0007-boxed-values.md), [ADR-0015](../adr/0015-type-families.md), §7.3 `M1`, `M2`, `M8` | `1` | `ValuesTest`, `ImmutableRefusalTest` |
| 2 | Bindings, scopes, `const`, assignment | [ADR-0013](../adr/0013-destructuring.md), §7.3 `E7`–`E11`, `language.md` §Variables | `1` | `LanguageFeatureTest`, `BytecodeCompilerTest` |
| 3 | Function signatures and overload selection | [ADR-0012](../adr/0012-overload-resolution.md) | `1` | `LanguageFeatureTest` overload cases |
| 4 | Collection protocols and iteration | [ADR-0016](../adr/0016-iteration-snapshots.md), [ADR-0017](../adr/0017-equality-and-hashing.md), §7.3 `M4` | `1` | `ValuesTest`, `ValueTest`, `ConcurrencyTest` |
| 5 | Destructuring and unpacking | [ADR-0013](../adr/0013-destructuring.md) | `1` | `LanguageFeatureTest`, `BytecodeCompilerTest` |
| 6 | Expression-oriented `try` | [ADR-0014](../adr/0014-try-as-an-expression.md) | `1` | `LanguageFeatureTest`, `BytecodeCompilerTest` |
| 7 | `class`, interface and `data` behaviour | `language.md` §Data types only | `0.5` | `LanguageFeatureTest` |
| 8 | Modules, imports and initialization | — | `—` | nothing |
| 9 | JavaBridge and host conversion | [ADR-0007](../adr/0007-boxed-values.md) partly, §7.3 `M8`, `M9` | `0.5` | `ImmutableRefusalTest` |
| 10 | Exception taxonomy and error messages | [ADR-0005](../adr/0005-errors.md), §7.3 `M11`, `M12`, §7.4 `U2`, `U3` | `1` | `ErrorMessageContractTest`, `BuildJarTest` |
| 11 | Concurrency, task lifecycle, cancellation | §7.3 `M5`–`M7`, `M13`, §7.4 `U9` | `0.5` | `ConcurrencyTest` |
| 12 | Interpreter/bytecode semantic equivalence | §7.4 `U1`–`U9`, [ADR-0003](../adr/0003-bytecode.md) | `1` | `verify-conformance.py`, `BuildJarTest` |
| 13 | Docstring metadata and generated documentation | — | `—` | nothing |
| 14 | Stdlib API registration and documentation | `oq doc`, `docs/stdlib/reference.md` | `1` | `check-docs-sync.py` |
| 15 | Runtime packaging and classloader behaviour | [ADR-0006](../adr/0006-classfile-writer.md), `backend.md` | `1` | `BuildJarTest` |

`check-docs-sync.py` asserts that every document named in this table exists, so a contract cannot
be deleted without this page noticing.

## 8.2 The contracts that are missing

Three areas have no contract at all. Each is a decision that will otherwise be made by accident
during implementation, so each is recorded here with the questions that must be answered before
the work starts.

### 8.2.1 Modules, imports and initialization (`—`)

There is no module system. `import` is not a keyword — measured, `import "mod.oq"` is a parse
error — and `use` imports a **Java** class, not an Oq file:

```
$ oq run use.oq          # use "mod.oq"
error: cannot find Java class 'mod.oq'
```

A multi-file program is a concatenation. `JarBuilder` embeds modules in a jar
(`BuildJarTest.jarEmbedsModulesSoItWorksFromAnyDirectory`), so some internal notion of a module
already exists — `oq build` accepts a set of files — but it is not exposed to the language and is
not specified. The questions: does `import` exist, is a module a file or a directory, what is the
initialization order, are top-level statements run on import, and can a cycle be detected.

### 8.2.2 Docstring metadata and generated documentation (`—`)

`oq doc` reads the **standard library sources**, not a program. Measured, `oq doc mod.oq` looks
for the standard library under the path it was given and reports
`Cannot find the standard library sources under ...`. A program cannot document itself, and there
is no docstring syntax: a leading `###` line in a function body is a comment, not metadata.

This is the contract behind "documentation is a product surface", and the gap is that it applies
only to the standard library. The questions: is a docstring a comment convention or a string
expression, does it survive to the LSP hover, and does it survive into a built jar.

### 8.2.3 `class` and interfaces (`0.5`)

`class` does not exist. `data` does, and its contract is stated and holds: structural equality, a
hash consistent with it, a readable text form, and per-instance default expressions.

```
data Point(x, y)
p = Point(1, 2)
print p == Point(1, 2)      # true
print p                     # Point(x: 1, y: 2)
print length(set([P(1), P(1), P(2)]))   # 2 — equality and hash agree
```

Two `data` behaviours are absent and both fail honestly rather than silently:

```
x, y = p                    # error: cannot destructure P
q = p.with(b = 9)           # error: expected to close the argument list
```

Destructuring of a `data` value was deferred by ADR-0013 and copy-with-changes does not exist.
Neither is a defect; both are gaps that the Alpha 4 `data` work (directive §14.2) owns.

## 8.3 Contradictions found while writing this register

Recording the contracts exposed one place where the implementation, the specification and the
parser disagree, and it is a language defect rather than a documentation one.

**`finally` is accepted by the parser and does not exist in the language.** `language.md` says so
plainly: *"There is no `finally` and no typed catch."* The parser has no `finally` clause, and
that is the problem: `finally` is not a keyword, so it lexes as an identifier, and the statement
list of a `catch` block absorbs it. The failure therefore depends on whether an error occurred,
which is the worst possible shape for a bug:

```
# no error: the catch body never runs, so nothing looks wrong
try
    print "a"
catch e
    print "b"
finally
    print "cleanup"
end
print "done"
```

```
a
done
```

The program above is accepted, runs, and never prints `cleanup`. Now with an error:

```
try
    1/0
catch e
    print "caught"
finally
    print "cleanup"
end
```

```
caught

error: 'finally' is not defined
  --> g2.oq:5:1
   5 | finally
       ^^^^^^^

  help: Check the spelling, or define it before using it: finally = value
```

A programmer who writes `finally` expecting cleanup gets a program that silently skips the cleanup
when nothing fails, and dies with a confusing *undefined name* when something does. The diagnosis
names the wrong problem: the fault is a language construct that looks supported and is not.

This belongs in the exception taxonomy contract (`#10`) and is recorded in
`03-runtime.md` §7.3.6. The repair is either to implement `finally` or to make it a reserved word
with a diagnostic that says it does not exist; both are language changes and neither belongs in a
documentation branch.

## 8.4 What "versioned" means here

The version column tracks how settled a contract is, not how recently it changed:

- `1` — the behaviour is implemented, tested, and no other contract contradicts it.
- `0.5` — the behaviour is implemented and tested, but the contract is incomplete in a way
  another area already depends on. `#7` is `0.5` because `data` is specified but
  `class` is not, and both will share a method-resolution rule. `#9` is `0.5` because the bridge
  has no conversion layer (`03-runtime.md`, `M8`). `#11` is `0.5` because cancellation is absent
  and the depth counter is not thread-safe.
- `—` — absent.

A contract moves to `1` when the questions in §8.2 are answered and implemented, not when the
prose is written.

## 8.5 How this register stays honest

Three rules, each enforced rather than intended:

1. **Every document named here must exist.** `check-docs-sync.py` fails the build otherwise, so a
   contract cannot be quietly retired from the register by deleting its document.
2. **Every area the directive lists appears exactly once.** The register has fifteen rows and the
   directive names fifteen areas, in the same order, so an omission is a diff.
3. **A contract claimed as enforced must name a test.** A row that cannot name one is written as
   documented or absent. Two rows in this register are already `—`, and saying so is the point.
