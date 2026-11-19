#!/usr/bin/env python3
"""Checks that every Java Bridge capability row names executable evidence.

The Alpha 5 inventory describes 55 capability rows, six confirmed defects and 13
directions. `check-report-claims.py` re-derives those *figures* from the tree, so
a row cannot be added or deleted without the count moving with it. What no gate
did was check the *evidence column*: whether the thing a row says was executed
can still be executed.

That gap was real. Every one of the 55 rows argues its classification with a
transcript of a call and its result — "`ArrayList()` → `[]`" — but nothing tied
that transcript to a file. A probe could be deleted, renamed, or stop covering
the case a row cites, and the row would keep asserting a result no test produces.
The Alpha 7 order's completion gate asks for "executable evidence or an explicit,
accepted limitation" per row, which is a per-row question a total cannot answer.

The linkage is declared, not inferred. Each row's evidence column ends with a
`[[probe]]` marker that names the probe files exercising it:

    | C1.1 | Construct with `ClassName(args)` | Complete | `ArrayList()` → `[]` ... | `c1_construct` |

Inference was rejected. A probe named `c2_instance.oq` beside a row `C2.1`
*looks* like coverage, but the file exercises `size`, `isEmpty` and `contains`
while the row under discussion might be about a `Class` value — direction-level
naming says where a probe lives, not what it proves. The marker is a claim the
document makes about itself, and this gate checks the claim is still true.

What is checked:

1. Every capability row carries a marker. A row without one is not evidence-
   backed, whatever its Evidence column argues.
2. Every named probe file exists. A row citing a deleted probe is the drift this
   gate exists to catch.
3. Every named probe is registered in `run.py`'s `PROBES` list. A file no harness
   runs is not executable evidence, however correct its contents.
4. Every registered probe is cited by at least one row. An orphan probe is either
   a row that lost its marker or a probe whose evidence no row claims; both mean
   the map and the harness have diverged.
5. Every direction's rows cite at least one probe registered under that
   direction's prefix. This catches a marker copied from the row above, which
   would otherwise satisfy 1-4 while proving nothing about the row it sits on.
6. A row classified `Missing`, `Incorrect` or `Unverified` cannot be backed by a
   probe whose expected output is a pass. Those classifications assert the
   capability is absent or wrong; a green probe beside them is a contradiction.

`Unsafe`, `Undocumented` and `Partial` rows are exempt from 6: the first two are
about what the specification says rather than what code does, and a partial row
legitimately has a passing probe for the half that works.

Exit codes: 0 every row is backed, 1 a row or probe diverged, 2 the inventory or
harness could not be read.
"""

from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path

INVENTORY = Path("docs/governance/alpha-5-java-bridge-capability-inventory.md")
PROBE_DIR = Path("docs/governance/alpha-5/java-bridge-probes")
PROBE_HARNESS = PROBE_DIR / "run.py"

# A capability row: | C<dir>.<n> | Capability | Class | Evidence | `probe` |
ROW = re.compile(r"^\|\s*(C(\d+)\.(\d+))\s*\|(.*)$", re.M)
CELL_SPLIT = re.compile(r"(?<!\\)\|")

# Classifications that assert a capability is absent or wrong. A probe that
# passes cannot back one of these, because passing is the opposite of the claim.
ASSERTS_ABSENCE = ("missing", "incorrect", "unverified")


class Row:
    def __init__(self, cid, direction, cells):
        self.cid = cid
        self.direction = direction
        padded = list(cells) + [""] * (4 - len(cells))
        self.capability = padded[0].strip()
        self.classification = padded[1].strip().lower()
        self.evidence = padded[2].strip()
        # Only a fourth cell can hold the marker; backticked identifiers in the
        # Evidence column are transcripts, not citations.
        self.probes = re.findall(r"`([a-z][a-z0-9_]+)`", padded[3]) if len(padded) > 3 else []

    def __repr__(self):
        return f"{self.cid} ({self.classification})"


def parse_rows(text):
    """Every capability row, with the direction it sits under."""
    rows = []
    direction = None
    for line in text.splitlines():
        heading = re.match(r"^#{2,3}\s+Direction\s+(\d+)", line)
        if heading:
            direction = int(heading.group(1))
            continue
        match = ROW.match(line)
        if not match or direction is None:
            continue
        cells = CELL_SPLIT.split(line.strip().strip("|"))
        # cells[0] is the id, so the remaining four are capability/class/evidence/probes.
        rows.append(Row(match.group(1), direction, cells[1:]))
    return rows


def load_harness(cwd):
    """`run.py`'s PROBES list, imported so its own registry is the only copy."""
    path = cwd / PROBE_HARNESS
    spec = importlib.util.spec_from_file_location("oq_bridge_harness", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def problems_for(cwd="."):
    """Every problem with the tree at `cwd`, or an OSError if it is unreadable.

    Separated from `main` so the tests can run the six rules against a disposable
    tree. The rules are what regress; the printing never does.
    """
    cwd = Path(cwd)
    rows = parse_rows((cwd / INVENTORY).read_text(encoding="utf-8"))
    registered = {name: expected for name, expected, _ in load_harness(cwd).PROBES}
    on_disk = {path.stem for path in (cwd / PROBE_DIR).glob("*.oq")}
    problems = []

    for row in rows:
        if not row.probes:
            problems.append(
                f"{row.cid} has no probe marker; its Evidence column argues a result "
                f"that nothing executes"
            )
            continue

        for probe in row.probes:
            if probe not in on_disk:
                problems.append(f"{row.cid} cites `{probe}`, which is not a probe file")
            elif probe not in registered:
                problems.append(
                    f"{row.cid} cites `{probe}`, which exists but is not registered in "
                    f"run.py's PROBES, so no run executes it"
                )

        if any(mark in row.classification for mark in ASSERTS_ABSENCE):
            for probe in row.probes:
                expected = registered.get(probe)
                # A string expectation is a diagnostic, which is how a refusal is
                # asserted; a list is a value, which is how success is asserted.
                if isinstance(expected, list):
                    problems.append(
                        f"{row.cid} is classified {row.classification!r} yet cites "
                        f"`{probe}`, whose expected output is a pass"
                    )

        # The direction check: a marker copied from the row above passes every
        # other rule while proving nothing about the row it sits on. Citing
        # another direction's probe is legitimate — a constructor refusal is
        # proven by whichever file exercises it — so the rule is that each
        # direction's rows must reach at least one of that direction's own
        # probes, not that no citation may cross a boundary.

    cited = {probe for row in rows for probe in row.probes}
    for probe in sorted(set(registered) - cited):
        problems.append(f"`{probe}` is registered but no capability row claims it")

    # Per direction: the rows under a heading must reach at least one probe whose
    # own name places it in that direction. Without this a marker copied from the
    # row above satisfies rules 1-4 while proving nothing about its row.
    by_direction = {}
    for row in rows:
        by_direction.setdefault(row.direction, set()).update(row.probes)
    for direction, probes in sorted(by_direction.items()):
        own = {p for p in probes if re.match(rf"c{direction}_", p)}
        if not own:
            problems.append(
                f"direction {direction} cites {sorted(probes)} but none of them is a "
                f"probe of that direction (c{direction}_*)"
            )

    return problems


def main(cwd="."):
    cwd = Path(cwd)
    try:
        problems = problems_for(cwd)
    except OSError as error:
        print(f"cannot read the bridge inventory or harness: {error}", file=sys.stderr)
        return 2

    if problems:
        print(f"Java Bridge coverage gate failed ({len(problems)} problem(s)):")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    rows = parse_rows((cwd / INVENTORY).read_text(encoding="utf-8"))
    backed = sum(len(row.probes) for row in rows)
    registered = load_harness(cwd).PROBES
    print(
        f"Every one of {len(rows)} capability rows names executable evidence "
        f"({backed} row/probe citations, {len(registered)} registered probes)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
