# ADR-0040 — Database support

**Status:** Accepted
**Date:** 2026-11-09
**Context:** Alpha 4 §17.8 asks to "evaluate SQLite/JDBC support and a coherent database API,
including resource lifetimes, prepared statements, transaction behavior, type conversion, and
error mapping."

## The problem the directive did not name

Oqthara has no `close` and no destructors. Every earlier ADR met this and stepped around it:
ADR-0037 D4 declined sockets, ADR-0038 D6 declined key/certificate handles, ADR-0039 D1 declined
an executor type. A database connection is the most demanding instance of that same problem — it
holds an OS file lock, buffers an open transaction, and must be released even when the body raises.

A database API that returned a connection object would be the first API in the language to leak a
resource on the error path, because there is no `finally` to write and no `close` to call.

The `lock(body)` shape introduced in §17.7 for mutexes resolves this exactly. A body is a value
that runs to completion or raises, and the callee holds the `try`/`finally` that the caller cannot
write. The same shape makes a connection impossible to leak, because a connection never escapes:
there is no value of type `connection` and no function that returns one.

## Decisions

### D1 — Adopt SQLite via `sqlite-jdbc`; decline JDBC generally

SQLite is the right first database because it is embedded, requires no server, and its entire
state is a file the standard library already knows how to name.

Adding a general JDBC surface is declined. JDBC's value is driver interchangeability across
networked servers, and that value is inseparable from `Connection` handles, pooling, and
`DataSource` lifetimes — all of which assume `close`. An API shaped for that world would be
shaped wrong for this language. SQLite is offered as a module, not as "JDBC, briefly".

The project previously had **zero** external runtime dependencies. This ADR spends that property
deliberately and records the price below, so a later reviewer can see the trade rather than
discover it.

**Measured cost:** the standalone `oq.jar` grows from 435 KB to 13.5 MB, because `sqlite-jdbc`
bundles native SQLite binaries for every supported platform and the jar task unzips them. The
artifact is the only place this shows up; no build step changes.

**Verified:** sqlite-jdbc 3.45.3.0 opens an in-memory database, creates a table, inserts a row,
and reads back `1 ana 1.5` — run under the real build with the real jar task. The driver is
reachable from Maven Central and CI uses `setup-gradle` with default caching, so no offline
cache pinning is required.

**Native code:** `sqlite-jdbc` is JNI, so it is the first non-portable dependency. The Docker
image is `eclipse-temurin` (glibc), where the bundled natives load; a musl/Alpine image is not
supported by this decision and would need its own verification.

### D2 — Every call is one shot: open, run, close

There is no connection handle. Each function takes a database path, opens it, performs its work,
and closes it in a `finally` before returning or raising. A connection value never exists in the
language's value space, so it cannot be stored, aliased, closed twice, or forgotten.

```oq
rows = db_query("app.db", "SELECT id, name FROM users WHERE active = ?", [true])
for row in rows {
    print(row["name"])
}
```

This is why `db_query` needs the path on every call. The redundancy is the design: a repeated
path is the visible cost of an un-leakable resource, and it is smaller than the cost of a handle
that can be leaked.

### D3 — Parameters are always bound; SQL is never assembled

Every function that takes user data takes it in a parameters list, not spliced into the SQL text.
The implementation uses `PreparedStatement` and `setObject` exclusively.

This is a safety property, not a convenience one. A `db_execute(path, "DELETE FROM t WHERE id = " + id)`
would reintroduce SQL injection, and the module must not make the unsafe spelling shorter than
the safe one. No function in this module accepts a fully-formed statement containing substituted
values.

**Verified:** a parameterised insert of the literal text `'; DROP TABLE users--` is stored as a
string and the table survives; the same text interpolated into SQL text drops the table. This
is the pair of tests that proves binding is doing the work.

### D4 — Transactions are a body, not a sequence of calls

`db_transaction(path, body)` runs `body` with a transaction context and commits when it returns,
rolls back when it raises. `db_query` and `db_execute` called inside the body use the open
transaction; called outside one, they are auto-committed.

There is deliberately no `begin`/`commit`/`rollback` triple. Exposing them would let a caller
leave a transaction open across an exception — the exact defect D2 exists to prevent — and would
require a handle to carry the transaction between calls.

**Verified:** a body that inserts two rows and then raises leaves **zero** rows, and the raise
reaches the caller unchanged. A body that completes leaves both rows.

### D5 — SQL NULL maps to `nothing`, and back

`nothing` is already the language's absent value — it is what `json_parse` returns for JSON
`null`, and `is_nothing` tests it. SQL NULL maps to `nothing` on read. Passing `nothing` as a
parameter binds SQL NULL.

**Verified:** `db_query(":memory:", "SELECT NULL AS v")[0]["v"]` yields `nothing`, and
`is_nothing(...)` is `true`.

Type mapping is otherwise the obvious one and is pinned by tests: `INTEGER` → number (64-bit),
`REAL` → number (double), `TEXT` → text, `BLOB` → bytes, and any other type → its text form.
A number read from SQLite is a number in Oqthara; there is no distinct integer type to lose.

### D6 — Errors are values with stable text, raised not returned

A malformed statement, a missing file that could not be created, or a failed constraint raises
an error whose message names the failure. Following the pattern of the HTTP and URL modules, the
message is prefixed so it is greppable and does not depend on the driver's own wording.

The driver's exception text is included after the prefix, because suppressing it would make
diagnosis harder than the leak it prevents. The prefix is a contract the tests assert; the suffix
is not.

### D7 — In-memory databases are explicitly allowed

`:memory:` is a real SQLite path and the cheapest way to test. Because D2 opens per call, an
in-memory database opened by one call and closed by it retains nothing across calls.

This is a genuine limitation and is documented rather than hidden: an in-memory database is
useful only within a single file's lifetime when the path is used directly. Callers who need
shared in-memory state across statements use `db_transaction`, which holds one connection for
the duration of the body.

## Alternatives considered

**Return a connection object.** Rejected: it leaks on every error path, and the language has no
way to write the cleanup.

**Expose `begin`/`commit`/`rollback`.** Rejected under D4: an exception between `begin` and
`commit` leaves the transaction open with no way for the caller to know.

**Add a `close` builtin.** Rejected as a language change smuggled in through a database feature.
Resource lifetime is a language-wide question; answering it in one module would make every other
module's decision inconsistent. If `close` is ever added it should be a deliberate language
decision with its own ADR, and this module would change with it.

**Decline database support entirely.** Considered seriously. The §17.8 clause says "evaluate",
which permits a refusal. It was not taken because D2 and D3 make a coherent, leak-free, safe API
possible without any language change, and because a language with no way to read a database
cannot be used for the programs most likely to replace a scripting language. The cost is one
native dependency, recorded in D1.

**Use an in-memory-only implementation to avoid the dependency.** Rejected: it would be a
database API that cannot open a database file, satisfying the letter of §17.8 and none of its
purpose.

## Consequences

- `oq.jar` grows to ~13.5 MB. This is the first and only external runtime dependency.
- One API shape (`db_query`, `db_execute`, `db_transaction`) covers reads, writes, and
  transactions without a handle, and cannot leak.
- The language gains real persistence while the no-`close` constraint remains intact.
- If `close` is ever added, D2 should be revisited: per-call paths exist only because there is
  no handle, and a future handle-based API would supersede them rather than sit beside them.
- Alpine/musl containers are unsupported by D1 and would need their own native verification.
