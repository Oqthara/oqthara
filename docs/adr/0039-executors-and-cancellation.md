# ADR-0039 — Executors, cancellation, and synchronization

- Status: Accepted
- Date: 2026-11-10
- Supersedes: nothing
- Superseded by: nothing

## Context

§17.7 asks for thread and task utilities, executor abstractions, synchronization primitives
where justified, cancellation and timeout support, test and assertion utilities, and safe
inspection and type metadata. Some of this exists and some of it does not, and the parts that
do exist have defects that only appear when a program is actually run.

Four things were measured against the built CLI before this ADR was written.

**A task failure is silently discarded when nothing awaits it.** This program prints `true` and
`no await, still here`, then exits `0`:

```oq
fun boom()
  return 1 / 0
end
const t = spawn(boom)
sleep(0.2)
print(done(t))
print("no await, still here")
```

The division error happened inside the task. `done(t)` reports `true` because the future
completed — exceptionally, but completed. The program never learns that its work failed, and
the process exits successfully. A failure that nobody observes still happened.

**There is no timeout on `await`.** `await(t, 100)` is refused with `await() expects 1
argument, got 2`, so a task that never finishes can only be waited on forever. The step budget
does not help: it is per interpreter and the step counter is charged by statements, so a task
blocked in a builtin wait never charges anything.

**`spawn` leaks an executor on every call.** `spawn` builds a fresh
`newVirtualThreadPerTaskExecutor()` and never closes it. `par_map` correctly uses
try-with-resources and closes its executor; `spawn` does not. Each `spawn` therefore leaves an
executor object that is unreachable but never shut down. Since `spawn` is the primitive a
program calls in a loop, this is a leak proportional to program behaviour rather than to
program size.

**Shared mutable state races, and the language has nothing to protect it with.** Two tasks
each incrementing a shared counter 2000 times produced `3840`, `3850`, and `3704` across three
runs instead of `4000`:

```oq
n = 0
fun bump()
  i = 0
  while i < 2000
    n = n + 1
    i = i + 1
  end
  return n
end
const t = spawn(bump)
const t2 = spawn(bump)
await(t)
await(t2)
print(n)          # 3704..3850, never 4000
```

This is an ordinary lost update: `n = n + 1` reads, adds, and writes with no exclusion, so
two tasks can read the same value and both write the same successor. It is not a bug in the
interpreter — the semantics say nothing about atomicity — but a language that hands out
`spawn` and has no primitive to make a read-modify-write safe is inviting a class of bug the
programmer cannot fix from inside the language.

## Decision

### D1 — Executors are not added as a first-class concept

An executor is a thread pool with a lifetime, a queue, and a shutdown protocol. Exposing one
would require `close` (which the language does not have; see
[ADR-0037](0037-networking-and-urls.md) D4 and [ADR-0038](0038-constant-time-and-password-hashing.md)
D6), and it would duplicate what `spawn` and `par_map` already decide. Virtual threads are
cheap enough that a bounded pool is an optimisation, not a capability, and the language has no
way to tune one safely. `spawn` is the executor abstraction: one task, one virtual thread.

What is added instead is the fix for the leak in D2.

### D2 — `spawn` uses one shared, bounded executor owned by the process

`spawn` keeps a single static `ExecutorService` of virtual threads, created lazily and reused.
The previous per-call executor was unreachable and never shut down, so the leak grew with the
number of tasks. A shared executor is not closed when a program ends either, but there is
exactly one of it, it is created only if `spawn` is used, and its threads are virtual. Virtual
threads do not keep the JVM alive, so a program that ends with the executor still open exits
exactly as it did before.

`par_map` keeps its try-with-resources executor: it knows when its work is finished, so it can
close deterministically, and a shared pool would make its parallelism depend on unrelated
earlier calls.

### D3 — `await` gains an optional timeout, in seconds, and never blocks forever by accident

`await(task)` is unchanged. `await(task, seconds)` waits at most that long and raises
`the task did not finish within N seconds` if it is still running. The task is **not**
cancelled, because cancelling it would be a lie: the interpreter does not check for
interruption anywhere (measured — there is no `isInterrupted` or `Thread.interrupted` call in
`oq-core` or `oq-stdlib`), so an in-flight loop would keep running and keep mutating shared
state after the caller was told it had stopped. A timeout that reports a stop it cannot
enforce is worse than no timeout, because it makes the caller reason about a state that does
not exist.

The timeout uses the same unit as `sleep`: seconds, as a number, so `await(t, 0.5)` is half a
second. It is capped by the existing sleep budget rules rather than by a new constant, so a
long wait is refused with the same message a long `sleep` gets.

### D4 — Cancellation is not implemented, and this is recorded rather than approximated

Cancellation needs a checkpoint the interpreter reaches during execution. `Interpreter.tick`
is called on every statement and is the only such checkpoint, and it currently charges the
step budget. Adding an interrupt check there would make `cancel` meaningful for loops, but
not for a task blocked inside a builtin — and a task blocked inside a builtin is exactly the
case a timeout exists to escape.

A half-working `cancel` is worse than an absent one. It would appear to stop a task, report
success, and leave a virtual thread mutating a shared list. This ADR declines it and records
the exact reason so the next person does not implement it by halves.

### D5 — One synchronization primitive: `mutex()`

A mutex is justified by the measured lost update in the Context section, and only a mutex. It
is the one primitive with no policy: no ordering, no fairness promises, no condition
variables, no reader/writer variants. `mutex()` returns a value with `lock(callback)`:
acquire, call, release in a `finally`.

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
```

The body of a locked region has to be a named function. A lambda cannot be one, because
assignment is a statement and a lambda has an expression body with no statement form
(measured: `() -> n = n + 1` is refused with `unexpected '=' after a statement`, and
`() -> begin ... end` is refused with `unexpected keyword 'end'`). This is not a restriction
the mutex invents — it is how the language already reads — but it does mean `m.lock` is
written with `fun`, not with an inline arrow.

`lock` takes a callback rather than exposing `lock`/`unlock` as separate calls, because the
only correct use of separate calls is a try/finally, and the language has no `finally`
(measured: the spec says "There is no `finally`"). Exposing separate calls would guarantee
that some programs forget to unlock inside a `try`, and a permanently held mutex is a hang
with no diagnosis. A callback cannot be forgotten.

The mutex is not reentrant. A callback that locks the same mutex deadlocks, and `lock` detects
this and raises `this mutex is already held by this task` rather than hanging. Detecting it is
possible because the owning thread is known — the same reason it is cheap.

Values shared between tasks are already reachable, so the mutex protects a convention, not the
data. Nothing stops a program from writing `n = n + 1` outside the lock. This is documented
rather than enforced, because enforcing it would require every value to carry a lock and would
change the meaning of every assignment in the language.

### D6 — Test and assertion utilities are already present and are not extended

`assert(value)` and `assert(value, message)` exist and are tested. `type_of` exists. Nothing
in §17.7's "test/assertion utilities" and "inspection/type metadata" lines is missing at the
level this language needs: there is no test framework to integrate with, and a `describe`
block would be a style, not a capability. No new assertion builtins are added, and the ADR
records that §17.7's testing line was satisfied before it was read.

### D7 — Inspection metadata stays at `type_of`

`fields`-style reflection is not added. The type of a value is public and stable; its
internal shape is not, and the two `OqData` records (`Url`, `Response`, `QueryPair`, `Result`,
`Task`) already expose their fields through ordinary member access. A reflective field list
would expose implementation detail as API and would have to be kept in sync with every record
by hand.

## Consequences

- A leaked executor per `spawn` call is fixed; the observable behaviour of `spawn` is
  unchanged, which is why this is a repair rather than a redesign.
- `await(task, seconds)` makes a bounded wait possible. The task keeps running after the
  timeout, and the ADR says so instead of implying otherwise.
- `mutex()` closes the lost-update hole for programs that use it. Programs that do not are
  unchanged, including their existing races.
- Cancellation remains absent. A program that spawns an infinite task and exits will still
  exit — measured at 0.13 s, because virtual threads do not keep the JVM alive.
- No executor type, no thread pool, no `close`, no `finally`, and therefore no lifetime
  problem to solve later.

## Alternatives considered

**Cancelling on timeout.** Rejected: no interruption checkpoint exists in the interpreter, so
the cancel would be reported but not performed.

**A `thread` type with `join`/`interrupt`.** Rejected: a thread handle is a lifetime, and the
same absence of `close` applies.

**Atomic types instead of a mutex** (`atomic_int`, `atomic_add`). Considered and rejected as
the first step, because it would solve only counters. A mutex solves any read-modify-write,
including one that spans several values, and a language with exactly one concurrency primitive
is easier to reason about than one with `mutex` and four atomics. Atomics can be added later
without contradicting this decision if a measured need appears.

**Channels (`channel`, `send`, `recv`).** Rejected for now: a channel is a lifetime again, and
blocking `recv` with no timeout would be a hang with no escape until cancellation exists.
