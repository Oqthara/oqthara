#!/usr/bin/env python3
"""Checks that the Alpha 4 risk register agrees with itself and with the tree.

Four things are checked, because each has already been wrong once:

1. Every row's Exposure matches what the register's own exposure matrix resolves
   its Likelihood-by-Impact to. R-014 was filed as Medium while its row said Low.
2. The summary table's counts and ID lists are exactly the rows, per exposure,
   and its total is the number of rows. Adding R-028 left the counts describing
   a different distribution than the rows beneath them.
3. Every ID appears exactly once as a row, so a renumbered entry cannot leave a
   duplicate that the counts then absorb.
4. Every file path an entry cites still resolves, and every path an entry cites
   for its *absence* is still absent.

The commit that added R-028 claimed "a script now checks every row against the
matrix and each ID against the summary". No such script existed: the counts were
checked by hand and the claim was written as though it had been mechanised. This
is that script, added afterwards, and the register records the gap.

Check 4 was the register's own stated blind spot. It said its paths were
"resolved against the tree by hand, once", that the check "is not a gate" and
"will not notice if an entry starts citing a path that has since moved". A
maintainer filing an entry against a file that has been renamed produces a
citation that reads as authoritative and resolves to nothing. Both directions
matter, so absence is checked too: two entries exist precisely because a file
does *not* exist, and creating it would silently invalidate R-019 and R-022.

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


ROOT = Path(__file__).resolve().parent.parent

# A backticked string that looks like a repository path. The character class
# stops at whitespace and at the prose punctuation that wraps a citation
# (`...`, ``...``, `...`.), so `Stdlib.java` and `Stdlib.java` get the same
# treatment. No `*` or `?`, so a glob is not mistaken for a path.
CITATION = re.compile(
    r"`([A-Za-z0-9_][A-Za-z0-9_./-]*\.(?:java|py|yml|yaml|kts|md|json|xml|sh|ps1|mjs|oq|toml|txt|css|html))`"
)

# Directory prefixes that make a citation unambiguous when it is written as a
# bare file name, as R-003's `Stdlib.java` is. Tried in order.
SEARCH_DIRS = [
    Path("."),
    Path("oq-core/src/main/java/dev/oqthara/core"),
    Path("oq-runtime/src/main/java/dev/oqthara/runtime"),
    Path("oq-stdlib/src/main/java/dev/oqthara/stdlib"),
    Path("oq-compiler/src/main/java/dev/oqthara/compiler"),
    Path("oq-cli/src/main/java/dev/oqthara/cli"),
    Path("oq-lsp/src/main/java/dev/oqthara/lsp"),
    Path("scripts"),
    Path("docs/governance"),
    Path("docs"),
]

# The register lists, in its own text, the paths it cites knowing they do not
# resolve. Keeping the exemptions in the document rather than in this script
# means adding one is a visible edit a reviewer reads, and it lets the check run
# in both directions: an exempt path that starts existing is also an error,
# because R-019 and R-022 exist only while their paths are absent.
EXEMPT_TITLE = "Citations that intentionally do not resolve"

# Matched by pattern rather than by literal string because the register is a
# document that gets restructured: the section was written as `##`, and when it
# moved under a new `### The Alpha 7 boundary re-read` subsection it became `###`.
# A literal `startswith` then matched nothing, which silently disabled both the
# skip and the reverse check while every test still passed, because the tests
# rebuilt the document from the same wrong constant. The anchor is the title, not
# the level, and `TheExemptionHeadingIsReadFromTheRealDocument` pins it.
def exempt_heading(title: str = EXEMPT_TITLE) -> re.Pattern[str]:
    """The heading that introduces an exemption table, at any heading level.

    Parameterised by title so a second document can carry its own exemption
    table through this same code. The risk register and the threat model both
    cite paths that are deliberately absent — an entry *about* a missing file —
    and each document lists its own, because an exemption is a reviewed edit
    rather than data hidden in a checker.
    """
    return re.compile(r"^#{1,6}[ \t]+" + re.escape(title) + r"[ \t]*$", re.MULTILINE)


EXEMPT_HEADING = exempt_heading()


def exemption_block_end(text: str, title: str = EXEMPT_TITLE) -> int:
    """Offset just past the exemption table, or len(text) if there is no section."""
    heading = exempt_heading(title).search(text)
    if heading is None:
        return len(text)
    following = re.compile(r"^#{1,6}[ \t]+\S", re.MULTILINE).search(text, heading.end())
    return following.start() if following else len(text)


def parse_exemptions(text: str, title: str = EXEMPT_TITLE) -> dict[str, str]:
    """Reads the exemption table: unresolved path -> the reason it is cited anyway."""
    heading = exempt_heading(title).search(text)
    if heading is None:
        return {}
    block = text[heading.end():exemption_block_end(text, title)]
    exemptions: dict[str, str] = {}
    for line in block.splitlines():
        match = re.match(r"^\|\s*`([^`]+)`\s*\|\s*(.+?)\s*\|$", line)
        if match and not set(match.group(1)) <= set("-: "):
            exemptions[match.group(1)] = match.group(2)
    return exemptions


def resolve_citation(cited: str) -> Path | None:
    """Resolves a cited path the way a reader would, or None if nothing matches.

    A citation is accepted if it resolves from the repository root, or from the
    directory of the document that cites it (the register writes
    `alpha-4-requirement-matrix.md` and means the file beside it), or as a bare
    file name found under one of the well-known source directories. Anything
    else is reported rather than guessed at.
    """
    candidate = Path(cited)
    if candidate.is_absolute():
        return candidate if candidate.exists() else None
    for base in (ROOT, ROOT / "docs" / "governance"):
        if (base / candidate).exists():
            return base / candidate
    if "/" not in cited:
        for directory in SEARCH_DIRS:
            if (ROOT / directory / candidate).exists():
                return ROOT / directory / candidate
    return None


def check_citations(text: str) -> list[str]:
    """Every cited path resolves, and every exempted path still does not.

    The register used to say its paths were "resolved against the tree by hand,
    once", that the check "is not a gate" and "will not notice if an entry
    starts citing a path that has since moved". A citation to a renamed file
    reads as authoritative and resolves to nothing, which is how three stale
    numbers survived in a document whose purpose is to catch exactly that.
    """
    problems: list[str] = []
    exemptions = parse_exemptions(text)

    # The exemption block is a table, so its own two columns are the only part of
    # the document that is skipped. Slicing on the heading instead would let
    # anything appended after the table go unchecked, which is where a late entry
    # tends to land.
    body_lines: list[str] = []
    inside_exemptions = False
    for line in text.splitlines():
        if EXEMPT_HEADING.match(line):
            inside_exemptions = True
            continue
        if inside_exemptions:
            if re.match(r"^#{1,6}[ \t]+\S", line) or (line and not line.lstrip().startswith("|")):
                inside_exemptions = False
            else:
                continue
        body_lines.append(line)
    body = "\n".join(body_lines)

    for cited in dict.fromkeys(CITATION.findall(body)):
        resolved = resolve_citation(cited)
        if cited in exemptions and resolved is not None:
            problems.append(
                f"{cited} is listed as intentionally unresolved, but it now exists at"
                f" {resolved.relative_to(ROOT) if resolved.is_relative_to(ROOT) else resolved}"
                f"; the entry that cites it, and this exemption, need re-deriving"
            )
        elif cited not in exemptions and resolved is None:
            problems.append(
                f"{cited} is cited but does not resolve from the repository root or"
                f" from docs/governance/, and it is not listed under"
                f" '{EXEMPT_TITLE}'"
            )

    for cited, reason in exemptions.items():
        if not reason.strip():
            problems.append(f"{cited} is exempted from resolution without a stated reason")

    return problems


def check(text: str) -> list[str]:
    problems: list[str] = check_citations(text)
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
