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

Prints the standard library reference as Markdown.

## `oq build`

Compiles a program to bytecode.

## `oq version`

Prints the version.

## `oq help`

Prints usage.
