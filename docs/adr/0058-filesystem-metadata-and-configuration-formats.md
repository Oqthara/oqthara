# ADR-0058 — Filesystem metadata and configuration-format grammars

**Status:** Accepted
**Date:** 2026-11-14
**Related:** ADR-0036 (no `close`, so no handles), ADR-0037 (networking), the standard-library
inventory §8 and §17, the requirement matrix §17

## Context

`FileSystem.java` and `ConfigFormats.java` were delivered in PR #192 with their contracts
argued in the commit message and pinned by `FileSystemModuleTest` (37 tests) and
`ConfigFormatsTest` (57 tests). The standard-library inventory records both chapters in full —
`Oqthara equivalent`, `Missing`, `JVM fit`, `Lifetime`, `Security`, `Contract`, `Test`,
`Acc.` and `Excluded`. What neither had was a decision record, and ten builtins were
reachable and documented in the generated reference while appearing in no specification
passage.

That is a narrower gap than it first appears, and the distinction matters for how this record
is written. The inventory is a governance measurement: it states what is covered, what is
missing and what is refused. It is not a decision record, and a user reading the language
specification finds no entry for `ini_parse` at all. This ADR supplies the decision view and
adds the ten names to the specification; it deliberately does not restate the inventory, which
remains the authoritative measurement. It decides nothing that was not already implemented and
tested.

## Decision

### 1. Filesystem metadata is a value, not a handle

Seven names complete the metadata and traversal surface:

| Name | Behaviour |
|---|---|
| `stat(path)` | `size`, `is_file`, `is_dir`, `is_symlink`, `modified`, `created`, `readable`, `writable`, `executable`, and a POSIX `mode` where the platform has one |
| `touch(path)` | create an empty file, or update the modified time of an existing one |
| `temp_dir(prefix)` | a fresh temporary directory under the system temporary directory, named with the required `prefix`, which the caller owns and must delete |
| `copy_tree(source, target)` | recursive copy, bounded |
| `read_lines_or_empty(path)` | the tolerant form of `read_lines`, named for its tolerance |
| `path_absolute(path)` | absolute and normalized |
| `path_normalize(path)` | lexically normalized |

`stat` reads attributes with `NOFOLLOW_LINKS`, so `is_symlink` describes the link rather than
what it points at. A caller that wants the target's attributes asks for the target. `mode` is
**omitted on a platform without POSIX permissions** rather than filled with a fabricated
value: a number that means nothing is worse than an absent key, because the former compares
successfully against a wrong expectation.

`temp_dir` is the single name in the library whose lifetime belongs to the caller. It says so
in its own documentation string. When the language gains a `close` and a scoped body, this
name is the argument for using it; until then, the alternative was not shipping it.

### 2. `copy_tree` is bounded in bytes, not in entries

`copy_file` permits 16 MiB and `MAX_LIST_ENTRIES` permits 100,000 entries. An entry cap alone
would therefore allow a tree of 100,000 files of 16 MiB each — 1.6 TiB — so `copy_tree` under
an entry cap alone would be the way around `copy_file`'s limit. The bound is **one 16 MiB
budget for the whole tree**, the same figure applied once rather than multiplied.

It is checked twice, because the two checks catch different things: first against the sizes
the filesystem reports, then against the bytes actually streamed through `copyBounded`, which
counts as it copies. A reported size that disagrees with the bytes delivered — a FIFO reports
zero and yields whatever a writer sends — is admitted by the first check and stopped by the
second. Steps are charged per byte moved, matching `copy_file`.

A copy refused mid-tree **leaves the partial target behind and the error names it**. Removing
it would need a recursive delete, and that is a language decision this module does not take.

### 3. Recursive delete is deliberately absent

Removing a tree needs an explicit "and everything under it" flag. A flag is a decision about
how a destructive operation is spelled, which belongs to the language, not to the stdlib. The
absence is a decision, and the partial-target behaviour in Decision 2 follows from it.

### 4. Configuration formats are grammars with named refusals

Four names:

| Name | Behaviour |
|---|---|
| `ini_parse(text)` | `section` → `key` → `value`; a prefix before any section header goes under `""`; a duplicate key takes the last value |
| `ini_write(map)` | the inverse, with sections and keys sorted so the output is stable |
| `properties_parse(text)` | the `java.util.Properties` grammar, including `\uXXXX` and line continuations |
| `toml_get(text, dotted_key)` | one string, integer, float, boolean or array of those at a dotted path |

`toml_get` **refuses tables of tables, inline tables and dates by name, with a line number.**
That narrow scope is the decision. A parser that returned a plausible wrong value for a
construct it does not model would be worse than one that says which construct stopped it,
because the wrong value propagates silently.

`properties_parse` follows the JDK grammar and diverges in four documented places, each where
the JDK is silent or unhelpful:

1. A trailing backslash at end of input **raises** instead of vanishing.
2. A malformed `\u` escape **names its line**.
3. Values are **not trimmed**.
4. An **even** run of trailing backslashes ends the line.

Each divergence is a case where matching the JDK exactly would mean reproducing an accident.

## Consequences

**Security.** These names touch the filesystem, so section 17.9 requires a review. `copy_tree`
refuses rather than merges into a non-empty target, refuses any symbolic link in the tree
rather than following one out of it, and bounds the whole tree as Decision 2 describes.
`ini_parse`, `properties_parse` and `toml_get` are text-in/value-out with no filesystem
access at all, so a hostile document can exhaust the call's own limits but cannot reach a
path. None of the four evaluates anything it parses.

**No new type, dependency, or lifetime.** Nothing here adds a resource the language cannot
express, which is why all ten names were deliverable before any `close` construct exists.

**What this record changes.** Criterion 1 of section 17.9 — a documented purpose and scope —
and criterion 3 — a semantic and error contract — are now satisfied in a decision record and in
the specification, where before they were satisfied only in a governance measurement. Criteria
2, 4, 5, 6, 7, 8 and 9 were already satisfied: the generated reference, the JVM mapping, the 94
tests, the inventory's security review, the examples census and the inventory's own figures.
