# ADR-0070 — Java functional values stay Java objects

**Status:** Accepted
**Date:** 2026-11-19
**Related:** [ADR-0032](0032-static-fields-and-varargs.md) (static fields, varargs and the
functional-interface conversion),
[ADR-0065](0065-reflection-stays-unexposed-by-name.md) (reflection stays unexposed),
R-014 in [`alpha-4-risk-register.md`](../governance/alpha-4-risk-register.md),
[`java-function-conversion-decision-brief.md`](../governance/remediation/java-function-conversion-decision-brief.md),
`JavaBridge.singleAbstractMethod`, `JavaBridge.callFunctional`, `JavaBridge.callableArity`

## Context

The host boundary converts in one direction without help: an Oqthara `fun` stands wherever Java
wants a functional interface, because `JavaBridge` wraps it in a `java.lang.reflect.Proxy` that
calls back through the host that created it — that is ADR-0032's decision, and this one does not
reopen it. The other direction is partial. A Java method may return a functional-interface object,
and that object's abstract method is callable — `cmp(1, 2)` answers, `map(xs, Comparator.naturalOrder())` works, and a value can be composed and handed back
to Java. What the value is *not* is an Oqthara function: it cannot be stored in a `data` field and
called through the field, it cannot be branched on as callable, and a default method on the
returned object's interface is not reachable.

R-014 has been Open since Alpha 4 and was, for most of that time, mis-described. It was filed as a
conversion question — should a Java functional value *become* a `fun` — when the measurements show
two separate things: a shipped, tested, load-bearing conversion into Java, and two narrower gaps
that no conversion rule fixes. The brief that precedes this ADR separates them and costs three
options. This ADR takes one.

## Decision

### 1. A functional-interface value returned from Java stays an ordinary Java object

No implicit adapter, in either direction. A value whose interface has one abstract method at the
arity being called is callable with `()`; in every other position it is the Java object it is.
"Callable" and "is a `fun`" remain different predicates, and the difference is now a decision
rather than an unfinished conversion.

### 2. Arity is the dispatch rule, and it is the existing rule

`JavaBridge.singleAbstractMethod` scans the value's declared interfaces for exactly one abstract
method at the requested arity and answers `null` when two candidates match. A `null` answer is a
"wrong number of arguments" refusal that names the arity. This ADR records that rule as the
contract rather than changing it: a value can be callable at one arity and not another, and the
error says which.

### 3. The two remaining gaps are named, reproduced, and out of scope here

- **A function in a `data` field must be called as `b.mapper()(5)`, because the call spelling
  `b.mapper(...)` reads the field through a zero-argument accessor.** `b.mapper` on its own is a
  genuine field read (`Interpreter.java:789`) and answers `<function <lambda>>`; the *call* spelling
  does not read the field directly. `MethodResolverImpl.java:53` synthesises a zero-arity
  `NativeFunction` for any called name that matches a field, so `b.mapper(5)` passes one argument to
  a function that takes none and reports "mapper() expects 0 arguments, got 1". `b.mapper()(5)`
  answers `10`, and a non-function field behaves identically. Nothing is broken and nothing is
  converted: this is the accessor convention showing through, and this ADR records it because the
  register previously described it as a conversion bug. Filed as
  [#269](https://github.com/Oqthara/oqthara/issues/269) as a documentation question rather than a
  defect, since the behaviour is correct and the surprise is the spelling.
- **An Oqthara function value has no member surface at all.** `cmp.reversed()` on a lambda is
  refused with "type function <lambda> has no method 'reversed'", and so is `cmp.compare(1, 2)` —
  the refusal is Oqthara's own member resolution, not `java.lang.reflect.Proxy`. The proxy is never
  reached, because the value on the Oqthara side is a function, not a Java object. Java code that
  *does* reach the proxy invokes default methods normally: `Collections.reverseOrder(cmp)` answers
  `1` for `rev.compare(1, 2)`. So the asymmetry is real but its cause is the value model, not
  `Proxy`, and closing it would mean giving function values a Java member surface — a different and
  much larger question than conversion.

Both are recorded here because R-014 named both and described the mechanism of each incorrectly.
Neither is a reason to add a conversion rule, because neither is a conversion.

### 4. Nothing about the access boundary moves

No new reflection surface, no new reachability rule, no export widening. The value that comes back
from Java is used through the same method resolution any other Java object uses, and ADR-0065's
"reflection stays unexposed by name" is untouched.

## Alternatives considered

| Option | What it buys | Why not |
| --- | --- | --- |
| **B — an explicit `as_oq`/`as_java` adapter** | conversion becomes visible in source; two-ways-to-do-it unless the implicit path is deleted, and deleting it is a breaking change | the simplicity test rejects two spellings of one operation, and the implicit direction-into-Java path is used by `sort`, `map`, `filter` and every Java method taking a functional interface |
| **C — implicit conversion in both directions, made total** | one value model, no two-tier callability | needs an ambiguity rule for arity that the language does not have, changes `equals`/`hashCode`/`toString` on every callback, and still cannot make a JDK object *be* an Oqthara closure — only behave like one in some positions |

Option A is the only one that changes no program, which is the whole of its argument.

## Consequences

- **The `data`-field defect becomes a language bug rather than a bridge question**, which is where
  it belongs and where it can be fixed without touching interop.
- **A program cannot assume a Java value is callable.** It can test by calling, and the refusal
  names the arity. Pinning the rule in `docs/spec/language.md` makes the subtlety permanent, which
  is the accepted cost of not adding a second conversion path.
- **Moving to B or C later stays possible.** A adds no refusal, so B is additive and C is a
  semantic addition; no program written under A becomes invalid under either.
- **Interpreter and compiled parity is unchanged and already gated.** Both hosts resolve through
  `JavaBridge.callFunctional`, and `run.py --both` compares every probe's output line for line
  across the two backends.
- R-014 moves from `Open` to `Accepted`, with this ADR as the decision it requires. Its two named
  gaps are what the "explicitly accepted" limitation consists of, not a claim that they are gone.

## What this ADR does not claim

That a Java lambda is indistinguishable from a `fun` — the decision is that it does not have to be.
That the `data`-field defect is acceptable — it is a real bug, named here so it is not mistaken for
a conversion question, and to be fixed on its own terms. That every JDK class works: the bridge
surface is what is documented and verified, and ADR-0065's boundary is intact.
