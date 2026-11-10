# ADR-0037: Networking, URLs, and HTTP timeouts

- **Status:** Accepted
- **Date:** 2026-11-10
- **Related:** [ADR-0036](0036-time-zones-and-filesystem.md), [ADR-0033](0033-encoding-and-hashing.md), [ADR-0017](0017-equality-and-hashing.md)

## Context

Requirement §17.5 asks for "socket APIs where justified; HTTP client/server capabilities
according to scope; URL parsing and encoding; TLS configuration and certificate validation;
timeouts, retries, cancellation, and resource cleanup".

Probing the built CLI before designing found six functions absent and two defects in the code
that already exists.

Absent:

```
print url_parse("https://example.com/a")   # error: 'url_parse' is not defined
print url_encode("a b")                     # error: 'url_encode' is not defined
print url_decode("a%20b")                   # error: 'url_decode' is not defined
print query_parse("a=1&b=2")                # error: 'query_parse' is not defined
print query_encode({a: 1})                  # error: 'query_encode' is not defined
print url_join("https://example.com/a/", "b")  # error: 'url_join' is not defined
```

Present and working: `http_get(url)`, `http_post(url, body)`, both returning a `Response` value
with `.status`, `.body` and `.ok`. A request to `https://example.com` returns 200, so TLS with
default certificate validation works.

Two defects, measured:

```
$ oq run t.oq
# print http_get("http://127.0.0.1:1/")
error: HTTP request failed: null

# print http_get("http://no-such-host.invalid/")
error: HTTP request failed: null
```

The message ends in `null` because the handler appends `e.getMessage()`, and the message is null
for both `ConnectException` and `UnknownHostException`. A user learns nothing about what failed.
The hint is a generic "Check the URL and your network connection."

Second, the timeouts are three unexplained literals: `connectTimeout` 15 s, request `timeout`
30 s, and a body deadline that reuses `COMMAND_TIMEOUT` (30 s). The three are not derived from
one another and none is reachable from a program.

## Decision 1: URL parsing is `java.net.URI`, and unparseable text is an error

`url_parse(url)` returns a `Url` value with `scheme`, `host`, `port`, `path`, `query` and
`fragment`. Fields that the URL does not carry are `nothing`; `port` is the port that was written
or `nothing`, never a scheme default, because inventing 443 makes it impossible to tell
`https://example.com` from `https://example.com:443` and the two are not the same string.

Measured against `java.net.URI`, which is what the HTTP client already uses:

```
https://example.com/a/b?x=1&y=2#frag  scheme=https host=example.com port=-1 path=/a/b query=x=1&y=2 frag=frag
https://example.com                   scheme=https host=example.com port=-1 path=      query=nothing frag=nothing
https://example.com:8443/p            port=8443
/just/a/path                          scheme=nothing host=nothing path=/just/a/path
mailto:x@y.z                          opaque; path is nothing
https://example.com/a b               IllegalArgumentException: Illegal character in path
```

`path` is the decoded path, so `https://example.com/%7Euser` yields `/a/... /~user`. The raw form
is not exposed: a program that needs the bytes it was given already has the original string.

Text that `URI.create` rejects is an error whose message names the input, following the same rule
as `date_parse` and `instant_parse` in ADR-0035 — a malformed input is refused where it is
detected, not carried forward as `nothing`.

Relative references such as `/just/a/path` are **not** an error. `URI` accepts them and they are
useful with `url_join`. Requiring a scheme would reject input that is meaningful.

## Decision 2: `url_encode` is form encoding, and it is named for that

There are two different escaping jobs and Java offers one API that quietly does the wrong thing
for each:

```
URLEncoder.encode:      "a b"  -> a+b        "a+b" -> a%2Bb      "x/y" -> x%2Fy
new URI(null,null,s):   "a b"  -> a%20b      "a+b" -> a+b        "ü"   -> ü
```

`URLEncoder` is `application/x-www-form-urlencoded`: it turns a space into `+` and escapes `/`.
`URI`'s single-argument constructor does not escape non-ASCII at all, so its output is not
safe to put on the wire.

`url_encode(text)` is therefore **form encoding**, matching `URLEncoder`: space becomes `+`,
everything outside the unreserved set is percent-encoded as UTF-8, and `url_decode` inverts it
with `URLDecoder`, which maps `+` back to a space. The pair round-trips, which is the property
that matters:

```
url_decode(url_encode("a+b"))   -> a+b      (the + is escaped to %2B on the way out)
url_decode(url_encode("ü/~."))  -> ü/~.
```

The trap this decision creates is that `url_encode` is wrong for a **path segment**: it escapes
`/` and turns spaces into `+`. Rather than expose two similarly-named functions and hope the
caller picks correctly, `url_join(base, ref)` takes the reference as unescaped text and escapes
it per segment itself. That covers the path case, which is the one that actually appears in
programs, without adding a second encoding name a reader has to disambiguate.

## Decision 3: query strings are a list of pairs, not a map

`query_parse(query)` returns a list of `{name, value}` values in the order they appear.
`query_encode(pairs)` accepts that same list.

A map was rejected. `?a=1&a=2` is legal and common, a map silently drops one of them, and
`query_parse` would then be lossy in a way the caller cannot see. Order also carries meaning for
signatures and for servers that care which duplicate wins.

Repeated names therefore survive:

```
query_parse("a=1&a=2&b=3")
  -> [QueryPair(name: "a", value: "1"), QueryPair(name: "a", value: "2"), QueryPair(name: "b", value: "3")]
```

A pair with no `=` is not an error; its value is the empty text, which is what a browser sends for
`?flag`. A name or value is percent-decoded with `+` as space, the same rule as `url_decode`, so
`?q=a+b` yields `a b`.

Names and values are text. Numbers are not coerced: a caller who wants a number has `number()`.

`query_encode` accepts **either** shape: a `QueryPair` as `query_parse` returns it, or a plain map
`{"name": ..., "value": ...}`.

The map form was added because of a defect found while probing the first implementation, not by
design. `OqData` values cannot be constructed from source — only builtins create them — so a
`query_encode` that accepted only `QueryPair` had no caller-writable input at all:

```
# before: the inverse of query_parse could not be called
print query_encode([{"name": "a", "value": "1"}])
# error: query_encode() needs a list of {name, value} pairs, found map
```

Both forms are accepted rather than replacing one with the other, so a parsed query can still be
re-encoded, which is the round-trip property the pair list exists to provide. The round trip is
text-equivalent but not byte-identical, because `+` and `%20` both decode to a space and both
encode back to `+`:

```
print query_encode(query_parse("q=a+b&r=x y"))   -> q=a+b&r=x+y
print query_encode(query_parse("q=a%2Bb"))       -> q=a%2Bb
```

A literal plus travels as `%2B` and so survives unchanged; a space written as either form comes
back as `+`. That is the defined behaviour of form encoding, not a loss.

## Decision 4: no sockets, and no HTTP server

§17.5 says "socket APIs where justified". They are not justified here.

A raw socket API needs a story for partial reads, partial writes, connection lifetime, half-close,
and cancellation of a blocked read — and `readCappedWithinDeadline` in the existing HTTP code
already shows how much care a blocked read requires. Every one of those is a defect surface, and
a language with no resource construct (ADR-0036, Decision 6) has nowhere to put the lifetime.

There is also no `open` returning a handle and no `close`, so a socket value could not be released
deterministically. Adding sockets first would mean adding resource management first, which is a
larger decision than §17.5 asks for. `http_get`/`http_post` cover the outbound request case, which
is what programs actually do.

An HTTP **server** is likewise out of scope: it needs concurrency, a listener lifetime, and a
routing model, and none of those exist. This is recorded rather than left ambiguous so that a
future proposal starts from a decision instead of re-litigating it.

## Decision 5: TLS keeps the JVM default, and there is no opt-out

`HttpClient` is built without an `SSLContext`, so certificate and hostname validation are the JVM
defaults and a bad certificate is refused. Measured: `https://example.com` succeeds.

No `insecure` flag and no `trustAll` option will be added. A test hook that disables validation is
indistinguishable, at the call site, from a program that ships it to production, and this language
has no build profiles to separate the two. A user who genuinely needs a private CA has the JVM
trust store, which is the supported mechanism.

## Decision 6: one timeout, reachable, and it names the phase that expired

The three literals become one setting with one name and one default:

- connect timeout: 15 s (unchanged, it is a TCP handshake);
- headers: 30 s (unchanged);
- body: 30 s (unchanged).

The values do not change in this ADR. What changes is that they are named constants with a
comment that says what each covers, instead of three numbers that happen to be 15, 30 and 30.

`timeout` is **not** exposed as a parameter in this change. A per-call timeout is a fourth argument
on `http_get` and a third on `http_post`, which is a signature change on two shipped functions and
belongs in its own decision with its own tests. What is fixed here is the part that is a defect:
the body deadline reusing `COMMAND_TIMEOUT` conflates "a subprocess is slow" with "a server stalled
a response", so the message said `30 seconds` for a reason the reader could not connect to HTTP.
Each phase now reports its own.

## Decision 7: no retries, because an automatic retry makes a request non-idempotent

A retry loop was considered and rejected. `http_post` is not idempotent: retrying a POST that the
server received but did not answer creates a duplicate. A blanket retry on "network error" cannot
distinguish that case, so the safe default is to fail and let the program decide. The error already
carries enough to retry: a program can catch it and call again.

## Decision 8: the failure message names the cause, not `null`

`ConnectException` and `UnknownHostException` carry no message, which is why the current output is
`HTTP request failed: null`. The fix walks to the root cause and names the class of failure in
words a reader can act on:

```
http_get("http://127.0.0.1:1/")            -> cannot reach the host: connection refused
http_get("http://no-such-host.invalid/")   -> cannot resolve the host: unknown host
```

The mapping is by cause type, not by string matching a message, because the message is exactly
what is missing. A cause that is not one of the known types still names its class, so the output is
never `null`.

## Consequences

- `url_parse` refuses text `URI` refuses. A caller with a possibly-bad URL must catch, which is
  correct: carrying an unparseable URL forward only moves the failure.
- `port` is `nothing` when absent. Code that wants 443 must write the default itself, and will be
  right about which scheme it assumed.
- `url_encode` is form encoding. Using it on a path segment is wrong, and `url_join` exists so that
  the common case does not require knowing that.
- `query_parse` returns pairs in order, so `.length` is the number of pairs, not the number of
  names.
- Sockets and servers are explicitly out of scope. A future ADR that adds them must first address
  resource lifetime.
- Every HTTP failure now has a message that names the cause and a phase. This is a user-visible
  change to existing error text.
