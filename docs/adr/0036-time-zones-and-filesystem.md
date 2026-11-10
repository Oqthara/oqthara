# ADR-0036: Time zones, file operations, and binary data

- **Status:** Accepted
- **Date:** 2026-11-10
- **Related:** [ADR-0035](0035-csv-and-structured-data.md), [ADR-0033](0033-encoding-and-hashing.md)

## Context

Requirement §17.3 asks for "date/time and time-zone APIs; path abstraction; filesystem
metadata and traversal; file copying/moving where safe; buffered and structured I/O; resource
management and deterministic close semantics".

Probing the built CLI before designing found the following absent:

```
print tz_convert(1)     # error: 'tz_convert' is not defined
print copy_file(1)      # error: 'copy_file' is not defined
print read_bytes(1)     # error: 'read_bytes' is not defined
print walk(1)           # error: 'walk' is not defined
print is_file(1)        # error: 'is_file' is not defined
```

What exists is a working but incomplete set: `now`, `today`, `datetime`, `timestamp`, `sleep`;
`read`, `read_lines`, `save`, `append`, `exists`, `list_files`, `delete`, `make_dir`,
`file_size`, `path_join`, `path_name`, `path_parent`, `cwd`.

This ADR decides what to add, what to refuse, and — importantly — what the existing functions
already promise that was never written down.

## Decision 1: local time is a deliberate hazard, and the docs will say so

Measured, not assumed. The same program under three `TZ` values:

```
$ TZ=UTC oq run t.oq
today()    2026-11-10
datetime() 2026-11-10T14:04:24

$ TZ=Pacific/Kiritimati oq run t.oq
today()    2026-11-11
datetime() 2026-11-11T04:04:24
```

So `today()` and `datetime()` are **local**, and therefore the same program produces different
strings on different machines. `now()` and `timestamp()` are not: they are UTC. This is a real
trap, and the specification currently does not warn about it.

The language will not change `today()` — programs depend on the local date meaning the local
date, and a silently changed time zone is a worse defect than a documented trap. Instead the
specification gains an explicit warning, and the UTC-explicit alternatives are named.

## Decision 2: time-zone conversion takes an explicit zone and returns an offset-bearing instant

`tz_convert(instant_text, zone)` converts an ISO-8601 instant into a wall-clock text carrying a
numeric offset, for a named IANA zone:

```
$ print tz_convert("2026-11-10T14:04:24Z", "Asia/Tokyo")
2026-11-10T23:04:24+09:00
```

Rejected alternatives:

- **A default of the machine's zone.** That is exactly the defect Decision 1 documents.
- **A separate "zoned instant" type.** The language has no user-visible type system rich enough
  to justify one; text with an offset round-trips through every format this project already
  produces.
- **Rendering a zone abbreviation such as `JST`.** Abbreviations are ambiguous (`CST` is three
  different zones) and `java.time` refuses to infer one.

The zone name is validated against `ZoneId`, so a typo is an error naming the input rather than
a silent fallback to UTC. `java.time`'s tzdb is the source of truth, which means the result
depends on the installed tzdb version — recorded as a known limitation rather than hidden.

## Decision 3: file copying refuses to clobber, and moving is copy-then-delete

`copy_file(source, target)` fails if the target exists. `move_file(source, target)` is
implemented as a copy followed by a delete rather than `Files.move`, because `Files.move` across
filesystems may copy and may partially fail, leaving a half-written target that the program
cannot detect. Doing both steps explicitly means a failure between them leaves the source
intact — the recoverable direction.

Both operations are charged against the transfer budget, so copying a large file is not a way to
touch unlimited bytes. Neither is atomic, and the documentation will say so rather than imply a
guarantee the implementation does not make.

## Decision 4: binary files are hex text, not a bytes type

§17.1 asks for "bytes, binary data". Measured: `base64_encode` and `hex_encode` both *require
text* and there is no bytes type at all.

```
$ print base64_encode(1)
error: base64_encode() needs text, got number
```

Adding a bytes type is a language change — literals, equality, printing, indexing, the whole
value protocol — and it would land in this round as an unreviewed second value representation.
Instead `read_bytes` returns lowercase hex text and `write_bytes` accepts it, matching the
encoding module exactly:

```
$ print read_bytes("a.bin")      # 6869
$ write_bytes("b.bin", "6869")
$ print read_bytes("b.bin")      # 6869
```

This round-trips through JSON, files, and the existing encoders without a new type, and a
program that needs real binary arithmetic should not be parsing hex in the first place.

Rejected: a byte-list representation. `[104, 105]` round-trips too, but it is 4x larger, is
indistinguishable from a genuine list of numbers, and every existing function that takes text
would need a second overload.

## Decision 5: traversal is explicit, bounded, and does not follow links

`walk(directory)` returns relative paths under a directory, recursively. It is bounded by the
same entry cap as `list_files`, and it does **not** follow symbolic links: following links during
a recursive walk is how a walker loops forever on a cycle, and a bounded depth is not enough if
the cycle is short.

`is_file` and `is_dir` are added because `exists` cannot distinguish a directory from a file, and
`file_size` silently reports a directory's own size (measured: 160 bytes for a directory) rather
than failing.

## Decision 6: no resource-management construct; the documentation stops implying one

Measurements:

- There is no `with`, no `defer`, and no `close` in the language.
- There is no `open` returning a handle. `read`, `save`, and `append` open and close inside the
  builtin.
- Every handle the stdlib acquires is closed before the builtin returns, so there is nothing for
  the program to leak.

The requirement therefore reads "resource management and deterministic close semantics" as a
demand for a pattern that this design does not need. Rather than invent a `with` statement whose
only use would be to close handles the language never exposes, the ADR records the property the
implementation actually has: **no builtin in this round returns an open resource**, and any
future builtin that does must arrive with the construct that closes it.

## Decision 7: the filesystem is trusted; this is a documented boundary, not a bug

Measured, and reported here because it is a security-relevant fact a reader would otherwise
assume wrongly:

```
$ print read("../../../etc/hosts").length()
213
$ print exists("/etc/passwd")
true
```

Oqthara programs are not confined to the working directory and not confined to a sandbox. The
specification already says "Oqthara is **not** a sandbox", and this ADR neither changes that nor
pretends otherwise. `copy_file` and `move_file` do not weaken it: a program that can already read
and write any path gains no new reach by being able to copy.

## Consequences

- `tz_convert` makes time-zone handling explicit; `today()` stays local and is documented as a
  hazard with UTC-explicit alternatives named.
- Two filesystem conveniences (`copy_file`, `move_file`) and two predicates (`is_file`, `is_dir`)
  join the surface; none of them introduces a new trust boundary.
- Binary data is hex text, so the encoding module stays the single representation of bytes.
- Traversal does not follow links, which prevents the cycle failure mode at the cost of not
  being able to walk into a linked directory.
- There is still no `with`/`close`, and this is now a recorded decision rather than an omission.
