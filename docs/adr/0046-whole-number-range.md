# ADR-0046: A whole-number literal at the low end of the range

**Status:** Accepted
**Date:** 2026-11-11

## Context

`number` is a 64-bit signed whole number. Its range is `-9223372036854775808` to
`9223372036854775807`, but only the upper end was writable. The lexer read the digits
of a whole-number literal on their own and asked `Long.parseLong` to convert them, so
it refused any magnitude past `Long.MAX_VALUE`. A unary minus was a separate operator
applied afterwards, which meant `-9223372036854775808` failed before the minus was ever
seen:

```
error: number 9223372036854775808 is too large
  --> min.oq:1:8
  help: The largest supported whole number is 9223372036854775807.
```

The lowest value was still reachable as `-9223372036854775807 - 1`, so no value was
unrepresentable, but one end of the range required arithmetic and the other did not.

A test had recorded this as intent rather than as a defect, on the reasoning that the
lexer cannot know whether a minus follows. That reasoning is correct about the lexer
and wrong about the language: the parser does know.

## Decision

The lexer no longer refuses an oversized whole-number literal. It emits the token with
an unparsed value — a `null` literal — which is the only representation available for
a magnitude that no `long` holds. Two parser sites convert the token back to a value:

1. **Primary.** A whole-number token with no parsed value is refused by name, with the
   same message and help text as before. Everything that is not immediately preceded by
   a minus keeps its existing behaviour exactly, including a literal inside a list or as
   a call argument.
2. **Unary minus.** A minus applied directly to such a token produces `Long.MIN_VALUE`
   and nothing else; any other magnitude is refused with a message naming the smallest
   supported value.

The minus has to be checked when the unary operator is parsed rather than left to
arithmetic, because `Long.MIN_VALUE` has no positive counterpart: negating the value at
runtime would wrap back to itself and silently accept every out-of-range magnitude. The
parser compares the lexeme against the digits of `Long.MIN_VALUE` before committing.

`- -9223372036854775808` still evaluates to `-9223372036854775808`, because the inner
minus consumes the literal and the outer one negates the resulting value, where `-l`
wraps. That is the same wrapping arithmetic as every other negation at the boundary, and
ADR-0015 already fixes whole-number arithmetic as wrapping rather than checked.

## Consequences

The two ends of the range are now written the same way, and the range stated in
`docs/spec/language.md` is honest about both. A program that relied on the error to
catch a too-large literal still gets the error everywhere a minus is not present.

The lexical token carries a `null` literal, which nothing else produces. That is a
narrow widening of the token contract, and it is safe only because every consumer of a
whole-number literal goes through the parser's conversion. The `Token` record keeps its
shape, so no other code changes.

## Verification

`LexerTest` pins that the lexer defers rather than refuses, and that the token keeps its
lexeme. `ErrorMessageContractTest` pins both ends of the range printing, one past each
end being refused by name, and a list element at the oversized magnitude being refused.
`StdlibCoverageTest` pins the boundary arithmetic that reaches the lowest value, and
`BytecodeCompilerTest` pins that a compiled program prints the same two values the
interpreter does.
