#!/usr/bin/env python3
"""Checks that the Alpha 4 risk register agrees with itself.

Three things are checked, because each has already been wrong once:

1. Every row's Exposure matches what the register's own exposure matrix resolves
   its Likelihood-by-Impact to. R-014 was filed as Medium while its row said Low.
2. The summary table's counts and ID lists are exactly the rows, per exposure,
   and its total is the number of rows. Adding R-028 left the counts describing
   a different distribution than the rows beneath them.
3. Every ID appears exactly once as a row, so a renumbered entry cannot leave a
   duplicate that the counts then absorb.

The commit that added R-028 claimed "a script now checks every row against the
matrix and each ID against the summary". No such script existed: the counts were
checked by hand and the claim was written as though it had been mechanised. This
is that script, added afterwards, and the register records the gap.

Exit codes: 0 all checks pass, 1 a mismatch, 2 the document could not be read.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REGISTER = Path("docs/governance/alpha-4-risk-register.md")

# The matrix as written in the document. Kept here as data rather than parsed,
# because a checker that agrees with a malformed table is not a checker. A test
# asserts this dict still matches the table in the file.
MATRIX = {
    ("High", "Low"): "Low",
    ("High", "Medium"): "Medium",
    ("High", "High"): "Critical",
    ("Medium", "Low"): "Low",
    ("Medium", "Medium"): "Medium",
    ("Medium", "High"): "High",
    ("Low", "Low"): "Low",
    ("Low", "Medium"): "Low",
    ("Low", "High"): "Medium",
}

EXPOSURES = ["Critical", "High", "Medium", "Low"]

ROW = re.compile(
    r"^\|\s*(R-\d{3})\s*\|\s*(.+?)\s*\|\s*(High|Medium|Low)\s*\|\s*(High|Medium|Low)"
    r"\s*\|\s*\*{0,2}(Critical|High|Medium|Low)\*{0,2}\s*\|\s*(\d+)\s*\|\s*(\w+)\s*\|$"
)


def parse_matrix(text: str) -> dict[tuple[str, str], str]:
    """Reads the document's exposure matrix so a test can compare it to MATRIX."""
    found = {}
    for likelihood, low, medium, high in re.findall(
        r"^\|\s*\*{0,2}(High|Medium|Low)\*{0,2}\s*\|\s*\*{0,2}(\w+)\*{0,2}"
        r"\s*\|\s*\*{0,2}(\w+)\*{0,2}\s*\|\s*\*{0,2}(\w+)\*{0,2}\s*\|$",
        text,
        re.MULTILINE,
    ):
        found[(likelihood, "Low")] = low
        found[(likelihood, "Medium")] = medium
        found[(likelihood, "High")] = high
    return found


def parse_rows(text: str) -> list[dict[str, str]]:
    rows = []
    for line in text.splitlines():
        match = ROW.match(line)
        if match:
            rows.append(
                {
                    "id": match.group(1),
                    "risk": match.group(2),
                    "likelihood": match.group(3),
                    "impact": match.group(4),
                    "exposure": match.group(5),
                    "workstream": match.group(6),
                    "status": match.group(7),
                }
            )
    return rows


def parse_summary(text: str) -> tuple[dict[str, set[str]], dict[str, int], int | None]:
    """Reads the summary table: exposure -> the IDs it lists and the count it states."""
    section = text.split("## Summary by exposure", 1)
    if len(section) < 2:
        return {}, {}, None
    block = section[1].split("\n## ", 1)[0]

    listed: dict[str, set[str]] = {}
    stated: dict[str, int] = {}
    total = None
    for line in block.splitlines():
        total_match = re.match(r"^\|\s*\*{0,2}Total\*{0,2}\s*\|\s*\*{0,2}(\d+)\*{0,2}", line)
        if total_match:
            total = int(total_match.group(1))
            continue
        row = re.match(
            r"^\|\s*\*{0,2}(Critical|High|Medium|Low)\*{0,2}\s*\|\s*(\d+)\s*\|\s*(.*?)\s*\|$",
            line,
        )
        if row:
            exposure, count, ids = row.groups()
            listed[exposure] = set(re.findall(r"R-\d{3}", ids))
            stated[exposure] = int(count)
    return listed, stated, total


def check(text: str) -> list[str]:
    problems: list[str] = []
    rows = parse_rows(text)
    if not rows:
        return ["no risk rows were parsed; the row format may have changed"]

    documented_matrix = parse_matrix(text)
    if documented_matrix and documented_matrix != MATRIX:
        differs = sorted(
            f"{key}: document says {documented_matrix[key]}, checker says {MATRIX[key]}"
            for key in set(documented_matrix) | set(MATRIX)
            if documented_matrix.get(key) != MATRIX.get(key)
        )
        problems.append(
            "the exposure matrix in the document no longer matches the checker: "
            + "; ".join(differs)
        )

    seen: set[str] = set()
    for row in rows:
        if row["id"] in seen:
            problems.append(f"{row['id']} appears as a row more than once")
        seen.add(row["id"])
        expected = MATRIX.get((row["likelihood"], row["impact"]))
        if expected is None:
            problems.append(f"{row['id']} has an unknown likelihood/impact pair")
        elif row["exposure"] != expected:
            problems.append(
                f"{row['id']} is {row['likelihood']} likelihood by {row['impact']} impact,"
                f" which the matrix resolves to {expected}, but its row says"
                f" {row['exposure']}"
            )

    listed, stated, total = parse_summary(text)
    if not listed:
        problems.append("the summary table could not be parsed")
        return problems

    for exposure in EXPOSURES:
        actual = {row["id"] for row in rows if row["exposure"] == exposure}
        if actual != listed.get(exposure, set()):
            missing = sorted(actual - listed.get(exposure, set()))
            extra = sorted(listed.get(exposure, set()) - actual)
            detail = []
            if missing:
                detail.append("missing from the summary: " + ", ".join(missing))
            if extra:
                detail.append("in the summary but not a row: " + ", ".join(extra))
            problems.append(f"{exposure}: " + "; ".join(detail))
        # The count is checked separately: editing it alone leaves the ID list
        # intact, so a set comparison would not notice, and the historical bug
        # was precisely a count that disagreed with the rows.
        if stated.get(exposure) != len(actual):
            problems.append(
                f"{exposure}: the summary states {stated.get(exposure)} but"
                f" {len(actual)} rows have that exposure"
            )

    if total is None:
        problems.append("the summary has no total")
    elif total != len(rows):
        problems.append(f"the summary total is {total} but there are {len(rows)} rows")

    return problems


def main() -> int:
    try:
        text = REGISTER.read_text(encoding="utf-8")
    except OSError as unreadable:
        print(f"cannot read {REGISTER}: {unreadable}", file=sys.stderr)
        return 2

    problems = check(text)
    if problems:
        print(f"risk register: {len(problems)} problem(s)")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    rows = parse_rows(text)
    counts = {e: sum(1 for r in rows if r["exposure"] == e) for e in EXPOSURES}
    tally = " + ".join(str(counts[e]) for e in EXPOSURES)
    print(f"risk register: {len(rows)} rows, {tally} = {len(rows)}, matrix and summary agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
