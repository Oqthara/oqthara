# An Oqthara function at the Java boundary — decision brief

**State:** OPEN — no decision taken. Nothing here is implemented, and nothing here proposes to
implement before the decision is recorded.
**Scope:** Alpha 7 §2.1, and R-014 in
[`docs/governance/alpha-4-risk-register.md`](../alpha-4-risk-register.md).
**Blocks:** nothing merged. Every behaviour described as current is shipped and tested.

The risk register says of this question: *"That decision needs an ADR."* This is the brief that
ADR would decide. It states what exists, what each option would cost, and which questions the
architecture already answers — so that the decision is about one choice rather than about
re-deriving the current state.

The register's own description of the gap is **stale**, and correcting it is part of this brief
rather than a separate task: see "The register is out of date" below.

---

## The question

One sentence: **should an Oqthara function convert to a Java functional interface implicitly,
and should a Java functional interface convert to an Oqthara function implicitly?**

Both directions exist today, one by design and one by accident. The question is not whether to
build them but whether to *commit* to them, because each is currently a behaviour nobody chose.

---

## What exists today (measured, not recalled)

Every claim below was executed against `oq-cli/build/libs/oq.jar` from commit `6818f3a` on both
backends. Programs are in `/tmp` and are not part of the tree; the shapes are reproduced here so
they can be re-run.

### Direction into Java: an Oqthara function becomes a Java object

A function argument that meets an interface with exactly one abstract method becomes a
`java.lang.reflect.Proxy` that calls back through the host that created it
(`JavaBridge.functionalInterface`, `JavaBridge.java:617`).

```oq
use java.util.Comparator
use java.util.Collections
xs = [3,1,2]
Collections.sort(xs, (a, b) -> a - b)
print xs
```

```
[1, 2, 3]
```

This works identically on both backends and is the shape the register calls closed. It is
**not** the gap.

### Direction out of Java: a Java object is callable as a function

```oq
use java.util.function.Function
f = Function.identity()
print f(9)
```

```
9
```

The value prints as `java.util.function.Function$$Lambda/0x00000080010645c8@...`, so it is a
Java object, and calling it applies the single abstract method whose arity matches the call. The
same value also survives the places a `fun` is *required*, not merely accepted:

```oq
use java.util.function.Function
fun use_it(k, xs)
  return map(xs, k)
end
print use_it(Function.identity(), [1,2])
```

```
[1, 2]
```

And it round-trips out and back in:

```oq
use java.util.function.Function
id = Function.identity()
inc = (x) -> x + 1
composed = id.andThen(inc)
print composed.apply(5)
print map([1,2], composed)
```

```
6
[2, 3]
```

**This is the behaviour the register says does not exist.** It has existed since `a4c1c68`
"Make Java functional interface values callable" (#122, 2026-11-11), which pre-dates the
register's current text.

### The register is out of date

R-014 says, in the present tense:

> a method that takes a `Function` and hands one back on return yields a JDK object that Oqthara
> can call through its methods but **cannot hold as a `fun` value**, and a Java lambda never
> becomes a value the language can pass to `map`

Both halves were measured false. `map(xs, composed)` above passes a JDK lambda to `map` and
returns `[2, 3]`; `use_it(Function.identity(), [1,2])` passes one to a user-defined `fun`
parameter. The register's own next paragraph records the *forward* fix ("The same signature is
now resolved on a public supertype, and both calls work") without withdrawing the sentence it
contradicts.

The register is therefore not evidence for this decision, and should not be cited as if it were.
What remains genuinely open is narrower, and is listed below.

### What genuinely does not work

**1. A function stored in a `data` field is reached by an accessor, so it is called as
`b.mapper()(5)`.**

```oq
data Box(mapper)
b = Box((x) -> x * 3)
print b.mapper()(5)
```

```
15
```

Written as `b.mapper(5)` it reports `mapper() expects 0 arguments, got 1`, because `b.mapper` is the
zero-argument *accessor* and passing `5` is an argument to the accessor, not to the function it
returns. Reading the field into a variable first also works (`h = b.mapper; print h(5)` → `15`).

**Corrected 2026-11-19:** this item originally called the accessor convention a parser or
interpreter bug, and then described it as the field read rather than the call spelling. It is
neither bug: `b.mapper` is an ordinary field read that answers the function, and only the *call*
`b.mapper(5)` goes through a zero-argument accessor (`MethodResolverImpl.java:53`). It applies
identically to a non-function field, and nothing needs repairing. The surprise is real and worth a
documentation note, which is why it is filed as
[#269](https://github.com/Oqthara/oqthara/issues/269), but it is not a defect and it is not part of
R-014.

**2. An Oqthara function value has no member surface, so a default method cannot be reached from
the Oqthara side.**

```oq
use java.util.Comparator
cmp = (a, b) -> a - b
rev = cmp.reversed()
```

```
error: type function <lambda> has no method 'reversed'
```

**Corrected 2026-11-19:** this paragraph originally blamed `java.lang.reflect.Proxy`, citing
`JavaBridge.java:637`. That line is real, but it is unreachable from this program. `cmp` is an
Oqthara function, so `cmp.reversed()` is refused by the interpreter's own member resolution before
any Java dispatch happens — `cmp.compare(1, 2)` is refused the same way, and so is reading
`cmp.toString`. The proxy's default-method refusal only fires when *Java* invokes a default method
on the proxy, and that path works: `Collections.reverseOrder(cmp).compare(1, 2)` answers `1`. The
asymmetry is real — a JDK comparator's `reversed()` works, a lambda's does not — but its cause is
that a function value has no methods at all, not that the proxy drops default methods. Closing it
would mean giving function values a Java member surface, which is a larger question than
conversion and is not what any of the three options below proposes.

**3. Whether a Java value is a `fun` is not answerable in the language.** There is no `type`
builtin, so a program cannot branch on "is this callable" and must instead assume.

---

## Options

Three options, stated with their costs. The recommendation is at the end.

### Option A — record what exists; convert in neither direction implicitly

Make the current behaviour official: a single-abstract-method interface value is a Java object
whose one abstract method may be called with `()`, and an Oqthara function may stand where a
functional interface is wanted. Add nothing to the grammar.

**Cost:** no code change; documentation and tests only. **What it forbids:** nothing that works
today would stop working, and no new program would start working. It leaves the `data`-field and
default-method gaps as they are.

**Risk:** the current behaviour is subtle — arity decides whether a call is a SAM call or a
"wrong number of arguments" — and pinning it in a spec makes the subtlety permanent rather than
resolving it.

### Option B — explicit adapter, opt-in and visible

Add one builtin pair, for example `as_java(iface, fn)` and `as_oq(java_value)`, so conversion
happens only where a program asks. Keep the implicit behaviour for compatibility or remove it.

**Cost:** a new builtin (keyword budget is 30, currently 22 — a builtin does not spend it),
specification, conformance, both backends. Removing the implicit path would be a **breaking
change** to programs that rely on `f(9)` today.

**What it buys:** conversion becomes visible in the source, which is the difference between
"this looks like a function" and "this is a Java object standing in for one". It also gives the
`data`-field and `default`-method gaps somewhere to be answered later without changing call
semantics again.

**Risk:** two ways to do the same thing, which the simplicity test rejects unless the implicit
path is removed — and removing it is the expensive part.

### Option C — implicit conversion in both directions, as today, made total

Finish the conversion: make a default method reachable from the Oqthara side, decide what a
`data` field holding a function does, and make a Java lambda indistinguishable from a `fun`.

**Cost:** the largest of the three. A proxy that answers default methods must implement them —
possible with `Proxy` plus a method-handle `invokeDefault`, but it changes what `equals`,
`hashCode` and `toString` mean for a callback, and those are already special-cased
(`JavaBridge.java:630`). Making a Java lambda indistinguishable from a `fun` means answering
"what is its arity" for arbitrary JDK objects, which the current `singleAbstractMethod` answers
only by scanning declared interfaces at a given arity and returning **null when two candidates
match** — a case that would have to be resolved by a rule the language does not have.

**What it buys:** no two-tier value model, which is the strongest argument for it. **Risk:**
`Proxy` cannot make a JDK object become an Oqthara closure; it can only make the call work. A
program could hold a value that behaves like a function in some positions and not others
regardless of how many methods the proxy answers.

---

## Questions the architecture already answers

These are not open; they are settled by existing decisions and should not be reopened by this
ADR.

**Arity.** Java decides it. `functionalArity` reads the single abstract method's parameter
count, and a call at another arity is refused with a message that names both counts
("'sort' wants a Comparator, which takes 2 arguments, but '<lambda>' takes 1"). A `Proxy` has one
`invoke` signature, so a variadic Oqthara function cannot be represented; a 3-argument lambda
handed to `sort` is refused by the same rule. There is no default-argument or partial-application
story and this ADR should not invent one.

**Overload resolution.** Java decides it, and Oqthara does not participate. Where two functional
interfaces at the same arity are both candidates, `abstractMethodsAt` returns `null` and the
conversion does not happen — a deliberate refusal rather than an arbitrary pick. Widening Java's
overload rules is out of scope.

**Null.** `nothing` becomes `null` crossing into Java and back: `Optional.of(1).map((x) ->
nothing)` yields `Optional.empty`. A `null` handed *to* a callback is not observed by this
brief's tests, because `Optional` short-circuits rather than invoking the lambda.

**Exception propagation.** An Oqthara error raised inside a callback surfaces at the Oqthara call
site with its original span, not as a Java `InvocationTargetException`; the program below reports
the `NumberFormatException` at `Integer.parseInt`, the line where the callback was called, not
the line where `map` was. This works today and must not regress.

```oq
use java.lang.Integer
use java.util.Optional
b = (x) -> Integer.parseInt(x)
print Optional.of("nope").map(b)
```

```
error: NumberFormatException: For input string: "nope"
  --> z1.oq:3:19
   3 | b = (x) -> Integer.parseInt(x)
```

**Threads.** The proxy runs the function on whichever thread Java calls it from — that is what
the `JavaBridge` comment says and what `CompletableFuture.supplyAsync(() -> 41 + 1).join()`
demonstrates (`42`). A callback that touches mutable Oqthara state from another thread is
therefore not serialised by the bridge. That is a concurrency question for the language, not for
this ADR, but a total-conversion option must not silently claim otherwise.

**Object identity.** Each crossing builds a new `Proxy`; whether the same Oqthara function
compares equal to itself after crossing is unmeasured here and is not decided by any option.

**Both backends.** Every measurement above was run through `oq.jar` (interpreter) and through a
compiled jar, which the probe harness runs on both. Any change must keep that parity — the
conformance suite already pins callback behaviour on both.

---

## Recommendation

**Option A, recorded as a decision rather than a deferral.**

Three reasons, in the order that decided it.

*It is the only option that changes nothing.* The behaviour described under "what exists today"
is shipped, tested and load-bearing for `sort`, `map`, `filter` and every Java method that takes
a functional interface. A decision that changes no program cannot break one.

*The two gaps worth closing are not Java gaps.* Calling a function held in a `data` field fails
for plain Oqthara functions, and a default method on a proxy is a stated limitation of `Proxy`.
Neither is fixed by choosing B or C, and both are cheaper to fix on their own terms if they turn
out to matter.

*The simplicity test rejects B.* Two ways to convert is exactly the shape the test excludes
unless the implicit path is deleted, and deleting it breaks working programs. C is the only
option that would genuinely unify the model, and its cost is a new rule for ambiguous arity plus
a changed meaning for `equals` on every callback — a large change to buy a consistency no program
has asked for.

**What is genuinely lost by choosing A**, stated so it is not hidden: an Oqthara program cannot
rely on a Java lambda's default methods, cannot branch on whether a value is callable, and cannot
assume that a function stored in a `data` field is callable through the field. Each is named
above with a reproduction, so a later reader can tell a limitation from a bug.

**This does not close the door.** Choosing A adds no refusal, so moving to B or C later is
additive for B and a semantic addition for C; no program written under A becomes invalid under
either.

---

## If the decision is B or C

The sequence, so the estimate is not left implicit. It is the same shape as the typed-fields
brief's:

1. **Grammar and spec.** One builtin (B) or one rule for ambiguous arity (C) in
   `docs/spec/language.md`, with the refusal cases written down before the accept cases.
2. **Interpreter.** `JavaBridge` change plus the call path in `Interpreter`, because the
   conversion has to be visible where a call is resolved, not only where an argument is
   converted.
3. **Compiler.** The same in `Codegen`, then `oq build` and a compiled run of each conformance
   program that touches the bridge — the fallback accounting in
   [`alpha-6-conformance-fallback-accounting.md`](../alpha-6-conformance-fallback-accounting.md)
   applies.
4. **Conformance.** One program per accepted shape and one per refused shape, both backends.
5. **Probes.** A probe in `docs/governance/alpha-5/java-bridge-probes/` for the new behaviour,
   with the expected lines recorded from a real run, plus the harness count updated in every
   document that quotes it.

Steps 1–5 are a milestone, not a task. Nothing in this brief authorises starting them.

---

## What this brief does not decide

- Whether the `data`-field call gap (`b.mapper(5)`) is fixed. It is a language question with a
  Java-shaped symptom.
- Whether a proxy should answer default methods. Named as a limitation, recommended out of scope.
- Whether a Java value can be tested for callability. There is no `type` builtin; adding one is a
  separate proposal.
- Anything about generics, which Alpha 7 §4 keeps as its own deliverable.
