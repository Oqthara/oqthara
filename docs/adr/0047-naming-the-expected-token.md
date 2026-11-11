# ADR-0047: A parser diagnostic names the token it expected

**Status:** Accepted
**Date:** 2026-11-11

## Context

`Parser.expect(kind, what)` reported a missing token as `"expected " + what`. Callers passed
one of two shapes in `what`:

- a noun phrase naming what the caller must type — `"a parameter name"`, `"a function name"`;
- a purpose clause saying what the token was for — `"to close the parameter list"`,
  `"after the function name"`, `"before '>' in a lambda"`.

The first shape reads correctly. The second does not, because the token itself is missing:
the message became `expected to close the parameter list`, which names a purpose and never
says what was expected. Twenty-seven of the forty-eight call sites used the second shape.

The same defect reached the documentation. ADR-0027, ADR-0019, the contracts specification
and the governance README all quoted the fragment as though it were the intended wording —
ADR-0027 lists `error: expected after the function name` beside a note explaining the
behaviour, so the broken message was recorded as the contract:

```
error: expected to close the parameter list
  --> dm1.oq:1:13
   1 | fun f(items = [])
                   ^
  help: Found '=' instead.
```

A caller reading that has to infer the token from the caret. `Found '=' instead` says what
was there, not what was wanted.

## Decision

`expect` distinguishes the two shapes by reading the phrase it was given. A phrase beginning
`to `, `after `, `before ` or `between ` is a purpose clause, and the token symbol is named in
front of it. Anything else is a noun phrase and is used as the whole message, unchanged.

The symbol comes from one `switch` over `Token.Kind`, beside the existing `expectKeyword`,
which already wrote `expected 'end' to close ...` and was therefore correct all along. That
asymmetry — the keyword path naming its token and the punctuation path not — is what let the
defect survive: every test that pinned a parse error pinned a keyword one.

Both messages keep their `Found ... instead.` hint, so the pair now reads

```
error: expected ')' to close the parameter list
  help: Found '=' instead.
```

## Consequences

- All twenty-seven purpose-clause sites are corrected by one change rather than by editing
  each string. The noun-phrase sites are untouched, verified by `expected a parameter name`
  and `expected a function name` still reading as before.
- The exemption is textual, so a future noun phrase beginning with one of the four words
  would be prefixed wrongly. The alternative — a second method or a boolean flag at every
  call site — moves the same knowledge to twenty-seven places instead of one, and the four
  words are not natural openers for a noun phrase in this codebase.
- Four documents that quoted the old fragments are updated to the text the CLI now produces,
  each probed against the built binary rather than transcribed. ADR-0019 and ADR-0027 are
  historical records; their quoted output is corrected so a reader checking the ADR against
  the current build is not misled about what a probe returns today.
- `ErrorMessageContractTest.everyExpectedTokenIsNamed` asserts both halves: that named
  messages are produced, and that no diagnostic for the probed inputs still contains
  `expected to `, `expected after `, `expected before ` or `expected between `.
- This is a wording change, not a semantic one. No program that parsed before fails now, and
  the conformance corpus is unchanged at 203 compiled, 13 fallback, 0 divergences.

## Verification

- `./gradlew build` green, `oq-core` line coverage 90.9% (gate 80%).
- `verify-conformance.py`: 203 compiled, 13 fallback, 0 divergences across 229 programs.
- `check-docs-sync.py` and `check-links.py` clean, 187 local links.
- Each corrected document example was re-run against `oq-cli/build/install/oq/bin/oq` and the
  new output pasted from that run.
