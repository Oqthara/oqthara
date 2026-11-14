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

# Language that means "still open" however it is spelled. Matched as substrings inside the
# resolution cell, because "Deferred; tracking issue 12" is a deferral too. This is a
# heuristics list, not a proof: no finite list covers every way of saying a fix is not done,
# and the policy says so rather than promising exhaustiveness.
DEFERRED_PHRASES = (
    "defer", "postpon", "shelv", "won't fix", "wont fix", "will not fix", "not fix",
    "unfix", "tbd", "todo", "to do", "still open", "left open", "remains open", "remain open",
    "open issue", "no action", "not address", "unaddress", "out of scope", "follow-up",
    "follow up", "later pr", "later release", "future work", "next pr", "another pr",
    "subsequent pr", "pending", "not yet",
)
# Words deliberately absent: "skipped", "ignored", "unchanged", "untouched", "later". Each
# appears in ordinary descriptions of a completed fix -- "fenced blocks are skipped", "the
# existing check is unchanged" -- and treating them as deferrals rejected correct records.
# A resolution may legitimately say it is *not* a deferral. Those words are removed before the
# scan, so "this is not a deferral; fixed in `abc1234`" is not rejected for naming the thing it
# is denying.
DEFERRAL_NEGATIONS = ("not ", "no ", "never ", "non-", "n't ")
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


FENCE = re.compile(r"^\s*(```|~~~)")


def split_row(line: str) -> list[str]:
    r"""Cells of a table row, honouring `\|` as a literal pipe inside a cell.

    Splitting on every `|` puts a cell's remaining text into columns nobody reads, and the
    column positions are what decide a verdict, so a legitimate `` `a \| b` `` in a location
    silently moved a resolution out of the cell the gate examines.
    """
    stripped = line.strip().strip("|")
    cells, current, escaped = [], [], False
    for char in stripped:
        if escaped:
            current.append(char)
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == "|":
            cells.append("".join(current).strip())
            current = []
        else:
            current.append(char)
    cells.append("".join(current).strip())
    return cells


def tables(text: str) -> list[list[list[str]]]:
    """Return every Markdown table in `text` as a list of rows of cells.

    Fenced blocks are skipped: a record that quotes the template, or the rejected shape it is
    arguing against, would otherwise have that quotation parsed as data. A record documenting
    why a pattern fails must not fail because it documented it.
    """
    found: list[list[list[str]]] = []
    current: list[list[str]] = []
    in_fence = False
    for line in text.splitlines():
        stripped = line.strip()
        if FENCE.match(line):
            in_fence = not in_fence
            if current:
                found.append(current)
                current = []
            continue
        if in_fence:
            continue
        if stripped.startswith("|") and stripped.endswith("|"):
            cells = split_row(stripped)
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


# Values that name no party. Without this, a record whose Reviewer and Implementer are both
# `—` satisfied "does not name the reviewer" while naming nothing.
PLACEHOLDER_FIELDS = {
    "", "-", "--", "—", "–", "n/a", "na", "none", "tbd", "todo", "null", "x",
    "reviewer", "implementer", "author", "agent", "unknown", "same as above", "see above",
    "<reviewer>", "<implementer>", "<name>", "<agent>",
}


def check_field(text: str, label: str) -> str | None:
    r"""Read a `**Label:** value` line, returning the value or None.

    Accepts `**Label**: value` as well as `**Label:** value`, and any capitalisation of the
    label, because both forms are ordinary Markdown and rejecting one is a defect of the gate
    rather than of the record.
    """
    match = re.search(
        rf"^\*\*{re.escape(label)}(?::\*\*|\*\*:)[ \t]*(.+)$", text, re.M | re.I
    )
    return match.group(1).strip() if match else None


def names_a_party(value: str | None) -> bool:
    """True when a Reviewer/Implementer value actually identifies somebody."""
    if value is None:
        return False
    return re.sub(r"[`*_.]", "", value).strip().lower() not in PLACEHOLDER_FIELDS


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
    r"""Model identifiers named in `text`, excluding anything that is really a URL.

    `[\w.-]+/[\w.-]+` also matches `example.com/run`, so a record could claim independence by
    pointing at a link. A slug that follows a scheme or a domain-ish host is not a model name.
    """
    named = []
    for match in MODEL_SLUG.finditer(text):
        before = text[: match.start()].rstrip()
        if before.endswith(("://", "//")) or "@" in before[-1:]:
            continue
        if re.fullmatch(r"[a-z]+\.[a-z]{2,}", match.group(0).split("/")[0]):
            continue
        named.append(match.group(0).lower())
    return named


# "This is not a deferral" names the thing it denies. Only these meta-words are stripped when
# negated; "not fixed" is a deferral and must stay readable as one.
DENIED_DEFERRAL = re.compile(
    r"\b(?:not|never|no|isn't|aren't|wasn't|weren't|n't)\s+(?:a\s+|an\s+)?"
    r"(?:deferral|deferment|deferred|postponement|postponed|todo|tbd|follow-?up)\b"
)


def mentions_deferral(resolution: str) -> bool:
    """True when a resolution cell says the finding is still open in any wording."""
    normalized = re.sub(r"[`*_]", "", resolution).lower()
    normalized = DENIED_DEFERRAL.sub(" ", normalized)
    return any(phrase in normalized for phrase in DEFERRED_PHRASES)


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


def first_commit_sha(value: str) -> str | None:
    r"""The first commit SHA in `value`, ignoring backticks, case and trailing annotation.

    `Reviewed at` is prose around a SHA: it may carry a date, a parenthetical, a round number, or
    a full 40-character SHA in capitals. Reading the first whitespace-separated token made every
    one of those a rejection.
    """
    for token in re.findall(r"[0-9a-fA-F]{7,40}", value):
        if re.fullmatch(r"[0-9a-fA-F]{7,40}", token):
            return token.lower()
    return None


def cites_a_commit(root: pathlib.Path, resolution: str) -> bool:
    """True when a resolution names a commit that resolves in this repository.

    The resolution cell must carry evidence, not just a claim that the fix happened. A SHA in
    backticks is the convention; a cell with no SHA at all has no evidence to check.
    """
    shas = re.findall(r"`([0-9a-fA-F]{7,40})`", resolution)
    if not shas:
        return False
    for sha in shas:
        exists, shallow = commit_exists(root, sha.lower())
        if not exists:
            continue
        # The cited commit has to be in the history the gate is checking out. Otherwise a
        # resolution can name any commit in the repository -- another branch, an unrelated fix
        # merged long ago -- and be read as evidence that this finding was resolved.
        if shallow:
            return True
        if is_ancestor(root, sha.lower()):
            return True
    return False


def is_ancestor(root: pathlib.Path, sha: str) -> bool:
    """True when `sha` is reachable from HEAD."""
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", sha, "HEAD"],
        cwd=root, capture_output=True, text=True,
    )
    return result.returncode == 0


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


class AuthorLookup(Exception):
    """The author of a pull request could not be determined."""


def automated_author(root: pathlib.Path, number: str) -> str | None:
    """Return the automated login that opened this pull request, or None.

    Raises `AuthorLookup` when the question cannot be answered at all -- a missing `gh`, an
    unauthenticated token, or a failed call. That distinction matters: "not a bot" and "could
    not ask" lead to the same rejection but mean different things, and reporting the first when
    the second is true sends a maintainer looking for a missing file instead of a broken token.
    """
    try:
        result = subprocess.run(
            ["gh", "pr", "view", number, "--json", "author", "--jq", ".author.login"],
            cwd=root, capture_output=True, text=True, timeout=30,
        )
    except FileNotFoundError as error:
        raise AuthorLookup("the `gh` command is not installed") from error
    except (OSError, subprocess.SubprocessError) as error:
        raise AuthorLookup(f"the `gh` call failed: {error}") from error
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip().splitlines()
        raise AuthorLookup(
            "`gh pr view` failed" + (f": {detail[0]}" if detail else "")
            + ". The step needs a GH_TOKEN with pull-requests: read")
    author = result.stdout.strip()
    if not author:
        raise AuthorLookup("`gh pr view` returned an empty author")
    return author if author in AUTOMATED_AUTHORS else None


def validate(root: pathlib.Path, number: str, allow_shallow: bool = False) -> None:
    path = record_path(root, number)
    display = path.relative_to(root)

    if not path.exists():
        try:
            bot = automated_author(root, number)
        except AuthorLookup as error:
            problems.append(
                f"could not determine the author of PR #{number}: {error}. A review record is "
                f"required unless the author is an automated account")
            return
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

    parties = {}
    for label in ("Reviewer", "Implementer"):
        value = check_field(text, label)
        if not names_a_party(value):
            problems.append(f"{display} does not name the {label.lower()}")
        else:
            parties[label] = value.strip().lower()

    # The order forbids presenting one party as the other. A record that lists the same agent as
    # both did not separate the two roles, whatever it says elsewhere.
    if len(parties) == 2 and parties["Reviewer"] == parties["Implementer"]:
        problems.append(
            f"{display} names the same party as Reviewer and Implementer; the order requires "
            f"implementation and review to be separate wherever practical"
        )

    reviewed_at = check_field(text, "Reviewed at")
    if not reviewed_at:
        problems.append(f"{display} does not say which commit it reviewed")
    else:
        sha = first_commit_sha(reviewed_at)
        exists, shallow = commit_exists(root, sha) if sha else (False, False)
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
    # Every table with a Category header contributes, not just the first. Taking the first
    # match let a summary table shadow the real one, which made a `fail` verdict further down
    # invisible and let a record declare fewer findings than it contained.
    category_table = None
    all_category_rows: list[list[str]] = []
    for table in tables(text):
        header, rows = header_and_rows(table)
        if header[:1] == ["category"]:
            if category_table is None:
                category_table = (header, [])
            all_category_rows.extend(rows)
    if category_table is not None:
        category_table = (category_table[0], all_category_rows)
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
    # The header may name the column `#`, `No`, `Num` or `Number`; all of them are in use, and
    # a record that used one the gate did not recognise had its findings ignored entirely.
    finding_headers = {"#", "id", "no", "num", "number"}
    findings_table = None
    all_finding_rows: list[list[str]] = []
    for table in tables(text):
        header_f, rows_f = header_and_rows(table)
        if header_f[:1] and header_f[0] in finding_headers and "severity" in header_f:
            if findings_table is None:
                findings_table = (header_f, [])
            all_finding_rows.extend(rows_f)
    if findings_table is not None:
        findings_table = (findings_table[0], all_finding_rows)

    if findings_table is None:
        # Required even when the record claims no findings. Without it, a record could state in
        # prose that it found a critical issue and be counted as having found none.
        problems.append(
            f"{display} has no findings table with a severity column; it is required even when "
            f"the record reports no findings, so that 'no findings' is a recorded claim"
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
                # the policy says cannot happen for these severities. Matching the whole cell
                # only would let "Deferred; tracked in <commit>" through, which is a deferral
                # with a citation attached.
                deferred = mentions_deferral(resolution)
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
