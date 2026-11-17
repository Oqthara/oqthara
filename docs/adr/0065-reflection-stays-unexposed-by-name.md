# ADR-0065 — Reflection stays unexposed by name

**Status:** Accepted
**Date:** 2026-11-16
**Related:** ADR-0027 (classes and contracts, which first narrowed `Class`),
[`language.md`](../spec/language.md) §What the boundary does not restrict,
[`alpha-5-java-bridge-capability-inventory.md`](../governance/alpha-5-java-bridge-capability-inventory.md)
D2 and A6.2, [`alpha-6-requirement-matrix.md`](../governance/alpha-6-requirement-matrix.md) A6.2,
`JavaBridge.CLASS_SELF_DESCRIPTION`, `JavaBridge.CLASS_REFLECTION`

## Context

Alpha 5 made a class object answer questions about itself — `getName`, `getSimpleName`,
`isInterface`, `getSuperclass` — and deliberately refused lookup of its declared members:
`getDeclaredMethod`, `getMethod`, `getField` and the other entry points return a help line
saying they are reflection and are not exposed.

Alpha 6's item A6.2 asked the open question. Its oldest wording read as a gap to close. Its
current wording reads the opposite way: closing it would require a reviewed capability decision
and, if granted, a sandboxing story, because `setAccessible` on an application class is permitted
by the JVM. Alpha 6 was allowed to answer permanently no and retire the row.

Two facts decided it.

**The refusal is a narrowing of what a name reaches, not a sandbox.** The spec already says so,
and it is worth testing rather than repeating. `MethodHandles` is an ordinary class, so `use`
reaches it; `MethodHandles.lookup()` resolves against the bridge's own class, which is on the
classpath, not against JDK internals. From there a program can reach an entry point the allowlist
refuses *by name* and invoke it:

```
$ cat d2_reflection_reachable.oq
use java.lang.Class
use java.lang.invoke.MethodHandles
use java.lang.invoke.MethodType
use java.lang.String
c = String("x").getClass()
kind = MethodType.methodType(Class)
handle = MethodHandles.lookup().findVirtual(Class, "getEnclosingClass", kind)
print handle.invokeWithArguments(c)

$ java -jar oq.jar run d2_reflection_reachable.oq
nothing
```

`getEnclosingClass` is in `CLASS_REFLECTION`, so writing `c.getEnclosingClass()` fails. The
probe deliberately names a refused entry point rather than a friendly member: reaching something
the allowlist already permits would demonstrate only that `findVirtual` works, which is not the
claim. `nothing` is the answer here because a JDK class declared at the top level has no enclosing
class, and it is the same answer the refused spelling would give. So granting the allowlist entry
would not add a capability to the language. It would add a shorter spelling to a capability the
language already has.

The route has rough edges, and they are worth stating so the record is not read as "reflection is
as easy as any other call". `handle.invoke(...)` is refused — `MethodHandle.invoke` is
signature-polymorphic, so the bridge resolves it as an ordinary method and reports `cannot change
an unchangeable collection: MethodHandle.invoke cannot be invoked reflectively` — and
`invokeWithArguments` is what works. `MethodHandles.privateLookupIn` on a JDK class fails with
`module java.base does not open java.util to unnamed module`, which is the module system doing its
job. None of that reopens the decision: the entry points the allowlist names are reachable and
invocable, which is the whole claim, and the awkwardness of the route argues against granting a
convenient short spelling rather than for it.

**The JVM cannot supply the sandbox that granting it would imply.** `setAccessible` succeeds for
application classes and is blocked only for JDK internals. An in-process sandbox strong enough to
make a reflective surface safe does not exist in this JVM; the real boundary is the operating
system restriction on the process that hosts the program, which is what the spec's trust
statement already says.

## Decision

Reflection stays unexposed by name. `Class` remains a self-description surface, and the member
lookup entry points stay refused with the help line that names them as reflection.

A6.2 is retired rather than deferred. It is not a gap waiting for an implementer, and a future
change that proposes to open it should have to overturn this record and supply a sandbox the JVM
does not currently offer, not merely notice that the row is still open.

The wording of the refusal carries the decision, so a reader does not have to infer it:

```
$ java -jar oq.jar run d2_reflection_refused.oq
help: 'getDeclaredMethod' is reflection, which is not exposed
```

## Consequences

- A program that wants reflection has it, through `MethodHandles`, and pays for it in lines. That
  is the intended shape: reachable deliberately, not by accident, and not by a one-word spelling
  on every class object.
- The claim that reflection remains reachable is now executed evidence rather than a comment.
  `d2_reflection_reachable.oq` runs on both backends and prints the same line, so the harness
  compares the interpreter against the compiler on it like any other probe.
- The spec's boundary section and this record cannot drift apart silently: the probe fails if
  `MethodHandles` stops resolving, and `d2_reflection_refused.oq` fails if the refusal stops
  naming reflection.
- No syntax, keyword, or backend change follows from this record. The Alpha 6 keyword count is
  unaffected.
