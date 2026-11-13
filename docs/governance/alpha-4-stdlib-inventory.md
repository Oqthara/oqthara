# Alpha 4 standard-library inventory — measuring breadth against a denominator

The Alpha 4 requirement matrix records §17 (broad standard library) as `PARTIAL`, and its
stated gap is not a missing feature but a missing unit: *"Still unmeasured against the Python
reference, so 'breadth' has no denominator."*

This document supplies that denominator. It does not add a builtin, close a row or change any
code. It answers three questions with numbers rather than adjectives:

1. What does Oqthara actually have? Measured from the source, not from documentation claims.
2. What does the Python 3 standard library actually contain, by domain? Measured from the
   official index, not from recollection.
3. Given both, how much of the reference surface is covered, partly covered, absent, and
   deliberately excluded — and which absences are worth closing first?

Scope: the language as it exists at `5f7b868`, the merge of PR #93, together with the
`textwrap`/`difflib` builtins added in PR #94. Nothing here is a commitment. Every forward
statement is written as *proposed*, with acceptance criteria attached, because a planning
document that promises is a document that cannot be checked.

## Method

- **Oqthara side measured from source.** Counted registration sites in
  `oq-stdlib/src/main/java/dev/oqthara/stdlib/Stdlib.java`,
  `DatabaseModule.java`, `Patterns.java` and `MethodResolverImpl.java`, then reconciled
  name-for-name against
  the generated surface in [`docs/stdlib/reference.md`](../stdlib/reference.md). The two agree
  exactly, in both directions, with zero drift: 172 global names and 205 documented method
  entries on each side. Every name cited in this document was found by grep in the files named
  against it, and `check-docs-sync.py`'s `stdlib_inventory_citations_resolve` re-resolves every
  one of them on every run, because the sentence you are reading was not true when it was first
  written: line numbers had drifted repeatedly — the import that added `Errors` to `Stdlib.java`
  shifted every registration below it by one on its own — and `check-docs-sync.py` caught each
  drift only after the fact. A number
  can stay right while every pointer into the file goes wrong, which is why the pointer is now
  checked rather than asserted.
- **Python side measured from the index.** Parsed the table of contents of
  <https://docs.python.org/3/library/index.html>, consulted 2026-11-10. That page yields 34
  top-level chapters and 243 links.
- **Counting rule.** A link counts as one module if it resolves to its own documentation page.
  Fragment anchors fold into the page that documents them, so `curses.textpad` counts inside
  `curses` and the eight `test.support.*` anchors count inside `test`. Of the 243 links, 232
  are distinct pages and 11 are fragments; one page (`unittest.mock-examples`) is a worked
  example rather than a module. **232 − 1 = 231 distinct documented modules.** The
  Introduction chapter is a single anchor with no module of its own and is not counted.
- **Domains.** The 30 domains below are exactly the 30 chapters of the index that document at
  least one module (34, less 3 empty chapters — CLI modules, Removed Modules, Security
  Considerations — less the Introduction). No chapter is merged or split, so the denominator
  can be re-derived from the index by anyone.

## Step 1 — the measured Oqthara inventory

### Headline numbers

| Measure | Value | Evidence |
|---|---|---|
| Global builtin **names** | **172** | 143 in `Stdlib.java` + 3 in `DatabaseModule.java` + 2 in `Patterns.java` + 12 in `Statistics.java` + 6 in `Compression.java` + 6 in `TextWrap.java` |
| Callable **registrations** | **183** | 9 names are overloaded, adding 11 extra registrations (`range` and `csv_parse` have three each): `assert`, `await`, `csv_parse`, `csv_write`, `exit`, `input`, `random`, `range`, `round` |
| Method **families** | **9** | `MethodResolverImpl.java`, one method per family |
| Documented method names | **205** | `docs/stdlib/reference.md` L190–L226 |
| Host-dependent builtins | **36** | `oq-stdlib/.../Builtins.java:45` (`HOST_DEPENDENT`), the compiled-code bridge |
| Builtins exercised by `examples/` | **67 of 172** | 42 `.oq` files, checked by name |

The matrix carried "89 global functions and eight method families" when this document was
drafted. That figure was measured at an earlier commit and had been stale since the date builtins
landed; the matrix has since been corrected to the numbers above. The 89 was never the generated
count — even the reference produced when it was written already listed 98.

### Where each global is registered

Installation is one call, `Stdlib.install` at `Stdlib.java:103`, which delegates to one
private installer per section. Each row below gives the installer's declaration line, so a
name can be located by section and then by the line numbers in the following table.

| Section | Installer | Registrations | Unique names |
|---|---|---|---|
| Core | `Stdlib.java:128` | 26 | 21 |
| Text | `Stdlib.java:246` | 10 | 10 |
| Collections | `Stdlib.java:300` | 14 | 14 |
| Math | `Stdlib.java:380` | 31 | 29 |
| Files | `Stdlib.java:513` | 13 | 13 |
| File operations | `Stdlib.java:629` | 7 | 7 |
| Patterns | `Patterns.java:39` | 2 | 2 |
| Statistics, identifiers, ordered search | `Statistics.java:32` | 12 | 12 |
| Text layout | `TextWrap.java:26` | 6 | 6 |
| Compression | `Compression.java:49` | 6 | 6 |
| JSON | `Stdlib.java:763` | 4 | 4 |
| Encoding | `Stdlib.java:782` | 11 | 11 |
| Logging | `Stdlib.java:897` | 6 | 6 |
| Arguments | `Stdlib.java:944` | 1 | 1 |
| CSV | `Stdlib.java:1094` | 5 | 2 |
| Dates | `Stdlib.java:1252` | 2 | 2 |
| Time | `Stdlib.java:1279` | 6 | 6 |
| URLs | `Stdlib.java:1337` | 6 | 6 |
| HTTP | `Stdlib.java:1520` | 2 | 2 |
| Concurrency | `Stdlib.java:1676` | 6 | 5 |
| Process | `Stdlib.java:1742` | 4 | 4 |
| Database | `DatabaseModule.java:44` | 3 | 3 |
| **Total** | | **183** | **172** |

### The 172 names, by registration site

Each entry is `name` at `Stdlib.java:line` unless marked `DB:`, which means
`DatabaseModule.java:line`. Overloads appear once per registration line, so a name with three
lines is one name and three registrations.

**Core** (21) — `print`:129, `write_text`:142, `write`:149, `input`:156 and 160, `text`:168, `repr`:170, `number`:172, `type_of`:174, `is_nothing`:176, `is_number`:178, `is_text`:180, `length`:182, `range`:184, 188 and 191, `error`:200, `assert`:203 and 210, `args`:216, `exit`:218 and 221, `sort_by`:227, `map`:238, `filter`:240, `clone`:242.

**Text** (10) — `format`:247, `join`:249, `upper`:262, `lower`:264, `trim`:266, `contains`:268, `split`:270, `replace`:275, `regex`:278, `matches`:295.

**Collections** (14) — `keys`:301, `values`:307, `push`:313, `pop`:322, `sum`:327, `sort`:329, `reverse`:331, `unique`:333, `set`:335, `frozenset`:342, `list`:348, `enumerate`:354, `zip`:365, `range_list`:375.

**Math** (29) — `abs`:381, `floor`:393, `ceil`:395, `round`:397 and 404, `sqrt`:423, `pow`:426, `sin`:431, `cos`:434, `tan`:437, `asin`:440, `acos`:443, `atan`:446, `atan2`:449, `hypot`:454, `ln`:460, `log10`:464, `log2`:468, `exp`:472, `degrees`:475, `radians`:478, `pi`:481, `e`:482, `tau`:483, `min`:486, `max`:488, `sorted`:490, `clamp`:492, `random`:498 and 500, `random_item`:506.

**Files** (13) — `read`:514, `read_lines`:518, `save`:529, `append`:542, `exists`:556, `list_files`:558, `delete`:583, `make_dir`:590, `file_size`:598, `path_join`:605, `path_name`:608, `path_parent`:612, `cwd`:616.

**File operations** (7) — `is_file`:630, `is_dir`:632, `read_bytes`:634, `write_bytes`:650, `copy_file`:662, `move_file`:667, `walk`:682.

**Patterns** (2) — `fnmatch`:40, `glob`:49, both in `Patterns.java`.

**Statistics, identifiers, ordered search** (12) — `mean`:33, `median`:35, `variance`:37,
`stdev`:39, `mode`:42, `percentile`:44, `uuid`:49`is_uuid`:51`heap_push`:54`heap_pop`:56`bisect_left`:58`bisect_right`:61all in `Statistics.java`. One registration per name, so the
section adds 12 to both columns. Specified by [ADR-0041](../adr/0041-statistics-identifiers-and-ordered-search.md).

**Text layout** (6) — `wrap_text`:27, `fill_text`:32, `dedent_text`:37, `indent_text`:39,
`shorten_text`:44, `unified_diff`:49, all in `TextWrap.java`. One registration per name, so the
section adds 6 to both columns. Specified by
[ADR-0043](../adr/0043-word-wrapping-and-text-diffs.md), whose one deliberate divergence from
Python is that a hyphen is an ordinary character rather than a break opportunity.

**Compression** (6) — `zlib_compress`:50, `zlib_decompress`:52, `gzip_compress`:55,
`gzip_decompress`:57, `zip_compress`:60, `zip_decompress`:62, all in `Compression.java`.
One registration per name, so the section adds 6 to both columns. Specified by
[ADR-0042](../adr/0042-whole-value-compression.md), whose reason for existing is the output cap:
the defence counts the bytes actually produced rather than trusting a length field, because a
hostile producer writes whatever length it likes.

**JSON** (4) — `json_parse`:822, `json`:824, `json_write`:826, `json_pretty`:828.

**Encoding** (11) — `hex_encode`:841, `hex_decode`:843, `base64_encode`:845, `base64_decode`:847, `sha256`:849, `sha1`:851, `md5`:853, `hmac_sha256`:855, `secure_random_hex`:857, `secure_equal`:867, `pbkdf2_sha256`:875.

**Logging** (6) — `log_debug`:959, `log_info`:961, `log_warn`:963, `log_error`:965, `log_level`:967, `log_enabled`:977.

**Arguments** (1) — `arg_parse`:1003.

**CSV** (2) — `csv_parse`:1153, 1155 and 1158, `csv_write`:1162 and 1164.

**Dates** (2) — `date_parse`:1311, `instant_parse`:1313.

**Time** (6) — `now`:1338, `today`:1340, `datetime`:1342, `timestamp`:1344, `tz_convert`:1346, `sleep`:1368.

**URLs** (6) — `url_parse`:1396, `url_encode`:1420, `url_decode`:1424, `query_parse`:1433, `query_encode`:1452, `url_join`:1492.

**HTTP** (2) — `http_get`:1579, `http_post`:1581.

**Concurrency** (5) — `spawn`:1735, `await`:1743 and 1750, `done`:1764, `mutex`:1771, `par_map`:1773.

**Process** (4) — `env`:1801, `run_command`:1803, `cpu_count`:1855, `memory_mb`:1857.

**Database** (3) — `db_query` DB:45, `db_execute` DB:70, `db_transaction` DB:89. These are
installed into the global namespace by `DatabaseModule.install` (`DatabaseModule.java:44`),
called from `Stdlib.install`, so by the definition used here — a name callable without a
qualifier — they are globals. Architecturally they are a module, which is why they live in
their own file and why `oq doc` missed them until it was repaired (126 → 129 at the time).

`fnmatch` and `glob` follow a different pattern: they live in `Patterns.java`, are installed
directly from `Stdlib.install` (`Stdlib.java:110`), and `oq doc` listed both from the commit
that added them. A builtin defined in its own file is therefore not inherently at risk — the
`DatabaseModule` miss came from that module installing a *separate* interpreter namespace, not
from the file boundary.

### The 9 method families

| Family | Resolver | Methods | Documented |
|---|---|---|---|
| `mutexMethod` | `MethodResolverImpl.java:70` | 2 | 2 |
| `iteratorMethod` | `MethodResolverImpl.java:98` | 3 | 3 |
| `listMethod` | `MethodResolverImpl.java:109` | 41 | 39 |
| `mapMethod` | `MethodResolverImpl.java:310` | 29 | 29 |
| `frozenSetMethod` | `MethodResolverImpl.java:412` | 38 | 25 |
| `setMethod` | `MethodResolverImpl.java:485` | 27 | 27 |
| `rangeMethod` | `MethodResolverImpl.java:565` | 18 | 16 |
| `textMethod` | `MethodResolverImpl.java:649` | 43 | 43 |
| `numberMethod` | `MethodResolverImpl.java:728` | 21 | 21 |
| **Total** | | **222** | **205** |

Two discrepancies, both benign and both recorded here rather than smoothed over:

- `listMethod` carries two extra internal aliases, `slice` and `subList`
  (`MethodResolverImpl.java:269`), not documented as separate names.
- `rangeMethod` has 18 case labels producing 16 distinct names, because two names are
  accepted in two spellings.
- `frozenSetMethod` accepts 38 case labels but documents 25, because 13 of them are the
  mutable spellings (`add`, `add_all`, `clear`, `discard`, `fill`, `pop`, `remove`,
  `remove_all`, `retain_all`, `shuffle`, `sort`, `update`, `frozenset` itself) that the
  immutable family must answer in order to refuse them with a message that names the frozen
  type, rather than falling through to a generic "no such method".

The families overlap by design — `iterator`, `list`, `set`, `length` and `text` are offered by
several families — so 205 is a count of *documented names per family*, not 205 distinct
method identifiers language-wide.

Family membership, for the record:

- `list` (39): add, all, any, clone, contains, copy, each, filter, find, find_index, first,
  flatten, get, insert, iter, join, last, length, map, max, min, pop, push, reduce, remove,
  remove_value, reverse, set, size, skip, sort, sorted, sorted_desc, sum, take, text, to_set,
  to_string, unique.
- `map` (29): clear, clone, contains, contains_key, copy, each, entries, get, has, has_value,
  items, iter, keys, length, map, merge, pop, put, remove, reverse, set, size, sorted, text,
  to_list, to_set, to_string, update, values.
- `set` (27) / `frozenset` (25): add, clone, contains, copy, difference, has, intersection,
  issubset, issuperset, items, iter, length, list, max, min, remove, reverse, set, size,
  sorted, sum, symmetric_difference, text, to_list, to_set, to_string, union; `frozenset`
  omits `add` and `remove` because it is immutable.
- `range` (16): contains, first, items, iter, last, length, list, max, min, reverse, set,
  size, sorted, sum, to_list, to_set.
- `text` (43): capitalize, characters, chars, contains, ends, ends_with, find, index_of, iter,
  length, lines, list, lower, lowercase, number, pad_end, pad_start, repeat, replace,
  replace_all, reverse, set, size, slice, sorted, split, starts, starts_with, strip,
  strip_end, strip_start, substring, text, to_list, to_number, to_set, to_string, trim,
  trim_end, trim_start, upper, uppercase, words.
- `number` (21): abs, acos, asin, atan, ceil, cos, degrees, exp, floor, is_even, is_odd, ln,
  log10, log2, radians, round, sin, sqrt, tan, text, to_string.
- `iterator` (3): has_next, iter, next. `mutex` (2): is_free, lock.

### What the 172 do not include

Verified absent by grep across `oq-stdlib/src/main/java/dev/oqthara/stdlib/`, zero matches
each: `itertools`, `decimal`, `fraction`, `gzip`,
`tar`, `lzma`, `shelve`, `pickle`, `dbm`, `yaml`, `toml`, `deep_equal`, `random_bytes`,
`date_add`, `date_diff`, `temp_dir`, `home_dir`, `os_name`, `platform_name`, `shell_quote`,
`shlex`. Two near-misses worth stating precisely: `pad_start` exists as a **text method**
only, not as a global, and `sort_by` is a global while `sort`/`sorted` are both globals and
methods. `glob` and `fnmatch` were on this absent list when the document was drafted and
shipped since, in `Patterns.java`; `uuid` likewise, in `Statistics.java`. All three are counted
in the 172 above. The list is re-grepped whenever it is edited, because a name that has shipped
and stayed on it is a false absence claim rather than a stale one.

Also relevant to the comparison below: there is **no `open` returning a handle**, no `bytes`
type ([ADR-0033](../adr/0033-encoding-and-hashing.md); binary content travels as hex text), no
`date` type distinct from text, and therefore no method family for either. A program is split
across files by `use "oq:name"` (`docs/spec/language.md:1751`), searched next to the program,
then `modules/`, then `lib/`; the namespace is flat and there is no `private`, no export list
and no qualified name.

### How much of it the examples exercise

41 `.oq` files under `examples/`, of which **55** distinct builtins appear as identifiers
in executable code (comments are stripped first, and a name in method position such as
`.sort()` is not counted, so the measure is of global use):

`args`, `assert`, `await`, `contains`, `cwd`, `db_execute`, `db_query`,
`db_transaction`, `delete`, `done`, `error`, `exists`, `exit`, `hmac_sha256`,
`input`, `json`, `json_parse`, `length`, `lower`, `map`, `matches`, `max`,
`md5`, `mutex`, `now`, `number`, `par_map`, `path_join`, `pbkdf2_sha256`,
`print`, `query_encode`, `query_parse`, `random`, `read`, `read_lines`,
`round`, `secure_equal`, `secure_random_hex`, `set`, `sha256`, `sleep`, `sort`,
`sorted`, `spawn`, `split`, `sqrt`, `sum`, `text`, `timestamp`, `today`,
`upper`, `url_encode`, `url_join`, `url_parse`, `write`.

74 builtins are exercised by no example. That is a coverage fact about the examples, not about
the builtins — every name above has unit coverage — but it is the honest measure of how much
of the surface a reader can learn by example, and it bounds how confidently the surface can be
called usable rather than merely present.

## Step 2 — the Python-reference inventory, by domain

Every domain below is one chapter of the official index, and every row answers the same ten
questions. Status codes: **C** covered, **P** partly covered, **A** absent and applicable,
**X** deliberately excluded. The module counts in each block sum to the chapter's page count,
which is how the domain total of 231 is arrived at.

Conventions used in the tables:

- *JVM fit* answers whether a JVM-hosted language can host the capability at all, with the
  reason, not merely a verdict.
- *Lifetime* assumes ADR-0036 Decision 6 — there is no `with`, no `defer` and no `close`, and
  no builtin returns an open resource. "Per-call" means the capability completes inside one
  call, so nothing outlives it. "Blocked" means the capability *is* a lifetime and therefore
  cannot be exposed without the missing construct.
- *Contract* states what an Oqthara implementation must guarantee — the semantic promise, not
  the syntax.
- *Test* names a concrete regression test in the style the repository already uses
  (`UrlAndHttpTest`, `CsvAndDatesTest`, `HostBuiltinLimitsTest`, `ConstantTimeAndHashingTest`,
  `DatabaseTest`, `StdlibFuzzRegressionTest`).
- *Acc.* is the acceptance criterion: the observable fact that would let the row be marked
  done.

### 1. Text processing services — 9 modules

| Attribute | Value |
|---|---|
| Modules | `string`, `string.templatelib`, `re` [P]; `unicodedata` [A]; `difflib`, `textwrap` [C]; `stringprep`, `readline`, `rlcompleter` [X] |
| Oqthara equivalent | Text method family (43 names, `MethodResolverImpl.java:649`): `upper`, `lower`, `trim`, `split`, `replace`, `words`, `lines`, `pad_start`, `pad_end`, `repeat`, `substring`, `find`, `starts_with`; globals `format`:237, `join`:238, `regex`:266, `matches`:283, `wrap_text`:27, `fill_text`:32, `dedent_text`:37, `indent_text`:39, `shorten_text`:44, `unified_diff`:49; string interpolation `"{expr}"` in source |
| Missing | No character-class constants (`string.digits`); no `Template` substitution; no `SequenceMatcher` ratio or `get_close_matches`; no Unicode category or normalisation API; no `locale`-independent case folding |
| JVM fit | Yes — `java.util.regex`, `java.text.Normalizer` and `Character` cover the mechanics; nothing here needs a host facility the JVM lacks |
| Lifetime | Per-call. Every one of these is a pure text-to-text transformation, so no construct is required and none of them can be blocked by ADR-0036 |
| Security | `regex` exposes Java's engine, whose backtracking can be super-linear. A catastrophic pattern is a denial-of-service surface inside a single call, and the language has no per-call evaluation budget for it. `unicodedata` normalisation is security-relevant positively: it is the defence against homoglyph and overlong-encoding confusion |
| Contract | `regex` and `matches` must stay Java-semantics and be documented as such: `matches` is `find()`, not `fullmatch`; groups return a flat list when the pattern has no groups and a list per match when it does; Python's `(?P<name>)` is not accepted. A proposed `normalise` must be Unicode NFC by default and must state the form |
| Test | `TextRegexSemanticsTest` — assert `matches("b", "abc")` is true (find, not fullmatch), `regex` returns flat strings for a group-free pattern and lists for a grouped one, and an invalid pattern raises rather than returning empty. Delivered `textwrap`/`difflib`: `TextWrapTest` asserts a 20-column wrap of a known string yields exactly the expected lines, that a word longer than the width is split at the width, that identical sides produce no diff at all, and that a hyphen is not a break opportunity |
| Acc. | The Java-versus-Python difference is written in `docs/spec/language.md` with an example of each; no builtin claims Python `re` compatibility. The wrapper's one divergence from Python -- a hyphen is not a break opportunity -- is pinned by `aHyphenIsAnOrdinaryCharacter` so a change to it is a deliberate act |
| Excluded | `stringprep` (IDNA/stringprep tables; no consumer in the language and a maintenance liability), `readline` and `rlcompleter` (terminal line editing is a property of the host REPL, not of the language, and the JVM cannot portably own terminal echo) |

### 2. Binary data services — 2 modules

| Attribute | Value |
|---|---|
| Modules | `struct` [A]; `codecs` [A] |
| Oqthara equivalent | None. Binary content is carried as **hex text** through `read_bytes`:562 / `write_bytes`:578, with `hex_encode`:709 / `hex_decode`:711 as the conversion |
| Missing | Packing and unpacking typed fields at byte offsets; integer endianness control; character-set transcoding as a first-class operation (UTF-8 is the only encoding the language recognises) |
| JVM fit | Partly — `ByteBuffer` with explicit `order()` gives `struct` semantics cheaply, and `Charset` gives `codecs`. What the JVM cannot give back is a *bytes value*: the language has no such type by [ADR-0033](../adr/0033-encoding-and-hashing.md), so any result must be re-expressed as hex text, which doubles size and makes offset arithmetic text-shaped |
| Lifetime | Per-call. Both are pure functions over values already in memory |
| Security | Hex round-tripping is the safer policy and should stay: it removes the "is this string bytes or text" ambiguity that produces encoding-confusion bugs. A `struct`-style API reintroduces length-driven parsing, which must reject a short buffer rather than zero-pad |
| Contract | A proposed `bytes`-shaped API must keep hex as the interchange form, must be explicit about endianness with no host default, and must raise on a buffer shorter than the declared layout. Transcoded text must be declared invalid-and-rejected rather than silently replaced with `?` |
| Test | `BinaryLayoutTest` — pack two integers and a length-prefixed field, unpack them, and assert round-trip equality plus a raised error on a truncated buffer; `TranscodeTest` asserting a lone invalid continuation byte raises instead of substituting |
| Acc. | Round-trip on every declared layout; truncation raises; no API accepts or returns a host-native byte container |
| Excluded | Nothing in this chapter is excluded outright. `struct` is absent rather than excluded because `ByteBuffer` makes it feasible; what blocks it is the missing `bytes` type, which is a language decision recorded in ADR-0033 rather than a stdlib gap |

### 3. Data types — 15 modules

| Attribute | Value |
|---|---|
| Modules | `datetime`, `zoneinfo`, `collections`, `copy`, `pprint` [P]; `heapq`, `bisect` [C]; `calendar`, `collections.abc`, `array`, `reprlib`, `enum`, `graphlib` [A]; `weakref`, `types` [X] |
| Oqthara equivalent | `now`:1205, `today`:1207, `datetime`:1209, `timestamp`:1211, `tz_convert`:1213, `date_parse`:1178, `instant_parse`:1180; `clone`:233 plus `.clone()`/`.copy()` on every collection family; `repr`:168, `format`:237, `json_pretty`:696; `heap_push`:53, `heap_pop`:55, `bisect_left`:57, `bisect_right`:59; list/map/set/frozenset/range families |
| Missing | No date *arithmetic* (add a day, subtract two dates, a duration type); no formatting other than the fixed shapes; no `deque`, `Counter`, `defaultdict`, `OrderedDict` or named tuples; no topological sort; no enumerations or algebraic tags beyond `data`. Priority queues and binary search shipped in `Statistics.java` as `heap_push`/`heap_pop`/`bisect_left`/`bisect_right`, so they are no longer missing |
| JVM fit | Yes — `java.time` is the strongest date/time library on any platform and `TreeMap`/`PriorityQueue` give `heapq` and ordered maps directly. The gap is a language-level one: with no `date` type there is nothing for arithmetic to return |
| Lifetime | Per-call. `zoneinfo` deserves one note: tzdata in a jar must be packaged, and `ZoneId.of` failing on an unknown zone is the correct behaviour, not a fallback to UTC |
| Security | Time-zone data is a supply-chain surface (a stale tzdata silently mis-dates logs and tokens). The current design — explicit IANA name in, numeric offset out, abbreviation refused — is the right one and should not be relaxed. `collections.abc` has no security weight; `reprlib` and `pprint` can leak memory contents into logs if applied to a value holding a secret, which is an argument for keeping `format` display-only |
| Contract | `now()`/`timestamp()` are **UTC**; `today()`/`datetime()` are **local** — the deliberate asymmetry already specified and measured. Any date arithmetic must define which of the four it returns and must not depend on the host default zone. A proposed duration type must be exact for whole days and refuse month arithmetic without an explicit rule |
| Test | `DateArithmeticTest` — add 1 day across a daylight-saving boundary and assert the instant differs by 23 or 25 hours rather than always 24; `HostTimeZoneIndependenceTest` running with `-Duser.timezone=Pacific/Auckland` and asserting `now()` is unchanged while `today()` moves. `heapq`/`bisect` shipped with `StatisticsTest`, which asserts that `n` pushes and pops yield sorted order and that the two bisects differ by exactly the duplicate count |
| Acc. | Every date operation states and tests its zone basis; arithmetic is DST-correct on at least two zones; a collection addition is accepted only with a property test against a sorted reference |
| Excluded | `weakref` (there is no user-visible object graph to reference weakly, and the JVM's collector is not observable), `types` (no module or class objects exist to inspect) |

### 4. Numeric and mathematical modules — 8 modules

| Attribute | Value |
|---|---|
| Modules | `math`, `random` [P]; `statistics` [C]; `numbers`, `math.integer`, `cmath`, `decimal`, `fractions` [A] |
| Oqthara equivalent | `abs`:381, `floor`:393, `ceil`:395, `round`:397/404, `sqrt`:423, `pow`:426, `sin`:431, `cos`:434, `tan`:437, `asin`:440, `acos`:443, `atan`:446, `atan2`:449, `hypot`:454, `ln`:460, `log10`:464, `log2`:468, `exp`:472, `degrees`:475, `radians`:478, `pi`:481, `e`:482, `tau`:483, `min`:486, `max`:488, `clamp`:492; number methods `is_even`, `is_odd`, `abs`, `ceil`, `floor`, `round`, `sqrt`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `ln`, `log10`, `log2`, `exp`, `degrees`, `radians`; `random`:498/500, `random_item`:506; `mean`:33, `median`:35, `variance`:37, `stdev`:39, `mode`:42, `percentile`:44 |
| Missing | No exact decimal arithmetic; no rational type; no seeding, no Gaussian, no shuffle; `statistics` shipped in full at six functions, so what remains absent is `fmean`/`geometric_mean`/`harmonic_mean`/`quantiles`/`multimode`/`NormalDist`/regression, all declined in [ADR-0041](../adr/0041-statistics-identifiers-and-ordered-search.md); no complex numbers; no numeric-tower distinction between int and float beyond `is_number` |
| JVM fit | Yes, with one caveat. `BigDecimal` gives exact decimal and `StrictMath` gives the transcendentals, so the arithmetic is cheap. The caveat is semantic, not technical: Python's `decimal` has a *context* (precision, rounding, traps) and reproducing it faithfully is a design decision, not a wrapper |
| Lifetime | Per-call. Nothing here can be blocked by ADR-0036, which makes this chapter the best value-per-unit-of-risk on the whole list |
| Security | Unseedable randomness is a security property worth keeping: `random()` is deliberately not reproducible and `secure_random_hex`:799 is the separate cryptographic source. The risk to avoid is a `seed` function that gets used for tokens. `decimal` removes the float-rounding class of bug from money handling, which is a correctness and trust issue rather than a threat |
| Contract | `round` now states its tie-breaking rule once in the spec and does not vary by arity, pinned by `RoundTieTest`. The trigonometric, logarithmic and exponential functions are specified by [ADR-0051](../adr/0051-non-finite-numbers-and-transcendentals.md), which settles the non-finite policy by measurement rather than by assumption: a **domain error raises** (`sqrt` of a negative, a logarithm of zero or less, `asin`/`acos` outside `[-1, 1]`, a negative base raised to a fractional power), while **overflow and underflow of a finite input stay silent** (`exp(1000)` is an infinity, `1.0e-308 / 10.0` is `0.0`), and a pole is not a domain error (`pow(0, -1)` is an infinity). `NaN` is therefore reachable only by combining infinities, and is detectable with `n != n` — no `is_nan` builtin exists. Every function is `StrictMath` rather than `Math` because `Math` may use platform intrinsics whose last bits vary by CPU and JVM; measured, `Math.sin` and `StrictMath.sin` disagreed on 36,073 of 1,000,000 inputs. `infinity` is not a literal, so the only way to obtain one is to overflow a finite number. A proposed exact-decimal domain must construct from text, never from a float, and must state precision and rounding explicitly per operation — but it cannot be called `decimal`, see the note below. **Name collision:** `Values.typeName` already returns `decimal` for a `Double`, so `type_of(1.5)` is `decimal` and [ADR-0015](../adr/0015-type-families.md) records that as the value model. A Python-style exact-decimal domain under the same name would make `type_of` ambiguous for the two numbers that most need telling apart. The domain is therefore blocked on a naming decision, and is not scheduled rather than half-built under a name that already means something else. [ADR-0045](../adr/0045-exact-java-numbers.md) has since removed the second objection that used to sit beside this one: `normalize` no longer narrows a `BigDecimal` to a `double`, so exact values survive the Java bridge and only the name remains undecided. A proposed `seed` must be documented as "not for secrets" in its own description, not only in prose |
| Test | `RoundTieTest` — six tests pinning the tie rule at both arities, including the three negative cases where upward rounding, banker's rounding and away-from-zero disagree; verified to fail when the implementation is mutated to `Math.rint`. `TranscendentalTest` — 31 tests publishing the constants as an external oracle rather than against the JDK (`sin(pi/2)` is 1 because a right angle's sine is 1), every domain violation in both the builtin and method form, and a sweep asserting no in-domain input yields `NaN`. `RandomIsUnseededTest` — asserts no `seed`/`srand`/`set_seed` builtin exists, that bounded draws stay in their half-open range and cover it, and that 100 draws are not all one value. All three files exist and are green; the earlier version of this row named them before they were written, which is the same defect class this document repairs elsewhere. `StatisticsTest` carries a fixed sample with published mean/median/stdev values, the tie and interpolation rules, and the single-element and empty-sample cases |
| Acc. | Tie-breaking documented and tested; every numeric addition carries at least one edge case at zero, one negative and one very large value |
| Excluded | Nothing from this chapter is excluded. `cmath` and `numbers` are absent because the language has no complex type and no abstract numeric tower, both of which are language decisions rather than omissions |

### 5. Functional programming modules — 3 modules

| Attribute | Value |
|---|---|
| Modules | `functools` [P]; `itertools`, `operator` [A] |
| Oqthara equivalent | First-class functions and lambdas (`x -> x * 2`, exercised in `examples/collections.oq`); `map`:229, `filter`:231, `sort_by`:218, `par_map`:1639; `.map()`, `.filter()`, `.each()`, `.reduce()`, `.any()`, `.all()`, `.find()` on the collection families |
| Missing | No lazy sequences or generators — every one of these is eager and materialises; no partial application or composition; no memoisation; no operator-as-value library beyond lambdas |
| JVM fit | Partly — the JVM can host all of it, but a lazy pipeline needs a *protocol* (a pull-based iterator), and the language's `iteratorMethod` (3 names) is that substrate only for existing collections, not for user-defined or unbounded sequences |
| Lifetime | Per-call for the eager forms. A proposed generator would be the first construct in the language whose value owns a suspended computation — a lifetime in all but name — so it must be settled before, not after, any lazy API is proposed |
| Security | Lazy pipelines make it easy to build an unbounded allocation inadvertently; the existing eagerness bounds memory by construction. If laziness arrives, it needs the same budget discipline as the 16 MiB text cap |
| Contract | Eager semantics must be stated as a guarantee, so a reader can rely on a side effect having happened when `map` returns. Any proposed lazy form must define exhaustion, single-pass-ness and behaviour on re-iteration |
| Test | `EagernessContractTest` — assert a side effect inside `.map()` has run before the call returns, pinning the eager contract. Proposed `reduce` on an empty list with no seed: `ReduceEmptyTest` asserting it raises with a named cause rather than returning `nothing` |
| Acc. | The eager guarantee is in the spec and tested; a lazy addition is accepted only with documented exhaustion and re-iteration behaviour |
| Excluded | Nothing excluded. `operator` is absent because lambdas already express it, not because it is impossible |

### 6. File and directory access — 9 modules

| Attribute | Value |
|---|---|
| Modules | `pathlib` [P]; `glob`, `fnmatch` [C]; `os.path`, `stat`, `shutil` [P]; `filecmp`, `tempfile` [A]; `linecache` [X] |
| Oqthara equivalent | `read`:443, `read_lines`:447, `save`:458, `append`:471, `exists`:485, `list_files`:487, `delete`:512, `make_dir`:519, `file_size`:527, `path_join`:534, `path_name`:536, `path_parent`:540, `cwd`:544, `is_file`:558, `is_dir`:560, `read_bytes`:562, `write_bytes`:578, `copy_file`:590, `move_file`:595, `walk`:610 |
| Missing | No temporary directory or file; no recursive copy or delete; no permission, modification-time or ownership inspection; no symbolic-link control beyond `walk` refusing to follow them; no path value — paths are text. `glob`:49 and `fnmatch`:40 in `Patterns.java` cover name matching, with `**` recursion and `*` matching a leading dot (unlike a POSIX shell) |
| JVM fit | Yes — `PathMatcher("glob:...")`, `Files.walkFileTree`, `Files.createTempDirectory` and `BasicFileAttributes` supply all of it. This is the cheapest high-value chapter on the list |
| Lifetime | Per-call for everything above, and that is the whole reason it is cheap. `tempfile` is the exception worth naming: a temp *file* that the caller must delete is a lifetime, so a proposed version must be a scoped body (as ADR-0040 did for transactions) or return a directory the caller owns explicitly |
| Security | The filesystem is trusted by [ADR-0036](../adr/0036-time-zones-and-filesystem.md) Decision 7 and `read("../../../etc/hosts")` is documented to work, so path traversal is a known and accepted property, not a bug. `delete` must never be recursive without an explicit flag, and `copy_file`/`move_file` refusing to overwrite is a safety property to preserve. A glob over an attacker-controlled directory can enumerate a very large tree, so it needs the `MAX_LIST_ENTRIES`-style cap that `list_files` already has |
| Contract | Every path operation returns text joined with the platform separator and never a `Path` value; `walk` continues not to follow symlinks; glob must define its syntax (the `PathMatcher` dialect, not Python's `**`) and its ordering (sorted, deterministic) so output is reproducible |
| Test | `GlobTest` — 26 cases. `aStarMatchesWithinOneSegment`, `aGlobstarMatchesAcrossSeparators`, `aQuestionMarkMatchesExactlyOneCharacter`, `aDashInAClassIsARange`, `anUnclosedClassIsRefused` and `resultsUseForwardSlashesOnEveryPlatform` pin the dialect. `TempDirOwnershipTest` (proposed) asserting a scoped body's directory is gone on normal return **and** on raise |
| Acc. | Documented dialect with a test per metacharacter; deterministic ordering; an entry cap that raises rather than truncating silently |
| Excluded | `linecache` (source-line caching exists to feed tracebacks in a CPython-style debugger; Oqthara has no `linecache` consumer and adding a cache with no reader is dead surface) |

### 7. Data persistence — 6 modules

| Attribute | Value |
|---|---|
| Modules | `sqlite3` [C]; `pickle`, `copyreg`, `shelve`, `marshal`, `dbm` [X] |
| Oqthara equivalent | `db_query`:45, `db_execute`:70, `db_transaction`:89, specified by [ADR-0040](../adr/0040-database-support.md). A connection is opened and closed inside every call; `db_transaction(path, body)` commits on return and rolls back on raise |
| Missing | Everything else. No generic object serialisation, no key-value store, no `dbm`-style file database |
| JVM fit | Yes — SQLite via JDBC is already proven in this repository, at a documented cost: the first external dependency, the standalone jar growing from 435 KB to 13.5 MB, and the first JNI dependency, so Alpine/musl is unsupported |
| Lifetime | **Solved, and the model for everything else.** ADR-0040 Decision 2 opens and closes per call and repeats the path on every call — "the redundancy is the design". Decision 4 makes the transaction a *body*, so there is no `begin`/`commit`/`rollback` to leak. This is the only chapter whose lifetime problem is closed |
| Security | Parameters are always bound, never spliced, and the placeholder count is checked because SQLite treats an unbound placeholder as NULL — measured: a forgotten parameter wrote `nothing` and exited 0. Null maps to `nothing` in both directions. `pickle` is excluded partly on these grounds: deserialising untrusted bytes is remote code execution by design |
| Contract | No connection value may ever be returned; a path must be required on every call; a nested `db_transaction` must be refused rather than silently flattened; errors must carry the stable prefixed text of Decision 6 |
| Test | `DatabaseTest` already covers query, execute, transaction commit and rollback, NULL round-trip and placeholder-count refusal. Proposed addition: `TransactionNestingRefusedTest` asserting a nested transaction raises with the documented prefix |
| Acc. | A program cannot obtain a handle; a raise inside the body rolls back observably; the dialect prefix appears in every documented error |
| Excluded | `pickle` (arbitrary code execution on load, and no `bytes` type to deserialise from), `copyreg` (exists only to extend pickle), `shelve` (pickle plus dbm), `marshal` (a CPython bytecode format with no meaning here), `dbm` (a file-backed handle that would outlive the call, which ADR-0036 forbids) |

### 8. Data compression and archiving — 8 modules

| Attribute | Value |
|---|---|
| Modules | `compression` (namespace) [X]; `zlib`, `gzip`, `zipfile` [C]; `compression.zstd`, `bz2`, `lzma`, `tarfile` [A] |
| Oqthara equivalent | `zlib_compress`/`zlib_decompress`, `gzip_compress`/`gzip_decompress`, `zip_compress`/`zip_decompress` ([ADR-0042](../adr/0042-whole-value-compression.md)), each a whole-value pair over lowercase hex. `zlib_compress` is hex-in because it is the raw byte path; `gzip_compress` is the one exception, taking text and encoding it as UTF-8 first, which is why `gzip_compress("hello")` needs no `hex_encode` while `zlib_compress` refuses odd-length input. Every decompressor is hex-in. `read_bytes`:562 / `write_bytes`:578 remain the only raw binary path |
| Missing | bzip2, xz and tar containers. There is no deflate-level control, no zstd and no entry-at-a-time archive walk |
| JVM fit | Yes, and unusually cheaply — `Deflater`/`Inflater`, `GZIPInputStream`, `ZipInputStream` and `ZipEntry` all operate on in-memory arrays, so a whole-value API needs no host I/O at all |
| Lifetime | Per-call, and that is what it shipped as: `zlib_*`, `gzip_*` and `zip_*` are byte-in/byte-out over hex text, with no streaming and no entry-at-a-time walk. This is the chapter where the ADR-0036 constraint cost least |
| Security | Decompression is the textbook amplification attack: a small input can expand until memory is exhausted. The shipped API therefore carries a hard output cap with a named error and trusts no length field in the container, and zip entries have path-traversal defence because entry names can contain `..`. `tarfile` needs the same defence when it lands |
| Contract | Round-trip exactness on arbitrary bytes; a declared maximum output size that raises rather than truncating; gzip output carries a pinned mtime so a round trip is byte-identical rather than merely decompressing to the same value |
| Test | `CompressionTest` — 26 cases. `aHighlyCompressibleBombIsRefusedRatherThanAllocated` pins the amplification cap with a named error, `traversalEntryNamesAreRefused` and `absoluteEntryNamesAreRefused` cover the zip path-traversal defence for entry names that shipped ahead of `tarfile`, and `gzipOutputStartsWithTheFormatMagicNumber` pins the envelope. Every compression builtin is registered exactly once |
| Acc. | Round-trip on random data for zlib, gzip and zip; amplification capped and tested; entry names sanitised; no API returns a stream |
| Excluded | `compression` only — it is a namespace package with no members of its own |

### 9. File formats — 5 modules

| Attribute | Value |
|---|---|
| Modules | `csv` [C]; `configparser`, `tomllib`, `plistlib` [A]; `netrc` [X] |
| Oqthara equivalent | `csv_parse`:1021/1021/1024, `csv_write`:1031/1029, specified by [ADR-0035](../adr/0035-csv-and-structured-data.md): RFC 4180, quoted fields, embedded delimiters and line breaks, opt-in comments |
| Missing | No INI parsing, no TOML, no property list; no schema, no typed columns — CSV rows come back as text |
| JVM fit | Yes — a TOML parser is a self-contained algorithm and INI is a small state machine; neither needs a dependency worth carrying for a 13.5 MB jar that already apologises for its size |
| Lifetime | Per-call. Text in, value out |
| Security | CSV formula injection is the live risk: a field beginning `=`, `+`, `-` or `@` is executed by spreadsheet software on open. `csv_write` should document this rather than silently escape, because escaping changes the data. Config parsing must not evaluate anything — a config format that can call code is a remote code execution surface |
| Contract | `csv_parse` must accept embedded delimiters and line breaks inside quotes and must reject an unterminated quote rather than accepting the truncated row; `csv_write` must quote exactly what RFC 4180 requires and no more; a proposed config parser must never interpolate |
| Test | `CsvAndDatesTest` covers the RFC 4180 cases today. Proposed: `CsvFormulaInjectionTest` asserting `csv_write` is documented to be non-escaping and that a round trip of `=1+1` yields `=1+1` unchanged, plus `TomlParseTest` with a nested table and a duplicate-key rejection |
| Acc. | Round-trip equality on the RFC's own examples; unterminated input raises; no parser evaluates |
| Excluded | `netrc` (a credential file format — reading one would put a password in a text value with no way to clear it, and the language has no secure-string lifetime; a language that cannot wipe a secret should not have a helper that loads one) |

### 10. Cryptographic services — 3 modules

| Attribute | Value |
|---|---|
| Modules | `hashlib`, `hmac`, `secrets` [all P] |
| Oqthara equivalent | `sha256`:717, `sha1`:719, `md5`:721, `hmac_sha256`:723, `pbkdf2_sha256`:743, `secure_random_hex`:799, `secure_equal`:735 — [ADR-0033](../adr/0033-encoding-and-hashing.md) and [ADR-0038](../adr/0038-constant-time-and-password-hashing.md) |
| Missing | No SHA-512, SHA-3 or BLAKE2; no incremental/streaming digest; no HKDF or scrypt/argon2; no `token_urlsafe`, no `choice`, no `randbelow`; HMAC only in SHA-256 |
| JVM fit | Yes, entirely — every one of these is a `MessageDigest`, `Mac` or `SecureRandom` call. The gap is API surface, not capability |
| Lifetime | Per-call. There is deliberately no key or certificate handle: ADR-0038 Decision 6 refuses a key API precisely because a key needs a lifetime and there is no `close` |
| Security | This is the most sensitive chapter and the current choices are the right ones: `secure_equal` is constant-time via `MessageDigest.isEqual` and opt-in so `==` is unchanged; `pbkdf2_sha256` requires both inputs and bounds the iteration count at 10 000 000; `secure_random_hex` caps at 4096 bytes. The residual risk is silence — a reader may assume `md5` and `sha1` are safe for integrity, so their documentation must say they are legacy |
| Contract | `secure_equal` compares in time independent of the first differing byte and accepts text only; `pbkdf2_sha256` returns 32 bytes and its output must match `hashlib.pbkdf2_hmac` rather than a second implementation of ours; `secure_random_hex` must fail closed if the platform source is unavailable, never fall back to a seeded generator |
| Test | `ConstantTimeAndHashingTest` already checks `sha256("abc")`, `sha1("abc")`, `md5("abc")` and the RFC 4231 HMAC vector against published values. Proposed addition: `KdfCrossCheckTest` comparing `pbkdf2_sha256` against a vector generated by `hashlib.pbkdf2_hmac`, and `SecureEqualLengthLeakTest` comparing equal-length inputs differing only in the last byte |
| Acc. | Every digest checked against a published vector, not against itself; no API accepts a key as a value that outlives the call |
| Excluded | Nothing excluded — all three are partially present. A key or certificate API stays refused per ADR-0038 Decision 6 |

### 11. Generic operating system services — 9 modules

| Attribute | Value |
|---|---|
| Modules | `logging` [C]; `os`, `io`, `time` [P]; `logging.config`, `logging.handlers`, `platform`, `errno` [A]; `ctypes` [X] |
| Oqthara equivalent | `log_debug`:901, `log_info`:903, `log_warn`:905, `log_error`:907, `log_level`:909, `log_enabled`:919 with a threshold from `OQ_LOG_LEVEL` ([ADR-0034](../adr/0034-logging-and-argument-parsing.md)); `env`:1743, `cwd`:616, `cpu_count`:1797, `memory_mb`:1799; `read`/`write`/`read_lines`/`read_bytes`/`write_bytes`; `now`/`today`/`datetime`/`timestamp`/`sleep` |
| Missing | No stream objects; no monotonic or high-resolution timer, no `strftime`/`strptime`; no log file output, rotation or handler configuration; no OS name or platform string; no errno constants |
| JVM fit | Yes — `System.getenv`, `Runtime.availableProcessors`, `System.nanoTime` and `java.util.logging` (or a one-file writer) cover it. `strftime`/`strptime` map onto `DateTimeFormatter` with the caveat that the pattern languages differ |
| Lifetime | Per-call, with one exception worth flagging: a log *handler* that owns a file descriptor is a lifetime, which is why the environment-variable threshold plus stderr is the right shape and a `log_to_file` should not be added without the scoped-body pattern |
| Security | Logging is where secrets leak. `format` and `repr` are display-only today, and the log functions must not gain automatic interpolation of arbitrary values without a documented redaction story. `env` exposes the whole environment, including tokens, to the program — acceptable for a scripting language, but it must be documented that a log line printing `env(...)` writes every secret to disk |
| Contract | `sleep` stays capped at 10 s and refuses more rather than silently truncating; log levels are ordered and `log_enabled` agrees with what `log_info` will actually emit; `now()` is UTC and `today()` is local, stated once |
| Test | `LoggingAndArgumentsTest` covers levels and thresholds. Proposed: `SleepCapRefusedTest` asserting `sleep(11)` raises with the documented text rather than sleeping; `StrftimeInteropTest` (proposed) asserting a known instant formats identically through the Oqthara API and `DateTimeFormatter` |
| Acc. | `log_enabled(x)` is true exactly when `log_x` emits; timers document their resolution and monotonicity; no logging call can be configured to write to a network destination |
| Excluded | `ctypes` (foreign function calls — the JNI cost is already documented at 435 KB → 13.5 MB for SQLite alone, and arbitrary native calls are a memory-safety surface the runtime cannot police) |

### 12. Command-line interface libraries — 8 modules

| Attribute | Value |
|---|---|
| Modules | `argparse` [C]; `fileinput` [A]; `optparse`, `getpass`, `curses`, `curses.ascii`, `curses.panel`, `cmd` [X] |
| Oqthara equivalent | `arg_parse`:871, a declarative parser returning `{flags, values, rest}`; `args`:208; `input`:159/157 |
| Missing | No typed options or defaults beyond the flag/value split; no subcommands; no usage generation; no iteration over files named on the command line |
| JVM fit | Yes — argument parsing is pure string processing, which is why it is already done |
| Lifetime | Per-call. The one adjacent risk is `getpass`, whose echo suppression changes terminal state and must be restored even on error — a lifetime in disguise, and a reason to leave it out |
| Security | `arg_parse` must not interpret a value, so a path passed as an argument stays a path; no shell expansion happens, which removes the classic injection path. `getpass` is excluded partly because a text value holding a password cannot be wiped |
| Contract | `arg_parse` returns exactly three keys and never guesses: an unknown flag lands in `rest`, not in `flags`; a missing value for a flag that needs one raises with a named cause rather than yielding `nothing` |
| Test | `LoggingAndArgumentsTest` covers the flag/value/rest split. Proposed: `ArgParseUnknownFlagTest` asserting an unrecognised `--wat` appears in `rest` and does not raise |
| Acc. | Unknown input is surfaced, never dropped; the three-key shape is stable and documented |
| Excluded | `optparse` (superseded by `argparse` in CPython itself and present here as `arg_parse`), `getpass` (terminal echo state is a restore-on-error lifetime and a password in a text value cannot be cleared), `curses`, `curses.ascii`, `curses.panel` and `cmd` (terminal-addressed full-screen UI; a JVM-hosted language has no portable terminal it owns, and the host REPL is the interface) |

### 13. Concurrent execution — 11 modules

| Attribute | Value |
|---|---|
| Modules | `threading`, `concurrent.futures` [P]; `subprocess` [C]; `sched`, `contextvars` [A]; `multiprocessing`, `multiprocessing.shared_memory`, `concurrent`, `concurrent.interpreters`, `_thread`, `queue` [X] |
| Oqthara equivalent | `spawn`:1677, `await`:1685/1692, `done`:1706, `mutex`:1713, `par_map`:1715, `run_command`:1745 — [ADR-0039](../adr/0039-executors-and-cancellation.md). One shared executor, a bounded `await(task, seconds)` that raises without cancelling, and a mutex that closed a measured lost update (2000 + 2000 increments produced 3840/3850/3704 before, 4000 on every run after) |
| Missing | No thread creation or pooling control, no priorities, no daemon semantics; no queue or channel; no per-task context propagation; no scheduled callbacks; no process-based parallelism |
| JVM fit | Partly — the JVM has real parallelism, which is an advantage over CPython's GIL, but it has no `fork`, so `multiprocessing` is not portable. `concurrent.interpreters` is a CPython 3.14 facility with no JVM analogue |
| Lifetime | Per-call for `spawn`/`await`/`par_map`, because a task is a value the runtime owns. A `queue` would be a lifetime (who closes it, what happens to a blocked reader) and is excluded on exactly that ground |
| Security | `par_map` runs user code on a shared pool, so an exception must not silently vanish; a lost task failure is a correctness and a security-relevant bug, since a validation step could be skipped. `mutex` is advisory only and must not be presented as a memory model |
| Contract | `await` raises on timeout and does **not** cancel, because the interpreter checks for interruption nowhere — this must stay documented, since the opposite is what a reader assumes. `par_map` must preserve input order in the output and must propagate the first failure |
| Test | `ExecutorsAndCancellationTest` and `WaitedBudgetTest` cover the executor and the bounded wait. Proposed: `ParMapOrderTest` asserting order preservation over 10 000 elements, and `ParMapFailurePropagatesTest` asserting one raising element fails the whole call |
| Acc. | Order preserved; a failure surfaces; `await` timeout is distinguishable from a task result of `nothing` |
| Excluded | `multiprocessing` and `multiprocessing.shared_memory` (the JVM has no `fork`, and shared-memory segments are a lifetime), `concurrent` and `concurrent.interpreters` (namespace package and CPython subinterpreters), `_thread` (a CPython internal low-level module), `queue` (a queue is a resource with a closing protocol; ADR-0039 declines channels for the same reason) |

### 14. Networking and interprocess communication — 7 modules

| Attribute | Value |
|---|---|
| Modules | `signal` [A]; `asyncio`, `socket`, `ssl`, `select`, `selectors`, `mmap` [X] |
| Oqthara equivalent | None at this layer. Outbound HTTP only: `http_get`:1446, `http_post`:1448, with 15 s connect and 30 s header/body deadlines, redirects normalised, TLS fully validated with no opt-out |
| Missing | No sockets, no listeners, no event loop, no readiness polling, no memory-mapped files, no signal handling |
| JVM fit | Yes technically — `SocketChannel`, `Selector`, `AsynchronousSocketChannel` and `MappedByteBuffer` exist. The obstacle is not the host, it is the language |
| Lifetime | **Blocked for all seven.** A socket, a listener, a selector and a mapped buffer are all resources that own an OS handle outside the call. [ADR-0037](../adr/0037-networking-and-urls.md) Decision 4 states it: "a language with no resource construct … has nowhere to put the lifetime" |
| Security | Sockets are the chapter where a mistake becomes a remote vulnerability: partial reads, unbounded buffering, TLS downgrade, and an accept loop with no backpressure. `http_get`/`http_post` avoid all of it by delegating to the JVM client with validation mandatory |
| Contract | Nothing to guarantee for absent APIs. For what exists: TLS certificate and hostname validation with no opt-out, one shared deadline, and a transport failure classified by cause so a refused connection is distinguishable from an unresolved name |
| Test | `UrlAndHttpTest` covers the existing client including the two repaired defects. Proposed `signal`: none — there is no acceptable contract to test until a handler has a defined lifetime |
| Acc. | Outbound requests validate TLS and never silently retry; no API exposes a connection, a listener or a file descriptor |
| Excluded | `socket`, `ssl`, `select`, `selectors` (ADR-0037 Decision 4 — no sockets, no HTTP server, no resource construct), `asyncio` (needs an event loop, a task lifetime and coroutine scheduling; the language's `await` joins one task and is not that), `mmap` (a mapped buffer is a handle). `signal` is absent rather than excluded: a handler is a lifetime and no design has been proposed that avoids one |

### 15. Internet data handling — 7 modules

| Attribute | Value |
|---|---|
| Modules | `json`, `base64` [C]; `binascii` [P]; `email`, `mimetypes`, `quopri` [A]; `mailbox` [X] |
| Oqthara equivalent | `json_parse`:692, `json`:694, `json_write`:695, `json_pretty`:696 (nesting capped at 256 by `Json.java:20`); `base64_encode`:713, `base64_decode`:715; `hex_encode`:709, `hex_decode`:711 |
| Missing | No MIME message model, no quoted-printable, no CRC-32 or Adler-32, no type guessing from a filename, no RFC 5322 address handling |
| JVM fit | Yes — `java.util.Base64`, `java.util.zip.CRC32` and `MimeType`-style tables. Mail parsing is a large but self-contained grammar |
| Lifetime | Per-call. Text or hex in, value out |
| Security | JSON depth is already capped at 256, which closes the stack-exhaustion vector; that cap must not be raised without measuring. `base64_decode` must reject a non-canonical alphabet rather than guessing, and a decoder must not allocate on a declared length. Email parsing is historically the richest source of parser bugs and must not be added without a fuzz corpus |
| Contract | `json_parse` rejects trailing content and duplicate keys by documented rule; `base64_decode` accepts one alphabet only and raises otherwise; base64 and hex stay the only binary carriers, so no API returns a byte container |
| Test | `JsonFuzzRegressionTest` covers depth and malformed input. Proposed: `Base64AlphabetTest` asserting URL-safe input raises on the standard decoder, and `Crc32GoldenTest` asserting CRC-32 of `"123456789"` is `0xCBF43926` |
| Acc. | Canonical-only decoding; depth cap tested at the boundary; a fuzz corpus per parser |
| Excluded | `mailbox` (a mail store is a directory of files with an index — a resource lifetime *and* an email model, so it depends on two things that do not exist) |

### 16. Structured markup processing — 13 modules

| Attribute | Value |
|---|---|
| Modules | `html`, `html.parser`, `html.entities` [A]; `xml`, `xml.etree`, `xml.dom`, `xml.dom.minidom`, `xml.dom.pulldom`, `xml.sax`, `xml.sax.handler`, `xml.sax.utils`, `xml.sax.reader`, `pyexpat` [X] |
| Oqthara equivalent | None. No markup parsing and no HTML escaping builtin |
| Missing | No escaping (`&` → `&amp;`), no entity table, no element tree, no SAX-style callbacks, no XPath |
| JVM fit | Yes for HTML escaping and entities — trivially. Partly for XML: the JVM ships several XML parsers and **every one of them must be configured to disable external entities and DTDs** before it is safe, which is a configuration invariant rather than a default |
| Lifetime | Per-call, if a tree is built in memory. A SAX callback style would need a callback protocol the language does not have |
| Security | This is the chapter with the most severe documented reasoning. XXE and the billion-laughs expansion both come from *trusting* a document's own declarations; a hand-written parser is worse, not better, because it re-implements the attack surface. The matrix already records XML as deliberately absent on these grounds. HTML escaping, by contrast, is a *defence* — its absence means a program must hand-roll the replacement, which is where escaping bugs live |
| Contract | A proposed `html_escape` must escape exactly `&`, `<`, `>`, `"` and `'`, must be idempotent-free (escaping twice must double-escape, so callers know), and must never be applied implicitly. No XML API may be added without external-entity and DTD resolution disabled and a test proving it |
| Test | `HtmlEscapeTest` — a string containing all five characters plus an already-escaped entity, asserting the exact output and that double-escaping is visible. Proposed `xml`: `XxeRefusedTest` with a document declaring an external entity and asserting the entity is not resolved and the call raises |
| Acc. | Escaping exact and tested; no XML entry point resolves an external entity or processes a DTD, proven by test |
| Excluded | All ten XML modules. Reason: XML parsing is safe only when external entity resolution and DTD processing are disabled, the JVM parsers do not do that by default, and a hand-written parser without that hardening would be a security regression. The gap is recorded rather than filled badly — the same position the requirement matrix already takes |

### 17. Internet protocols and support — 22 modules

| Attribute | Value |
|---|---|
| Modules | `urllib.parse`, `uuid` [C]; `urllib.request`, `urllib.error` [P]; `urllib.robotparser`, `http.cookies`, `ipaddress` [A]; `webbrowser`, `wsgiref`, `urllib`, `http`, `http.client`, `ftplib`, `poplib`, `imaplib`, `smtplib`, `socketserver`, `http.server`, `http.cookiejar`, `xmlrpc`, `xmlrpc.client`, `xmlrpc.server` [X] |
| Oqthara equivalent | `url_parse`:1263, `url_encode`:1287, `url_decode`:1291, `query_parse`:1300, `query_encode`:1319, `url_join`:1359; `http_get`:1446, `http_post`:1448; `uuid`, `is_uuid` (version 4, canonical lowercase only) |
| Missing | No IP address arithmetic or validation; no cookie parsing; no robots.txt evaluation; no authentication, proxy or cookie jar support in the HTTP client; no FTP, POP3, IMAP, SMTP; no server of any kind; no UUID versions other than 4 and no name-based generation |
| JVM fit | Yes for the four absent items — `UUID.randomUUID()`, `InetAddress` with `BigInteger` arithmetic, and cookie parsing is text. No for the fifteen excluded items, which need listeners, connection objects or XML |
| Lifetime | Per-call for all four absent items: a UUID is a value, an IP address is a value, a cookie is a value, a robots rule set is a value. Per-call is precisely why they are cheap. Every excluded item is a connection, a listener or a jar — a lifetime |
| Security | `url_join` escaping at segment level is the defence against path injection and must stay. `uuid` must use a cryptographic source for v4; a time-based v1 leaks host identity and clock, so it should not be offered. `http.cookies` must not scope cookies across hosts by default. `ipaddress` is a *validation* primitive: it is how a program rejects a host it did not intend to reach |
| Contract | `url_parse` returns the same component keys for every scheme and never normalises case away from the declared one; `http_post` sets `Content-Type: application/json`; the client identifies itself as `oqthara/0.1`; a proposed `uuid` returns the canonical 8-4-4-4-12 lowercase form and v4 only; a proposed `ip_is_private` must state which ranges count |
| Test | `UrlAndHttpTest` covers parsing, encoding and the client. `StatisticsTest` covers UUID: canonical lowercase shape and version nibble, uniqueness across generations, and `is_uuid` rejecting the brace and `urn:uuid:` forms `UUID.fromString` accepts. Proposed: `IpAddressClassifyTest` asserting `127.0.0.1`, `10.0.0.1`, `::1` and a public address classify as documented, and that `999.1.1.1` raises |
| Acc. | Every URL component present for every scheme; UUID shape and version asserted, with the accepted spellings pinned to exactly one; address parsing rejects rather than coerces |
| Excluded | `webbrowser` (launches a host GUI application — not a language facility), `wsgiref`, `socketserver`, `http.server` (servers need listeners and lifetimes; ADR-0037 Decision 4), `urllib`, `http`, `xmlrpc` (namespace packages), `http.client` (a connection object *is* a lifetime), `ftplib`, `poplib`, `imaplib`, `smtplib` (each is a stateful protocol session with a connection), `http.cookiejar` (a persisted jar plus a policy engine), `xmlrpc.client`, `xmlrpc.server` (XML, excluded above) |

### 18. Multimedia services — 2 modules

| Attribute | Value |
|---|---|
| Modules | `wave`, `colorsys` [both A] |
| Oqthara equivalent | None |
| Missing | No RIFF/WAVE header parsing; no RGB/HSV/HLS conversion |
| JVM fit | Yes for `colorsys` (three arithmetic functions). Partly for `wave`: the header is little-endian chunk-structured data, so it needs the `struct`-shaped capability that chapter 2 lacks |
| Lifetime | Per-call. Sample frames as hex text would be workable |
| Security | Low. The risk in audio containers is a declared length that disagrees with reality, so a parser must trust the actual byte count over the header field |
| Contract | A proposed conversion must state its ranges (0–1 versus 0–255) and clamp rather than wrap |
| Test | `ColorsysGoldenTest` asserting red → HSV → red round-trips within 1e-12, and that hue 1.0 equals hue 0.0 |
| Acc. | Round-trip within a stated tolerance; documented ranges |
| Excluded | Nothing excluded. Both are absent because they are low value, not because they are blocked — which is why neither appears on the shortlist |

### 19. Internationalization — 2 modules

| Attribute | Value |
|---|---|
| Modules | `gettext`, `locale` [both A] |
| Oqthara equivalent | None. Text is UTF-8 and the JVM default locale is not consulted by any builtin |
| Missing | No message catalogues, no plural forms, no locale-sensitive formatting or collation, no currency or number formats |
| JVM fit | Yes — `ResourceBundle` and `MessageFormat` are direct equivalents, and `Collator` covers collation |
| Lifetime | Per-call, if a catalogue is loaded whole. A gettext API that keeps a catalogue open is a lifetime, and `ResourceBundle` caches internally, which would need to be documented as invisible state |
| Security | Low direct risk. The real risk is silent substitution: a missing translation key that yields the key itself looks like a bug rather than an error, so a proposed API should make "missing" observable. Locale-dependent parsing is a correctness trap — `1,5` is one-and-a-half in some locales and a list in others |
| Contract | A proposed API must never use the host default locale implicitly; formatting must take an explicit locale or none, and "none" must mean a fixed documented locale rather than the machine's |
| Test | `LocaleIndependentFormatTest` running with `-Duser.language=tr` and asserting that `upper("i")` and number formatting are unchanged — Turkish dotless-i is the classic counter-example |
| Acc. | Output independent of host locale, proven by running the suite under at least one non-English default |
| Excluded | Nothing excluded. Both are absent and plausible; they lose to other chapters on value per unit of work |

### 20. Graphical user interfaces with Tk — 10 modules

| Attribute | Value |
|---|---|
| Modules | `tkinter`, `tkinter.colorchooser`, `tkinter.font`, `dialog`, `tkinter.messagebox`, `tkinter.scrolledtext`, `tkinter.dnd`, `tkinter.ttk`, `idle`, `turtle` — all [X] |
| Oqthara equivalent | None |
| Missing | All of it: windowing, widget toolkit, dialogs, turtle graphics, and IDLE |
| JVM fit | No — there is no canonical GUI toolkit a JVM-hosted language can adopt without choosing a side (Swing, JavaFX, SWT), and the choice would bind every program to one of them. `tkinter` is also literally a binding to a C library that would be a new native dependency |
| Lifetime | Blocked. A window, a widget and an event loop are all long-lived objects with a teardown protocol — the strongest form of the missing `close` |
| Security | Low, but a GUI toolkit historically carries a large native attack surface and clipboard/DPI integration would widen permissions |
| Contract | None proposed |
| Test | None |
| Acc. | Not applicable — the chapter is out of scope by design |
| Excluded | All ten. Reason: host-specific GUI with no portable JVM choice, plus an event loop and widget lifetime the language cannot express. A user who needs a GUI should build it in Java and call the interop boundary |

### 21. Development tools — 7 modules

| Attribute | Value |
|---|---|
| Modules | `pydoc` [C]; `unittest` [P]; `typing`, `devmode`, `doctest`, `unittest.mock` [A]; `test` (with its eight `test.support.*` submodules) [X] |
| Oqthara equivalent | `oq doc` generates a reference page from declarations, with `_Undocumented._` for a declaration lacking a comment and a footer counting documented versus undocumented ([ADR-0030](../adr/0030-docstrings.md)); `docs/stdlib/reference.md` is that output. `assert`:202/200 with arity 1 or 2 |
| Missing | No in-language test runner or assertions beyond `assert`; no mocking; no doctest extraction; no type annotations; no deprecation warnings |
| JVM fit | Yes, but with a governance caveat: [ADR-0039](../adr/0039-executors-and-cancellation.md) Decision 6 records that test and assert utilities are already present and are not to be extended. That decision, not feasibility, is what bounds this chapter |
| Lifetime | Per-call. An in-language runner that owns a report file would be a lifetime |
| Security | `assert` must not be the only guard on a security check, because a build could disable it later; documentation should say assertions are for invariants, and explicit raises are for validation. `unittest.mock` is excluded partly because a mock that silently accepts any call hides exactly the contract violations tests exist to find |
| Contract | `oq doc` must list every builtin regardless of which file defines it — the 126 → 129 repair exists because it once did not. `assert` with a message must include the message verbatim |
| Test | `StdlibCoverageTest` (in `oq-cli`) asserts the documented surface matches the registered one. Proposed: `DocCoversModuleDefinedBuiltinsTest` — a guard that fails if a `DatabaseModule`-style file adds a builtin the doc page omits, which is the exact regression that produced the 126 → 129 discrepancy |
| Acc. | The doc page and the registration table agree exactly, enforced by a test rather than by inspection |
| Excluded | `test` and its eight `test.support.*` submodules (CPython's own regression suite and the helpers that exist only to serve it; there is nothing here to port) |

### 22. Debugging and profiling — 9 modules

| Attribute | Value |
|---|---|
| Modules | `audit_events`, `pdb`, `profiling`, `pstats`, `timeit`, `trace` [A]; `bdb`, `faulthandler`, `tracemalloc` [X] |
| Oqthara equivalent | None in-language. Structured `error` values and `try` are the only diagnostic surface; `traceback` content is produced by the runtime on an uncaught raise |
| Missing | No single-stepping debugger, no profiler, no timing harness, no call tracing, no memory accounting beyond `memory_mb`:1723 |
| JVM fit | Partly — a profiler and a debugger are JVM-level tools (JFR, JDI) that would be exposed *through* the host, not reimplemented; a `timeit`-style harness is pure timing and would be cheap |
| Lifetime | Per-call for a timing harness. A stepping session is a lifetime and an interactive one at that |
| Security | A tracing facility that prints values will print secrets; `audit_events` exists in CPython as a security-audit hook and has no JVM equivalent that is safe to expose. Profiling perturbs timing, which matters if a program's security depends on a timeout |
| Contract | A proposed timing harness must state its clock (monotonic), its resolution and whether it discards warm-up, because all three determine whether the number means anything |
| Test | `TimeitResolutionTest` (proposed) asserting the reported elapsed time is monotonic and non-negative over 1000 iterations, and that the harness measures a known `sleep(0.01)` within a generous band |
| Acc. | Documented clock and resolution; no profiler output contains raw values |
| Excluded | `bdb` (a debugger *framework*; without `pdb` there is nothing to build), `faulthandler` (crash dumps are the JVM's job, and its own are better), `tracemalloc` (JVM heap analysis is a host tool) |

### 23. Software packaging and distribution — 3 modules

| Attribute | Value |
|---|---|
| Modules | `ensurepip`, `venv`, `zipapp` — all [X] |
| Oqthara equivalent | None, and none needed: the language ships as one standalone jar, and `JarBuilder` produces a runnable jar per program |
| Missing | Nothing that maps. There is no package index, no dependency resolver and no virtual environment — by design, since there are no third-party Oqthara packages |
| JVM fit | No — `ensurepip` and `venv` exist to manage CPython's interpreter-plus-site-packages layout. Adopting them would mean adopting a package ecosystem the language does not have |
| Lifetime | Not applicable |
| Security | Adopting a package manager would create a supply-chain surface the project deliberately does not have — one of the strongest security properties in the current design, and worth preserving explicitly |
| Contract | None proposed |
| Test | None |
| Acc. | Not applicable |
| Excluded | All three. Reason: they manage a CPython installation layout that does not exist here; the jar is the distribution unit, and a package manager would add a supply chain with no user |

### 24. Python runtime services — 16 modules

| Attribute | Value |
|---|---|
| Modules | `sys`, `builtins`, `dataclasses`, `traceback`, `inspect` [P]; `warnings`, `atexit`, `annotationlib` [A]; `sys.monitoring`, `sysconfig`, `__main__`, `contextlib`, `abc`, `__future__`, `gc`, `site` [X] |
| Oqthara equivalent | `args`:216, `env`:1743, `exit`:218/221; `type_of`:174, `repr`:170, `format`:247; `data` declarations supplying record-shaped types; `error`:200 |
| Missing | No introspection of the builtin namespace as a value; no warning category system; no exit hooks; no docstring reflection API beyond `oq doc` |
| JVM fit | Partly — the JVM has all of it internally, but several modules exist only to describe *CPython's* runtime: `sysconfig` reports build flags, `sys.monitoring` is a CPython profiler hook, `site` is CPython's startup sequence, `__future__` is a compile-time flag mechanism |
| Lifetime | Per-call. `atexit` is the one that implies deferred execution, which is a form of lifetime the language does not model |
| Security | `gc` and `sys` introspection can expose memory contents if a repr is printed; the existing display-only `format` is the safer shape. `warnings` is a low-risk gap but its absence means a deprecated builtin cannot announce itself |
| Contract | `exit` must be the only way to terminate with a status and its status must reach the shell; `type_of` must return a stable name for every first-class type, tested exhaustively against the type list |
| Test | `TypeOfTotalTest` (proposed) asserting `type_of` returns a documented name for every value the language can construct, and raises for nothing. `ExitStatusTest` asserting `exit(3)` reaches the shell as 3 |
| Acc. | `type_of` total over the type list; exit status observable |
| Excluded | `sys.monitoring` (a CPython profiler hook with no JVM meaning), `sysconfig` (CPython build configuration), `__main__` (module identity; there are no module objects), `contextlib` (all of it is context managers, which require `with`), `abc` (JVM types; no user-defined abstract base classes), `__future__` (compile flags for CPython grammar), `gc` (the JVM collector is not user-controlled and should not be), `site` (CPython startup path configuration) |

### 25. Custom Python interpreters — 2 modules

| Attribute | Value |
|---|---|
| Modules | `code`, `codeop` — both [X] |
| Oqthara equivalent | None |
| Missing | Nothing that maps: these compile and execute Python source into a Python frame |
| JVM fit | No — they exist to let a Python program host a Python interpreter. Oqthara is not a Python implementation and must not become one (§9.6) |
| Lifetime | Not applicable |
| Security | Exposing an `exec`-like facility would be arbitrary code execution inside a program that thought it had none |
| Contract | None proposed |
| Test | None |
| Acc. | Not applicable |
| Excluded | Both. Reason: interpreter-hosting machinery for a language this is not |

### 26. Importing modules — 9 modules

| Attribute | Value |
|---|---|
| Modules | `importlib` [P]; `zipimport`, `pkgutil`, `modulefinder`, `runpy`, `importlib.resources`, `importlib.resources.abc`, `importlib.metadata`, `sys_path_init` [X] |
| Oqthara equivalent | `use "oq:name"` (`docs/spec/language.md:1751`): loads `name.oq` from next to the program, then `modules/`, then `lib/`; the `.oq` suffix is optional; a module's top-level statements run before the importing program's, once, however many times it is imported; an import cycle terminates because a file loads at most once; there is one flat namespace, no `private`, no export list and no qualified name |
| Missing | No programmatic import, no lazy or conditional import, no package hierarchy, no search-path control, no module metadata, no import hooks |
| JVM fit | Yes — the loader is already implemented and is a filesystem search plus a parser call |
| Lifetime | Per-call at load time. A loaded module is state that lives for the process, which is deliberate and documented rather than a leak |
| Security | The search order is a real surface: `lib/` after the program directory means a file planted next to a program shadows one in `lib/`. That is worth documenting explicitly, because it is the same class of problem as a relative-path include. There is no `private`, so a module's helpers are visible to the importer and can collide — a name collision raises rather than shadowing, which is the safe direction |
| Contract | Load-at-most-once, deterministic search order, and a duplicate top-level name raising `'twice' is already defined with 1 parameter` rather than silently overwriting. Any proposed search-path API must keep the order inspectable |
| Test | `ExamplesRunTest` runs `modules.oq` through the real CLI, which exercises `use`. Proposed: `ModuleCyclesTerminateTest` importing `a` → `b` → `a` and asserting both functions are defined and the process exits 0; `ModuleSearchOrderTest` planting the same name in two search locations and asserting the documented one wins |
| Acc. | Cycle terminates; duplicate name raises with the documented text; search order documented and tested |
| Excluded | `zipimport`, `pkgutil`, `modulefinder`, `runpy`, `importlib.resources`, `importlib.resources.abc`, `importlib.metadata`, `sys_path_init` — all are facilities for CPython's site-packages and frozen-module machinery, which does not exist here |

### 27. Python language services — 11 modules

| Attribute | Value |
|---|---|
| Modules | `ast`, `symtable`, `token`, `keyword`, `tokenize`, `tabnanny`, `pyclbr`, `py_compile`, `compileall`, `dis`, `pickletools` — all [X] |
| Oqthara equivalent | None exposed. The language has its own lexer, parser and bytecode, but does not publish them as values |
| Missing | Nothing that maps: every one of these exposes *CPython's* concrete syntax tree, symbol table, token stream or bytecode |
| JVM fit | No — these describe Python's grammar and CPython's instruction set. Oqthara's own AST exists internally (`oq-core`, `Ast.java` and `Parser.java`) but exposing it would be a language-design decision, not a stdlib port |
| Lifetime | Not applicable |
| Security | `compileall` and `py_compile` write bytecode caches — file-writing side effects a reader would not expect from a library name |
| Contract | None proposed |
| Test | None |
| Acc. | Not applicable |
| Excluded | All eleven. Reason: they expose CPython's internals; porting them would mean claiming CPython compatibility, which §9.6 forbids |

### 28. MS Windows specific services — 3 modules

| Attribute | Value |
|---|---|
| Modules | `msvcrt`, `winreg`, `winsound` — all [X] |
| Oqthara equivalent | None |
| Missing | Nothing portable: console control, the registry and sound playback |
| JVM fit | No — host-specific by definition. A JVM-hosted language that exposed the registry would break every non-Windows program's guarantee and would need a native dependency per platform |
| Lifetime | Blocked: registry and sound handles are lifetimes |
| Security | Registry access is a privilege boundary; exposing it from a scripting language would widen permissions well beyond the language's needs |
| Contract | None proposed |
| Test | None |
| Acc. | Not applicable |
| Excluded | All three. Reason: single-platform services; the language targets the JVM, which already abstracts or deliberately omits them |

### 29. Unix-specific services — 10 modules

| Attribute | Value |
|---|---|
| Modules | `shlex` [A]; `posix`, `pwd`, `grp`, `termios`, `tty`, `pty`, `fcntl`, `resource`, `syslog` [X] |
| Oqthara equivalent | None. `run_command`:1745 takes one executable and arguments with no shell, so no quoting helper is needed for it |
| Missing | No shell-word quoting or splitting; no user/group lookup; no terminal control; no resource limits; no syslog |
| JVM fit | Partly — `shlex` is pure string logic and is portable. The rest are POSIX interfaces with no Windows equivalent, so exposing them would make a program's behaviour depend on the host OS in a way the spec cannot describe |
| Lifetime | Per-call for `shlex`. Blocked for the rest: a file descriptor, a tty and a pty are all handles |
| Security | The important point is a *negative* one: because `run_command` has no shell, there is no quoting bug to have. Adding `shell_split` would invite a program to build a command string that it then passes as a single argument, which is the classic injection mistake. If `shlex` is added it must come with documentation saying it is not needed by `run_command` |
| Contract | A proposed quoting function must be round-trip exact — `split(quote(xs))` equals `xs` for every `xs` — and must refuse a string containing a newline rather than deciding silently |
| Test | `ShlexRoundTripTest` (proposed) — property-style: for randomly generated argument vectors including spaces, quotes, `$`, backticks and empty strings, `split(quote(xs)) == xs`; plus a rejection test for an embedded newline |
| Acc. | Round-trip property holds over generated input; no API produces a string intended for a shell |
| Excluded | `posix`, `pwd`, `grp`, `termios`, `tty`, `pty`, `fcntl`, `resource`, `syslog` — POSIX-only interfaces over handles, none of which has a JVM-portable contract |

### 30. Superseded modules — 2 modules

| Attribute | Value |
|---|---|
| Modules | `getopt`, `profile` — both [X] |
| Oqthara equivalent | `arg_parse`:871 supersedes `getopt` in the same way `argparse` supersedes it in CPython |
| Missing | Nothing worth having: both are retained in CPython for backwards compatibility only |
| JVM fit | n/a |
| Lifetime | Not applicable |
| Security | None |
| Contract | None proposed |
| Test | None |
| Acc. | Not applicable |
| Excluded | Both. Reason: superseded by design in CPython itself; implementing a deliberately obsolete API would add surface with no user |

## Step 3 — the honest denominator

### 1. Count summary

Across the 30 domains and **231 documented modules**:

| Status | Modules | Share |
|---|---|---|
| **Covered** | **20** | 8.7% |
| **Partly covered** | **33** | 14.3% |
| **Absent** | **60** | 26.0% |
| **Deliberately excluded** | **118** | 51.1% |
| **Total** | **231** | 100.0% |

Per domain, in the chapter order used above:

| # | Domain | Modules | C | P | A | X |
|---|---|---|---|---|---|---|
| 1 | Text processing | 9 | 2 | 3 | 1 | 3 |
| 2 | Binary data | 2 | 0 | 0 | 2 | 0 |
| 3 | Data types | 15 | 2 | 5 | 6 | 2 |
| 4 | Numeric and mathematical | 8 | 1 | 2 | 5 | 0 |
| 5 | Functional programming | 3 | 0 | 1 | 2 | 0 |
| 6 | File and directory access | 9 | 2 | 4 | 2 | 1 |
| 7 | Data persistence | 6 | 1 | 0 | 0 | 5 |
| 8 | Compression and archiving | 8 | 3 | 0 | 4 | 1 |
| 9 | File formats | 5 | 1 | 0 | 3 | 1 |
| 10 | Cryptographic services | 3 | 0 | 3 | 0 | 0 |
| 11 | Generic OS services | 9 | 1 | 3 | 4 | 1 |
| 12 | CLI libraries | 8 | 1 | 0 | 1 | 6 |
| 13 | Concurrent execution | 11 | 1 | 2 | 2 | 6 |
| 14 | Networking and IPC | 7 | 0 | 0 | 1 | 6 |
| 15 | Internet data handling | 7 | 2 | 1 | 3 | 1 |
| 16 | Structured markup | 13 | 0 | 0 | 3 | 10 |
| 17 | Internet protocols | 22 | 2 | 2 | 3 | 15 |
| 18 | Multimedia | 2 | 0 | 0 | 2 | 0 |
| 19 | Internationalization | 2 | 0 | 0 | 2 | 0 |
| 20 | Tk GUI | 10 | 0 | 0 | 0 | 10 |
| 21 | Development tools | 7 | 1 | 1 | 4 | 1 |
| 22 | Debugging and profiling | 9 | 0 | 0 | 6 | 3 |
| 23 | Packaging | 3 | 0 | 0 | 0 | 3 |
| 24 | Runtime services | 16 | 0 | 5 | 3 | 8 |
| 25 | Custom interpreters | 2 | 0 | 0 | 0 | 2 |
| 26 | Importing modules | 9 | 0 | 1 | 0 | 8 |
| 27 | Language services | 11 | 0 | 0 | 0 | 11 |
| 28 | MS Windows services | 3 | 0 | 0 | 0 | 3 |
| 29 | Unix-specific services | 10 | 0 | 0 | 1 | 9 |
| 30 | Superseded modules | 2 | 0 | 0 | 0 | 2 |
| | **Total** | **231** | **20** | **33** | **60** | **118** |

Two readings of this table matter, and only one of them is flattering:

- **Against the whole index, coverage is 23%** (20 covered plus 33 partly, of 231 — and "partly"
  is doing real work in that figure). That is the honest headline, and it is deliberately not
  presented as a percentage of *reachable* surface, because choosing the denominator after
  seeing the answer is how a metrics document stops being trustworthy.
- **Against the chapters a JVM-hosted general-purpose language could plausibly host, the
  picture is different.** Of the 118 exclusions, the largest blocks are chapters that are
  host-specific or describe CPython rather than any language: Tk GUI 10, language services 11,
  Windows 3, packaging 3, custom interpreters 2, plus 8 of importing-modules and 8 of runtime
  services. Excluding them is not modesty, it is scope.

The figure the requirement matrix can now cite is therefore not "89 functions, breadth unknown"
but: **172 global builtins and 205 documented method names, against 231 documented Python
modules, with 20 covered, 33 partly covered, 60 absent and 118 excluded for a stated technical
reason.** Every exclusion above names its reason; none is a silent omission.

### 2. What this document does not claim

**Oqthara does not claim CPython compatibility, and must not.** This inventory is a measurement
device, not a roadmap to parity. §9.6 fixes the policy that CPython is reference material and
that differences are adapted explicitly rather than inherited — the same position
[`alpha-4-9-7-collection-evidence.md`](alpha-4-9-7-collection-evidence.md) takes when it says
its matrix "is **not** a claim of Python compatibility". Concretely, the following are
*documented differences*, not defects awaiting repair:

- `regex`/`matches` are Java `java.util.regex` semantics. `matches` is `find()`, not
  `fullmatch`; `(?P<name>)` is not accepted; group results differ in shape.
- There is no `bytes` type; binary content is hex text (ADR-0033).
- There is no `date` value type; dates are text in the shapes `today()`, `now()`,
  `date_parse` and `instant_parse` produce.
- `now()`/`timestamp()` are UTC while `today()`/`datetime()` are local — a deliberate
  asymmetry, specified rather than inherited.
- `format(x)` is display-only and takes no placeholders; it is not `str.format`.
- Failure is a raised `error`, never a sentinel return, so functions that would return `None`
  to signal failure raise instead.
- Modules are a flat namespace loaded by `use "oq:name"`, with no `private`, no qualified
  names and no package hierarchy.

Any future row in this table that is marked "covered" by making one of those behaviours
Python-like would be a regression in specification, not progress in breadth.

### 3. Prioritised next domains

Ordered by the three criteria given: (a) user value, (b) how cheap the JVM makes it, (c) whether
it needs a resource-lifetime construct the language does not have. The ordering is driven by (c)
first among equals, because a cheap domain that needs a `close` is not cheap.

| Rank | Proposed domain | Modules it would touch | (a) Value | (b) JVM cost | (c) Lifetime | Why here |
|---|---|---|---|---|---|---|
| 1 | ~~**Filesystem pattern matching** — `glob`, `fnmatch`~~ **shipped in `a4df608`** | 2 delivered | High — every script that processes files enumerates them by pattern; `list_files` alone forces the caller to filter by hand | Very low — `PathMatcher("glob:...")` and `walk` (#606) already do the work | None, per-call | The single best ratio on the list. It completes a chapter that is already 4/9 partly covered and needs no new concept |
| 2 | ~~**Whole-value compression**~~ — `zlib`, `gzip` and `zip` **shipped with ADR-0042** | 3 delivered | High — the project measures jar size and transfer budgets; compression is the obvious missing primitive for a language that already passes binary data as hex | Low — `Deflater` and `ZipInputStream` work on in-memory arrays | None, per-call, *provided* it is byte-in/byte-out over hex | Turns the chapter's 0/7 into 3/7 without touching the ADR-0036 constraint. Delivered with the amplification cap from the security column enforced by counting produced bytes |
| 3 | ~~**`statistics`**~~ **shipped with ADR-0041** | 1 delivered | High — mean/median/stdev are the first thing a scripting language is used for after string and file handling | Trivial — pure arithmetic over a list | None, per-call | No new type, no new concept, no dependency. The only work is choosing rules (sample versus population variance) and testing them |
| 4 | ~~**`uuid`**~~ **shipped with ADR-0041** | 1 delivered | Medium-high — stable identifiers for records, filenames and correlation ids | Trivial — `UUID.randomUUID()` plus format validation | None, per-call | Smallest possible surface with a large practical payoff; v4 only, so no clock or host leak |
| 5 | ~~**`heapq` + `bisect`**~~ **shipped with ADR-0041** | 2 delivered | Medium — ordered insertion, priority queues and binary search are the missing algorithmic layer under the existing `sort`/`sorted` | Low — `PriorityQueue` and a two-line binary search | None, per-call | Cheap and genuinely useful, but less frequently needed than 1–4, so it ranks below them |
| 6 | **`textwrap` + `difflib`** | delivered — [ADR-0043](../adr/0043-word-wrapping-and-text-diffs.md) | Medium — wrapping to a column is common in CLI output (this repository's own tooling does it); a diff is useful for test failures | Low — wrapping is arithmetic on words; a diff is an LCS algorithm with no dependency | None, per-call | Rounds out text processing, which is the most-used chapter, at the cost of two pure functions |
| 7 | **Exact decimal arithmetic** — `decimal` | 1 absent | High — money and any measurement that must not drift | Medium — `BigDecimal` exists, but Python's *context* (precision, rounding, traps) is a design decision, not a wrapper | None, per-call | **Blocked on a naming decision, and the engineering prerequisite now exists.** `decimal` is already the name `type_of` returns for a `Double` ([ADR-0015](../adr/0015-type-families.md)), so an exact-decimal domain cannot take that name without making `type_of` ambiguous for the two numbers a caller most needs to distinguish. Moving the float name instead is a breaking change to a released type name across five specification passages. Both routes are language decisions for the maintainer. The other half of the old reason — that `BigDecimal` arithmetic could not survive the bridge — was true until [ADR-0045](../adr/0045-exact-java-numbers.md) stopped `normalize` narrowing exact values to `double`, so the domain is now genuinely waiting on the name alone |
| 8 | **Lazy sequences** — `itertools` | 1 absent | High — infinite and lazy pipelines are what a functional surface is for | Medium-high — needs a pull protocol and a decision on exhaustion and re-iteration | **Yes** — a suspended computation is the first value in the language that owns something outside the call | Last despite high value, because it is the only entry on this list that needs a language decision before a builtin |

Deliberately **not** on the list, for the reasons the ADRs already give: sockets and an HTTP
server (ADR-0037 Decision 4 — no lifetime to put them in), anything XML (XXE and
billion-laughs; a hand-written parser would be a security regression), database or file
handles (ADR-0040 Decision 2 exists precisely because a handle cannot be exposed), key and
certificate APIs (ADR-0038 Decision 6), and any extension of test/assert utilities
(ADR-0039 Decision 6). None of these is excluded for want of importance; each is excluded
because the language lacks the construct that would make it safe, or because a later decision
closed it.

### 4. The blocker that recurs

Three of the thirty domains record an outright block in their Lifetime row — Chapter 14
(networking), Chapter 20 (Tk) and Chapter 28 (MS Windows services) — and three more record
a conditional one: Chapter 8 is per-call only if compression is designed byte-in/byte-out,
Chapter 13 is per-call only because a task is a value the runtime owns, and Chapter 29 is
per-call for `shlex` alone. Every one of these is blocked by the same missing construct.
This is the documented position, not an inference:

> There is no `with`, no `defer`, and no `close` in the language. … Every handle the stdlib
> acquires is closed before the builtin returns, so there is nothing for the program to leak. …
> **no builtin in this round returns an open resource**, and any future builtin that does must
> arrive with the construct that closes it.
>
> — [ADR-0036](../adr/0036-time-zones-and-filesystem.md), Decision 6

> A language with no resource construct (ADR-0036, Decision 6) has nowhere to put the lifetime.
>
> — [ADR-0037](../adr/0037-networking-and-urls.md), Decision 4, on refusing sockets

**I agree with this position, and the inventory strengthens the case for it.** Three
observations from measuring the surface support the decision rather than merely deferring to it:

1. **The constraint has cost far less than it looks.** Of the 60 absent modules, only a handful
   are blocked by resource lifetime — sockets, listeners, memory mapping, `dbm`, a scoped temp
   file, a signal handler, a queue. The other fifty-odd are blocked by nothing but work: they
   are pure functions over values that already fit the per-call shape. The missing `close` is
   therefore not the reason the library is narrow; it is the reason a small, specific set of
   domains must wait, and it has been used correctly to avoid the domains where a half-measure
   would ship a leak.
2. **The one solved case proves the pattern generalises.** ADR-0040 Decision 2 opens, uses and
   closes a connection inside every call and requires the path again on every call — "the
   redundancy is the design" — and Decision 4 makes a transaction a *body* rather than a
   `begin`/`commit`/`rollback` triple. That is a resource-owning domain delivered with no
   `close`, and it is the template for `tempfile` and for any archive that must write rather
   than return bytes.
3. **Both of the top-ranked proposals shipped unchanged.** Filesystem patterns (rank 1) and
   compression (rank 2) are pattern-in/names-out and byte-in/byte-out respectively. Neither
   needs a handle, so neither was blocked, and both landed with the cap or the refusal that
   makes them safe. The constraint therefore did not stand between the language and its most
   valuable next domains — which is the strongest evidence
   that it is well placed.

What I would add, and what this document is for: the three ADRs state the rule, but until now
nothing stated the *test* a proposal must pass. It is one question, and it should be asked of
every future row in this table:

> **Does the value outlive the call?** If it does — a handle, a cursor, a socket, a lock held
> across calls, an archive entry being written, a suspended computation — the proposal needs the
> construct first, or the scoped-body form that ADR-0040 established. If it does not, the
> proposal is unblocked and its only obstacle is work.

Everything in the "absent" column has been sorted on exactly that question, which is why the
absent set is large, cheap and honest, and the blocked set is small, expensive and documented.

---

**Last verified:** `27cac46` (merge of PR #136) plus the `textwrap`/`difflib` builtins
added in PR #94, 2026-11-11. The coverage tallies in the status table, the per-domain table and its
Total row are now checked by `scripts/check-docs-sync.py`, which derives them from the
per-domain chapters and fails when any of the three disagrees.
