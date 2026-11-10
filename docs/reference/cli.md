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

## `oq version`

Prints the version.

## `oq help`

Prints usage.
