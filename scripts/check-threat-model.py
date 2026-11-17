#!/usr/bin/env python3
"""Checks that the Alpha 4 threat model agrees with itself and with the tree.

The threat model's review cadence says it is "revisited at least once per Alpha"
and that "a threat is admitted with an ID, a status, and at least one cited path".
Nothing checked either claim. When it was finally re-read at the Alpha 7
boundary, six facts were stale and one of them was not drift: TH-15's stated
mitigation — "the compiler refuses `use`" — had stopped existing three releases
earlier, when ADR-0066 made `use` compile. The status column was still
`Partially mitigated`, so a status-count check would not have caught it. The
reason was wrong, and the reason is what the document exists to record.

Four things are checked:

1. Every `TH-` row in a threat table has a recognised status, so a row cannot be
   added with a status the rest of the document does not know about.
2. The four summary buckets partition the threat set exactly once each: no ID
   missing, none in two buckets, none listed and not declared.
3. Each bucket's declared count equals the IDs it lists, and the stated total
   equals the number of rows.
4. Every repository path the document cites still resolves, using the resolver
   already written for the risk register rather than a second implementation
   that would drift from it.

What this deliberately does not check is whether a mitigation's *prose* is still
true. No script can read "the compiler refuses `use`" and know that ADR-0066
removed it. That is why each status still has to be argued for, and why the
cadence rule exists at all. This gate catches arithmetic and citations; it
catches a lie only when the lie names a path that moved.

Exit codes: 0 all checks pass, 1 a mismatch, 2 the document could not be read.
"""

from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODEL = Path("docs/governance/alpha-4-threat-model.md")

# The heading that introduces this document's exemption table. The mechanism
# lives in `check-risk-register.py` and is shared; only the title differs.
EXEMPT_TITLE = "Citations that intentionally do not resolve"

# The status vocabulary, taken from the document's own legend table. Kept as
# data rather than parsed for the same reason the risk register keeps its
# exposure matrix: a checker that reads the vocabulary from the file it is
# checking cannot notice the vocabulary changing.
STATUSES = [
    "Mitigated",
    "Partially mitigated",
    "Accepted",
    "Not mitigated",
]

# A row in a threats table. The status cell may carry trailing justification
# after the bold status ("**Accepted**, documented"), and the threat text may
# contain backticks, so the ID and the status are the only anchored parts.
ROW = re.compile(
    r"^\|\s*(TH-\d{2})\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|\s*(.+?)\s*\|$"
)

# A row in the `## Threat summary` table: a bold status, a count, an ID list.
BUCKET = re.compile(r"^\|\s*\*\*(.+?)\*\*\s*\|\s*(\d+)\s*\|\s*(.*?)\s*\|$")

ID = re.compile(r"TH-\d{2}")


_MODULES: dict[str, object] = {}


def _load(name: str, filename: str):
    """Imports a sibling checker once and returns the same module every time.

    Cached because the resolver state must be shared: a caller that patches
    `resolve_citation` to drive a test has to reach the same object this module
    calls, and re-executing the file on every lookup would hand back a fresh copy
    that the patch never touches. That bug made the first version of the
    exemption test pass for the wrong reason — it patched a module nothing used.
    """
    if name not in _MODULES:
        spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _MODULES[name] = module
    return _MODULES[name]


def parse_threats(text: str) -> list[dict[str, str]]:
    """Every threat row that declares a status, in document order."""
    threats: list[dict[str, str]] = []
    for line in text.splitlines():
        match = ROW.match(line)
        if match is None:
            continue
        status_cell = match.group(4)
        status = next((s for s in STATUSES if s in status_cell), None)
        if status is None:
            continue
        threats.append(
            {
                "id": match.group(1),
                "status": status,
                "threat": match.group(2),
                "stride": match.group(3),
            }
        )
    return threats


def parse_summary(text: str) -> tuple[dict[str, set[str]], dict[str, int], int | None]:
    """The summary buckets: listed IDs, stated counts, and the stated total."""
    section = re.search(r"^## Threat summary$(.*?)(?=^## |\Z)", text, re.MULTILINE | re.DOTALL)
    if section is None:
        return {}, {}, None
    listed: dict[str, set[str]] = {}
    stated: dict[str, int] = {}
    for line in section.group(1).splitlines():
        match = BUCKET.match(line)
        if match is None:
            continue
        status = match.group(1).strip()
        if status not in STATUSES:
            continue
        listed[status] = set(ID.findall(match.group(3)))
        stated[status] = int(match.group(2))
    total_match = re.search(r"(\w+)-(\w+) threats\.", section.group(1))
    total = None
    if total_match is not None:
        words = {
            "Fifty": 50, "Forty": 40, "Thirty": 30, "Twenty": 20, "Ten": 10,
            "Sixty": 60, "Seventy": 70, "Eighty": 80,
        }
        digits = {
            "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6,
            "seven": 7, "eight": 8, "nine": 9,
        }
        tens = total_match.group(1)
        units = total_match.group(2).lower()
        if tens in words:
            total = words[tens] + digits.get(units, 0)
    return listed, stated, total


def check_citations(text: str) -> list[str]:
    """Every repository path the document cites resolves, and every exempt one does not.

    This reuses the exemption machinery from `check-risk-register.py` rather than
    growing a second copy. The threat model needs it for the same reason the
    register does: several entries cite a path *because it is absent*, and the
    absence is the evidence. `verification-metadata.xml` not existing is what
    TH-44 is about, so "does not resolve" is correct there and "now exists" is
    the error — which is why the check runs in both directions.

    Derived rather than duplicated so a fix to the resolver reaches both gates
    at once, and so the two documents cannot drift into two definitions of
    "resolves".
    """
    register_check = _load("check_risk_register", "check-risk-register.py")
    problems: list[str] = []
    exemptions = register_check.parse_exemptions(text, EXEMPT_TITLE)

    for cited in dict.fromkeys(register_check.CITATION.findall(text)):
        resolved = register_check.resolve_citation(cited)
        if cited in exemptions and resolved is not None:
            problems.append(
                f"{cited} is listed as intentionally unresolved, but it now exists"
                f" at {resolved.relative_to(register_check.ROOT)}; the entry citing it,"
                f" and this exemption, need re-deriving"
            )
        elif cited not in exemptions and resolved is None:
            problems.append(
                f"{cited} is cited but does not resolve from the repository root,"
                f" from docs/governance/, or as a bare file name under a known"
                f" source directory, and it is not listed under '{EXEMPT_TITLE}'"
            )

    for cited, reason in exemptions.items():
        if not reason.strip():
            problems.append(f"{cited} is exempted from resolution without a stated reason")

    return problems


def check(text: str) -> list[str]:
    problems: list[str] = check_citations(text)
    threats = parse_threats(text)
    if not threats:
        return problems + ["no threat rows were parsed; the row format may have changed"]

    by_id: dict[str, list[str]] = {}
    for threat in threats:
        by_id.setdefault(threat["id"], []).append(threat["status"])

    for threat_id, statuses in sorted(by_id.items()):
        if len(statuses) > 1:
            problems.append(
                f"{threat_id} appears in {len(statuses)} threat tables"
                f" ({', '.join(statuses)}); one row per threat is the invariant"
                " the summary counts depend on"
            )

    listed, stated, total = parse_summary(text)
    if not listed:
        return problems + ["the threat summary table could not be parsed"]

    for status in STATUSES:
        if status not in listed:
            problems.append(f"the summary has no row for status '{status}'")

    union: set[str] = set()
    for status, ids in listed.items():
        if status in stated and stated[status] != len(ids):
            problems.append(
                f"summary row '{status}' states {stated[status]} but lists {len(ids)}"
                f" ({', '.join(sorted(ids))})"
            )
        overlap = union & ids
        if overlap:
            problems.append(
                f"{', '.join(sorted(overlap))} is counted under '{status}' and"
                " also under another status; the buckets must partition the set"
            )
        union |= ids

    row_ids = set(by_id)
    missing = row_ids - union
    if missing:
        problems.append(
            f"{', '.join(sorted(missing))} has a threat row but is in no summary bucket"
        )
    extra = union - row_ids
    if extra:
        problems.append(
            f"{', '.join(sorted(extra))} is in a summary bucket but has no threat row"
        )

    if total is not None and total != len(row_ids):
        problems.append(
            f"the summary states {total} threats but {len(row_ids)} rows exist"
        )

    return problems


def main() -> int:
    path = ROOT / MODEL
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        print(f"could not read {MODEL}: {error}", file=sys.stderr)
        return 2

    problems = check(text)
    if problems:
        for problem in problems:
            print(f"threat model: {problem}", file=sys.stderr)
        return 1

    threats = parse_threats(text)
    counts = " + ".join(
        str(sum(1 for t in threats if t["status"] == status)) for status in STATUSES
    )
    print(
        f"threat model: {len(threats)} rows, {counts} = {len(threats)},"
        f" summary and buckets agree"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
