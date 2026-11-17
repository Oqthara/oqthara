#!/usr/bin/env python3
"""Checks that the workflows holding write scope pin their actions to a commit SHA.

TH-45 and R-008 record that every `uses:` in this repository referenced a moving
tag. A tag is mutable: `actions/checkout@v7` is whatever the owner sets `v7` to
at the moment the job starts, so an action that changes hands, or an account that
is compromised, executes on the next run without any commit here. On a read-only
workflow that is a supply-chain problem. On a workflow holding `contents: write`,
`id-token: write` or a repository secret it is a release-integrity problem: the
action can publish, sign, or use the token.

A7.5 answers it by pinning those two workflows to commit SHAs with a comment
naming the version, because a bare SHA is unreviewable.

This gate is not a blanket rule, deliberately. The read-only workflows keep
major-version tags, because that is where Dependabot's bump behaviour works
without a config change, and an all-or-nothing rule here would be abandoned at
the first exception. Which workflows are in scope is therefore derived from what
they declare, not from a list in this file:

* `contents: write` or any other `write` scope, or
* `id-token: write`, which can mint a signing identity, or
* any `secrets.NAME` reference, since the secret's permissions are not visible
  here and assuming they are narrow is how this goes wrong.

A workflow added later with a write scope is caught without anyone remembering
to add it to a list. A workflow that drops its write scope leaves scope on its
own, and the gate then says so rather than silently continuing to demand pins.

Exit codes: 0 all in-scope workflows are pinned, 1 a moving tag in scope or an
action pin without a version comment, 2 a workflow could not be read or parsed.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOWS = Path(".github/workflows")

# A pinned ref is a 40-character lowercase hex commit. Anything else -- a tag, a
# branch, a short SHA -- is mutable in the sense this gate cares about: a short
# SHA cannot be relied on to be unambiguous, and a tag is not a commit at all.
PINNED = re.compile(r"^[0-9a-f]{40}$")

# `uses: owner/repo@ref`, optionally with a subpath and a trailing comment that
# should name the version. `uses:` can appear as a step or a job-level reusable
# workflow call, so the pattern is anchored on the key rather than on the indent.
USES = re.compile(r"^\s*(?:-\s*)?uses:\s*(\S+)\s*(?:#\s*(.*))?$")

# Scopes that mean the job can change something outside itself.
WRITE_SCOPE = re.compile(r"^\s*[a-z-]+:\s*write\s*$")
SECRET_REF = re.compile(r"secrets\.[A-Za-z_][A-Za-z0-9_]*")


def read_workflows() -> list[tuple[str, str]]:
    """Every workflow file as (name, text), sorted so the report is stable."""
    files = sorted(WORKFLOWS.glob("*.yml"))
    return [(path.name, path.read_text(encoding="utf-8")) for path in files]


def holds_write_scope(text: str) -> str | None:
    """Why this workflow is in scope, or None if it is read-only.

    Returns the reason rather than a bool so the report can say what put a file
    in scope -- "it declares a secret" and "it declares id-token: write" call for
    different review attention.
    """
    for line in text.splitlines():
        stripped = line.split("#", 1)[0].rstrip()
        if WRITE_SCOPE.match(stripped):
            return f"declares {stripped.strip()}"
    for match in SECRET_REF.finditer(text):
        # Ignore mentions inside comments; a workflow that documents its secret
        # without using it is not holding one.
        line = text[: match.start()].rsplit("\n", 1)[-1]
        if "#" not in line:
            return f"references {match.group(0)}"
    return None


def uses_lines(text: str) -> list[tuple[int, str, str]]:
    """Every `uses:` as (line number, value, trailing comment)."""
    found = []
    for number, line in enumerate(text.splitlines(), start=1):
        match = USES.match(line)
        if match:
            found.append((number, match.group(1), (match.group(2) or "").strip()))
    return found


def check_workflow(name: str, text: str) -> list[str]:
    """Problems with one workflow, empty when it is pinned or out of scope."""
    reason = holds_write_scope(text)
    if reason is None:
        return []
    problems = []
    for number, value, comment in uses_lines(text):
        if "@" not in value:
            problems.append(f"{name}:{number} `{value}` names no ref at all")
            continue
        _, ref = value.rsplit("@", 1)
        if PINNED.match(ref):
            if not comment:
                problems.append(
                    f"{name}:{number} `{value}` is pinned but the comment does not name"
                    f" the version, which makes the pin unreviewable"
                )
            continue
        problems.append(
            f"{name}:{number} `{value}` uses the mutable ref `{ref}`; this workflow"
            f" {reason}, so it must pin a commit SHA with a `# vX.Y.Z` comment"
        )
    return problems


def check() -> list[str]:
    problems = []
    workflows = read_workflows()
    if not workflows:
        return [f"no workflows found under {WORKFLOWS}"]
    for name, text in workflows:
        problems.extend(check_workflow(name, text))
    return problems


def summarise() -> str:
    """One line naming what is in scope, so the gate's coverage is visible."""
    in_scope = []
    for name, text in read_workflows():
        reason = holds_write_scope(text)
        if reason:
            in_scope.append(f"{name} ({reason})")
    return (
        f"{len(in_scope)} of {len(read_workflows())} workflows hold write scope and are pinned: "
        + "; ".join(in_scope)
    )


def main() -> int:
    try:
        problems = check()
    except OSError as error:
        print(f"action pins: {error}", file=sys.stderr)
        return 2
    if problems:
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"action pins: {summarise()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
