# ADR-0033: Encoding and hashing in the standard library

- **Status:** Accepted
- **Date:** 2026-11-10
- **Related:** [ADR-0032](0032-static-fields-and-varargs.md)

## Context

Requirement §17 asks for a broad, Python-informed standard library. Measuring the surface
rather than guessing at it found 89 global functions and eight method families, covering
collections, text, math, files, JSON, time, HTTP, concurrency and processes. Two whole domains
from §17 were absent, and probing confirmed each name:

```
print sha256("abc")
# error: 'sha256' is not defined

print base64_encode("abc")
# error: 'base64_encode' is not defined
```

§17.1 lists "bytes, binary data, encoding, base64"; §17.6 lists "cryptographic hashes; HMAC;
secure random/secrets" and adds a rule that governs the design: *do not invent cryptography;
prefer vetted JVM providers*.

The language has no `bytes` type. `repr(255)` is `255` and text is the only sequence of
octets it owns, so any API has to decide how binary data is represented before it can decide
what a hash function returns. That decision is the whole of this ADR.

## Decision

**D1 — Binary data is written as text, in one of two documented spellings.** `hex` is
lowercase, fixed-width, two characters per octet. `base64` is standard alphabet with padding.
Both are reversible without loss, both are ASCII, and both survive a round trip through a file,
a JSON document or a terminal. There is no `bytes` type, and this ADR does not add one: a new
value family would need equality, ordering, iteration, slicing, printing and a conversion table,
and none of that is required to hash or encode something.

**D2 — The API is a noun per concern, not a bag of functions.** Four groups:

| Concern | Names |
| --- | --- |
| Encoding | `hex_encode`, `hex_decode`, `base64_encode`, `base64_decode` |
| Hashing | `sha256`, `sha1`, `md5` |
| Keyed hashing | `hmac_sha256` |
| Random | `secure_random_hex` |

Each takes and returns text. `md5` and `sha1` are offered because protocols and file formats
still name them, not because they are recommended; their documentation says so.

**D3 — The input is UTF-8 text, and the encoding is stated in the name.** `sha256(text)` hashes
the UTF-8 bytes of `text`, which is what `sha256sum` on a UTF-8 file computes for the same
content. Decoding takes the spelled form (`hex` or `base64`) and refuses anything that is not
canonically that; a decoder that silently accepts a truncated or wrongly padded input would
turn a corrupt value into a different, plausible one.

**D4 — Every provider is a JVM one.** `MessageDigest`, `Mac` and `SecureRandom` from the
platform, with algorithm names as constants. No hash, no cipher and no random generator is
written here. `SecureRandom` is used rather than `Random` for `secure_random_hex`, because the
name promises secrecy and a predictable generator would make that a lie.

**D5 — Decoders report the position, not just "invalid".** A decode failure names the offset
and what was expected there, because a base64 value pasted from a log is the likely source and
the offset is what tells the reader where it stopped being base64.

**D6 — Nothing here draws on the output budget.** These functions return a value; they do not
print. The budget is charged by `print` and by the writes that produce output. A hash of a
100 MB string costs memory through the string that already exists, and the single-value text
ceiling already bounds how large that can be.

## Rejected alternatives

- **A `bytes` value type.** The right answer for a language that wants binary arithmetic and
  bitwise operators, and far too large for the requirement being met. Text spellings cover
  encoding, hashing and storage today.
- **Returning a list of octets from a decode.** Ergonomic for a program that wants to index
  octets and awkward everywhere else; it also makes the common case — compare two digests —
  compare two lists instead of two strings.
- **Hashing a `list` of octets as a second overload.** It doubles the surface for a case a
  caller can reach with `join`; the text spelling has to exist anyway.
- **Uppercase hex, or accepting both cases on decode.** A digest printed by one function should
  compare equal to one printed by the same function on another machine, so the spelling is
  fixed. Decoding rejects uppercase rather than guessing, and `lower()` makes the intent clear
  at the call site.
- **`rand_bytes` built on `Random`.** Predictable output under a name that suggests otherwise.
- **Hashing without saying which algorithm is deprecated.** `md5` would be used for passwords.
  The reference says plainly that it must not be.

## Consequences

- `sha256("abc")` is
  `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`, the published value for
  the ASCII text, which is what the test asserts.
- `hex_decode` and `base64_decode` round-trip with their encoders, and refuse malformed input
  with a position.
- Passwords still have no primitive here. The reference says hashing is not password storage and
  points at a slow KDF being absent, rather than letting a reader assume `sha256` will do.
- Binary protocols that need bit manipulation remain out of reach; that is a `bytes` decision,
  still open.
- `secure_random_hex` is the only source of randomness that promises unpredictability; `random`
  remains the one for games and shuffling.
