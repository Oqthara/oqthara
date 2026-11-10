# ADR-0035: CSV and structured text data

- **Status:** Accepted
- **Date:** 2026-11-10
- **Related:** [ADR-0034](0034-logging-and-argument-parsing.md)

## Context

Requirement §17.2 asks for "JSON; CSV; XML where justified; date/time serialization and
parsing; safe serialization formats with explicit security boundaries".

Measuring the surface before designing found JSON present and everything else absent. Probed
against the built CLI, each of these reports the function does not exist:

```
print csv_parse("a,b")
# error: 'csv_parse' is not defined

print xml_parse("<a/>")
# error: 'xml_parse' is not defined

print date_parse("2026-11-10")
# error: 'date_parse' is not defined
```

JSON is `json_parse(text)` and `json(value)`, and it is good enough for its domain: it refuses
duplicate keys, refuses trailing content, and refuses an object key that is not a string. CSV
is the format a program meets most often after JSON and the one it gets wrong most often, so
this ADR decides what to do about it, about XML, and about date strings.

## Decision

**D1 — CSV is parsed by a real RFC 4180 state machine, not by splitting on commas.** Quoting
is the whole of CSV: `a,"b,c"` has two fields and `a,",b"` has two fields, and a parser that
splits cannot tell them apart. The parser walks the text once, tracks whether it is inside
quotes, and treats a doubled quote inside a quoted field as one literal quote. Splitting is
what the caller would have written; it is not what a library may do.

**D2 — A malformed CSV is an error, never a best guess.** An unterminated quoted field, a
quote inside an unquoted field, or text after a closing quote raises an error naming the line.
Silently repairing the input would produce a table with the wrong shape and no indication that
anything was wrong, which is worse than refusing it.

**D3 — The delimiter is a parameter with a one-character contract, not a regex or a string.**
`csv_parse(text)` splits on `,`; `csv_parse(text, ";")` splits on `;`, for the European
convention that programs actually encounter. Exactly one character is accepted, because a
multi-character delimiter does not appear in any CSV dialect and permitting it invites callers
to treat the function as a general splitter.

**D4 — CSV comments follow the `#` convention, and a leading `#` must be opt-in by the caller
or never.** Comment lines are not in RFC 4180 but are common in hand-maintained data files.
A line whose first field begins with `#` is a comment only when the caller passes
`comment: true`; otherwise a `#` is data. Guessing would silently drop a data row for anyone
whose first column holds a part number.

**D5 — CSV output always quotes a field that needs it and never quotes one that does not.**
`csv_write(rows)` quotes a field when it contains the delimiter, a double quote, a carriage
return or a line feed; it quotes nothing else. Double quotes are escaped by doubling. A file
written this way parses back to the same rows, which is the only property worth guaranteeing
and the one the tests assert round-trip.

**D6 — XML is not implemented.** §17.2 says "XML where justified", and it is not justified
here. Parsing XML correctly needs namespace resolution, entity expansion rules, and a decision
about DTDs and external entities — the last of which is the source of the XXE vulnerability
class. A parser written for this project in this round would be an unfuzzed, unhardened XML
parser handed to programs that parse untrusted input, which is a security regression
disguised as a feature. `json_parse` covers the structured-data need; XML is recorded as
absent in the requirement matrix rather than approximated.

**D7 — Date parsing returns the same two shapes the time functions already return.** `now()`
returns an ISO-8601 instant string and `today()` returns an ISO-8601 date string. `date_parse`
therefore returns a date text in the same `YYYY-MM-DD` shape, and `instant_parse` returns the
same `...Z` shape `now()` produces, so a parsed value and a generated value are
interchangeable. Both raise an error naming the input when it does not match, rather than
returning nothing, because a caller that has to distinguish "no value" from "bad value" will
forget to.

**D8 — Nothing here normalises or guesses a time zone.** A date without an offset is a date in
no particular zone, and inventing the machine's zone would make a program's result depend on
where it ran. `date_parse` accepts exactly `YYYY-MM-DD`; `instant_parse` accepts an ISO-8601
instant with an explicit offset and converts it to UTC. A caller that has a local time and no
offset has a modelling problem this library will not hide.

## Rejected alternatives

- **Split CSV on the delimiter.** Loses quoted fields, which is most real CSV. Rejected by D1.
- **A general `split_csv` that returns text lines.** The unit of CSV is the row of fields, not
  the line; a quoted field may contain a newline. Rejected for the same reason.
- **Repair malformed CSV.** Produces wrong data with no signal. Rejected by D2.
- **Accept any-length delimiter.** Not a CSV concept, and it makes the function a splitter.
  Rejected by D3.
- **Echo comment lines back from `csv_parse`.** They are not data and a caller that wants them
  can read the file. Rejected.
- **A hand-written XML subset parser.** This is the XXE and billion-laughs class of bug, and a
  partial parser is not safer than a full one — it is a full one with the hardening removed.
  Rejected by D6.
- **Wrap the JVM's `DocumentBuilderFactory` for XML.** It would be safe *if* every hardening
  flag were set correctly, and a library that offers XML only when the caller happens to get
  five flags right is a trap. Deferred rather than done badly.
- **Return nothing on an unparseable date.** Forces every caller to re-check. Rejected by D7.
- **Default a zoneless date to the machine's zone.** Makes output non-reproducible across
  machines, which this project has already refused for logging timestamps. Rejected by D8.

## Consequences

`csv_parse` and `csv_write` round-trip through RFC 4180 quoting, including embedded delimiters,
embedded newlines and doubled quotes. `date_parse` and `instant_parse` produce exactly the
shapes `today()` and `now()` produce, so the two are interchangeable at the type level.

The honest cost: **XML is not available**, and a program that must read an XML feed has to do
it through Java interop with `javax.xml` and set its own hardening flags. That is a real gap
for a broad standard library and it is recorded as one rather than papered over. CSV also
does not stream: `csv_parse` reads the whole text, because the language has no streaming
abstraction yet, so a 2 GB CSV cannot be parsed and the caller gets a bounded-input error
instead of an out-of-memory failure.
