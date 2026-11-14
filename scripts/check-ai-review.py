#!/usr/bin/env python3
"""Fails when a pull request has no well-formed AI review record.

ADR-0059 makes AI review the review mechanism for this repository. Before that decision the
obligation existed only in agent instructions and was enforced by nothing, so a pull request
could merge with no review at all and the audit trail looked the same either way.

This script is the enforcement. It is deliberately structural rather than persuasive: it can
prove a review was recorded, that every category was addressed, and that a critical finding was
not left unresolved. It cannot prove the review was thorough. A record with an empty findings
list where defects existed is a valid record and a bad review, and no automated check tells
those apart. The policy says so instead of implying otherwise.

Usage:
    scripts/check-ai-review.py [--pr NUMBER] [--root PATH]

The pull request number comes from --pr, then $GH_PR_NUMBER, then the GitHub Actions event
payload, then $GITHUB_REF (`refs/pull/224/merge`).

Dependency-free: Python 3 standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys

REVIEWS = pathlib.Path("docs") / "governance" / "reviews"

# The seven areas the policy requires a reviewer to evaluate. A record that omits one is
# incomplete, and the omission is exactly what this gate exists to catch.
CATEGORIES = (
    "functional correctness",
    "architecture and maintainability",
    "security and privacy",
    "regression and compatibility",
    "test coverage and validation",
    "documentation accuracy",
    "release integrity",
)

# Categories that a documentation-only or governance-only change may legitimately not reach.
# They must still be listed -- the record says so, rather than staying silent.
NOT_APPLICABLE_ALLOWED = {"release integrity"}

SEVERITIES = ("critical", "high", "medium", "low")
# A finding at these severities must carry a resolution. ADR-0059 requires critical and high
# findings to be resolved and validated before integration.
MUST_RESOLVE = {"critical", "high"}

# Cell values that mean "still open" however they are spelled.
DEFERRED = {
    "deferred", "won't fix", "wont fix", "will not fix", "tbd", "todo", "still open",
    "open", "pending", "later", "backlog", "accepted risk", "no action",
}
VERDICTS = {"pass", "fail"}

problems: list[str] = []
checked: list[str] = []


def note(what: str) -> None:
    checked.append(what)


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


def resolve_pr_number(explicit: str | None) -> str | None:
    if explicit:
        return explicit.strip()
    env = os.environ.get("GH_PR_NUMBER", "").strip()
    if env:
        return env
    event_path = os.environ.get("GITHUB_EVENT_PATH", "")
    if event_path and pathlib.Path(event_path).exists():
        try:
            payload = json.loads(pathlib.Path(event_path).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            payload = {}
        number = (payload.get("pull_request") or {}).get("number")
        if number:
            return str(number)
    ref = os.environ.get("GITHUB_REF", "")
    match = re.match(r"refs/pull/(\d+)/", ref)
    if match:
        return match.group(1)
    return None


def record_path(root: pathlib.Path, number: str) -> pathlib.Path:
    return root / REVIEWS / f"PR-{number}.md"


def tables(text: str) -> list[list[list[str]]]:
    """Return every Markdown table in `text` as a list of rows of cells."""
    found: list[list[list[str]]] = []
    current: list[list[str]] = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("|") and stripped.endswith("|"):
            cells = [cell.strip() for cell in stripped.strip("|").split("|")]
            # The separator row (| --- | --- |) carries no data.
            if all(re.fullmatch(r":?-{2,}:?", cell) for cell in cells if cell):
                continue
            current.append(cells)
        else:
            if current:
                found.append(current)
                current = []
    if current:
        found.append(current)
    return found


def header_and_rows(table: list[list[str]]) -> tuple[list[str], list[list[str]]]:
    if not table:
        return [], []
    return [cell.lower() for cell in table[0]], table[1:]


def check_field(text: str, label: str) -> str | None:
    """Read a `**Label:** value` line, returning the value or None."""
    match = re.search(rf"^\*\*{re.escape(label)}:\*\*[ \t]*(.+)$", text, re.M)
    return match.group(1).strip() if match else None


def check_paragraph(text: str, label: str) -> str | None:
    """Read a `**Label:**` block including its wrapped continuation lines.

    A statement of independence does not fit on one line, and checking only the first line
    rejects a record that answered the question correctly.
    """
    match = re.search(rf"^\*\*{re.escape(label)}:\*\*[ \t]*(.*)$", text, re.M)
    if not match:
        return None
    lines = text[match.start():].splitlines()
    kept = [lines[0]]
    for line in lines[1:]:
        if not line.strip() or line.lstrip().startswith(("#", "|", "**")):
            break
        kept.append(line)
    joined = " ".join(part.strip() for part in kept).strip()
    return re.sub(rf"^\*\*{re.escape(label)}:\*\*[ \t]*", "", joined).strip()


# A model identifier is written vendor/name, as the agent files pin them
# (`deepseek-ai/deepseek-v4.1-flash-1017`). Requiring the literal word "model" would reject a
# record that named the model precisely, so either form is accepted.
MODEL_SLUG = re.compile(r"[\w.-]+/[\w.-]+")

# A claim of independence has to describe *how* it was preserved, not just assert it. Thirteen
# words is not a quality bar -- it only rules out "yes" and "yes, trust me".
MIN_INDEPENDENCE_WORDS = 13


def models_named(text: str) -> list[str]:
    return [slug.lower() for slug in MODEL_SLUG.findall(text)]


# What the agents in this repository pin. This is the fallback for a checkout that does not
# carry .github/agents -- a test fixture, or a partial export. scripts/check-docs-sync.py checks
# it against the agent files, so the two cannot drift apart silently.
FALLBACK_PINNED = {"deepseek-ai/deepseek-v4.1-flash-1017"}


def pinned_models(root: pathlib.Path) -> set[str]:
    """Every model an agent in this repository pins, lowercased."""
    pinned = set()
    agents = root / ".github" / "agents"
    if agents.is_dir():
        for agent in agents.glob("*.agent.md"):
            match = re.search(r"^model:[ \t]*(\S+)[ \t]*$", read(agent), re.M)
            if match:
                pinned.add(match.group(1).strip("\"'").lower())
    return pinned or set(FALLBACK_PINNED)


def cites_a_commit(root: pathlib.Path, resolution: str) -> bool:
    """True when a resolution names a commit that resolves in this repository.

    The resolution cell must carry evidence, not just a claim that the fix happened. A SHA in
    backticks is the convention; a cell with no SHA at all has no evidence to check.
    """
    shas = re.findall(r"`([0-9a-f]{7,40})`", resolution)
    if not shas:
        return False
    return any(commit_exists(root, sha)[0] for sha in shas)


def commit_exists(root: pathlib.Path, sha: str) -> tuple[bool, bool]:
    """Return (exists, is_shallow). A shallow clone cannot disprove a SHA."""
    shallow = subprocess.run(
        ["git", "rev-parse", "--is-shallow-repository"],
        cwd=root, capture_output=True, text=True,
    ).stdout.strip() == "true"
    if not re.fullmatch(r"[0-9a-f]{7,40}", sha):
        return False, shallow
    result = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{sha}^{{commit}}"],
        cwd=root, capture_output=True, text=True,
    )
    return result.returncode == 0, shallow


# Automated pull requests cannot author a review record about themselves, and requiring one
# would stop every dependency update. The exemption is narrow, is a login check rather than a
# label a contributor can set, and the change still passes every other required context.
AUTOMATED_AUTHORS = {"dependabot[bot]", "renovate[bot]", "github-actions[bot]"}


def automated_author(root: pathlib.Path, number: str) -> str | None:
    """Return the automated login that opened this pull request, if any."""
    if not os.environ.get("CI"):
        return None
    try:
        author = subprocess.run(
            ["gh", "pr", "view", number, "--json", "author", "--jq", ".author.login"],
            cwd=root, capture_output=True, text=True, timeout=30,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    return author if author in AUTOMATED_AUTHORS else None


def validate(root: pathlib.Path, number: str, allow_shallow: bool = False) -> None:
    path = record_path(root, number)
    display = path.relative_to(root)

    if not path.exists():
        bot = automated_author(root, number)
        if bot:
            note(f"{display} is absent, and PR #{number} was opened by {bot}, an automated "
                 f"author that cannot review its own change; every other required context "
                 f"still applies")
            return
        problems.append(
            f"{display} is missing: PR #{number} has no AI review record. "
            f"ADR-0059 requires one per pull request; see "
            f"docs/governance/ai-review-policy.md"
        )
        return

    text = read(path)

    heading = re.search(r"^#\s+AI review\s+.\s+PR\s+#(\d+)\s*$", text, re.M)
    if not heading:
        problems.append(f"{display} has no '# AI review — PR #<n>' heading")
    elif heading.group(1) != number:
        problems.append(
            f"{display} is named for PR #{number} but its heading says PR #{heading.group(1)}"
        )

    declared = check_field(text, "Pull request")
    if declared is None:
        problems.append(f"{display} has no '**Pull request:**' line")
    elif declared.strip().lstrip("#") != number:
        problems.append(
            f"{display} declares pull request '{declared}' but is the record for #{number}"
        )

    for label in ("Reviewer", "Implementer"):
        if not check_field(text, label):
            problems.append(f"{display} does not name the {label.lower()}")

    reviewed_at = check_field(text, "Reviewed at")
    if not reviewed_at:
        problems.append(f"{display} does not say which commit it reviewed")
    else:
        sha = reviewed_at.strip("`").split()[0] if reviewed_at.strip("`").split() else ""
        exists, shallow = commit_exists(root, sha)
        if not exists:
            if shallow and allow_shallow:
                note(f"{display} reviewed {sha}; a shallow clone cannot confirm it exists")
            elif shallow:
                problems.append(
                    f"{display} says it reviewed '{sha}', which this shallow clone cannot "
                    f"resolve. Fetch the full history, or pass --allow-shallow if the "
                    f"degradation is acceptable"
                )
            else:
                problems.append(
                    f"{display} says it reviewed '{sha}', which is not a commit in this repository"
                )

    # The independence statement. ADR-0059 requires the record to say, in words, whether the
    # reviewer was independent of the implementer -- and every agent pins the same model, so the
    # honest answer is normally "no".
    independence = check_paragraph(text, "Independent")
    if not independence:
        problems.append(
            f"{display} has no '**Independent:**' line. Every agent pins the same model, so a "
            f"record that stays silent on independence reads as a claim of independence it "
            f"cannot support (ADR-0059)"
        )
    elif not re.match(r"(yes|no)\b", independence, re.I):
        problems.append(
            f"{display} '**Independent:**' must start with 'yes' or 'no', got '{independence}'"
        )
    else:
        claimed_independent = independence.lower().startswith("yes")
        named = set(models_named(independence))
        pinned = pinned_models(root)
        if claimed_independent:
            # ADR-0059: independence needs a different model *and* a mechanism that stops the
            # reviewer inheriting the implementer's framing. Checking only the first word
            # would accept "yes", which is a claim with nothing behind it.
            if not named:
                problems.append(
                    f"{display} claims independence but names no model, so there is nothing to "
                    f"check the claim against (ADR-0059)"
                )
            elif not named - pinned:
                problems.append(
                    f"{display} claims independence but names only {sorted(named)}, which every "
                    f"agent in this repository pins; a reviewer running the same model is not "
                    f"independent of the implementer"
                )
            elif len(independence.split()) < MIN_INDEPENDENCE_WORDS:
                problems.append(
                    f"{display} claims independence without describing the mechanism that keeps "
                    f"the reviewer from inheriting the implementer's framing; a different model "
                    f"alone is not enough (ADR-0059)"
                )
        elif not named:
            problems.append(
                f"{display} says it is not independent but does not name any model, so a reader "
                f"cannot tell what the reviewer ran or why the two are related"
            )

    # The categories table.
    category_table = None
    for table in tables(text):
        header, rows = header_and_rows(table)
        if header[:1] == ["category"]:
            category_table = (header, rows)
            break
    if category_table is None:
        problems.append(f"{display} has no '| Category | ... |' table")
        return

    header, rows = category_table
    for required in ("verdict", "findings"):
        if required not in header:
            problems.append(f"{display} category table has no '{required}' column")
            return
    verdict_at = header.index("verdict")
    count_at = header.index("findings")

    seen: dict[str, int] = {}
    for row in rows:
        if len(row) <= max(verdict_at, count_at):
            problems.append(f"{display} has a malformed category row: {' | '.join(row)}")
            continue
        name = row[0].strip("*` ").lower()
        verdict = row[verdict_at].strip("*` ").lower()
        if name not in CATEGORIES:
            problems.append(
                f"{display} lists '{name}', which is not one of the seven required categories"
            )
            continue
        if name in seen:
            problems.append(f"{display} lists the category '{name}' twice")
        if verdict not in VERDICTS and "not applicable" not in verdict:
            problems.append(
                f"{display} gives '{name}' the verdict '{verdict}'; expected pass or fail"
            )
        if "not applicable" in verdict and name not in NOT_APPLICABLE_ALLOWED:
            problems.append(
                f"{display} marks '{name}' as not applicable, but only "
                f"{sorted(NOT_APPLICABLE_ALLOWED)} may be omitted"
            )
        try:
            count = int(row[count_at].strip("*` "))
            if count < 0:
                problems.append(
                    f"{display} gives '{name}' the finding count {count}; a count cannot be "
                    f"negative, and negative counts let the totals cancel out"
                )
            else:
                seen[name] = count
        except ValueError:
            problems.append(
                f"{display} gives '{name}' the finding count '{row[count_at]}', which is not a "
                f"number"
            )

    missing = [name for name in CATEGORIES if name not in seen]
    if missing:
        problems.append(
            f"{display} does not address {len(missing)} of the seven required categories: "
            + ", ".join(missing)
        )

    # A `fail` verdict anywhere means the change was not approved by this review.
    for row in rows:
        if len(row) > verdict_at and row[verdict_at].strip("*` ").lower() == "fail":
            problems.append(
                f"{display} records a failing verdict for '{row[0].strip('*` ')}'; a change "
                f"that failed review cannot be integrated"
            )

    # The findings table, if any findings are declared.
    declared_findings = sum(seen.values())
    findings_table = None
    for table in tables(text):
        header_f, rows_f = header_and_rows(table)
        if header_f[:1] in (["#"], ["id"]) and "severity" in header_f:
            findings_table = (header_f, rows_f)
            break

    if declared_findings and findings_table is None:
        problems.append(
            f"{display} counts {declared_findings} finding(s) but has no findings table with a "
            f"severity column"
        )
        return

    if findings_table is not None:
        header_f, rows_f = findings_table
        severity_at = header_f.index("severity")
        resolution_at = header_f.index("resolution") if "resolution" in header_f else None
        if resolution_at is None:
            problems.append(f"{display} findings table has no 'resolution' column")
            return
        if len(rows_f) != declared_findings:
            problems.append(
                f"{display} counts {declared_findings} finding(s) in its category table but "
                f"lists {len(rows_f)}; one of the two is wrong"
            )
        for row in rows_f:
            if len(row) <= max(severity_at, resolution_at):
                problems.append(f"{display} has a malformed finding row: {' | '.join(row)}")
                continue
            severity = row[severity_at].strip("*` ").lower()
            if severity not in SEVERITIES:
                problems.append(
                    f"{display} records the severity '{severity}', expected one of "
                    + ", ".join(SEVERITIES)
                )
                continue
            resolution = row[resolution_at].strip()
            if severity in MUST_RESOLVE:
                absent = not resolution or resolution in {"—", "-", "n/a"}
                # A non-empty cell is not a resolution. "deferred", "won't fix" and "TBD" are
                # all non-empty and all mean the finding is still open, which is exactly what
                # the policy says cannot happen for these severities.
                deferred = resolution.lower().strip(" .*`") in DEFERRED
                if absent or deferred or not cites_a_commit(root, resolution):
                    problems.append(
                        f"{display} leaves a {severity} finding unresolved: "
                        f"'{' | '.join(row)[:90]}'. ADR-0059 requires critical and high "
                        f"findings to be resolved and validated in the pull request that "
                        f"found them, with a commit that exists here as the evidence"
                    )

    note(f"{display} is a well-formed review record for PR #{number} "
         f"({len(seen)}/7 categories, {declared_findings} finding(s))")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--pr", help="pull request number (default: from the environment)")
    parser.add_argument("--root", default=".", help="repository root (default: .)")
    parser.add_argument(
        "--allow-shallow",
        action="store_true",
        help="accept a reviewed commit that a shallow clone cannot resolve",
    )
    args = parser.parse_args(argv)

    root = pathlib.Path(args.root).resolve()
    number = resolve_pr_number(args.pr)
    if not number:
        print(
            "check-ai-review: error: no pull request number. Pass --pr, set GH_PR_NUMBER, or "
            "run on a pull_request event.",
            file=sys.stderr,
        )
        return 2
    if not re.fullmatch(r"\d+", number):
        print(f"check-ai-review: error: '{number}' is not a pull request number", file=sys.stderr)
        return 2

    try:
        validate(root, number, args.allow_shallow)
    except Exception as error:  # a broken check must fail loudly, never pass silently
        problems.append(f"validation raised {type(error).__name__}: {error}")

    if problems:
        print(f"AI review gate failed for PR #{number}:")
        for problem in problems:
            print("  -", problem)
        return 1

    for item in checked:
        print("  -", item)
    print(f"AI review gate passed for PR #{number}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
