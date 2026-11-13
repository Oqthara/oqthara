# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- **`split("")` returned values that were not text.** With an empty separator, Java's
  `String.split` walks UTF-16 units, so `"a😀b".split("")` came back as five entries — `a`, an
  unpaired high surrogate, an unpaired low surrogate, `b`, and `""` — and the two halves are
  values no program can render, compare, or print. Every other text method works in code points
  (ADR-0024), so this was the one place one leaked out. The call is now refused with
  `split() needs a separator that is not empty`, which is also what Python does for the same
  input, and the help line points at `chars()` and `join()`. Real separators still keep their
  trailing empty fields. (#147)
- **`unified_diff` could exhaust the heap on a few thousand lines.** The longest-common-
  subsequence table is a full `(n + 1) x (m + 1)` matrix, so two 5,001-line inputs asked for a
  25-million-cell allocation — roughly 100 MB of cell data — and a program running under a
  96 MiB heap died with an out-of-memory error it had no way to catch or report. Inputs whose
  table would exceed `TextWrap.MAX_DIFF_CELLS` (4,000,000 cells, about a 2,000-line file
  compared against another) are now refused with an ordinary `OqError` naming both line counts,
  the size it refused, and the limit. A refused diff is never partially returned. (#178)
- **`repr` of text containing a brace did not paste back as that text.** `repr` is documented as
  rendering a value so the line can be pasted back as source, and it escaped only `"`, `\`, and
  the `\n`, `\t` and `\r` whitespace escapes. An opening brace was emitted verbatim, so with
  `x = 99` the text `{x}` rendered as `"{x}"` and pasted back as `99` — a different value, with no
  error to signal it. Opening braces are now doubled, and a closing brace is doubled only where
  the parser would otherwise pair it with a doubled opening brace. NUL is now escaped as `\0`.
  Other control characters are still written as themselves: the parser preserves them and has no
  escape rule for them, so escaping them would not round-trip. (ADR-0057)
- **`zlib_decompress` and `gzip_decompress` accepted uppercase hex.** The JDK's
  `HexFormat.parseHex` takes `A`-`F` as readily as `a`-`f`, so the compression functions were the
  one place in the language where hex spelling did not matter, while `hex_decode` has always
  refused it. They now refuse it with the same wording, naming the offending character and its
  offset. (#175)
- **Trailing bytes after a compressed stream were silently ignored.** `zlib_decompress`
  returned the value and discarded whatever followed the stream, and `gzip_decompress` did the
  same, so a second gzip member was silently dropped instead of being reported. Both now refuse
  with `found N trailing bytes after the compressed stream`, singular for one. The gzip trailer
  is located from where the deflate stream actually stopped rather than from the end of the
  input, so appended bytes are reported as trailing instead of being misdiagnosed as a truncated
  stream; the stored CRC and length are then verified, where before `GZIPInputStream` did it.
  (#176, #188)
- **`zip_decompress` returned directory entries as files.** An archive containing only
  `folder/` came back as `{"folder/": ""}` instead of raising the documented
  `zip_decompress() found no entries`, and content a foreign writer attached to a directory name
  was exposed as a file. Directory entries are now skipped, so an archive of only directories
  has no entries and is refused. `zip_compress` refuses the matching contradiction rather than
  resolving it, raising `zip_compress() refused content for the directory entry: <name>` when a
  `/`-terminated name is given non-empty content. (#187)
- **`zip_decompress` silently overwrote duplicate entry names.** An archive naming `same.txt`
  twice returned only the last, losing the first without saying so. It now refuses with
  `zip_decompress() found a duplicate entry name: <name>`; identical content does not make the
  archive unambiguous. The JDK's own `ZipOutputStream` will not write such an archive, which is
  how the defect stayed hidden. (#171)
- **`hex_decode` and `base64_decode` replaced malformed UTF-8 instead of refusing it.** Both
  decoders built their result with `new String(bytes, UTF_8)`, whose convenience constructor
  substitutes U+FFFD for anything that is not UTF-8. `hex_decode("ff")` therefore returned a
  replacement character, and re-encoding it produced `efbfbd` rather than `ff`, breaking the
  lossless round trip ADR-0033 D1 promises. Both now decode strictly and raise
  `hex_decode() decoded bytes that are not valid UTF-8` (or the `base64_decode` form) naming
  the byte offset that opened the bad sequence. (#158)
- **`base64_decode` accepted non-canonical pad bits.** The JDK decoder ignores the unused low
  bits of the last Base64 character, so `Zh==` and `Zg==` both decoded to `f` and two distinct
  spellings named the same value — `base64_encode(base64_decode(s)) == s` was false for an
  input the decoder had accepted. `base64_decode` now refuses non-zero pad bits with
  `base64_decode() found non-zero pad bits at offset N, got 'c'`, and it reports misplaced or
  excessive `=` padding with its own offset rather than the platform decoder's "wrong 4-byte
  ending unit". Canonical inputs are unchanged. (#159)

### Added
- **`oq runtime install` fetches and installs the JDK Oqthara runs on.** Until now `oq` could
  resolve a managed runtime but nothing could put one there, so a machine with no Java 21+ had to
  install a JDK by hand before `oq` would start. The command downloads the pinned Eclipse Temurin
  21.0.13+9 archive for the platform, verifies it, and installs it under `OQ_HOME` — where the
  resolver already prefers it over `java` on `PATH`. The SHA-256 of all five platform archives is
  compiled into `oq` rather than fetched beside the archive, so a mirror that substitutes bytes
  has nothing to overwrite; an archive that does not match is refused before anything is unpacked.
  `oq runtime status` reports the platform, the pin, whether a runtime is installed, whether an
  older one is retained, and which Java would actually be used. (ADR-0048 D3)
- **Runtime archives are unpacked from tarballs as well as ZIPs.** Only Windows publishes a ZIP;
  the Linux and macOS archives are gzip-compressed tar, so the installer could not have consumed
  them. Tar extraction goes through the same containment rules and the same expansion caps as ZIP
  — traversal, absolute paths, duplicates and write-through-symlink are all refused — and follows
  ADR-0048 D4's rule for links by creating one only when its target resolves inside the
  destination. The archives really do contain links: the Linux JDKs carry 205 relative `legal/`
  cross-links, so refusing every link would have made every non-Windows install impossible.
- **A corrupt cached download repairs itself.** The cache was consulted before the network and
  never invalidated, so one bad archive made every later install fail on the same bytes with no
  remedy but deleting the file by hand. The cached archive is now verified before it is trusted
  and discarded when verification fails, so the next attempt re-downloads.
- **Exact rational arithmetic under the name `fraction`.** A `fraction` holds a `BigInteger`
  numerator and denominator, always in lowest terms with a positive denominator, so
  `fraction(2, 4)` and `fraction(1, 2)` are one value that prints as `1/2`. Seventeen builtins
  along the `fraction_` prefix construct, parse, format, take apart, add, subtract, multiply,
  divide, negate, take the absolute value, compare, floor, ceil, round, exponentiate and render
  one. Every operation is exact, including comparison: `fraction_compare` cross-multiplies
  rather than converting to a `double`, which is what makes
  `9999999999999999/30000000000000000` compare as less than `1/3` and
  `10000000000000001/30000000000000000` as greater, even though both are the same double.
  `fraction_to_decimal(value, places)` is the only conversion outward and the caller states the
  precision, so rounding is never silent. Two bounds keep the type from being a memory request:
  the integer exponent of `fraction_pow` is limited to `10000` in magnitude, and each part is
  limited to `32768` bits because a bound on an exponent is bypassed by squaring in a loop while
  a bound in the constructor is not. The type name `fraction` was confirmed free across the
  repository before it was chosen, and `decimal` remains reserved: `type_of(3.14)` already
  answers `"decimal"` for a `Double`, so no exact-decimal type can take that name.
  [ADR-0053](docs/adr/0053-exact-rational-arithmetic.md) records the decisions.
- **Trigonometry, logarithms and exponentials, and a settled rule for non-finite
  numbers.** `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`, `hypot`, `ln`, `log10`,
  `log2`, `exp`, `degrees` and `radians` join the standard library, along with the constants
  `pi()`, `e()` and `tau()`. All of them use `java.lang.StrictMath`, so a given input yields
  the same bits on every platform and JVM: `Math.sin` and `StrictMath.sin` disagreed on 36,073
  of 1,000,000 probed values, which would have made printed results machine-dependent.
  `log2` returns the exponent directly when its input is exactly a power of two, because
  `log(x) / log(2)` is inexact for 439 of the 2098 powers of two a double holds — `2^-1021`
  came out as `-1021.0000000000001` — and anyone using `log2` for bit arithmetic would have
  seen that. The constants are zero-arity functions rather than values because the compiled
  backend has no representation for a builtin value. Methods mirror the builtins, so `9.sqrt()`
  and `sqrt(9)` cannot disagree. [ADR-0051](docs/adr/0051-non-finite-numbers-and-transcendentals.md)
  records the whole set of decisions.
- **An example and four conformance cases for the new functions**, and the specification now
  states the non-finite rule instead of leaving it to be discovered by experiment.

### Changed
- **A domain error raises instead of returning `NaN`.** `sqrt(-1)`, `ln(0)`, `ln(-1)`,
  `log10(0)`, `log2(-4)`, `asin(2)`, `acos(-2)` and `pow(-8, 0.5)` now raise, matching
  `1 / 0` and `round(NaN)`; before this, `sqrt(-1)` and `pow(-8, 0.5)` silently returned `NaN`
  while every other arithmetic mistake raised. Overflow of a finite input still yields
  `infinity` (`exp(1000)`, `1.0e308 * 10`) and underflow still yields `0.0`, because those
  inputs are in the domain and only the answer is out of range; `pow(0, -1)` is `infinity` for
  the same reason, a pole not being a domain error. `NaN` remains reachable by combining
  infinities, and `n != n` is the test for it, so no `is_nan` builtin is needed. `pow(-2, 3)`
  is still `-8`, and `0 ** 0` is still `1`.
- ADR-0017 claimed `0.0/0.0` is `NaN`; it raises `division by zero`, because `Values.divide`
  checks the divisor before dividing. The stale sentence is corrected.
- The conformance corpus is one case larger (233 programs, 213 executable), and the bytecode
  backend now compiles 204 of them rather than 203.
- **`oq java` reports which JVM Oqthara will use, and ADR-0048 D1 is implemented.** The order is
  fixed: an explicit `OQ_JAVA`, then a `.oq/java` project pin found by walking up from the working
  directory, then a managed runtime under `OQ_HOME` or `~/.oq`, then `java` on `PATH`. The first
  executable that exists *and* reports Java 21 or newer wins. A candidate below the floor is
  recorded and skipped rather than ending the search, so a stray JDK 17 in `OQ_JAVA` cannot hide a
  working JDK 21, and the failure message names every origin checked and why each was refused.
  `oq java --json` prints the same decision machine-readably. Resolution reads only the
  environment and the filesystem, so it is complete and tested without installing anything; it
  does not download, extract or install, and those decisions remain unimplemented.
- **Examples for the modules added this round, and a test that runs them.** `urls.oq`,
  `hashing.oq`, `concurrency.oq` and `database.oq` demonstrate URL handling, hashing and PBKDF2,
  the mutex and `await`, and SQLite with transaction rollback and a refused injection. §17.9
  requires an example per module, and these four had none. `ExamplesRunTest` executes every
  example through the real CLI; the README previously claimed CI did this, and nothing did.
- **SQLite database support.** `db_query(path, sql, params)` reads, `db_execute(path, sql, params)`
  writes, and `db_transaction(path, body)` commits when the body returns and rolls back when it
  raises. There is no connection value: every call opens, works and closes, so a connection
  cannot be leaked — the language has no `close`, and a handle would be the first API to leak on
  the error path. Parameters are always bound, never spliced, and the placeholder count is
  checked because SQLite treats an unbound placeholder as NULL: a forgotten parameter previously
  wrote `nothing` and exited 0. SQL NULL maps to `nothing` in both directions. This adds the
  project's first external runtime dependency, which grows the standalone jar from 435 KB to
  13.5 MB; the trade is recorded in ADR-0040.
- **`oq doc` now scans every standard library source file.** It previously read only
  `Stdlib.java`, so a built-in defined in its own module file was missing from a reference page
  that claims to list every function. The page went from 126 to 129 functions.
- **A mutex, a bounded `await`, and one shared task executor.** `mutex()` returns the one
  concurrency primitive the language now has: `m.lock(body)` acquires, runs the body, and
  releases however the body ends, so a raising body cannot leave the lock held. It takes a
  function rather than a `lock`/`unlock` pair because the language has no `finally`, and it is
  not reentrant — locking it twice from the same task raises instead of deadlocking. This
  closes a measured defect: two tasks each incrementing a shared counter 2000 times produced
  3840, 3850 and 3704 instead of 4000, and now produce 4000 on every run. `await(task, seconds)`
  bounds the wait and then raises, without cancelling: the interpreter checks for interruption
  nowhere, so a reported stop would not be a real one, and the task keeps running. `spawn` now
  uses one shared virtual-thread executor instead of creating and abandoning one per call.
  Cancellation, executors and channels are deliberately absent, with the reasons recorded.
  Specified by [ADR-0039](docs/adr/0039-executors-and-cancellation.md).
- **Constant-time comparison and password hashing.** `secure_equal(a, b)` compares two texts
  through `MessageDigest.isEqual`, so the running time does not depend on where they first
  differ; `==` is unchanged, because most text comparisons are not secrets and paying that cost
  everywhere to defend the few would be a poor trade. `pbkdf2_sha256(password, salt, iterations)`
  derives 32 bytes of `PBKDF2WithHmacSHA256` as hex, with both the salt and the count required
  and the count bounded so a mistake in a variable cannot look like a hang. The output is
  checked against an independent implementation (`hashlib.pbkdf2_hmac`) rather than against
  itself, so it matches the standard vectors. Key and certificate APIs are deliberately not
  added: a key needs a lifetime and the language has no `close`, so it could only be handed out
  with no way to be sure it was discarded. Certificate validation itself is untouched and
  cannot be turned off. Specified by
  [ADR-0038](docs/adr/0038-constant-time-and-password-hashing.md).
- **URLs, query strings, and honest HTTP failures.** `url_parse` splits a URL into `scheme`,
  `host`, `port`, `path`, `query` and `fragment`, leaving `port` as `nothing` rather than
  inventing the scheme default. `url_encode`/`url_decode` are form encoding, so a space is `+`
  and a literal `+` is `%2B`; `query_parse` returns ordered `{name, value}` pairs so a repeated
  name survives, and `query_encode` accepts either those pairs or a plain map. `url_join`
  resolves and escapes a reference per path segment, which form encoding cannot do because it
  would turn `/` into `%2F`. Two defects are fixed: every transport failure used to report
  `HTTP request failed: null`, and now names the cause (`connection refused` is distinguished
  from a host that did not resolve); and the three HTTP deadlines were unexplained literals,
  one of which reused the subprocess timeout. Specified by
  [ADR-0037](docs/adr/0037-networking-and-urls.md). Sockets and an HTTP server are deliberately
  not implemented — neither can be given a lifetime while the language has no `close`.
- **CSV reading and writing, and date parsing.** `csv_parse` follows RFC 4180 with a real quote
  state machine rather than splitting on commas, so `a,"b,c"` is two fields and `a,",b"` is
  two fields; a field may contain the delimiter, a line break, or a doubled quote. Malformed
  input raises an error naming the line instead of being repaired into a table with the wrong
  shape. `csv_write` quotes exactly the fields that need it, so writing then reading gives back
  the same rows. `date_parse` and `instant_parse` return the same shapes `today()` and `now()`
  already produce, so a parsed value and a generated one are interchangeable. Specified by
  [ADR-0035](docs/adr/0035-csv-and-structured-data.md). XML is deliberately not implemented —
  an unhardened hand-written parser would be a security regression, and that is recorded as
  absent rather than approximated. The reference now lists 109 built-in functions, up from 105.
- **Time zones, file operations, and binary content.** `tz_convert(instant, zone)` converts an
  ISO-8601 instant into wall-clock text with an explicit numeric offset for an IANA zone; an
  abbreviation such as `JST` is an error rather than a guess, because `CST` is three different
  zones. `copy_file` and `move_file` refuse an existing target, and moving copies before
  deleting so a failure leaves the source intact. `read_bytes`/`write_bytes` move binary content
  as lowercase hex, matching what the encoders already take. `walk` traverses recursively
  without following symbolic links, and `is_file`/`is_dir` distinguish what `exists` cannot —
  `file_size` reports a directory's own size rather than failing. The specification now also
  documents, because it was measured, that `today()` and `datetime()` are local while `now()`
  and `timestamp()` are UTC, so the same program prints different dates in different zones.
  Specified by [ADR-0036](docs/adr/0036-time-zones-and-filesystem.md).
- **Logging, and a command-line argument parser.** `log_debug`, `log_info`, `log_warn` and
  `log_error` write `LEVEL message` to the error stream so a program's output stays pipeable;
  the threshold comes from `OQ_LOG_LEVEL`, defaults to `info`, and can be changed at run time
  with `log_level`. `arg_parse(args(), ["verbose", "port:int", "name:text?"])` returns
  `{flags, values, rest}`, converts typed values, treats a trailing `?` as optional, and raises
  an error naming the offending token when the command line does not match what was declared.
  Both are specified by [ADR-0034](docs/adr/0034-logging-and-argument-parsing.md). The
  reference documentation now lists 105 built-in functions, up from 98.

### Fixed
- **A timestamp had two shapes depending on its value, and one of them could not be read
  back.** `tz_convert` rendered with `OffsetDateTime.toString()`, which omits the seconds field
  when the second is zero, so a zero-second instant printed `2026-11-10T14:04Z` — and
  `instant_parse` on the project's own output then failed with "could not read". The round-trip
  ADR-0036 relies on was broken for one value in sixty. `datetime()` had the same omission,
  printing `2026-11-10T15:04` instead of `...T15:04:00`. Both now render through a named
  `DateTimeFormatter`, and `LocalDateTime(...)` with a sixth or seventh argument keeps its
  seconds instead of silently discarding them — a bridge bug that made
  `LocalDateTime(2026, 11, 10, 15, 4, 24)` equal to `15:04:00`. Six regression tests cover the
  three defects, five of them verified to fail on the previous code; no existing test had caught them
  because every `tz_convert` case used `:24` seconds, the one value where the two behaviours
  agree ([ADR-0056](docs/adr/0056-timestamp-shape-without-a-clock.md)).
- **`walk`, `glob` and `list_files` sorted before they bounded, so a large tree killed the
  program instead of being refused.** `Files.walk(dir).sorted()` is a stateful operation: it
  buffers every entry before yielding the first one, so the 100,000-entry check ran only after
  the whole tree had been materialised. A directory of 700,001 files therefore died with `the
  program ran out of memory` under a 32 MiB heap instead of reporting that the tree was too
  large. `glob` was worse, because its cap counts matches: a pattern that matched nothing was
  unbounded by definition, since every non-matching entry is traversal it paid for and never
  returned. All three now drain the stream into a bounded list and sort afterwards, so the cap
  applies before the allocation. Sorting still uses `Path.compareTo`, not a string comparison,
  so the documented order is unchanged: `a-x` still precedes `a/b`. `glob` also gains a visited
  budget of 200,000 entries, deliberately larger than the 100,000-result cap, because a
  pattern that rarely matches still has to look at every entry to answer; exceeding it raises
  `patterns visited too many entries`. All four traversal functions also report an unreadable
  directory as a named user error now: `Files.walk` reports it as an `UncheckedIOException`, so
  the `IOException` branch never saw it and the failure escaped as an internal error with exit
  70 and no diagnostic.
- **`write_bytes` accepted more than `read_bytes` would give back.** `read_bytes` has always
  refused a file over 16 MiB, but the writer had no limit, so a program could create a file it
  could not read. Worse, the hex was decoded and the target truncated before anything was
  checked: an oversized write destroyed the existing file and only then failed. The size is now
  checked against the hex length before the decode allocates, so the refused write creates
  nothing and leaves an existing target byte-identical.
- **`await` charged the timeout instead of the wait.** `await(task, 10)` billed ten seconds to
  the program's 60-second wait budget even when the task had already finished, so eight awaits of
  instant work raised `the program waited for too long` in microseconds — found by writing
  `examples/concurrency.oq`. Without a timeout the same call billed nothing at all, so a loop
  could wait forever by never passing one. Both now bill the time actually waited, and two
  regression tests fail on the old behaviour.
- **A built jar now carries the database driver.** `oq build` copied only `dev.oqthara` classes
  into the jar it produced, so a program that called `db_query` built without error and then
  failed at run time with "The SQLite driver is missing from this build" — the message was
  accurate, but a built jar is documented as standalone. The driver needs its SLF4J binding as
  well, since it logs on first use and the jar otherwise dies with `ClassNotFoundException:
  org.slf4j.LoggerFactory`. Both travel now, and the jar for `examples/database.oq` grows to
  13,859,109 B; `BuildJarTest` asserts it prints exactly what the interpreter prints.
- **Building a jar no longer re-reads its dependencies once per class.** `JarBuilder` asked each
  source for one entry at a time, and reading a single entry out of a zip means inflating every
  entry before it, so copying the 143 driver and SLF4J classes re-inflated the driver archive
  143 times. Carrying the driver therefore cost ~19 s per built jar instead of ~1.4 s, which
  made `./gradlew build` appear to hang: `BuildJarTest` alone went from 1m 23s to 17m 5s and the
  full build from 9m to over 24m. Each source now hands its wanted entries to the builder in a
  single pass. The jar's contents are unchanged, and a regression test fails at 19,062 ms on the
  old code.
- **A static field can be read off its class.** `Integer.MAX_VALUE`, `Math.PI` and every other
  static field were unreachable: the bridge asked the `Class` *object* for a field of that
  name and found none, so the program failed with `cannot read field 'MAX_VALUE' on java
  Class`. A class receiver is now read as a class, and only a field declared `static` is
  accepted — naming an instance field on the class is still refused, since there is no
  instance to read from. Found by probing the six interop behaviours the requirement matrix
  listed as unspecified, not by a failing test.
- **A Java varargs method accepts its trailing arguments.** `String.format("%s-%s", "a", "b")`,
  `String.join` and `Arrays.asList` were all refused with `has no static method 'format'`,
  because the bridge demanded one argument per declared parameter and a `T...` parameter is
  declared as a trailing `T[]`. Arguments beyond the fixed prefix are now packed into that
  array, including the empty tail — `String.format("no arguments")` used to fail twice over,
  once on arity and once on an out-of-bounds read in overload scoring. Overload selection is
  unchanged for fixed-arity calls and slightly disfavours a varargs match on a tie.
- **A failure inside an imported module quotes the module, not the importer.** The error
  excerpt was always drawn from the main program's source, so an error inside `boom.oq` was
  positioned at `boom.oq:2:11` but quoted line 2 of the program that imported it — a line the
  reader has no reason to connect to the message. The excerpt is now drawn only when the
  retained source is the file the error points at; otherwise the position stands alone. This
  affects `oq run` and the compiled jar alike. See
  [ADR-0029](docs/adr/0029-closures-and-modules.md).
- **Text counts code points, not UTF-16 units.** `"a😀b".length()` reported `4` for a
  three-character text, `.to_list()` and `.characters()` split the emoji into two lone
  surrogates (values that cannot be encoded as UTF-8), and `text[i]` returned half a
  character — while `.chars()` and slicing already counted code points. Every operation that
  measures, indexes, walks, slices or reverses text now agrees: `length()` is `3`, `[1]` is
  `"😀"`, `.reverse()` is `"b😀a"`, `.find()` returns a character offset, and
  `.substring()`/`.pad_*()` take character bounds. ASCII and BMP text are unchanged. See
  [ADR-0024](docs/adr/0024-text-is-a-sequence-of-code-points.md).

### Added
- **Encoding and hashing.** Two §17 domains were absent, and the requirement asks for both:
  `hex_encode`/`hex_decode` and `base64_encode`/`base64_decode` cover the "bytes, binary data,
  encoding, base64" line, and `sha256`, `sha1`, `md5`, `hmac_sha256` and `secure_random_hex`
  cover "cryptographic hashes; HMAC; secure random/secrets". There is no `bytes` type, so
  binary data is spelled as text — lowercase hex or padded standard base64 — and every function
  takes and returns text, hashing the UTF-8 bytes so the digest matches `sha256sum` for the
  same content. Every primitive is a JVM provider; nothing implements a hash here. A decoder
  reports the offset of a character outside its alphabet rather than raising an internal
  error, and refuses unpadded base64, which the platform decoder would otherwise accept as a
  truncated value. `secure_random_hex` uses `SecureRandom`, not `Random`. The reference states
  that `md5`/`sha1` are not safe for a secret and that hashing is not password storage. See
  [ADR-0033](docs/adr/0033-encoding-and-hashing.md).
- **Documentation comments.** A multi-line text literal standing before a `fun`, `data`,
  `class` or `contract` documents it, with JavaDoc-style tags: `@param`, `@return`, `@throws`,
  `@since`, `@author`, `@deprecated` and `@see`. The tags are parsed into a `Docstring` rather
  than left as text, and an unrecognised tag is kept under its own name instead of refused, so
  writing one is not an error. `oq doc --source <path>` renders a file, or every `.oq` file under
  a directory, from the comments its declarations carry; a declaration with none is listed as
  `_Undocumented._` and counted, so the page shows the gap as well as the content. A multi-line
  literal that does not precede a declaration stays an ordinary value, so no existing program
  changes meaning. See [ADR-0030](docs/adr/0030-docstrings.md).
- **Hover in the language server.** `oq-lsp` now answers `textDocument/hover` with the
  signature and documentation comment of the declaration under the cursor, rendered as
  markdown, and with the real arity of a builtin taken from the interpreter's table rather
  than a second copy of the signature. A position that names nothing declared returns `null`
  instead of an empty popup. Resolution is lexical, so shadowing and `use` imports are not
  followed. See [ADR-0031](docs/adr/0031-hover.md).
- **Modules and closures are specified.** `use "oq:name"` loads `name.oq` from beside the
  program, `modules/` or `lib/`; a file loads at most once, so import cycles terminate, and a
  module's top-level statements run before the importer's. There is one flat namespace: a
  module contributes its functions and types but not its locals, and a top-level name
  declared twice is an error at the importer. A `fun` inside a `fun` captures the enclosing
  bindings by reference, `const` included, so a closure sees later writes and a captured
  parameter outlives the call that created it. Both behaviours shipped earlier and worked;
  what was missing was the specification, which
  [ADR-0029](docs/adr/0029-closures-and-modules.md) and
  [the language spec](docs/spec/language.md#modules) now provide.
- **Immutable `data` records.** `data Point(x, y) immutable` fixes the fields at construction
  and makes the value usable as a map key or set member, where a mutable `data` value is
  refused. Equality, hashing and rendering are unchanged — the modifier removes the guard
  that stopped the existing structural hash from being reached, rather than adding a second
  one. A refused write names the field and shows the rebuild that replaces it, in the
  positional form the constructor accepts. A record is only as hashable as its contents, so
  one holding a list is refused as a key exactly as a tuple holding one is. Both the
  interpreter and compiled bytecode enforce it at the single write path, and `clone` carries
  the modifier across, so neither backend nor a copy can launder the value into a mutable
  one. `immutable` is contextual, so `immutable = 7` still names a variable. See
  [ADR-0028](docs/adr/0028-data-evolution.md).
- **Classes and contracts.** A `class` declares fields with defaults, methods that reach the
  object as `this`, and an optional `init` block that runs after the fields are filled.
  Instances are reference values: equality is identity, `type_of` is the class name, and
  printing names the object rather than its fields. A field starting with `_` is private to
  its class. `contract` lists the method names and arities a class promises through
  `implements`, checked when the class is declared, so a missing method fails where the
  mistake is rather than at the first call. `class`, `contract`, `implements`, `init` and
  `this` are contextual, so programs that use those names as variables keep working. There
  is no inheritance; polymorphism comes from contracts. Classes run on the interpreter for
  now: `oq build` reports `codepath: interpreter launcher (class 'C': classes are not
  compiled yet)` instead of silently falling back. See
  [ADR-0027](docs/adr/0027-classes-and-contracts.md).

- **`frozenset`, the immutable and hashable set.** `frozenset([1, 2])` holds the same
  operations as `set` minus the mutators, and is accepted as a map key or a set member
  wherever a `set` is refused. Every operation that returns a collection returns a frozenset,
  so `frozenset([1]).union([2])` stays immutable. `set(fs)` and `fs.to_set()` convert between
  the two. See [ADR-0022](docs/adr/0022-hashability-and-frozenset.md).

- **Collection operations now read every family.** `sum`, `min`, `max` and `sorted` accept any
  collection rather than only a list, so `sum(set([1, 2, 3]))` and `sum(range(0, 4))` work
  instead of reporting `set has no method 'sum'`. `min` and `max` take either one collection or
  several values. `sorted` returns a new list and leaves its argument alone.
  `[1, 1, 2].to_set()`, `"ab".to_list()`, `"aab".to_set()`, `(1, 2).to_set()` and
  `{"a": 1}.to_list()` fill in the conversion gaps; a map converts to its keys, matching what it
  iterates as. See [ADR-0021](docs/adr/0021-collection-family-operations.md).

- **`sort_by` reads any iterable and is stable.** It delegated to a `sort` *method*, which only
  a list has, so `sort_by(set([3, 1]), fn)` failed with `set has no method 'sort'` — naming a
  method the caller never wrote. It now reads the collection directly and sorts stably:
  `sort_by([(1, "a"), (1, "b"), (0, "c")], fn)` keeps `"a"` before `"b"`.

- **Set algebra is complete.** `.symmetric_difference`, `.issubset`, `.issuperset`,
  `.clone()` and `.copy()` join `.union`, `.intersection` and `.difference`. None of them change
  either operand.

- **Map `merge`, `update`, `items` and `pop`.** `m.merge(other)` and `m.update(other)` are the
  same in-place merge under two spellings. `.items()` yields `[key, value]` pairs that an
  indexing loop can consume, while `.entries()` keeps returning `Entry` values.
  `m.pop(key, default)` returns the default when the key is absent.

- **Iteration is a protocol.** Lists, tuples, sets, maps (keys), ranges and text now answer
  `.iter()`, which returns an `iterator` carrying a position. An iterator answers `has_next()`
  and `next()`; calling `next()` on a spent one is an error that says so, rather than returning
  `nothing`, because a collection may contain `nothing` and the two would be indistinguishable.
  `for` still snapshots (ADR-0016), while `.iter()` reads the live collection — that difference
  is the reason to have both, and it is documented. An iterator is a position rather than a
  collection, so `length`, indexing, slicing and `in` refuse it instead of consuming it, and
  `xs.next()` names the fix (`Call iter() first`). A `data` type joins the protocol by defining
  `iter`. See [ADR-0020](docs/adr/0020-iterator-protocol.md).

- **`enumerate(xs)`** pairs each item with its 0-based position: `enumerate(["a", "b"])` is
  `[[0, "a"], [1, "b"]]`.

- **`range(from, to, step)`** counts by a step, so `range(0, 10, 2)` is `0 until 10 by 2` and
  `range(5, 0, -2)` counts down. A step of `0` is refused with `a range step cannot be 0`. A
  range also grew a method surface it never had: `.to_list()`, `.to_set()`, `.first()`, `.last()`
  and `.contains(x)`, plus `.length()` and `.iter()`. `(0 until 3).to_list()` used to fail with
  `type range has no method 'to_list'` even though a range was already loopable.

- **Changing a collection while looping over it is well defined.** A loop now walks a snapshot
  of the collection taken when it started, so the body may `add`, `remove` or `remove_value` on
  the very collection it is visiting. This used to be a fatal bug: `for x in xs` followed by
  `xs.add(9)` reached `java.util`'s fail-fast check and died on exit code 70 with
  `internal error: ConcurrentModificationException ... This is a bug in Oqthara`, which no `try`
  could catch. The collection methods that take a callback (`map`, `filter`, `each`, `any`,
  `all`, `find`, `find_index`, `reduce`, `flatten`, and the map versions of `each` and `map`)
  snapshot the receiver too. Ranges and text behaved this way already.
  See [ADR-0016](docs/adr/0016-iteration-snapshots.md).


- **`try` as an expression.** `try ... catch ... end` may now be used wherever a value is
  expected, not only as a statement: `n = try / number(text) / catch err / 0 / end`. The value is
  the value of the branch that ran, taken from that branch's final statement when it is an
  expression, and `nothing` otherwise. Both the interpreter and the compiled backend produce the
  same value; a `try` nested inside a larger expression compiles too, because the lowerer spools
  operands into locals so `try_start` still begins from an empty operand stack. See
  `docs/adr/0014-try-as-an-expression.md`.

- **Function overloading by parameter count.** A name may now carry several definitions as long
  as they differ in arity, and a call selects the definition whose parameter count matches. This
  replaces a silent data-loss defect: the second `fun area(...)` used to overwrite the first, so
  `area(2, 3)` failed with "area() expects 1 argument, got 2" and the one-parameter body was
  unrecoverable. Resolution is deterministic, independent of declaration order, and identical in
  the interpreter and in compiled bytecode. Two definitions with the same arity are now rejected
  where the second is declared, and a user definition replaces a built-in of the same name
  instead of colliding with it. Oqthara has no parameter type annotations, so arity is the only
  discriminator; the rule is specified in [ADR-0012](docs/adr/0012-overload-resolution.md).
- `arity()` on `OqCallable`, with `VARIADIC` for callables that accept no single parameter count,
  so a variadic built-in can never silently win an exact-arity resolution.
- **Destructuring assignment and binding.** `a, b = (1, 2)` binds or updates several names from
  one value, and `const c, d = ...` binds them read-only. The value may be a tuple, a list, a
  map (yielding keys), or text (yielding characters); the number of values must match the number
  of names exactly. All names are checked before any is written, so a failed destructuring leaves
  every target unchanged rather than half-updated. Destructuring is a binding operation, not
  pattern matching: there is no rest target, `_` is not a discard, and `(a, b) = x` remains a
  tuple literal on the left and is rejected. The rule is specified in
  [ADR-0013](docs/adr/0013-destructuring.md).

### Documentation

- **Three formal state-machine models of the language, the compiler and the runtime, and the
  contract that binds them**, under `docs/spec/states/`. `01-language.md` models execution — states `S0`–`SX`, transitions
  `T1`–`T38`, invariants `L1`–`L12` — covering evaluation, binding and scope, control flow, error
  propagation and termination. `02-compiler.md` models `oq build` — states `S0 Accept`–`SX
  Rejected`, transitions `C1`–`C39` across the front end, middle end, back end and artifact
  stages, invariants `K1`–`K13`. `03-runtime.md` models a running program — states `R0 Cold`–`R10
  Shutdown` plus `RX Failed`, transitions `E1`–`E47` across values, collections, task lifecycle,
  the host boundary, resource budgets and shutdown, invariants `M1`–`M13`. All three document the
  states a program can reach, the moves between them, the properties that hold in every state, and
  the invalid transitions with the exact diagnostic each produces. `04-unified.md` states the
  cross-backend contract as `U1`–`U9` and the four measured divergences as `D1`–`D4`: which models
  a compiled jar actually satisfies, and where the two execution paths are allowed to differ.
  Every diagnostic quoted in any of them was measured against a running build, not inferred from
  source.
- `03-runtime.md` records five defects the model exposed, none of them fixed here. Container
  equality does not widen numeric element types, so `[1] == [1.0]` is false while `1.0 in [1]` is
  true and `set([1, 1.0])` holds two elements that both report as members. Objects from the
  standard library that are not Oq values pass through the bridge unwrapped, so `LocalDate.now()`
  reports its type as `java LocalDate` rather than as an Oq type. `spawn` allocates an executor per
  call and never closes it. There is no way to cancel a task or to join one: an unawaited task is
  silently abandoned when the program exits. And `Interpreter.depth` is a plain `int` shared by
  every virtual thread, so two concurrent tasks interleave their increments and can report a stack
  of depth two as `call stack is too deep` — a false diagnosis that tells the programmer to look
  for infinite recursion that is not there.
- **`04-unified.md` documents four divergences between the two execution paths** that the
  specification previously denied by omission. A compiled jar cannot quote the offending source
  line, because it does not carry the program text. It has no step budget and no recursion depth
  ceiling, so `while true` compiled runs until the machine stops it. A task that fails and is
  never awaited is discarded without a word. And no task can be cancelled or joined. `L12` and
  `M10` in the other two documents were corrected to say what is true rather than what was
  assumed.
- **A register of the fifteen semantic and runtime contracts** the directive asks for, in
  `docs/spec/contracts.md`, with the version each is at and the test that enforces it. Three areas
  have no contract at all — modules and imports, docstring metadata, and `class` — and the
  register says so rather than implying coverage that does not exist. `check-docs-sync.py` now
  fails if the register loses a row or names a document that has been deleted.
- **`docs/spec/design.md` was stale in four ways.** It listed 20 keywords when the lexer defines
  21; it pointed at `STATUS.md`, a file that does not exist; it claimed the keyword list "is
  checked by a test in `oq-core`", when no such test exists — the check lives in
  `scripts/check-docs-sync.py`; and its architecture diagram described the bytecode backend as
  future work that "will consume the same AST", when that backend has shipped and consumes the
  intermediate representation instead. The diagram now shows both execution paths. A third
  keyword check was added to `check-docs-sync.py` so `design.md`'s own count cannot drift again.
- **`oq check` was documented as more than it does.** `docs/reference/cli.md` said it "parses and
  checks a program", but `Main.check` only parses: `break` outside a loop, an undefined name and a
  read of an undefined variable all print `OK` and exit 0. The page now says it is a syntax check
  only and points at `oq run` for the rest. The gap itself is recorded as a defect in
  `docs/spec/states/02-compiler.md` rather than papered over.
- `docs/adr/0002-ci-split.md` no longer points at the removed `STATUS.md` and `RESUME.md`.

### Added

- **Slice syntax.** `xs[from:to]` and `xs[from:to:step]`, with either bound omittable
  (`xs[:3]`, `xs[2:]`, `xs[:]`). A negative bound counts from the end. A step walks the
  result, and a negative step reverses it, so `xs[::-1]` reverses a list. Slicing a list,
  tuple, range or set gives a list; slicing text gives text. Bounds are clamped to the
  sequence, so `xs[0:999]` is the whole list, but a step of `0` is refused. `[1,2,3][0:2]`
  previously failed to parse at all. See [ADR-0019](docs/adr/0019-slicing.md).

### Fixed
- **A refused key is reported where the program wrote it.** The refusal is raised inside the
  collection, which holds no source position, so it fell back to the last marked span — and
  the interpreter marks none, so `m[[1]] = 2` reported `<unknown>:1:1` and pointed at the
  program's first line. The set and map literal paths did the same on both backends. The span
  is now threaded through, so each reports the line and column of the offending expression.

- **Mutating a collection while a live iterator walks it is an ordinary error, not an internal
  one.** `.iter()` reads the live collection (ADR-0020), so the mutation is genuinely invalid —
  but `java.util`'s fail-fast check escaped as
  `internal error: ConcurrentModificationException ... This is a bug in Oqthara`. That blames
  the language for something the program did, and leaves nothing to act on. It now reports
  `the collection changed while this iterator was walking it` with the source position and a
  hint to use `for`, which walks a snapshot. `for` is unaffected.

- **A mutable value can no longer be a map key or a set member.** Hashing is structural, so
  inserting a list and then mutating it left the entry present but unreachable: `length(m)`
  still counted it and `repr(m)` still printed it, but no lookup could find it again. The same
  applied to sets, maps and `data`. These are now refused up front:
  `{[1]: "a"}` reports `a list cannot be used as a map key`, and `set([[1]])` reports
  `a list cannot be used as a set member`. Nested tuples are walked, so `{([1],): "a"}` is
  refused too. This is **breaking** for any program that relied on the old behaviour, and no
  conformance program does; the suite is unchanged at 0 divergences. A mutable collection that
  must be a key converts to a `frozenset` (sets) or a tuple (lists of fixed length).

- **The generated method listing no longer promises methods a family refuses.** `oq doc`
  counted `case "x" -> null` arms as available methods and double-counted a nested switch, so
  `docs/stdlib/reference.md` listed `add` under frozensets and `sum` twice under ranges. It now
  counts only the outermost arms and skips refusals.
- `reverse()` keeps the receiver's family. `range(0, 3).reverse()` returned a **list**, while
  `list`, `text`, `set` and `frozenset` all preserve theirs; a reversal cannot make an immutable
  value mutable, so it must not widen a `range` either. It now returns the `range` `2 to 0 by -1`,
  and reversing twice returns the original. This is the same rule `clone()` follows.

- `clone()` no longer changes a value's family. `(1, 2).clone()` returned a **list**, so cloning
  an immutable value produced something mutable; it now returns a tuple.

- **`slice` and `subList` resolve negative bounds instead of ignoring them.** Both folded
  every negative bound to `0` through `clampIndex`, so `xs.slice(-2, 5)` returned the whole
  list rather than its last two items, and `xs.slice(0, -1)` returned `[]` rather than
  everything but the last item. A negative bound now counts from the end, as `xs[-1]` does,
  and is then clamped; `xs.slice(0, 999)` still returns the whole list.
- **Index operations share one rule, and an out-of-range `insert` no longer crashes.** `insert`
  handed an unchecked index to `ArrayList.add`, so `l.insert(99, 0)` exited 70 with
  `internal error: IndexOutOfBoundsException` and `This is a bug in Oqthara`: a user mistake
  was reported as a defect in the language, and no `catch` could reach it. `remove(99)`
  returned `nothing` and removed nothing, so a caller could not tell success from failure, and
  `remove(-1)` did nothing although `l[-1]` works everywhere else. `remove` also accepted a
  value as a second spelling of `remove_value`, making `l.remove(1)` ambiguous.
  `l[i]`, `.get(i)`, `.insert(i, x)` and `.remove(i)` now all accept a negative index and all
  refuse a position that does not exist with the same located error; `insert` additionally
  accepts the length, because appending names a position. `take`, `skip` and `subList` still
  clamp, since their argument is a count or a bound rather than a position. See
  [ADR-0018](docs/adr/0018-index-semantics.md).
- **`clone` and `copy` on a map return a map.** They returned the JDK `LinkedHashMap`, so the
  copy lost the equality and hashing contract added in ADR-0017 and `m.clone()[1.0]` did not
  find a key stored as `1`.
- **`==` and hashing now agree at every depth.** `1 == 1.0` was `true`, but `[1] == [1.0]`,
  `(1,) == (1.0,)` and `{1: "a"} == {1.0: "a"}` were all `false`, while
  `length(set([1, 1.0]))` was `2` and `{1: "a"}[1.0]` raised. The numeric rule widened at the
  top level and then fell through to `Object.equals`, and sets and maps hashed raw `Long` and
  `Double`, which hash differently. Equality is now structural at every depth and hashing
  agrees with it, so a set cannot hold two equal values and a map finds a key whichever
  numeric representation wrote it. `unique`, `union`, `intersection`, `difference` and
  `has_value` had each rebuilt that rule by hand and now use the single definition. A `data`
  value also compared its fields with `Object.equals`, so `P(1, 2) == P(1.0, 2)` was `false`;
  it now uses the same rule, which also applies inside collections. A self-referential
  collection now reports
  `this collection contains itself, so it cannot be hashed or compared` and exits 1 instead of
  exiting 70 with an internal error. See [ADR-0017](docs/adr/0017-equality-and-hashing.md).
- **A `const` can no longer be defeated by redeclaring it.** `const x = 1` followed by
  `const x = 2` in the same scope used to replace the binding, so `x` printed `2` while any
  further `x = ...` was still refused: the constant looked enforced and was not. Declaring a
  name twice in one scope is now refused, on both backends, with
  `'x' is already declared in this scope`. The same rule covers `const a, b = ...`, which
  declares names too and used to bypass it; a refused destructuring still writes nothing.
- **Three collection builtins no longer crash on a set.** `zip({1, 2}, {3, 4})`,
  `random_item({1})` and `par_map({1, 2}, f)` cast the result of `Values.iterable` straight to
  `List`, so any set argument threw `ClassCastException` and exited 70 with
  `internal error ... This is a bug in Oqthara`. A list, a range and text happened to satisfy the
  cast, which is why the defect survived: only the set path was ever wrong.
- **A callback argument of the wrong type is now the program's error.** `[1, 2].reduce(0, f)` —
  the arguments reversed, since the shape is `reduce(fn, start)` — cast `0` to `OqCallable` and
  reported an internal error. It now says `'reduce' needs a function as argument 1` and suggests
  `reduce(fn, start)`. The same applies to `sort`.
- **The conformance verifier now gates CI and tells the truth.** `scripts/verify-conformance.py`
  pinned a stale corpus size (197 executable programs) and a floor (182 compiled) that sat ten
  programs below reality, so it failed on a healthy tree while a ten-program bytecode
  regression would have passed. It pins the measured baseline (207 executable, 192 compiled)
  and now runs as a required CI step, which also covers the interpreter-against-jar comparison
  that previously ran nowhere. Its decision rules have regression tests in
  `scripts/tests/test_verify_conformance.py`.
- **`CONTRIBUTING.md` no longer understates the keyword count.** It claimed 18 keywords when
  the lexer defines 20; `try` and `catch` landed without the count being updated.
  `scripts/check-docs-sync.py` now reads `Lexer.KEYWORDS` directly instead of restating the
  names, and fails when `CONTRIBUTING.md` disagrees with the lexer in either direction.
- A range's length is documented as 64-bit. `docs/spec/language.md` now states that
  `length(r)` does not narrow through a 32-bit integer, and `StdlibCoverageTest` asserts
  `length(0 to 3000000000)` at the language level. Both were missing; the behavior itself was
  already correct.

## [0.1.0-alpha.3] - 2026-11-06

### Added

- **Tuples.** One comma makes a tuple: `(1, 2, 3)`, `(5,)` for a single value, and `((1, 2), 3)`
  to nest. `(a)` is still grouping, so parentheses keep their old meaning, and `()` is an error
  rather than an empty tuple. A tuple is immutable: indexing, `length`, iteration,
  `contains`, equality and lexicographic ordering all work, and every list mutator
  (`push`, `pop`, `sort`, `add`, `remove`, `remove_value`, `insert`, `reverse`, `clear`, `fill`,
  `shuffle`, `sort_by`, and assignment to an index) refuses with `a tuple is immutable` instead
  of silently copying. The names that only exist as `java.util.List` methods — `set`, `addAll`,
  `removeAll`, `retainAll`, `removeIf`, `replaceAll` — refuse the same way rather than leaking
  a Java exception. `.unique()` returns a tuple, `t.subList(0, 2)` returns a tuple slice, and a
  tuple can hold `nothing`. A tuple is never equal to a list, so `(1, 2) == [1, 2]` is `false`
  in both backends. Tuples compile to real bytecode, so `oq build` matches `oq run`, including
  a tuple passed as a function argument. The decision is
  recorded as `OQ-DEC-2026-006` in [the governance log](docs/governance/README.md).
- The specification now states operator precedence as a ten-level table read out of the
  parser, including the divergence that unary binds tighter than `**`, so `-2 ** 2` is `4`
  rather than Python's `-4`. It also specifies `format`, `write`, compound assignment and the
  `in`/`to` interaction that requires parentheses.

### Changed

- Host calls are bounded. `print`, `write` and `write_text` stop at 16 MiB of output, refused
  before anything is written; `read` and `read_lines` refuse a file over 16 MiB by capping the
  bytes read, so a pipe or `/dev/zero` cannot slip past; `input()` caps a line at 16 MiB
  instead of building it unbounded; `run_command` gives up after 30 seconds of wall clock in
  total and refuses output over 16 MiB, killing the whole process tree rather than leaking the
  children it started; `http_get` and `http_post` bound the body as well as the headers, so a
  server that stalls mid-body cannot hang the program, and stop at 16 MiB instead of
  buffering; `sleep` is capped at 10 seconds per call; `save` and `append` refuse more than
  16 MiB in one call; `list_files` caps a directory at 100,000 entries; `sleep` is bounded at
  60 seconds in total, because the per-call cap alone let a loop wait forever ten seconds at a
  time. Output has its own 16 MiB counter, and the newline `print` adds and the prompt `input`
  writes count against it, so neither is a way to emit unbounded bytes. That counter covers
  output only — reading a file does not draw on it, because reading is not writing output.
  The transfers that do bill the step budget are charged on top of their per-call limits, in
  the unit each one actually moves: bytes for `read`, `save`, `append`, HTTP and `run_command`,
  characters for `input`, directory entries for `list_files`. Output is the exception — `print`,
  `write` and `write_text` are bounded by the output counter and charge no steps. The older
  256-steps-per-byte rate made the documented 16 MiB unreachable, because `read` tripped the
  50,000,000-step budget at 195,312 bytes and reported `program ran for too long` instead of
  the size error.
  The specification states these limits and states plainly that they are resource controls,
  not a sandbox — and that a jar built as pure compiled bytecode has no step budget or
  deadlines, only the 16 MiB output cap, while a jar built as an interpreter launcher
  enforces every limit.

### Fixed

- `run_command` leaked the processes a timed-out command had started: only the direct child
  was destroyed. The whole tree is now killed and the program reports
  `command timed out after 30 seconds`.
- The conformance harness accepted an error message that merely contained the expected text,
  so an error that gained a prefix, lost a detail, or reported a different problem still
  passed. It now compares the whole first line and requires the diagnostic to carry a
  position.
- The compiled conformance count in `docs/spec/backend.md` drifted from what the suite
  measured; the documentation checker now compares it against both the test and the corpus.
- A jar built as compiled bytecode printed a raw `OutOfMemoryError` stack trace when the heap
  ran out, and compiled jars in general let any non-`OqError` failure escape the same way. The
  generated top-level handler now catches `Throwable` and routes the heap and stack cases
  through the renderer `oq run` already used, so both backends print `the program ran out of
  memory` and exit 1.
- `"ab" * 4611686018427387905` silently returned `ab`. The repetition product overflowed a
  `long`, slipped past the length check as a negative number, and repeated once. Both text and
  list repetition now use an exact product and refuse with `the repetition is too large to
  build`.
- The REPL never reset the step budget, so a long session eventually refused every command it
  had left. Each command now starts with the budget it would have on its own.
- `"" * 3000000000` failed with `internal error: IllegalArgumentException: count is negative`,
  and `[] * 4611686018427387905` looped over four quadrillion empty iterations. Repeating an
  empty text or list now returns empty immediately, without consulting the size guard that an
  empty value makes meaningless.
- `subList` was not part of the list API, so it fell through to `java.util.List.subList` and
  returned a live view of a JDK-private class. On a mutable list that view wrote through to the
  parent; on a tuple, mutating it leaked either `IllegalAccessException` or, for `sort`, a
  sorted list — a quietly mutable tuple. `subList` and `slice` are now real methods that return
  a copy, clamped to the sequence and keeping their type, so `t.subList(0, 2)` is a tuple and
  every mutator on it refuses with `a tuple is immutable`.

### Documentation

- `run_command` is documented as taking one executable path with no arguments and no shell,
  which is what it has always done and what its tests now assert.
- The version declaration agrees across the CLI, the build, the LSP server, the specification
  header and this changelog, and the documentation checker now compares the specification
  header and the LSP server so they cannot drift again.

## [0.1.0-alpha.2] - 2026-11-05

### Added

- `oq build` compiles lambdas that capture nothing, and passes them to stdlib methods:
  `double = x -> x * 2` and `xs.map(x -> x * x)` now produce real bytecode. Each lambda
  becomes one static method on the generated class plus a `runtime.CompiledFunction` value
  holding that method's name, so the call dispatches through the value rather than through a
  name, and a builtin with the same name cannot win. `Methods` now dispatches an
  `OqCallable` instead of refusing every callback. Conformance programs compiling to
  bytecode rose from 169 to 182 of 197 with zero divergences. A lambda that captures an
  enclosing variable is still refused: capturing would copy the value at creation time
  while the interpreter reads it at call time.
- `oq build` now dispatches deterministic stdlib builtins from generated bytecode:
  `abs`, `floor`, `ceil`, `round` (both arities), `sqrt`, `pow`, `min`, `max`, `clamp`,
  `number`, `text`, `type_of`, `is_nothing`, `is_number`, `is_text`, `upper`, `lower`,
  `trim`, `split`, `join`, `replace`, `contains`, `format`, `set`, `list`, `zip`, `keys`,
  `values`, `sum`, `sort`, `reverse`, `unique`, `push`, `pop`, `clone`, `range`, `assert`
  and the JSON builtins (`json_parse`, `json`, `json_write`, `json_pretty`). Generated code
  calls `stdlib.Builtins`, which installs the real `Stdlib` and invokes the same
  `NativeFunction` the interpreter uses, so no builtin is implemented twice. Variadic
  `print a, b` and `write a, b` also compile. Conformance programs compiling to bytecode
  rose from 145 to 169 of 197, with zero divergences from `oq run`. Builtins that depend on
  the host — files, clock, randomness, network, process state, `args`/`input`, and the
  callable-taking `spawn`/`await`/`par_map`/`map`/`filter` — are still refused by name and
  fall back to the launcher jar.
- A compiled jar now renders an uncaught `OqError` instead of a Java stack trace. The
  generated `main` carries one exception-table row over its whole body that hands the error
  to `EmbeddedMain.renderUncaught`, which uses the same `Details`/`Diagnostics` renderer as
  `oq run`; message, `file:line:column` and hint are byte-identical and the exit code is 1.
  The source line and caret are omitted because a compiled jar embeds no source. A program's
  own `try`/`catch` still wins for its range, so caught errors stay caught. Source position
  marks are now emitted for every operation that can raise, not only inside `try`, which is
  what makes an uncaught error report the interpreter's exact location.
- `oq build` compiles collections to real JVM bytecode: list, set and map literals,
  `xs[i]` reads, `xs[i] = v` writes, `length(x)` over text/list/set/map/range, `1 to 5` as
  a value, `x in collection`, and `for x in list / set / map / range / text` using a real
  iterator. Conformance programs compiling to bytecode rose from 52 to 72 of 197, still with
  zero divergences from `oq run`. Collections reuse the interpreter's own `Values` methods
  through `runtime.Collections`, so the backends cannot drift. Method calls (`xs.push(4)`),
  `data` and other builtins are refused by name and fall back to the launcher jar.
- `fuzzStdlibBuiltins` fuzzes the pure stdlib builtins, and `fuzzJsonParse` now round-trips
  through both JSON writers and bounds nesting. Bounded by `@FuzzTest(maxDuration)`, never run
  by `./gradlew build`.
- Fixed: `write "a"` produced bytecode that underflowed the operand stack, because the
  lowering popped a value the void call never pushed. Found while measuring the collections
  subset; now covered by a regression test.
- `oq build` compiles top-level `fun` declarations to real static JVM methods: parameters,
  return values, recursion and mutual recursion. Every parameter is one boxed `Object`, the
  descriptor is `(Ljava/lang/Object;...)Ljava/lang/Object;`, calls are `invokestatic` and
  `return` is `areturn`. Conformance programs compiling to bytecode rose from 41 to 52 of
  197, with zero divergences from `oq run`. A function that reads or writes a top-level
  variable, and a function named `print` or `write`, is refused by name and falls back to
  the launcher jar.
- Coverage-guided fuzzing with Jazzer 0.30.0: four targets covering the lexer, parser,
  nesting depth and the full compile pipeline, each in its own Gradle task and a dedicated
  `jazzer-fuzz` CircleCI job. A bounded run executes ~1.7 million cases.
- A curated regression corpus replayed by `CuratedCorpusTest` on every build.
- Fuzz targets for the JSON reader and writer, plus JSON nesting depth.
- Fixed: `json_parse(".")` escaped as a raw `NumberFormatException`; malformed numbers now
  produce a JSON syntax error. Found by fuzzing.
- Fixed: 390+ levels of nested parentheses, and 2000 nested blocks, crashed the checker with a raw
  `StackOverflowError` and exit 70. Found by fuzzing; now a normal diagnostic. The parser
  enforces a 128-level limit for both expressions and statement blocks.
- `oq build` compiles straight-line arithmetic, local variables (assignment, reassignment
  and reads), text concatenation and `print` to real JVM bytecode when the whole program is
  in that subset, embedding no source; anything else falls back to the launcher jar with a
  printed reason. Verified instruction-for-instruction with
  `javap` and by running the generated jars on JDK 21 and 25.
- `oq build` now produces an executable jar: a real class file written by Oqthara's own
  emitter, the interpreter and stdlib bundled in, the program source and its original name
  embedded. `java -jar app.jar` matches `oq run app.oq` exactly across all 28 deterministic
  examples. Works on JDK 21 and 25, with no external library.
- Relative file paths resolve against the program's directory, so runs are identical from
  any working directory.
- Generated jars pass program arguments through (`args()` works), encode the constant pool
  as modified UTF-8 so emoji survive, embed module sources, and reject unsafe jar entry
  names.
- `oq build` now lowers `break` and `continue` to real labeled jumps; nested loops target
  the innermost loop and the divergence warning is gone.
- `docs/spec/backend.md` records exactly what `oq build` does not do yet (no class files,
  placeholder lambdas, no interop or modules).
- `oq doc` generates `docs/stdlib/reference.md` from the implementation; CI fails when the
  checked-in copy is stale.
- Keyword count corrected from 18 to 20 in every document (the lexer is authoritative).
- `oq fmt` rewritten: canonical indentation and spacing, idempotent, output always parses,
  verified against every example.
- VS Code extension in `editors/vscode`: TextMate grammar with interpolation support,
  14 snippets, indentation rules, and tests that keep it in sync with the lexer.
- Distribution: `scripts/install.ps1`, a multi-stage `Dockerfile`, and
  `./gradlew :oq-cli:packageApp` producing a native launcher with an embedded JRE.
- `oq test` discovers `test_*.oq`, `*_test.oq` and anything under `tests/`, prints a
  pass/fail line per file, and exits non-zero when a test fails.
- `examples/tests/` contains the first language-level test suite.
- `try`/`catch` with an error value exposing `message`, `hint`, `file`, `line`, `column`.
- `round(value, places)` for rounding to decimal places.
- Lexicographic list comparison, so a sort key may be a list.
- 33 runnable examples, including a calculator, a BFS maze solver, a Sudoku validator,
  a Tower of Hanoi solver and a memoisation helper.
- Modules: `use "oq:name"` loads another `.oq` file, searched next to the program, then in
  `modules/`, then in `lib/`; loaded once, cycle-safe, with errors that name the module.
- `spawn`, `await`, `done` and `par_map` running on virtual threads, with task errors
  re-raised at the `await` site.
- Three more examples (anagrams, BST, queue) and a regression suite where every test names
  the real program that found the bug.
- Data field defaults are evaluated per instance; a mutable default such as `items = []`
  no longer aliases between objects.
- Interpolation now works when the expression starts with a quoted string, while JSON-like
  text such as `"{\"a\": 1}"` stays literal.
- `to` and `until` are contextual keywords, so they can be used as variable and parameter
  names (`fun hanoi(n, from, to, via)` now parses).
- `break` and `continue` outside a loop now produce a user error at the right line instead
  of an internal error with exit code 70.
- Clearer hints for comparison, negation and iteration type errors.
- Deterministic parser and lexer fuzzing: 7,000 random inputs must either parse or raise an
  `OqError`, never crash.
- `oq build` reports every known IR divergence (`break`, `continue`) as a warning instead
  of silently producing different behaviour from the interpreter.
- `oq build` with AST-to-IR lowering, an IR disassembler (`--emit-ir`), and runtime
  backend detection: on JDK 24+ it selects the classfile backend, and on JDK 21-23 it
  explains the requirement and exits with code 3.
- New `bench-jmh` Gradle module with JMH benchmarks for steady-state interpreter
  throughput (`numericLoop`, `fib`, `listOps`, `stringBuild`). Each benchmark parses its
  program once, runs it on a fresh CLI-equivalent interpreter writing to an in-memory
  sink, and asserts the exact expected output before measuring. The module is not wired
  into `check`/`build`, and a JUnit test re-checks every workload without running JMH.

### Fixed

- Numeric builtins now reject a non-number with the usual `error: ... needs a number`
  diagnostic instead of escaping as a raw `IllegalArgumentException`, which the CLI rendered
  as `internal error ... This is a bug in Oqthara` and exit 70. `range` was the worst case
  because it converted its arguments directly. `abs(Long.MIN_VALUE)` now widens to a decimal
  instead of returning a negative number, and `floor`/`ceil`/`round` keep a value beyond
  `Long.MAX_VALUE` as a decimal instead of saturating. The JSON writer refuses non-finite
  numbers, which it previously wrote as `Infinity`/`NaN` even though its own reader rejects
  them, and the reader rejects a number with no finite value such as `1e400`.
- `f(f(f(...)))` nested thousands deep crashed with a raw `StackOverflowError` instead of a
  diagnostic: the parser released its nesting counter before parsing a call's arguments, so
  this shape never accumulated depth. The counter is now held across the argument list.
- `print` and `write` now compile when used as expressions, yielding `nothing`, so
  `x = print(1)` and `print(1) + 1` behave exactly as under `oq run` instead of falling back
  to the interpreter launcher.

## [0.1.0-alpha.1] - 2026-11-04

### Added

- The Oqthara language: expressions, variables, conditionals, loops, functions,
  closures, lambdas and `data` records.
- `end`-terminated block syntax, newline statement separators, optional semicolons.
- Text interpolation with `{expression}`, triple-quoted multi-line text, and escaped
  braces.
- Collections: list, map and set literals with a rich method surface (`map`, `filter`,
  `reduce`, `sort`, `join`, `sum`, `unique`, `flatten`, set algebra).
- Ranges with `to` and `until`, usable in `for` loops and as values.
- First-class functions, hoisted declarations and lexical closures.
- Java interoperability: `use java.time.LocalDate`, constructor calls, instance
  methods, static methods, and automatic conversions for common types.
- Standard library: text, files, JSON, time, HTTP, processes, math and randomness.
- Diagnostics with file, line, column, source excerpt, caret and a human hint.
- CLI: `oq <file>`, `run`, `check`, `repl`, `fmt`, `new`, `version`, `help`.
- 130 conformance cases plus unit tests for the lexer, parser, values and interpreter.
- GitHub Actions CI across Linux, macOS and Windows on JDK 21 and 25.

[Unreleased]: https://github.com/Oqthara/oqthara/compare/v0.1.0-alpha.3...HEAD
[0.1.0-alpha.3]: https://github.com/Oqthara/oqthara/releases/tag/v0.1.0-alpha.3
[0.1.0-alpha.2]: https://github.com/Oqthara/oqthara/releases/tag/v0.1.0-alpha.2
[0.1.0-alpha.1]: https://github.com/Oqthara/oqthara/releases/tag/v0.1.0-alpha.1
