# Alpha 6 — conformance fallback accounting

**Measured:** 2026-11-21, against `oq-cli/build/libs/oq.jar` built from this branch.
**Method:** for every executable case in `conformance/`, `java -jar oq-cli/build/libs/oq.jar
build <case>.oq` was run and its `codepath:` line recorded. A case is a fallback when that line
says `interpreter launcher` instead of `compiled bytecode`. The count is then cross-checked
against `python3 scripts/verify-conformance.py`.
**Review mechanism:** AI review per ADR-0059 and `docs/governance/ai-review-policy.md`.
`Independent: no` — the reviewer is an AI agent (`deepseek-ai/deepseek-v4.1-flash-1017`), not a
human, and this document does not claim human review.

## 1. The measurement

```
$ cd conformance && for f in *.oq; do b=${f%.oq}; [ -f "$b.err" ] && continue; \
    java -jar ../oq-cli/build/libs/oq.jar build "$f" 2>&1 | grep '^codepath:'; done
checked=223
```

`python3 scripts/verify-conformance.py` on the same jar:

```
ok: 214 compiled, 9 fallback (223 of 223 executable), 0 divergences across 256 programs
```

223 executable cases, 214 compile to bytecode, 9 fall back to the interpreter. Zero
divergences: every one of the 223 produces byte-identical output on both backends. The nine
fallbacks are counted individually below. Nothing is reclassified as a pass; a fallback is a
program the bytecode backend does not accept, and the fallback is what makes it correct.

Investigating the nine changed the split. Three of the four root causes were confirmed
intentional. One was **not**: `map`, `filter` and `sort_by` had been declared host-dependent on
a premise that the code contradicts, and moving them off that list closed a gap rather than
widening a tolerance. That correction is recorded in §3(c) and in the code.

## 2. Every fallback, individually

Each reason below is the exact text the build printed, not a paraphrase.

| # | Case | Reason as printed | Root cause |
|---|---|---|---|
| 1 | `019-closures` | `a lambda that captures a variable which changes afterwards is not compiled` | (a) capture by reference |
| 2 | `067-fib-memo` | `'memo' is read from an enclosing scope; only functions that use their own parameters and locals are compiled` | (a) capture by reference |
| 3 | `087-nested-function-scope` | `'x' is read from an enclosing scope; only functions that use their own parameters and locals are compiled` | (a) capture by reference |
| 4 | `138-spawn-await` | `call to 'spawn' with 1 argument(s) is not compiled yet` | (c) callable-taking builtin |
| 5 | `140-spawn-error` | `call to 'spawn' with 1 argument(s) is not compiled yet` | (c) callable-taking builtin |
| 6 | `141-done-flag` | `call to 'spawn' with 1 argument(s) is not compiled yet` | (c) callable-taking builtin |
| 7 | `139-par-map` | `call to 'par_map' with 2 argument(s) is not compiled yet` | (c) callable-taking builtin |
| 8 | `209-relative-read` | `call to 'read' with 1 argument(s) is not compiled yet` | (b) host-dependent builtin |
| 9 | `228-classes-and-contracts` | `contract 'Shape': contracts are not compiled yet` | (d) contracts |

Four root causes, each examined in §3.

## 3. The four root causes

### (a) Capture by reference — 3 cases

A compiled lambda becomes a static method plus a `runtime.CompiledFunction` value, so it can
read its own parameters and locals but not an enclosing frame. The interpreter reads and
**writes** an enclosing binding at call time. `019-closures` is the case that distinguishes the
two, and it is not hypothetical — it is a mutable counter:

```oq
fun make_counter()
  count = 0
  return fun()
    count = count + 1
    return count
  end
end
```

Executed, the interpreter prints `1` then `2` from two calls to the same `c`: both calls share
one `count`, and the second sees the first call's write. A closure that copied `count` at
creation time would print `1` twice. That is a silently wrong answer, so the refusal is
correct and the reason names the variable:

```
codepath: interpreter launcher (a lambda that captures a variable which changes afterwards is not compiled)
```

Fixing it needs a cell — a heap box shared by the closure and the enclosing frame — which is a
real feature with its own design work, not a flag flip. **Verdict: intentional, justified, not
a defect.**

### (b) Host-dependent builtins — 1 case

`read` and its 32 siblings in `Builtins.HOST_DEPENDENT` read or mutate state outside the
program: files, clock, randomness, network, process, environment, `args`, `input`, `exit`.
`209-relative-read` resolves a path relative to the source file, which a self-contained jar
does not have. A compiled jar that resolved it differently from `oq run` would be a silent
divergence, so the refusal is the behaviour that keeps "one contract, one fallback" true. The
refusal is by name and the message is specific. **Verdict: intentional, justified.**

### (c) Callable-taking builtins — 4 cases, after correcting a misclassification

A compiled call site passes arguments to a `NativeFunction`; `spawn`, `await`, `done` and
`par_map` additionally need the concurrency scheduler.

**A premise in the code was false and is now fixed.** `Builtins.HOST_DEPENDENT` contained
`map`, `filter` and `sort_by`, and its javadoc justified them by saying "a compiled class
cannot create callable values yet". That is untrue: a compiled lambda is a
`runtime.CompiledFunction`, which `implements OqCallable` (`CompiledFunction.java:16`), and
`conformance/255-oqthara-callable-into-java.oq` already passed compiled lambdas into Java and
had Java call them back before this change; `conformance/256-callable-builtins-compile.oq` is
added here to pin the three builtins themselves. None of the three reads or mutates state outside the program, which is
the actual definition in `HOST_DEPENDENT`'s own javadoc. Removing the three names was measured,
not assumed:

```
$ java -jar oq.jar build c1.oq
codepath: compiled bytecode
$ java -jar c1.jar
[1, 4, 9]      # map(xs, x -> x * x)
[2, 3]         # filter(xs, x -> x > 1)
[3, 2, 1]      # sort_by(xs, x -> -x)
```

Differential-tested across shapes with `diff` against `oq run`: a named `fun` as the callable, a
multi-line `fun`, a Java functional value (`Function.identity()`), nested `map` inside `map`,
empty lists, a key over `length`, a lambda returning a list, and a Java-backed stateful key.
All byte-identical. Errors agree too, including the source position:

```
map([1, 2, 0], x -> 10 / x)   -> division by zero            at 2:23 on both backends
map([1, 2], 5)                -> this value is not a function at 2:10 on both backends
```

The full suite (`./gradlew test --rerun-tasks`) passes with the change, and
`verify-conformance.py` initially still reported `213 compiled / 9 fallback / 0 divergences`,
because no conformance case exercised the free-function spellings. Rather than leave the new
capability unpinned, `conformance/256-callable-builtins-compile.oq` was added and the baseline
re-measured to `214 compiled / 9 fallback (223 of 223 executable) / 0 divergences across 256
programs`. `run.py --both` now reports
`ok: 19 probes compared compiled bytecode against the interpreter`; `d1_sort_by` was the last
declared fallback and its entry was removed *because the gap closed*, with the runner failing
loudly first ("recorded fallback is gone, so the gap it recorded has closed") rather than
silently changing its verdict.

Two things this row does **not** claim. It does not claim the beat was worth it on its own: the
method spellings `xs.map(f)`, `xs.filter(f)` and `xs.sort(f)` already compiled before the
change, so the practical gap was one spelling. And it does not claim `sort_by` is flawless —
its per-comparison key evaluation is a separately pinned defect (`ListSortTest`), pre-existing
and unchanged here.

**Concurrency remains refused, and for a reason this document got wrong first.** The original
claim here was that a compiled jar has no scheduler. That is false: the helpers resolve against
`Stdlib.TaskRunner.EXECUTOR` (`Stdlib.java:96`), a static executor every jar links, so removing
the four names did make all four cases compile. What actually breaks is output. The interpreter's
`print` issues one `PrintStream.println` (`Stdlib.java:159`), holding the stream lock across the
whole line; the compiled path calls `Arithmetic.print` (`Arithmetic.java:48-56`), which issues
`out().print(text)` and then `out().print(newline)` — two separate lock acquisitions, so two
threads interleave and lines fuse. Measured on a two-thread probe: 18 of 40 compiled runs
printed `from taskfrom main`; 0 of 40 interpreter runs did. The lowering was reverted rather
than shipped, and making compiled printing atomic is not Alpha 6 work. **Verdict: the
`map`/`filter`/`sort_by` half was a misclassification and is fixed; the concurrency half is a
real defect in the compiled `print`, documented in `Builtins`' javadoc, not a pass.**

### (d) Contracts — 1 case

`228-classes-and-contracts` uses a `contract` with `Shape`. Contracts are checked at run time
by the interpreter's own machinery and are not lowered. Lowering them would mean emitting a
check per call site — doable, but it is feature work with semantics to specify (when a
contract is checked, what a violation reports), and refusing is honest until that exists.
**Verdict: intentional, justified.**

## 4. What this accounting does not claim

- It does not claim the backend is complete. Nine programs do not compile, and §3 says why for
  each.
- It does not claim the refusals are permanent. (a), (c) and (d) are each closable by real
  feature work; none is closable by a flag.
- It does not count a fallback as a pass. `verify-conformance.py` requires
  `compiled + fallback <= executable` and pins `compiled` against a regression, so a fallback
  cannot silently become a divergence.
- The 19 Java Bridge decision probes are a separate population with their own declaration in
  `docs/governance/alpha-5/java-bridge-probes/run.py`; none of them is a declared fallback any
  more — `d1_sort_by` was the last, and it stopped falling back when the misclassification above
  was corrected, so `FALLBACK` is empty.

## 5. Reproducing this document

```bash
./gradlew :oq-cli:standaloneJar
cd conformance
for f in *.oq; do b=${f%.oq}; [ -f "$b.err" ] && continue; \
  java -jar ../oq-cli/build/libs/oq.jar build "$f" 2>&1 | grep '^codepath:'; done
cd ..
python3 scripts/verify-conformance.py
```
