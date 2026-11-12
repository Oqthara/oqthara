# ADR-0042 — Whole-value compression

**Status:** Accepted
**Date:** 2026-11-11
**Supersedes:** nothing
**Related:** ADR-0033 (encoding and hashing), ADR-0036 (time zones and filesystem)

## Context

The stdlib inventory ranks whole-value compression as the highest-ranked domain still absent
after ADR-0041. The chapter `Compression and archiving` has eight modules and Oqthara covers
none of them. `read_bytes` and `write_bytes` already pass binary content as lowercase hex, and
`hex_encode`/`hex_decode` already exist, so the transport is settled; what is missing is the
primitive itself.

What the platform offers without a dependency: `Deflater`/`Inflater` for raw DEFLATE,
`GZIPOutputStream`/`GZIPInputStream` for the gzip container, and `ZipInputStream`/`ZipOutputStream`
for zip entries. All are in `java.base` and all work on in-memory byte arrays.

The security note in the inventory is the constraint that shapes the API. Decompression is the
textbook amplification attack: a few hundred bytes of hostile input can expand until memory is
exhausted, and the container's own length field cannot be trusted to prevent it, because a hostile
producer writes whatever length it likes.

Absence, verified against the built CLI before this ADR was written:

```
oq run zlib.oq        # print zlib_compress("a")
error: 'zlib_compress' is not defined
oq run gzip.oq        # print gzip_compress("a")
error: 'gzip_compress' is not defined
oq run zip.oq         # print zip_compress({"a.txt": "68"})
error: 'zip_compress' is not defined
```

## Decision

**D1 — Three functions, byte-in and byte-out over hex.** `zlib_compress(data)` and
`zlib_decompress(data)` use raw DEFLATE. `gzip_compress(text)` and `gzip_decompress(data)` use the
gzip container. `zip_compress(files)` and `zip_decompress(data)` handle an in-memory archive.
Every input and every output is lowercase hex text, matching `read_bytes`.

Text goes in as UTF-8 through the existing `utf8` helper, exactly as `hex_encode` does; gzip is
defined over bytes, and every Oqthara text value has a UTF-8 encoding, so no separate text entry
point is needed.

**D2 — Decompression has a hard output cap that the caller cannot raise.** `zlib_decompress` and
`gzip_decompress` stop at 16 MiB of output — the same `MAX_TEXT_BYTES` budget the text reader
already uses — and raise `zlib_decompress() exceeded 16777216 bytes of output` naming the function.
The cap is enforced by counting bytes as they are inflated, not by trusting a header field.

This is the decision that matters. Enforcing the cap by reading a length from the input would
place the attack exactly where the defence is: a hostile producer declares a small length and
supplies a large payload. Counting the bytes actually produced cannot be lied to.

`zip_decompress` applies the cap to the whole archive's uncompressed total rather than per entry,
because a thousand entries each under the cap is the same attack as one entry over it.

**D3 — Inflation is incremental, and the cap is checked inside the loop.** The implementation
inflates in fixed-size chunks and tests the running total after each chunk, so a hostile input is
stopped after at most one chunk of overshoot rather than after allocating its full expansion. A
non-positive `Deflater.inflate` return means the input ended early and raises
`zlib_decompress() needs more input` rather than looping.

This is a correctness point, not a performance one: the obvious implementation is
`readAllBytes()` on an `InflaterInputStream`, which allocates the whole expansion before any cap
can be applied and therefore does not implement D2 at all.

**D4 — `zip_compress` takes a map of names to hex content, and refuses traversal.** The input is
an ordinary value `{"a.txt": "68656c6c6f", "dir/b.txt": "..."}`. Entry names are validated before
writing: an absolute path, a `..` segment, a backslash, or a NUL byte is refused with
`zip_compress() refused entry name: <name>`.

The refusal is unconditional rather than normalising the name. A name that had to be rewritten
to be safe is a name the caller did not mean, and silently storing a different name than the one
asked for is the kind of quiet wrong answer this project treats as a defect.

**The same rule applies when reading, and that is the direction that matters.** `zip_decompress`
validates every entry name it encounters with the identical rule and raises
`zip_decompress() refused entry name: <name>` rather than returning a map holding a name the
writer would have refused. Validating only on write would protect nothing: a program does not
choose the names in an archive it is handed. The obvious use of the result — write each entry's
content to the path named by its key — turns a hostile archive into an arbitrary-file-write, and
the map is the last place the check can happen. Nothing is returned when any entry is refused, so
the failure mode is refusal rather than a partial map the caller might act on.

This was a real defect until 2026-11-13. The writer refused the name and the reader accepted it
from an archive built with `java.util.zip`, so `zip_decompress` returned `../../etc/evil.txt` at
exit 0. The reader-side tests build their archives with `java.util.zip` rather than with
`zip_compress`, precisely so a writer-side rule cannot make a reader-side test pass by
construction; they also pin that names which merely *contain* dots (`..txt`, `a/b..c`) still
round-trip, because a reader made stricter than the writer would be a new defect.

**D5 — No stream, no handle, no partial value.** Every function consumes a complete value and
returns a complete value. This is not a limitation to be worked around later; it is what makes
the API expressible without a `close`, and it is the same constraint ADR-0037 applied to sockets
and ADR-0040 applied to database handles.

Compression of a value larger than memory is therefore unsupported, and that is recorded rather
than hidden. The alternative — a stream — needs the lifetime construct the language does not have.

**D6 — Reported sizes are not a first-class concept.** There is no `compressed_size` or `ratio`
function. A caller who wants the size calls `len()` on the hex text and divides by two, which is
what `len` is for, and which makes the hex/text relationship explicit rather than hiding a second
unit in the API.

## Consequences

- **Six new names** — 143 → 149 globals, 154 → 160 registrations. The `Compression and archiving`
  chapter moves from 0/8 to 3/8, and rank 2 is removed from the absent list.
- **The amplification cap is now the pattern for any future expanding operation.** Counting real
  output inside an incremental loop, with a named error, is recorded here so a later `tarfile` or
  archive-reader decision inherits it rather than rediscovering it.
- **`tarfile` remains absent.** The inventory proposed it alongside `zipfile`, but tar has no
  inherent entry-length limit and its metadata is text, so a hand-written reader would need the
  same hardening as a parser, for a format zip already covers. Refusing it keeps the surface at
  one archive format, and D4's traversal check is the half of the argument that generalises.
- **Round-tripping is not identity for text that is not valid UTF-8.** `gzip_decompress` returns
  hex, not text, precisely so that binary content survives; a caller who wants text decoded calls
  `hex_decode` themselves, which is where the UTF-8 assumption belongs.

## Alternatives considered

**A `compression` module object with methods**, matching `statistics`. Refused because the
existing encoding and hashing functions — which are the closest neighbours — are flat globals, and
a module object would be the only one of its kind in the stdlib.

**Returning base64 instead of hex.** Refused: `read_bytes` and `write_bytes` already set hex as the
binary spelling, and a second spelling would mean a caller has to know which function wants which.

**A cap that the caller passes**, as `zlib_decompress(data, max_bytes)`. Refused as the primary
form because the default would then be either unlimited or arbitrary, and a caller under pressure
would raise it. A fixed cap is a policy the language can defend; a parameter is a negotiation.

**`InflaterInputStream` with `readAllBytes()`.** Refused under D3: it allocates the full expansion
before the cap can be applied, so it does not implement the cap at all.
