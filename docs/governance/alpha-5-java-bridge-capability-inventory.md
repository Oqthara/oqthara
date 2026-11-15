# Alpha 5 — Java Bridge Capability Inventory

**Baseline commit:** `db23f88f90dc338cba75ff1d2bd3abc98a154cb8` (`main`, "Prepare 0.1.0-alpha.5 (#230)")
**Audit date:** 2026-11-16
**Method:** every row below was executed against the CLI built from the baseline commit
(`./gradlew :oq-cli:standaloneJar`). No row is classified from source reading alone unless it is
explicitly marked as a static finding.
**Review mechanism:** AI review per ADR-0059 and `docs/governance/ai-review-policy.md`.
`Independent: no` — the reviewer is an AI agent (`deepseek-ai/deepseek-v4.1-flash-1017`), not a
human, and this document does not claim human review.

## Classification vocabulary

| Label | Meaning |
|---|---|
| **Complete** | Implemented, executed, and produces the documented result. |
| **Partial** | Implemented for some receivers, argument shapes or backends but not others. |
| **Missing** | Requested by the specification or implied by the architecture and absent. |
| **Incorrect** | Implemented and produces a result that contradicts the specification or the JVM contract. |
| **Unsafe** | Reachable behaviour with a security consequence that is not documented where a reader would look for it. |
| **Undocumented** | Behaviour is implemented and correct but the specification does not state it. |
| **Unverified** | Claimed by governance documentation but with no executable evidence found. |

An **intentional limitation** is a capability that is absent by an explicit, reasoned decision. It
is only accepted as intentional when the specification or an ADR states the decision and its
rationale. "It has not been implemented" is not an intentional limitation.

## Direction 1 — Oqthara calls Java: construction

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C1.1 | Construct with `ClassName(args)` | Complete | `ArrayList()` → `[]`; `LocalDate(2026, 1, 1)` per spec. |
| C1.2 | Constructor overload selection | Complete | `StringBuilder(16)` and `StringBuilder("x")` both resolve; `describeConstructors` lists candidates on failure. |
| C1.3 | Failed construction names the available arities | Complete | `ArrayList(1 to 3)` → `cannot create ArrayList with 1 argument` + `Available constructors: ArrayList(Collection); ArrayList(); ArrayList(int)`. Matches the spec character for character. |
| C1.4 | Enum constant reference (`DayOfWeek.MONDAY`) | Complete | Prints `MONDAY`; `d.plus(1)` → `TUESDAY`; `d == DayOfWeek.MONDAY` → `true`. |

## Direction 2 — Oqthara calls Java: instance methods

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C2.1 | Call an instance method on a returned Java object | Complete | `al.size()`, `al.isEmpty()`, `al.get(0)`, `al.contains(1)` all answer correctly. |
| C2.2 | Call a method declared only on a package-private class via a public supertype | Complete | `Comparator.naturalOrder().compare(1, 2)` → `-1`. `JavaBridge.accessible`/`publicDeclaration` implement this. |
| C2.3 | Instance methods on a `Class` value | **Complete, with a stated boundary** | Found as Partial: `String("x").getClass().getName()` reported `Java class String has no static method 'getName'`, because `Interpreter.java:741` and `Methods.java:64` routed `Class<?>` receivers to `callStatic` only. Now statics resolve first, then a 22-method self-description allowlist answers `getName` → `java.lang.String`, `getSimpleName` → `String`, `isInterface` → `false`. Reflection members (`getDeclaredMethod`, `getMethods`, …) are refused by design; see D2 below for the security reason and the residual limitation. |

## Direction 3 — Oqthara calls Java: static methods and fields

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C3.1 | Static method call | Complete | `Math.abs(-7)` → `7`, `Integer.toString(42)` → `42`. |
| C3.2 | Static field read | Complete | `Integer.MAX_VALUE` → `2147483647`; `Math.PI` → `3.141592653589793`; `Long.MIN_VALUE` → `-9223372036854775808`. |
| C3.3 | Instance field named on the class is refused | Complete | `Integer.size` → `cannot read field 'size' on java Class`. Matches the spec. |
| C3.4 | Instance field read on a returned object | Complete (public only) | `readField` uses `getField` (public members only) and refuses private ones: `String("abc").value` → `cannot read field 'value' on text`. |

## Direction 4 — Overload selection and conversion

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C4.1 | Overload selection by argument fit | Complete | `String.valueOf(1)`→`1`, `(true)`→`true`, `(1.5)`→`1.5`; `Math.abs(-5)`→`5` and `Math.abs(-5.5)`→`5.5` reach different overloads. |
| C4.2 | Primitive and wrapper conversion (`int`/`long`/`double`/`boolean`/`char`) | Complete | `ArrayList.add(1)` then `al.toString()` → `[1, 2]`; `Character.isDigit("5")` → `true`; `Character.toUpperCase("a")` → `A`. |
| C4.3 | `String` conversion | Complete | `String.valueOf` of every scalar; `m.get("k")` returns text usable as an Oqthara `text`. |
| C4.4 | `List`/`Map` conversion | Complete | Oqthara `list` is an `ArrayList`; `Collections.sort(xs, ...)` mutates the caller's list in place. |
| C4.5 | `Path`/`File` conversion | Complete | `Path.of("/tmp").getFileName()` → `tmp`; `getNameCount()` → `1`. |
| C4.6 | Enum parameter conversion | Complete | `EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)` → `{MONDAY, FRIDAY}`. |
| C4.7 | `range` cannot satisfy a `Collection` parameter | Complete — **intentional limitation** | `ArrayList(1 to 3)` refused by name with a materialisation hint. Spec states the rationale: a `range` is a computed interval, not a stored collection. |
| C4.8 | Exact Java numbers survive the boundary | Complete | `BigDecimal("0.1").add(BigDecimal("0.2"))` → `0.3`; wide `BigInteger` arithmetic keeps all digits. ADR-0045. |

## Direction 5 — Varargs

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C5.1 | Varargs with several trailing arguments | Complete | `String.format("%s-%s", "a", "b")` → `a-b`. |
| C5.2 | Varargs with exactly one argument | Complete | `String.join(",", ["a"])` → `a`. |
| C5.3 | Varargs with zero arguments | Complete | `String.join(",", [])` → empty line. |
| C5.4 | Varargs through an array-taking method (`Arrays.asList`) | Complete | `Arrays.asList("x", "y").size()` → `2`. |

## Direction 6 — Exceptions crossing the boundary

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C6.1 | A Java throwable becomes an Oqthara error with the Java class and message | Complete | `NumberFormatException: For input string: "not a number"`. |
| C6.2 | The translated error carries the call site span and excerpt | Complete | The refusal shows `--> p1.oq:...` and the caret under the call. |
| C6.3 | The error is catchable as an Oqthara error | Complete | `try print Integer.parseInt("zz") catch err print err.message end` → `caught: NumberFormatException: For input string: "zz"`, execution continues. |

## Direction 7 — Callbacks (Oqthara function into Java)

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C7.1 | An Oqthara `fun` becomes the declared functional interface | Complete | `xs.forEach(fun(x) print x end)` prints `3 1 2`. |
| C7.2 | A lambda becomes a `Comparator` for `Collections.sort` | Complete | `Collections.sort(xs, (a, b) -> a - b)` → `[1, 2, 3]`. |
| C7.3 | Arity mismatch names the interface and both counts | Complete | `'forEach' wants a Consumer, which takes 1 argument, but '<lambda>' takes 2`. Matches the spec. |
| C7.4 | A callback keeps its own captures | Complete | Spec states it; `JavaBridgeCallbackTest` covers it. |

## Direction 8 — What Java hands back (reverse direction)

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C8.1 | A returned Java object keeps its methods | Complete | `ArrayList` returned from a Java call supports `size`/`get`/`contains`/`isEmpty`. |
| C8.2 | A returned Java collection is iterable by Oqthara | Complete | `for x in al` over a Java `ArrayList` after `Collections.reverse` yields `3 2 1`. |
| C8.3 | A Java functional interface is callable as a function | Complete | `Comparator.naturalOrder()` answers both `c.compare(1, 2)` and `c(1, 2)` → `-1`. |
| C8.4 | Declared interfaces outrank inherited ones | Complete | `Comparator.naturalOrder()` is an enum inheriting `Comparable`; `c(1, 2)` reaches `compare`, not `compareTo`. ADR-0032. |
| C8.5 | A Java function object can be passed to an Oqthara higher-order **method** that accepts any callable | Complete | `xs.map(Function.identity())` → `[1, 2, 3]`; `xs.filter(Predicate.isEqual(2))` → `[1]`; `xs.any/find/find_index` all accept it. |
| C8.6 | A Java function object can be passed to `sort`, `sort_by`, `reduce` and `list_sort` | **Complete** | Found as Incorrect: all four refused a callable Java object even though the same object was callable as `c(a, b)`, because they coerced with `instanceof OqCallable` while `map`/`filter` passed the raw value to `host.call`. Now `JavaBridge.callableArity` answers the arity and all four accept a Java functional value whose arity fits. See D1 below. |

## Direction 9 — Method-resolution precedence

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C9.1 | An Oqthara builtin wins over a same-named Java method on a collection receiver | **Complete — deliberate, now documented** | `ArrayList().sort(cmp)` is answered by the Oqthara `sort`, and `ArrayList().remove("x")` reports `remove takes an index, not text`; neither reaches `List.sort`/`List.remove(Object)`. The precedence is deliberate (ADR-0032 rejected Java-first dispatch because `list.map(...)` would then be answered by whatever `ArrayList` declares) and is now stated in `docs/spec/language.md` under "Which method a name means", with the shadowed set enumerated. The diagnostic wording is unchanged — repinning it would break existing CLI tests for a documentation problem. See D3 below. |
| C9.2 | The same rule holds on both backends | Complete | `Methods.dispatch` consults the resolver before `interop`, mirroring the interpreter. |

## Direction 10 — Backends

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C10.1 | `use` imports a Java class under the interpreter | Complete | Every probe above ran under `oq run`. |
| C10.2 | `use` imports a Java class under the compiled backend | **Partial — documented intentional limitation** | `oq build` does **not** fail: it emits `codepath: interpreter launcher (variable 'Math' is used before assignment)` and the jar runs correctly (`7 / 2 / 1`). Compiled code cannot call Java directly. Documented in `docs/spec/backend.md:274` as boundary item 2, with the fallback stated. This is correct behaviour; the earlier audit assumption that `use` was hard-refused was wrong. |
| C10.3 | Method calls on Java values that arrive from a compiled call | Complete | `Methods` → `JavaBridge` reflective delegation, stated at `backend.md:276`. |
| C10.4 | Interpreter and compiled output agree on an interop program | Complete | `bc3.oq` printed `7 / 2 / 1` under both `oq run` and `java -jar bc3.jar`. |

## Direction 11 — Classpath

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C11.1 | A class on the JVM classpath is importable | Complete | A user class `Hello` in a separate jar: `use Hello` → `cannot find Java class 'Hello'` without `-cp`, and `hi world` with it. |
| C11.2 | A JDK class is importable by fully qualified name | Complete | `use dev.oqthara.core.Lexer` exercised in the Alpha 4 matrix. |

## Direction 12 — Security of the boundary

| ID | Capability | Class | Evidence |
|---|---|---|---|
| C12.1 | Only public members are reachable; no accessibility escalation | Complete | `setAccessible` appears nowhere in production sources. `Class.getDeclaredMethod` is unreachable (via C2.3). Private fields are refused. |
| C12.2 | Java is reachable without restriction, including process execution | **Unsafe by design — now documented at the interop boundary** | `ProcessBuilder(["sh", "-c", "id"]).start().waitFor()` → `0`. `System.getenv("HOME")` → the real home. This is the same authority `run_command` and `env()` already grant and the project's "Oqthara is **not** a sandbox" statement (`language.md:1906`) already concedes, so it is not a new vulnerability or a regression. The interop section now says so directly in "What the boundary does not restrict", naming both doors. See D4 below. |

## Confirmed defects

### D1 — A callable Java object is refused at `sort`, `sort_by`, `reduce` and `list_sort`

**Severity: functional correctness.** The specification states the reverse direction is
"complete for functional interfaces" and that a Java function object "is callable as a function".
It is — `c(1, 2)` answers `-1`. But the same value is refused when passed to an Oqthara builtin
that needs a callback:

```text
'sort' needs a function as argument 1   (help: It got java NaturalOrderComparator. Write sort(fn).)
'sort_by' needs a key function
'reduce' needs a function as argument 1
'list_sort' needs a function as argument 2
```

while `map`, `filter`, `each`, `any`, `all`, `find` and `find_index` all accept it.

**Cause.** `map`/`filter` pass the raw argument to `host.call`, and both hosts resolve a Java
functional value (`Interpreter.invoke` line 719 via `JavaBridge.callFunctional`;
`Methods` via `JavaBridge.callFunctional`). `sort` (`MethodResolverImpl.java:217`), `reduce`
(`:195`), `sort_by` (`Stdlib.java:250`) and `list_sort` (`Stdlib.java:2476`) instead coerce with
`instanceof OqCallable` through `callableArgument` (`MethodResolverImpl.java:89`), which no Java
object satisfies. The inconsistency is the defect, not either behaviour alone.

**Reproduction:** `docs/governance/alpha-5/java-bridge-probes/d1_*.oq`.

**Status: fixed.** `JavaBridge.callableArity(Object)` answers a value's functional arity, and
`MethodResolverImpl.callableArgument` plus the shared `Stdlib.keyFunction` consult it, so a Java
functional value is accepted on the same terms as an Oqthara function. The arity is checked
against what the position needs — one for a key or a comparator, two for a fold — so a
`Comparator` handed to `sort_by` is still refused, now by count rather than by kind:

```text
sort([3, 1, 2], Comparator.naturalOrder())        -> [1, 2, 3]
sort_by([[3], [1]], Function.identity())          -> [[1], [3]]
list_sort([3, 1, 2], Function.identity())         -> [1, 2, 3]
reduce([1, 2], Function.identity())               -> 2
sort_by(items, Comparator.naturalOrder())          -> error: 'sort_by' needs a key function
                                                             taking 1 argument as argument 2
```

One trap had to be fixed alongside it: `OqCallable.VARIADIC` is `-1`, the same sentinel the bridge
returns for "not callable", so code that tested the sentinel before deciding callability let a
plain `3` through. Both call sites now decide callability first. Regression tests:
`ListSortTest.aJavaFunctionalValueIsAcceptedAsAKey` and
`ListSortTest.aTwoArgumentComparatorIsRefusedAsAKeyWithItsArityNamed`.

### D2 — Instance methods on a `Class` value are unreachable

**Severity: functional correctness, documented contract.** The specification says a value Java
hands back is "an ordinary Java object, and its methods are called the same way as any other
receiver's". A `Class` value contradicts that: `String("x").getClass().getName()` reports
`Java class String has no static method 'getName'`, which is both wrong (it is an instance method)
and misleading (the receiver *is* an instance). `Class<?>` receivers are excluded from instance
dispatch at `Interpreter.java:741` and `Methods.java:64` and sent to `callStatic`.

This is symmetric across backends and `getClass()` is rarely needed, so it is low impact — but the
error message names a method the caller never wrote, so it is a correctness defect in the message
and a gap in the documented rule.

**Status: fixed as a bounded capability.** A `Class` receiver now answers: statics first (so
`String.valueOf(7)` and `Arrays.asList(1, 2, 3)` resolve exactly as before), then the
self-description methods a class object should answer about itself —
`getName`, `getSimpleName`, `getCanonicalName`, `getTypeName`, `toString`, `equals`, `hashCode`,
`isInterface`, `isEnum`, `isArray`, `isPrimitive`, `isAnnotation`, `isSynthetic`, `isRecord`,
`isAssignableFrom`, `isInstance`, `getPackageName`, `getModifiers`, `getSuperclass`, `getInterfaces`,
`getComponentType`, `getClassLoader`. Reflection members are deliberately excluded.

```text
String("x").getClass().getName()         -> java.lang.String
String("x").getClass().getSimpleName()   -> String
String("x").getClass().isInterface()     -> false
String("x").getClass().getDeclaredMethod -> error: 'getDeclaredMethod' is reflection,
                                                    which is not exposed.
```

The narrowing limits what a *name* on a class object can reach; it is not a sandbox. Before
writing it up I claimed "no other route in the bridge allows it", and that claim was wrong. It is
corrected here rather than quietly dropped, because the difference matters for how much assurance
the allowlist buys.

Reflection is not **exposed by name**: `getClass()` cannot reach `getDeclaredMethod`. It is still
**reachable**. `MethodHandles.lookup()` resolves with lookup class `dev.oqthara.core.JavaBridge`,
which is on the classpath, so a program can find `Class.getDeclaredField` as a virtual method,
invoke it, call `setAccessible(true)`, and read private state. Reproduced against this build:

```text
lookup = MethodHandles.lookup()             -> dev.oqthara.core.JavaBridge
handle = lookup.findVirtual(Class, "getDeclaredField",
                            MethodType.methodType(Field, String))
handle.invokeWithArguments(String, "value") -> private java.lang.String java.lang.String.value
f.setAccessible(true); f.get(nothing)       -> s3cr3t
```

The JVM blocks that for JDK internals (`InaccessibleObjectException`) but **permits** it for
application classes on the classpath. Baseline `db23f88` behaves identically, so this is
pre-existing and not a regression from D2. The accurate statement is that the allowlist reduces
what is *accidentally* reachable and keeps `Class` from becoming a general-purpose reflective
surface; the trust boundary remains "running Oqthara code is running code with the host's
privileges", as the spec says. Regression tests: `JavaBridgeClassObjectTest` (8 tests).

**Residual limitation, stated:** reading a class's declared members is not available. A program
that needs it must do the work in Java. This is recorded in the acceptance criteria as a boundary
of A5.2, not deferred to Alpha 6.

### D3 — Builtin shadowing of Java methods is undocumented and misreported

**Severity: documentation / diagnostic.** `ArrayList().sort(...)` and `ArrayList().remove("x")`
are intercepted by the Oqthara collection methods before Java is consulted. The precedence itself
is a reasonable design (Oqthara semantics are identical on both backends), but:

1. Neither `docs/spec/language.md` nor an ADR states that an Oqthara method wins.
2. The diagnostic says the *method* is absent (`'sort' needs a function`, `remove takes an index`)
   when the truth is that a same-named Java method was shadowed. A reader is told their Java call
   is malformed when it is not.

**Status: documented.** `docs/spec/language.md` now has a subsection "Which method a name means"
stating that an Oqthara method on a built-in family wins, that Java is reached for everything else,
and why (ADR-0032 rejected Java-first dispatch because `list.map(...)` would then be answered by
whatever `ArrayList` happens to declare). The shadowed set is exactly
`add, contains, get, remove, set, size, sort, subList`, recomputed from the `case` labels in
`MethodResolverImpl.listMethod` rather than from memory. `clear` is *not* in it, so `xs.clear()`
reaches `java.util.List.clear` and mutates in place. The diagnostic wording was left alone: changing
it would break pinned messages in existing CLI tests for a documentation problem.

### D4 — Java interop is a second, undocumented route to unrestricted process execution

**Severity: documentation.** `ProcessBuilder` reaches arbitrary execution and `System.getenv`
reads the environment, both through `use`. The project's trust statement (`language.md:1906`)
already says Oqthara is not a sandbox and that a program "can start the processes the user can
start", so this is **not a new vulnerability and not a regression** — the boundary is unchanged
and no privilege is escalated. The defect is that the interop section does not say it inherits
that trust statement, which is exactly the kind of omission that makes a security claim
inaccurate at the place a reader checks it.

**Status: documented.** The interop section now has "What the boundary does not restrict", which
states that `use` inherits the "not a sandbox" trust statement verbatim, names `ProcessBuilder` and
`System.getenv` as the concrete doors, and records the reflection narrowing from D2. No behaviour
changed, because none was wrong.

## Intentional limitations (verified, not defects)

| Limitation | Evidence it is intentional |
|---|---|
| `range` does not cross as a `Collection` | Spec states the rationale and gives the refusal and the materialisation recipe. |
| Compiled code cannot call Java directly (`use` refused by name) | `backend.md:274` lists it as boundary item 2 with the interpreter fallback, and `oq build` degrades to the interpreter launcher instead of failing. |
| Only public members are reachable by name | **Amended after review; the unqualified form was false.** `setAccessible` is absent from the bridge's own call paths, so a member is not reachable *through the bridge*. It is reachable through `MethodHandles`, as the corrected D2 section shows, and `setAccessible` then works on application classes. The accurate statement is that the bridge exposes public members only. |

## Findings the adversarial review raised against this document

The review that gated this work read the committed diff rather than this narrative, and raised
six findings. Four changed the tree; two are recorded here because the resolution is "this is the
contract, and the audit had described it as a defect" or "this is broader than the audit said".
Both directions are recorded, because an audit that only records the findings it fixed is
indistinguishable from one that fixed everything.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| 1 | high | `language.md` listed a shadowed-method set that was wrong: it named `clear` and `set`, and omitted `size` and `subList`. | **Fixed.** Recomputed from the `case` labels in `listMethod` — the set is `add, contains, get, remove, set, size, sort, subList`. `clear` reaches Java. `set` resolves to the language's `to_set`. |
| 2 | high | `callableArgument` had no unit test, so mutating the callback arity to a constant left all 1652 tests green. | **Fixed.** `CallableArgumentTest`, 8 tests, taking the suite to 1660. Reported: the mutation now fails 3 of them. |
| 3 | medium | `run.py`'s `known_defect` marker turned any output change into a silent pass. | **Fixed.** A defect now records the exact output it produced, and either direction of change fails the run. Verified by injecting both a moving and a fixed defect. |
| 4 | medium | `getInterfaces` returns a raw `Class[]`, which the review described as unusable. | **Partly correct.** A `Class[]` cannot be indexed or `length`-ed, because the bridge returns Java arrays as opaque objects — but that is true of every array-returning method (`toArray()` behaves the same), so it is a property of the boundary, not of this entry. The array can be passed back into Java (`Arrays.asList(faces)` works), so the entry is not inert. Documented below; no change to the allowlist. |
| 5 | medium | "[reflection] which no other part of the bridge allows" was false. | **Fixed.** Reproduced with `MethodHandles.lookup()` + `findVirtual` + `setAccessible`, which reads a private field. Reworded here, in `JavaBridge.java`, and in `language.md`. Pre-existing at the baseline, so not a regression. |
| 6 | low | `list_sort(xs, nothing)` plain-sorts while `sort_by(xs, nothing)` and `xs.sort(nothing)` refuse. | **Not a defect.** ADR-0060 decision 1 states that "omitting it, or passing `nothing`, sorts by the values themselves". Changing it would break a recorded contract, so the asymmetry is documented and a comment in `listSort` points at the ADR. |

### Java arrays arriving as opaque values

An array returned by Java — from `getInterfaces`, `toArray`, or any method with an array return
type — is a Java object with no indexing or length in Oqthara. It is not converted to a list. It
can be passed back to a Java method that accepts one, and it prints as its `[L...;@hash` form,
which is not useful output. This is a real limitation of the boundary and it is stated as one;
the audit's entry for `getInterfaces` is correct that the value is awkward, and the review was
right to flag it even though the cause is general rather than specific to that entry.

**Not fixed in this phase.** Converting array returns to lists would change what every
array-returning method returns, including `toArray`, which the spec's varargs section documents
as accepted input to Java calls. That is a compatibility decision, not a bug fix, and it belongs
in a reviewed change of its own.

## Claims from the Alpha 4 matrix that this audit reclassifies

| Matrix claim (§16) | Audit result |
|---|---|
| (1) Overload selection | **Confirmed** — C4.1. |
| (2) Boxing | **Confirmed** — C4.2. |
| (3) Varargs including an empty tail | **Confirmed** — C5.1–C5.4. |
| (4) Exception translation with a span | **Confirmed** — C6.1–C6.3. |
| (5) Static fields, instance field on a class refused | **Confirmed** — C3.2–C3.3. |
| (6) Classloader reaches the classpath | **Confirmed** — C11.1–C11.2. |
| (7) Callbacks on both backends | **Confirmed** — C7.1–C7.4. |
| (8) Returned values keep their methods | **Confirmed for objects.** Found contradicted for `Class` values (C2.3 failed, D2); fixed by a static-first fallback with a self-description allowlist, so `getClass().getName()` now answers `java.lang.String`. |
| (9) Exact Java numbers | **Confirmed** — C4.8. |
| (10) Reverse callability | **Confirmed.** Found contradicted for higher-order builtins that coerced to `OqCallable` (C8.6 failed, D1); fixed, and all four now accept a Java functional value whose arity fits. The matrix's claim that `map` refuses a Java function is itself wrong: `map` always accepted it (C8.5). |

No row is left as **Unverified** for the ten §16 behaviours: each was executed.

## Alpha 5 acceptance criteria

Alpha 5 is **COMPLETE** when all of the following hold, each with executable evidence recorded in
the completion report:

| # | Criterion | Measurement |
|---|---|---|
| A5.1 | D1 is fixed: a callable Java object is accepted wherever an Oqthara callable with a compatible arity is accepted | The four refusing builtins accept `Comparator.naturalOrder()` / `Function.identity()` and produce the result the equivalent lambda produces, and a wrong-arity value is refused by count. Probes `d1_sort`, `d1_sort_by`, `d1_reduce`, `d1_list_sort`, `d1_key_arity`; regression tests `ListSortTest.aJavaFunctionalValueIsAcceptedAsAKey` and `aTwoArgumentComparatorIsRefusedAsAKeyWithItsArityNamed`. |
| A5.2 | D2 is fixed: `Class` values answer their self-description methods, and reflection is refused by name | `String("x").getClass().getName()` → `java.lang.String`; `getSimpleName()` → `String`; `getDeclaredMethod` refused with a help line saying it is not exposed. Statics are unaffected. Probes `d2_class_instance`, `d2_reflection_refused`; tests in `JavaBridgeClassObjectTest`. **Boundary:** reading declared members is not available, and the compiled backend inherits it through `Methods.callOnClass`, which is exercised where `use` compiles and otherwise through the documented interpreter fallback. |
| A5.3 | D3 is documented: the precedence rule is stated in the specification | A subsection "Which method a name means" in the interop section states that an Oqthara method wins, cites ADR-0032 for why, and enumerates the shadowed set (`add, contains, get, remove, set, size, sort, subList`, with `clear` reaching Java). Diagnostic wording is deliberately unchanged: repinning it would break existing CLI tests for a documentation problem. |
| A5.4 | D4 is documented: the interop section states it inherits the "not a sandbox" trust statement | A subsection "What the boundary does not restrict" names `ProcessBuilder` and `System.getenv`, references the existing trust statement, and records the reflection narrowing from A5.2. No behaviour change. |
| A5.5 | Every example in the interop section is executed and produces the documented output | A probe directory committed under `docs/governance/alpha-5/` with a runner script; `make`-free, plain `python3 `. |
| A5.6 | No public behaviour regresses | `./gradlew build` green with `oq-core` coverage ≥ 80%; `python3 -m unittest discover -s scripts/tests` OK; `python3 scripts/check-docs-sync.py` exit 0. |
| A5.7 | Interpreter and compiled backends agree on every probe | `run.py --both` compiles each probe with `oq build` in a scratch copy, runs the jar, and requires its output to equal the interpreter's line for line: `ok: 18 probes produce identical output on both backends`. Compared as text, so a backend that accepts what the interpreter refuses also fails. Verified in both directions by injecting a one-sided difference, which the check reported for all 18. |
| A5.8 | AI review is recorded with findings and resolutions | A review record under `docs/governance/reviews/` that passes `scripts/check-ai-review.py`, with `Independent: no` and the model named. |

## Alpha 6 acceptance criteria (defined, not started)

Alpha 6 is scoped to what Alpha 5 deliberately leaves open. It is **not** a licence to widen
scope at will; each item is a capability this inventory classified as absent or partial.

| # | Criterion | Measurement |
|---|---|---|
| A6.1 | Compiled code calls Java directly, removing boundary item 2 in `backend.md` | A program with `use java.lang.Math` builds with `codepath: compiled bytecode` and prints identical output to `oq run`. Requires native lowering of `use`, static calls and the argument conversions — the reason it is a phase of its own. |
| A6.2 | `Class` reflection is available to Oqthara programs | **Not inherited from Alpha 5** — Alpha 5 fixed self-description and deliberately refuses reflection. This row now means the opposite of its original wording: it would require a reviewed capability decision and, if granted, a sandboxing story, because `setAccessible` on application classes is permitted by the JVM. Alpha 6 may also decide the answer is permanently no, in which case this row is retired. |
| A6.3 | Compiled code answers `Class` self-description without the interpreter fallback | Alpha 5 mirrors the behaviour in `Methods.callOnClass` so the two agree by construction, but a jar whose `use` forces the interpreter fallback does not exercise it end to end. A6.1 is the prerequisite. |
| A6.4 | Probe harness runs in CI | `docs/governance/alpha-5/java-bridge-probes/` executes as a required check, so a regression in any inventory row fails the build. |

## What this audit does not claim

- It does not claim the bridge is complete in the sense of "every JDK class works". It claims
  every **documented** behaviour was executed, and the rows above are the ones with evidence.
- It does not claim independence of review; `Independent: no`.
- It does not treat a passing suite as proof a row holds. Each row cites the executed output.
- It does not claim the `Class` boundary is a defect. Reading declared members is refused on
  purpose; the security rationale and the JVM counter-example are in D2.
- It does not claim the compiled backend exercises `Class` dispatch end to end. `use` in a
  compiled program falls back to the interpreter, so `Methods.callOnClass` is verified by
  construction and by its test, not by a probe. A6.3 owns closing that.
