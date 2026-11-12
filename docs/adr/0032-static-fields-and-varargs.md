# ADR-0032: Static fields and varargs at the Java boundary

- **Status:** Accepted
- **Date:** 2026-11-10
- **Related:** [ADR-0031](0031-hover.md)

## Context

The Alpha 4 requirement matrix listed §16 (Java interop) as `PARTIAL` because six behaviours
were unspecified: overload selection, boxing, varargs, callbacks, exception translation and
classloader boundaries. Probing each one against the built CLI settled five of them — overload
selection, boxing and exception translation already worked, and the classloader already reached
any class on the server's classpath — and found two that did not:

```
print Integer.MAX_VALUE
# error: cannot read field 'MAX_VALUE' on java Class

print String.format("%s-%s", "a", "b")
# error: Java class String has no static method 'format'
```

Both were real defects rather than unstated limits, and both were invisible to the test suite
because nothing had ever tried them. That is the argument for the probing approach recorded
here as much as it is for the two fixes.

## Decision

**D1 — A class receiver is read as a class.** `readField` used one path for every receiver:
`receiver.getClass().getField(name)` and `field.get(receiver)`. For a `Class<?>` receiver that
asks a `Class` object for a field of its own name space, which is why `Integer.MAX_VALUE` was
not found. When the receiver is a `Class<?>`, the lookup is now `type.getField(name)` and the
read is `field.get(null)`. A field that is not `static` is declined with `NOT_FOUND` rather
than read, because a class-level read of an instance field has no instance to give.

**D2 — The varargs tail is packed, not rejected.** Reflection reports `T...` as a trailing
`T[]`, so an exact arity check refuses every varargs call, including the ones with a non-empty
tail that the feature exists for. `tryConvert` gained a `varargs` flag; when it is set, the
arguments after the fixed prefix are converted against the array's component type and packed
into a fresh array with `java.lang.reflect.Array`. The three-argument form still delegates with
`false`, so every existing caller keeps its behaviour.

**D3 — Overload scoring knows about the tail.** `score` indexed `types.length` over `args`,
which reads past the end of the argument list for a varargs call with an empty tail —
`String.format("no arguments")` reached `IndexOutOfBoundsException` and was reported as an
internal error. Scoring now covers the fixed prefix and then the packed tail, and subtracts one
point from a varargs match so it never wins a tie against a fixed-arity overload that fits the
same arguments.

**D4 — The conversion table stays the contract.** Arguments are converted before a candidate is
scored, so `String.valueOf(5)` reaches the `int` overload rather than the `Object` one, and
`Integer.valueOf("5")` reaches the string one. Choosing an overload is a matter of fit, not of
declaration order.

**D5 — The callback gap had one cause, and D5's first recording of it was wrong.** D5 originally
recorded two independent failures with two different causes. That was incorrect, and the probe
that disproves it is short:

```
xs = [1, 2, 3]
xs.ensureCapacity(100)          # succeeds, prints nothing
Collections.sort(xs)            # succeeds, prints [1, 2, 3]
```

Both are list method calls and both reach Java reflection. Dispatch therefore does consult the
Oqthara method table first, but it does **not** skip reflection for a non-`Class<?>` receiver:
`Interpreter.invoke` falls through to `JavaBridge.callMethod` whenever `Methods.resolve` returns
null. The consequent failures were one cause seen twice — `tryConvert` had no rule turning an
Oqthara function into a Java functional interface — and the `type list has no method 'forEach'`
diagnostic was the bridge reporting `NOT_FOUND` after every overload had been rejected for that
single reason.

The repair lives in `JavaBridge`: a function argument that meets an interface with exactly one
abstract method becomes a `java.lang.reflect.Proxy` that calls back through the host that
created it. The host matters, because a closure must be resolved in the environment it captured
and a compiled closure is a different class from an interpreted one; both implement
`OqCallable`, so the proxy is backend-agnostic. Arguments arriving from Java are normalized, so
a callback over a `List<Integer>` is handed `long` and never an `Integer`, and a return value the
interface cannot hold is refused with the function and the wanted type named rather than being
passed as `null`.

Two limits are stated rather than implied. A function whose declared arity does not match the
interface's single abstract method is declined, and the resulting diagnostic names the interface
and both counts instead of claiming the method does not exist. A callback that stands where no
functional interface is wanted — `list.add(fn)` — is left alone, because the value already fits
and wrapping it would change what the program stored.

## Rejected alternatives

- **Widening `readField` to read any field on the class.** `ArrayList.size` would then resolve
  to something that does not exist on a class; declining it keeps the error honest.
- **Accepting `field.get(receiver)` for a static field and ignoring the instance.** It happens
  to work, because reflection ignores the instance for a static field, but it reads as if the
  class object were an instance and hides the bug that was just fixed.
- **Making every call varargs.** A fixed-arity call that happens to end in an array would be
  packed a second time.
- **Changing dispatch so Java is consulted first for every receiver.** `list.map(...)` would
  then be answered by whatever `ArrayList` happens to declare, which changes existing programs.
- **Special-casing `String.format`.** The defect was in the general arity check, and a special
  case would leave `String.join` and `Arrays.asList` broken.

## Consequences

- `Integer.MAX_VALUE`, `Math.PI` and any other static field are readable.
- `String.format`, `String.join`, `Arrays.asList` and every other varargs method work, with or
  without a tail.
- A varargs overload still loses a tie to an equal-scoring fixed one, so an added varargs
  method cannot silently capture existing calls.
- Callbacks are available in both directions, which they were not when this ADR was written. An
  Oqthara function passed to a Java method that wants a functional interface becomes a proxy
  (ADR-0032's own follow-up, in `JavaBridgeCallbackTest`), and a Java value whose class declares
  one abstract method is callable as a function in the other direction. This paragraph previously
  said both forms were unavailable with no workaround to offer; it is corrected here rather than
  left to misdescribe shipped behaviour.
- The two defects were found by probing, not by tests. The regression tests added with this
  change pin the behaviours, but they do not explain why nothing caught them before: the suite
  had no interop case that read a static field or called a varargs method.
