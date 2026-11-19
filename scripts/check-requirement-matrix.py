#!/usr/bin/env python3
"""Fails when the Alpha 6 requirement matrix and the tree disagree.

`docs/governance/alpha-6-requirement-matrix.md` is the file a reader consults to
decide what Alpha 6 lacks, and until now no gate read it. The Alpha 4 matrix has
been checked by `check-docs-sync.py` since it was written; this file closes the
same hole for Alpha 6.

Four things are checkable without judging prose, and each is checked here:

1. Every cited `conformance/NNN` exists as `conformance/NNN-*.oq`.
2. Every cited `scripts/*.py`, ADR and source path exists.
3. Every quoted conformance figure (`215 compiled`, `224 executable`, `257
   programs`) matches a measurement derived from `conformance/` itself.
4. Every quoted probe total matches `run.py`'s `PROBES`, and the row that says
   "all 12 `NOT STARTED` rows exit 1" carries a count equal to the number of
   rows actually marked `NOT STARTED`.

A state cell is deliberately *not* checked against the code. Deciding whether a
capability is done is the judgement the matrix exists to record; deciding whether
the evidence it cites is real is mechanical. This gate does the second.

Dependency-free: Python 3 standard library only. Any unexpected exception is
reported as a failure, never swallowed.
"""

from __future__ import annotations

import importlib.util
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MATRIX = ROOT / "docs/governance/alpha-6-requirement-matrix.md"
STATES = ("DONE", "PARTIAL", "NOT STARTED", "BLOCKED", "REFUSED")

problems: list[str] = []
checked: list[str] = []


def note(what: str) -> None:
    checked.append(what)


def rows(text: str) -> list[tuple[int, list[str]]]:
    """The matrix's data rows as `(line number, four cells)`.

    A state cell is backticked (`| `DONE` |`), so the cell is stripped of
    backticks before it is compared. Header and separator rows fall out because
    their second cell is not a state.
    """
    out: list[tuple[int, list[str]]] = []
    for index, line in enumerate(text.splitlines(), start=1):
        if not line.startswith("| "):
            continue
        cells = [cell.strip().strip("`") for cell in line.strip().strip("|").split("|")]
        if len(cells) == 4 and cells[1] in STATES:
            out.append((index, cells))
    return out


def cited_conformance(text: str) -> set[int]:
    """Every `conformance/NNN` the document names, in any of its spellings.

    A bare number may be followed by the case's descriptive name
    (`conformance/257-swing-runs-with-no-display`), so the match stops at the
    number and the name is not read as part of it.
    """
    ids: set[int] = set()
    for match in re.finditer(r"conformance[/ ]`?(\d{3})", text):
        ids.add(int(match.group(1)))
    return ids


def probe_total() -> int:
    """The harness's own count, read from `PROBES` rather than from its output.

    Probes in `DISPLAY` are excluded because the default run excludes them: they need a display
    the harness provides and are run separately under `--under-display`. Counting them here
    would make the figure the document quotes disagree with the run it describes.
    """
    path = ROOT / "docs/governance/alpha-5/java-bridge-probes/run.py"
    spec = importlib.util.spec_from_file_location("probe_run", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return sum(1 for name, _, _ in module.PROBES if name not in module.DISPLAY)


def display_probe_total() -> int:
    """The probes only a display-backed run executes."""
    path = ROOT / "docs/governance/alpha-5/java-bridge-probes/run.py"
    spec = importlib.util.spec_from_file_location("probe_run_display", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return len(module.DISPLAY)


def executable_programs() -> tuple[int, int]:
    """`(executable, all)` conformance programs, by the `.err` convention."""
    programs = sorted((ROOT / "conformance").glob("*.oq"))
    executable = [p for p in programs if not p.with_suffix(".err").exists()]
    return len(executable), len(programs)


def cited_paths_are_real(text: str) -> None:
    pattern = r"`((?:docs|scripts|oq-[a-z]+|conformance)/[\w./-]+)`"
    for path in sorted(set(re.findall(pattern, text))):
        candidate = ROOT / path
        # A conformance case is cited without its extension (`conformance/257-swing-runs-with-no-display`),
        # so an exact match failing is not yet a problem.
        if candidate.exists() or candidate.with_suffix(".oq").exists():
            continue
        problems.append(f"the matrix cites {path}, which does not exist")
    note(f"every cited path resolves ({len(set(re.findall(pattern, text)))} checked)")


def cited_conformance_are_real(text: str) -> None:
    ids = cited_conformance(text)
    for number in sorted(ids):
        if not list((ROOT / "conformance").glob(f"{number:03d}-*.oq")):
            problems.append(
                f"the matrix cites conformance/{number:03d}, but no conformance/"
                f"{number:03d}-*.oq exists"
            )
    note(f"every cited conformance number resolves ({len(ids)} checked)")


def conformance_figures_agree(text: str) -> None:
    executable, total = executable_programs()
    for figure, measured, what in (
        ("executable", executable, "executable conformance programs"),
        ("programs", total, "conformance programs"),
    ):
        for match in re.finditer(rf"(\d+) of (?:the )?(\d+) {figure}|(\d+) {figure}", text):
            for value in filter(None, match.groups()):
                if int(value) != measured:
                    problems.append(
                        f"the matrix says {value} {what}, but conformance/ holds {measured}"
                    )
    note(f"conformance figures agree ({executable} executable of {total} programs)")


def probe_figures_agree(text: str) -> None:
    measured = probe_total()
    for match in re.finditer(r"(\d+) probes", text):
        if int(match.group(1)) != measured:
            problems.append(
                f"the matrix says {match.group(1)} probes, but run.py's PROBES holds {measured}"
            )
    note(f"probe figures agree ({measured} probes)")


def refusal_count_matches_the_rows(text: str) -> None:
    """The "all 12 `NOT STARTED` rows exit 1" sentence must count real rows.

    The sentence is evidence for the row above it, so a count that no longer
    matches the table makes the evidence claim more than it measured.
    """
    counted = sum(1 for _, cells in rows(text) if cells[1] == "NOT STARTED")
    match = re.search(r"all (\d+) `?NOT STARTED`? rows", text)
    if not match:
        problems.append("the matrix no longer states how many NOT STARTED rows exit 1")
        return
    if int(match.group(1)) != counted:
        problems.append(
            f"the matrix says all {match.group(1)} NOT STARTED rows exit 1, but "
            f"{counted} rows are marked NOT STARTED"
        )
    note(f"refusal evidence counts {counted} NOT STARTED rows")


def prose(text: str) -> str:
    """The document with fenced blocks removed.

    A fenced block is a transcript of what a command printed at the time, which
    is history rather than a claim about the tree — `check-report-claims.py`
    draws the same line for the Alpha 7 report. Prose is the matrix asserting a
    fact now, and that is what every check below reads. Backticked figures in
    prose are *not* exempt: unlike the report, this document uses inline code for
    its live evidence (`ok: 28 probes match their expectation`), so exempting it
    would exempt the claims the gate exists to read.
    """
    kept: list[str] = []
    fenced = False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if not fenced:
            kept.append(line)
    return "\n".join(kept)


def states_are_in_the_legend(text: str) -> None:
    """A state outside the legend is a typo the reader would take as a category."""
    legend = text.split("## Language surface")[0]
    for state in STATES:
        if state not in legend:
            problems.append(f"{state} is used but absent from the legend")
    note(f"all {len(STATES)} states appear in the legend")


def last_verified_pin_resolves(text: str) -> None:
    """The "Last verified" pin must name a commit reachable from HEAD.

    `check-docs-sync.py` enforces this for the Alpha 4 matrix. The Alpha 6
    matrix carried a pin that named no commit at all — "the commit named in the
    pull request that merged it" — which after a squash merge names a SHA that
    the squash discarded. A pin that cannot resolve is worse than none, because
    it reads as though the file was verified at a specific point.

    A shallow clone has no history to walk, so an unresolvable repository is
    reported rather than passed: the whole point is that the pin be checkable.
    """
    match = re.search(r"^\*\*Last verified:\*\* `([0-9a-f]{7,40})`", text, re.M)
    if not match:
        problems.append(
            "the matrix has no reachable 'Last verified' pin; a pin must name a commit"
        )
        return
    sha = match.group(1)
    try:
        resolved = subprocess.run(
            ["git", "rev-parse", "--verify", f"{sha}^{{commit}}"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        if resolved.returncode != 0:
            problems.append(f"the 'Last verified' pin {sha} is not a commit in this repository")
            return
        ancestor = subprocess.run(
            ["git", "merge-base", "--is-ancestor", resolved.stdout.strip(), "HEAD"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
    except OSError as missing:
        problems.append(f"cannot resolve the 'Last verified' pin without git: {missing}")
        return
    if ancestor.returncode != 0:
        problems.append(
            f"the 'Last verified' pin {sha} is not reachable from HEAD, so it names a "
            "commit a squash merge discarded"
        )
    note(f"'Last verified' pin {sha} is reachable from HEAD")


def main() -> int:
    try:
        text = MATRIX.read_text(encoding="utf-8")
    except OSError as unreadable:
        print(f"cannot read the requirement matrix: {unreadable}", file=sys.stderr)
        return 2

    live = prose(text)

    states_are_in_the_legend(text)
    cited_paths_are_real(live)
    cited_conformance_are_real(live)
    conformance_figures_agree(live)
    probe_figures_agree(live)
    refusal_count_matches_the_rows(live)
    last_verified_pin_resolves(text)

    if problems:
        print(f"requirement matrix: {len(problems)} problem(s)")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    for what in checked:
        print(f"checked: {what}")
    print(f"requirement matrix: every cited figure agrees across {len(rows(text))} rows")
    return 0


if __name__ == "__main__":
    sys.exit(main())
