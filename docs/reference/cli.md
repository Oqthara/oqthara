# `oq` command line

`oq` runs Oqthara programs. This page documents every subcommand the help text
lists.

## `oq run`

Runs a program.

## `oq check`

Parses a program and reports syntax errors without running it.

`oq check` is a **syntax** check only. It does not resolve names, bind variables, or
verify that a program can run. A file containing `break` outside any loop, or a reference
to a name that is never defined, prints `OK` and exits 0. Use `oq run` to find those
errors.

## `oq repl`

Starts the interactive read-eval-print loop.

## `oq fmt`

Formats a program.

## `oq test`

Runs the tests in a program.

## `oq new`

Creates a new project.

## `oq doc`

Prints the standard library reference as Markdown, generated from the registration calls in
the standard library so the page cannot list a function that does not exist.

### `oq doc --source <path>`

Documents your own declarations from the documentation comment each one carries. A directory is
walked for `.oq` files; a file is documented on its own. The page goes to standard output unless
`--out <file>` names a destination.

```
"""Adds two numbers.

@param a the first addend
@param b the second addend
@return the sum
"""
fun add(a, b)
  return a + b
end
```

```
### fun add(a, b)

Adds two numbers.

Parameters:

- `a` the first addend
- `b` the second addend

Returns: the sum
```

A declaration with no documentation comment is listed as `_Undocumented._` rather than left off
the page, so the page shows what is missing as well as what is present. The recognised tags are
`@param`, `@return`, `@throws`, `@since`, `@author`, `@deprecated` and `@see`; any other `@name`
line is rendered under its own name rather than refused. See
[ADR-0030](../adr/0030-docstrings.md).

## `oq build`

Compiles a program to bytecode.

## `oq java`

Shows the Java runtime Oqthara would use, and where it looked. See
[ADR-0048](../adr/0048-runtime-manager.md).

The resolution order is fixed, and the first executable that exists *and* is Java 21 or newer
wins:

1. `OQ_JAVA`, if set to an executable;
2. a `.oq/java` pin, found by walking up from the working directory so a pin at a project root
   applies to every directory beneath it;
3. a managed runtime under `OQ_HOME` (or `~/.oq`), used once installed by `oq runtime install`;
4. `java` on `PATH`.

A candidate that is below the floor is reported and skipped rather than aborting the search, so
an accidental JDK 17 in `OQ_JAVA` does not hide a working JDK 21 further down. `--json` prints the
same decision machine-readably, together with the detected platform and whether a managed runtime
could be installed there. The command resolves without downloading or installing anything.

The platforms a managed runtime can be installed on are enumerated rather than implied: Linux
x86_64 and aarch64, macOS x86_64 and aarch64, Windows x86_64. When nothing resolves and the
platform is outside that list, the failure names what was detected and what to do instead. Vendor
spellings of the same processor (`amd64`, `x64` and `x86_64`; `arm64` and `aarch64`) are folded to
one name first, so support does not depend on which JDK happens to be running. Platform support
governs installation only; setting `OQ_JAVA` or putting a Java 21+ on `PATH` still works anywhere
Oqthara runs.

## `oq runtime`

Manages the JDK Oqthara runs on, when you would rather not install one yourself.

### `oq runtime status`

Reports the detected platform, the pinned JDK release, whether a runtime is installed and whether
an older one is retained for rollback, and which Java `oq` would actually use right now. It reads
the environment and the filesystem only; it never contacts the network.

### `oq runtime install`

Downloads the JDK pinned for this platform, verifies it, and installs it under `OQ_HOME` (or
`~/.oq`) so the resolver prefers it over `java` on `PATH`. The pinned release is Eclipse Temurin
21.0.13+9, and the digest of every platform's archive is compiled into `oq` rather than fetched
beside it: an archive that does not match is refused before anything is unpacked, and that refusal
is the whole point of the pin. A mirror that substitutes bytes has nothing to overwrite.

Installation is atomic. The archive is unpacked into a staging directory, its `bin/java` is run to
check it reports Java 21 or newer, and only then is it renamed into place — so the reachable states
are "nothing installed" or "a validated runtime installed", never a half-extracted one. Two
installs at once cannot corrupt it: the second waits, and if the first succeeds the second finds
the work already done.

An existing runtime is replaced rather than overwritten, and the one being replaced is kept at
`runtime/java.prev`. Rolling back is renaming the two back; at most two are kept. `--force`
reinstalls the pinned runtime even when it is already installed.

If a cached download is corrupt, `oq` discards it and fetches it again rather than failing on the
same bytes forever. If the download is interrupted, the partial file is deleted, so nothing that
looks like a complete archive is ever left behind. Nothing is installed when the network is
unreachable, and an already installed runtime keeps working offline.

Platform support is the list given under `oq java`: Linux x86_64 and aarch64, macOS x86_64 and
aarch64, and Windows x86_64. On any other platform `oq runtime install` explains what it detected
and does not attempt a download.

## `oq version`

Prints the version.

## `oq help`

Prints usage.
