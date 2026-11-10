# ADR-0038: Constant-time comparison and password hashing

- **Status:** Accepted
- **Date:** 2026-11-10
- **Related:** [ADR-0033](0033-encoding-and-hashing.md), [ADR-0037](0037-networking-and-urls.md)

## Context

Requirement §17.6 asks for "cryptographic hashes; HMAC; secure random/secrets; key and
certificate handling through vetted JVM APIs; constant-time behavior where required; clear
distinction between cryptographic primitives and password hashing".

Probing the built CLI found hashing and randomness in place and the other two absent:

```
print hmac_sha256("k", "m")
# b60090e3052297aeb5a080889ce2fc4bca957e756faeb4df7d31800ca1e771ec
print sha256("abc")
# ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
print secure_random_hex(4)
# 1d08b4ef

print secure_equal("a", "a")        # error: 'secure_equal' is not defined
print pbkdf2_sha256("pw", "salt", 1)  # error: 'pbkdf2_sha256' is not defined
```

What exists, then, is the primitive half. A program can compute a MAC but has no way to compare
it without writing `==`, and `==` on text stops at the first differing byte. That is the exact
gap §17.6 names, and it is a defect in the surface, not a missing convenience.

## Decision 1: `secure_equal` compares in time independent of where the inputs differ

`secure_equal(a, b)` compares two texts and returns a boolean, taking the same time whether they
differ in the first character or the last, and whether they differ in length or not.

The implementation uses `java.security.MessageDigest.isEqual`, which is the JVM's own
constant-time comparison. Writing the loop here was considered and rejected: a hand-written
`diff |= a[i] ^ b[i]` is correct only if the compiler does not reintroduce a branch, and relying
on that is exactly the kind of assumption a language should not ask of its users.

Length is not a secret that leaks a usable amount. An attacker who learns that a 32-byte digest
is 32 bytes has learned what the algorithm already told them. What must not leak is the position
of the first difference within equal-length inputs, and that is what this function protects.

## Decision 2: it is for secrets, and the name says so

`secure_equal` is named for the property, not for the comparison. It is deliberately *not* the
default for `==`:

- `==` on text is used overwhelmingly for non-secrets, where the branch is free and the
  constant-time version is wasted work;
- making `==` constant-time would silently slow every string comparison in every program to
  defend a case most of them do not have;
- a program that must compare a MAC or a token has to choose to, and the name is what tells it
  that it should.

A boolean is returned rather than raising on mismatch, because the caller must decide what a
mismatch means. A language that raised would push callers into `try` for control flow and would
leak the same information through the exception path.

## Decision 3: `secure_equal` accepts text only, and that is a limitation to state

The parameter is text. Bytes are spelled as hex or base64 (ADR-0033), so a MAC is compared as the
hex text it already is:

```
const expected = hmac_sha256(key, message)
if not secure_equal(expected, received) { error("bad signature") }
```

Comparing two hex texts of equal length compares every byte of the digest, which is the property
that matters. Comparing a hex text against base64 of the same bytes would correctly report a
mismatch, since they are different text; a caller mixing encodings has a bug that this function
should not paper over.

There is no `secure_equal` for numbers. A number comparison that differs by value does not leak
a secret through timing in any way this language can expose, and offering the function for
numbers would suggest a hazard that is not there.

## Decision 4: password hashing is `pbkdf2_sha256`, and it is not `sha256`

The distinction §17.6 asks for is made by having two differently-named functions with different
parameter shapes, so that choosing the wrong one is visible at the call site:

```
sha256(text)                        # a digest: fast, unsalted, for integrity
pbkdf2_sha256(password, salt, iterations)   # a password hash: slow, salted, for storage
```

`pbkdf2_sha256` returns lowercase hex, like every other digest in the library. It is
`PBKDF2WithHmacSHA256` from the JVM, with the derived key length fixed at 32 bytes to match
`sha256`'s output width. Nothing here implements PBKDF2.

The salt is a text parameter rather than a generated one, because a function that generated and
returned the salt would also have to return the iteration count for the stored value to be
verifiable, and that is a record type, not a string. Generating the salt is the caller's job with
`secure_random_hex(16)`, and the stored value is the salt and the hash together:

```
const salt = secure_random_hex(16)
const stored = salt + ":" + pbkdf2_sha256(password, salt, 600000)
```

## Decision 5: the iteration count is required, has a floor, and has a ceiling

`iterations` is required. A default would be chosen by the library and then silently become too
low as hardware improves, which is how a password hash rots. Requiring it makes the cost a
decision the program records.

The floor is 1 and the ceiling is 10,000,000. The floor exists only to reject 0 and negatives,
which the JVM would reject anyway with a less clear message. The ceiling exists because
`pbkdf2_sha256` runs synchronously: at 10 million iterations a single call takes long enough to
look like a hang, and a program that asked for it has almost certainly passed a wrong variable.
The error names both bounds.

A wrong variable is a real failure mode here. `pbkdf2_sha256(pw, salt, 600000)` and
`pbkdf2_sha256(pw, salt, 600)` differ by a factor of a thousand and both are plausible typos.
The ceiling cannot catch that, but it does catch the case where someone passes a byte count.

## Decision 6: no key or certificate API, because there is no value to hold one

§17.6 lists "key and certificate handling through vetted JVM APIs". This is declined, and the
reason is structural rather than a matter of effort.

A key is a secret with a lifetime. This language has no `close` and no resource construct
(ADR-0036, Decision 6), so a key value would live until the garbage collector decided otherwise,
and there is no way to write "use this key, then be sure it is gone". Adding a key API without a
lifetime would teach the wrong shape and would be worse than the absence.

Certificate *validation* is not declined: it happens, with the JVM defaults, on every `https`
request (ADR-0037, Decision 5), and it cannot be turned off. What is declined is loading a
private key or a custom trust store from a program.

This is recorded here rather than left as a silent gap, so the next person does not read
`sha256` existing and conclude the cryptographic surface is complete.

## Decision 7: no constant-time guarantee is claimed beyond `secure_equal`

Only comparison is specified as constant-time. `hmac_sha256` and the digests are not documented
as constant-time, because they are not: they are the JVM providers' implementations, which make
no such promise, and a claim in this project's documentation that they were would be false.

The distinction matters because a reader who assumes every cryptographic function here is
timing-safe will not think to use `secure_equal`. The specification says plainly that comparison
is the only operation with this property.

## Consequences

- A program comparing a MAC with `==` remains wrong and is not warned about. Detecting it would
  require knowing which text is secret, which the language does not track. The documentation and
  the function name are the mitigation.
- `pbkdf2_sha256` is synchronous. A program deriving many hashes blocks for the total time. That
  is the intended cost, and parallelising it belongs to the concurrency work (§17.7), not here.
- The salt is the caller's to generate, so a caller who passes a constant salt gets a hash that
  is wrong in the same way a constant-salt hash is wrong anywhere. The function does not refuse
  it: a repeated salt is a legitimate pattern in some protocols, and refusing would be a guess.
- Key and certificate loading remain absent. This is a documented refusal with a stated reason,
  not an unfinished item.
