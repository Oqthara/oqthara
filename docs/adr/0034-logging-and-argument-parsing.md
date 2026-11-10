# ADR-0034: Logging and command-line argument parsing

- **Status:** Accepted
- **Date:** 2026-11-10
- **Related:** [ADR-0033](0033-encoding-and-hashing.md)

## Context

Requirement §17.4 asks for "logging; command-line argument parsing; subprocess execution with
explicit security and quoting rules; environment access; exit codes and process information".

Measuring the surface before designing found three of those five already present, and two
absent. Present: `run_command(path)` executes one program with no shell, a deadline and a
output cap; `env(name)` reads an environment variable; `args()` returns what followed the
program name; `exit(code)` ends the process. Absent, each confirmed by probing the built CLI:

```
print log_info("x")
# error: 'log_info' is not defined

print arg_parse(["a"], ["verbose"])
# error: 'arg_parse' is not defined
```

`args()` is the raw list. A program that wants `--verbose` or `--port 8080` has to walk the
list itself, and every program that does so will get the details wrong in its own way: an
option that takes a value, an unknown option, a repeated flag, a positional that begins with
`-`, and the `--` terminator that this CLI already passes through to programs (observed above:
`oq run p.oq -- a b c` yields `["--", "a", "b", "c"]`). That is the shape of a library, not of
a user's program.

## Decision

**D1 — Logging writes to the error stream, never the output stream.** A program's output is
its data; a log line is a diagnostic about the program. Mixing them makes `oq run p.oq > out`
capture logs, and it makes the output of a program that is piped into another program
unparseable. Six functions write one line each: `log_debug`, `log_info`, `log_warn`,
`log_error` and their shared formatter. `out()` is left alone entirely.

**D2 — The level is a global threshold, set by the program, defaulting to `info`.** A library
that always prints debug lines is unusable; a program that cannot see a warning is worse. The
threshold is read from `OQ_LOG_LEVEL` when present (`debug`, `info`, `warn`, `error`, `none`,
case-insensitive) and reset by `log_level(level)`. Reading the environment once at first use
keeps the rule simple: the variable is a deployment setting, not something a program
re-reads per call. An unrecognised value in the variable is ignored rather than fatal — a
typo in a deployment variable must not make a working program fail.

**D3 — A log line is `LEVEL message`, with the message untouched.** No timestamps, no source
locations, no JSON. A timestamp makes output non-deterministic, so a test that compares two
runs fails for a reason unrelated to the change; a source location duplicates what an error
already carries. `log_info("starting")` writes exactly:

```
INFO starting
```

The level is uppercase and padded to five characters (`DEBUG`, `INFO `, `WARN `, `ERROR`)
so a column of lines aligns without a formatting library.

**D4 — The message is converted with the same rules as `print`, not with `repr`.** `print` is
what a program already uses to render a value for a human, and a log line is that plus a
prefix. Using `repr` would quote strings and make `log_info("done")` print `INFO "done"`.

**D5 — Argument parsing is declarative, and every failure is an error, not a `null`.** One
function:

```
arg_parse(arguments, options) -> {flags: [...], values: {...}, rest: [...]}
```

`options` is a list of strings. A string with no `:` is a boolean flag; `name:` or `name:type`
takes a value, where the type is `text` (the default), `number` or `int`. The result has three
fields. `flags` holds the names of flags that were present, in the order given. `values` maps
an option name to its converted value; an option that was not given is **absent** from the
map, so `values["port"]` is an error the program must handle rather than a silent `null`. A
typed `?` suffix makes the value optional with a `null`. `rest` holds non-option arguments.

**D6 — A malformed command line raises `OqError`, and the message names the offending token.**
Unknown option, missing value, and a value that fails its type are all errors. This is the
same rule ADR-0033 D5 applied to decoders: a function that guesses hides the caller's mistake
until it is far away. The error carries a `help:` line listing the options the program
declared, because that is the only place the caller can learn what was expected.

**D7 — `--` terminates option parsing.** Everything after it is positional. The CLI already
passes `--` through to a program, so a parser that did not honour it would make the terminator
that appears in `args()` meaningless.

**D8 — No subprocess API is added here.** `run_command` exists and its contract is
deliberate: one executable path, no arguments, no shell, so there is no string for a caller to
inject a metacharacter into. §17.4's "explicit security and quoting rules" is satisfied by
having no quoting at all. Widening it to take an argument list is a separate decision with its
own threat model, and doing it here would bundle a security surface into a logging change.

## Rejected alternatives

- **`print` to stdout with a level prefix.** Rejected by D1: it corrupts program output.
- **A logger object with methods.** `logger.info(...)` needs a value type, and a value type
  would need to know the stream. Four global functions beside the existing globals are
  consistent with `env`, `args` and `exit`, which are also process-level concerns.
- **Timestamps by default, suppressed by a flag.** Rejected by D3: the deterministic default
  is the one tests and diffs depend on, and a timestamp is one `now()` call away for a program
  that wants it.
- **Returning `null` for a missing option.** Rejected by D5: `values["port"]` on an absent
  option then looks like a present option whose value is null. Absence is absence.
- **A `getopt`-style repeated call (`arg_next()`).** Rejected: it makes the parser stateful and
  the result depends on call order. One call returns the whole parse.
- **Accepting `-abc` as three flags.** Rejected: `-abc` is a legitimate value for a program
  whose options are all long, and clustering is a convention this language has not adopted
  anywhere else.
- **Parsing a string instead of a list.** Rejected: splitting a command line into words is the
  shell's job and the quoting rules differ per shell. `args()` already hands over words.
- **Widening `run_command` to take arguments in this ADR.** Rejected by D8.

## Consequences

- A program can log without corrupting its data stream, and its level is configurable without
  a code change.
- Log output is deterministic, so a test can assert on it exactly.
- A malformed command line fails at the boundary that can describe it, naming the token.
- An option's type is checked before the program sees the value, so `--port` cannot arrive as
  text and fail later inside a numeric operation.
- `arg_parse` does not support short-option clustering or `=`-joined values
  (`--port=8080`); an `=` is part of the value. That is a stated limit, not an oversight, and
  the error names the token if a caller relies on it.
- `run_command` still cannot pass arguments. A program that needs to run `git status` must
  write a script first; that remains the documented workaround.
