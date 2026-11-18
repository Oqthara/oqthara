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
    "subsequent pr", "pending", "not yet", "remains", "still present", "still there",
    "will land", "to be fixed", "to be addressed", "needs fixing", "known limitation",
    "accepted risk", "wontfix", "not going to", "no plan to", "left as is", "left unchanged",
    "another repository", "separate pr", "separately",
)
# Words deliberately absent: "skipped", "ignored", "unchanged", "untouched", "later". Each
# appears in ordinary descriptions of a completed fix -- "fenced blocks are skipped", "the
# existing check is unchanged" -- and treating them as deferrals rejected correct records.
# A resolution may legitimately say it is *not* a deferral. Those words are removed before the
# scan, so "this is not a deferral; fixed in `abc1234`" is not rejected for naming the thing it
# is denying.
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


def the_pull_request_this_run_belongs_to() -> str | None:
    """The pull request CI says this run is for, ignoring any override.

    `--pr` and `GH_PR_NUMBER` exist so a maintainer can validate a record locally. In CI they are
    also the shortest path from "the gate is enforced" to "no pull request is reviewed": adding
    `--pr 224` to the workflow makes every future pull request validate PR #224's record, find it
    well-formed, and pass. Neither check would object, and the required context stays green.

    So when the event payload names a pull request, the emitted number must be that one. The
    override is allowed to disagree only outside a pull-request run, which is what a local run is.
    """
    event_path = os.environ.get("GITHUB_EVENT_PATH", "")
    if not event_path:
        return None
    try:
        payload = json.loads(pathlib.Path(event_path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    number = (payload.get("pull_request") or {}).get("number")
    if number:
        return str(number)
    ref = os.environ.get("GITHUB_REF", "")
    match = re.match(r"refs/pull/(\d+)/", ref)
    return match.group(1) if match else None


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
            if all(re.fullmatch(r":?-+:?", cell) for cell in cells if cell):
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
    # Stripped like value cells are: `| **#** | **Severity** |` is a heading emphasised the way
    # the rest of the document is emphasised, and treating the markup as part of the column name
    # rejected an honest record for its formatting.
    return [cell.strip("*` ").lower() for cell in table[0]], table[1:]


# Values that name no party. Without this, a record whose Reviewer and Implementer are both
# `—` satisfied "does not name the reviewer" while naming nothing.
PLACEHOLDER_FIELDS = {
    "", "-", "--", "—", "–", "n/a", "na", "none", "tbd", "todo", "null", "x",
    "reviewer", "implementer", "author", "agent", "unknown", "same as above", "see above",
    "<reviewer>", "<implementer>", "<name>", "<agent>",
}


def outside_fences(text: str) -> str:
    """`text` with every fenced block replaced by blank lines.

    A record may quote the template or a rejected shape inside a fence. Fields and findings are
    read from the unfenced remainder, so a fenced example can neither supply a field the record
    does not have nor override one it does.
    """
    kept, in_fence = [], False
    for line in text.splitlines():
        if FENCE.match(line):
            in_fence = not in_fence
            kept.append("")
        else:
            kept.append("" if in_fence else line)
    return "\n".join(kept)


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


# Rows that summarise a table they belong to rather than add a row to it.
ROLL_UP_NAMES = {"total", "totals", "sum", "overall", "all"}

# Words in a Reviewer/Implementer value that describe *which pass* ran, not who ran it.
# `review-agent review pass 17` and `review-agent implementation pass 18` are one party; without
# this list they compared as two, which is the pair the separation check exists to catch. Matched
# as whole words only -- `review` occurs inside `review-agent`, and stripping that would compare
# the agent's own name.
PASS_WORDS = {"review", "implementation", "pass", "round", "run", "the", "a", "an"}


def party_identity(value: str) -> str:
    """The identifying part of a Reviewer/Implementer value, without the annotation.

    `review-agent (review pass of run 17)` and `review-agent (implementation pass of run 17)` are
    one party with two descriptions. Comparing whole strings let a single run be presented as an
    independent reviewer.
    """
    # Parentheses are stripped even when unterminated: `review-agent (pass 17` is an annotation
    # a typo left open, and keeping it turned one party into two.
    without_model = re.sub(r"\([^)]*\)?", " ", value)
    without_model = re.sub(r"[`*_]", "", without_model)
    words = re.findall(r"[a-z0-9][a-z0-9./-]*", without_model.lower())
    # The words that describe *which pass* are not part of who ran it. Stripped as whole words,
    # not substrings: `review-agent` contains `review`, and splitting it into `agent` made the
    # agent's own name the thing being compared.
    words = [word for word in words if word not in PASS_WORDS]
    # All-digit tokens are round and pass numbers. `review pass 17` and `review pass 18` are the
    # same party describing two passes, which is exactly the pair this check exists to merge.
    words = [word for word in words if not re.fullmatch(r"[0-9]+", word)]
    return " ".join(words)


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
    match = re.search(
        rf"^\*\*{re.escape(label)}(?::\*\*|\*\*:)[ \t]*(.*)$", text, re.M | re.I
    )
    if not match:
        return None
    lines = text[match.start():].splitlines()
    kept = [lines[0]]
    for line in lines[1:]:
        if not line.strip() or line.lstrip().startswith(("#", "|", "**")):
            break
        kept.append(line)
    joined = " ".join(part.strip() for part in kept).strip()
    return re.sub(
        rf"^\*\*{re.escape(label)}(?::\*\*|\*\*:)[ \t]*", "", joined, flags=re.I
    ).strip()


# A model identifier is written vendor/name, as the agent files pin them
# (`deepseek-ai/deepseek-v4.1-flash-1017`). Requiring the literal word "model" would reject a
# record that named the model precisely, so either form is accepted.
MODEL_SLUG = re.compile(r"[\w.-]+/[\w.-]+")

# `human:<name>` is not a model slug, but it is the one identity the gate cannot verify and the
# only honest way to record a genuinely independent human reviewer. Returning [] for it made that
# form unreachable, so the allowlist would have closed the door on the one claim worth trusting.
HUMAN_SLUG = re.compile(r"human:[\w.-]+", re.I)

# A claim of independence has to describe *how* it was preserved, not just assert it. Thirteen
# words is not a quality bar -- it only rules out "yes" and "yes, trust me".
MIN_INDEPENDENCE_WORDS = 13


def models_named(text: str) -> list[str]:
    r"""Model identifiers named in `text`, excluding anything that is really a URL.

    `[\w.-]+/[\w.-]+` also matches `example.com/run`, so a record could claim independence by
    pointing at a link. A slug that follows a scheme or a domain-ish host is not a model name.
    """
    named = [m.group(0).lower() for m in HUMAN_SLUG.finditer(text)]
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


def cites_a_commit(root: pathlib.Path, resolution: str, allow_shallow: bool = False) -> str | None:
    """Why this resolution cell is not evidence, or None when it is.

    The resolution cell must carry evidence, not just a claim that the fix happened. A SHA in
    backticks is the convention; a cell with no SHA at all has no evidence to check. Returns a
    reason rather than a bool so the record can be told *which* of the checks failed -- "names no
    commit" and "names the branch point" are different mistakes with different fixes.
    """
    shas = re.findall(r"`([0-9a-fA-F]{7,40})`", resolution)
    if not shas:
        return "names no commit in backticks"
    for sha in shas:
        exists, shallow = commit_exists(root, sha.lower())
        if not exists:
            continue
        if shallow and not allow_shallow:
            # `commit_exists` cannot tell a shallow clone that the SHA is *not* an ancestor, so
            # returning None here accepted an off-branch citation. The caller decides whether to
            # accept that degradation knowingly; the default is to refuse rather than to guess.
            return (f"`{sha}` cannot be checked for ancestry in a shallow clone; re-run with "
                    f"`--allow-shallow` to accept that, or fetch the full history")
        # The cited commit has to be in the history the gate is checking out. Otherwise a
        # resolution can name any commit in the repository -- another branch, an unrelated fix
        # merged long ago -- and be read as evidence that this finding was resolved.
        if not is_ancestor(root, sha.lower()):
            continue
        # The merge base predates every commit on the branch, so it cannot be evidence that
        # this branch fixed anything. Requiring a commit *newer than the review* was tried and
        # withdrawn: an earlier review round is legitimately fixed by earlier commits.
        base = merge_base(root)
        if base is None:
            # `merge_base` returning None means `origin/main` is not present, and every `if base`
            # guard then disappeared with it -- so a checkout missing one ref silently stopped
            # checking ancestry at all. A rule that switches itself off is not a rule.
            return (f"`{sha}` cannot be placed ahead of the branch point, because this checkout "
                    f"has no `origin/main` to compare against")
        if (base.startswith(sha.lower()) or sha.lower().startswith(base)):
            return f"`{sha}` is the merge base, so it predates the change under review"
        # Being *an ancestor of the base* is not enough either: the base is the newest commit
        # shared with main, so anything older predates the change under review just as surely.
        # The rule the docs state is "ahead of the merge base", and this is it.
        behind = subprocess.run(
            ["git", "merge-base", "--is-ancestor", sha.lower(), base],
            cwd=root, capture_output=True, text=True,
        )
        if behind.returncode == 0:
            return f"`{sha}` predates the merge base, so it cannot be a fix made on this branch"
        return None
    return f"names no commit on this branch ({', '.join(shas)})"


# The base branch, in the order a checkout is likely to have it. `origin/main` is the PR
# checkout; `main` is a local clone or a fixture that made its own history. Falling back keeps
# the ancestry rule from switching itself off in a checkout that is otherwise perfectly usable.
BASE_REFS = ("origin/main", "main")


def merge_base(root: pathlib.Path) -> str | None:
    for ref in BASE_REFS:
        result = subprocess.run(
            ["git", "merge-base", ref, "HEAD"], cwd=root, capture_output=True, text=True,
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()
    return None


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
# Only the dependency updaters. `github-actions[bot]` is deliberately absent: it opens pull
# requests from workflows, which is ordinary development and not a dependency bump, and
# exempting it let any workflow-authored change merge with no review record.
# GitHub reports an App-authored pull request as `app/<slug>`, not as `<name>[bot]`: Dependabot's
# modernised app answers `app/dependabot`. Both spellings are kept because an older installation
# still reports the bracketed login, and a missing spelling here does not degrade gently -- the
# exemption silently never matches and the required check goes red on every dependency pull
# request (PR #251, which is what R-024 describes).
AUTOMATED_AUTHORS = {
    "dependabot[bot]", "renovate[bot]",
    "app/dependabot", "app/renovate",
}

# Models this repository has a record of. `pinned_models(root)` supplies the agents' actual pins;
# this is the fallback list for a partial checkout, and `scripts/check-docs-sync.py` keeps the two
# from drifting. An independence claim naming anything else is a claim with no evidence.
KNOWN_MODELS = {
    "deepseek-ai/deepseek-v4.1-flash-1017",
    "anthropic/claude-sonnet-4.5",
    "openai/gpt-5.1-codex",
    "google/gemini-3-pro",
}


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


# Only dependency updaters are exempt. A workflow token can open a pull request too, and
# exempting every `[bot]` let a change opened by any workflow merge with no review at all.
AUTOMATED_AUTHORS_HINT = sorted(AUTOMATED_AUTHORS)


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

    raw = read(path)
    # Fields and tables are read from the unfenced remainder, so a fenced example cannot supply
    # or override a real field. The heading is a top-level line and is read from `raw`.
    text = outside_fences(raw)

    # Read from the unfenced text: taking it from `raw` let a heading that exists only inside
    # a fenced example satisfy the check, so the rendered document had no heading at all.
    heading = re.search(
        r"^#\s+AI review\s+\W+\s*PR\s*#(\d+)\s*$", outside_fences(raw), re.M | re.I
    )
    if not heading:
        problems.append(f"{display} has no '# AI review — PR #<n>' heading")
    elif heading.group(1) != number:
        problems.append(
            f"{display} is named for PR #{number} but its heading says PR #{heading.group(1)}"
        )

    declared = check_field(text, "Pull request")
    if declared is None:
        problems.append(f"{display} has no '**Pull request:**' line")
    else:
        # Every number in the declaration has to be this pull request. Records legitimately write
        # `PR #224`, `#224`, `[#224](...)` and `pull request 224`, and the old strip-then-compare
        # accepted only the last two while rejecting the first two outright.
        numbers = set(re.findall(r"\d+", declared))
        if numbers != {number}:
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

    # The order forbids presenting one party as the other. Two strings that differ only by a
    # parenthetical are the same party, so the comparison is on the identity each string names.
    if len(parties) == 2 and party_identity(parties["Reviewer"]) == party_identity(
        parties["Implementer"]
    ):
        problems.append(
            f"{display} names the same party as Reviewer and Implementer; the order requires "
            f"implementation and review to be separate wherever practical. If one run did both, "
            f"say so in the '**Independent:**' line instead of inventing a second party"
        )

    reviewed_at = check_field(text, "Reviewed at")
    if not reviewed_at:
        problems.append(f"{display} does not say which commit it reviewed")
    else:
        sha = first_commit_sha(reviewed_at)
        exists, shallow = commit_exists(root, sha) if sha else (False, False)
        if exists and not shallow:
            # The reviewed commit has to be in this branch's history. Otherwise a record can
            # claim to have reviewed a commit that is not part of the change at all.
            if not is_ancestor(root, sha):
                problems.append(
                    f"{display} says it reviewed '{sha}', which is not an ancestor of this "
                    f"branch; the review has to cover the change being integrated"
                )
            else:
                base = merge_base(root)
                if base and sha.startswith(base[:7]):
                    problems.append(
                        f"{display} says it reviewed '{sha}', which is the merge base of this "
                        f"branch; a record that reviewed the point the branch started from has "
                        f"not seen the change"
                    )
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
            elif not all(
                name in KNOWN_MODELS or name in pinned or name.startswith("human:")
                for name in named
            ):
                # Naming an invented model is not evidence of independence. The gate cannot
                # verify a model exists, so it requires the claim to name one of the models this
                # repository has configured, or to be marked `human:` -- which the maintainer
                # knows whether to accept, and which no AI review can claim for itself.
                problems.append(
                    f"{display} claims independence by naming "
                    f"{sorted(n for n in named if n not in KNOWN_MODELS and n not in pinned and not n.startswith('human:'))}, "
                    f"which this repository has no record of; independence must name a configured "
                    f"model or be marked 'human:'"
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
        elif named - KNOWN_MODELS - pinned and not all(
            name.startswith("human:") for name in named - KNOWN_MODELS - pinned
        ):
            # `no -- see scripts/check-ai-review.py` satisfied the model check with a file path.
            # The `no` answer has to name the party that actually reviewed, so the same rule as
            # the `yes` path applies: a configured model, or a `human:` marker.
            problems.append(
                f"{display} says it is not independent but names "
                f"{sorted(n for n in named - KNOWN_MODELS - pinned if not n.startswith('human:'))}, "
                f"which is not a configured model; name the model that actually reviewed"
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
            # A table that is prepared to list all seven categories addresses them; a roll-up
            # row that names a category differently is a different table, and merging it in
            # failed a correct record for stating a summary.
            if not any(row[0].strip("*` ").lower() in CATEGORIES for row in rows if row):
                continue
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
            # A trailing `| Total | | 55 |` roll-up sums the table it follows. Rejecting it as an
            # unknown category failed a correct record for stating its own total.
            if name not in ROLL_UP_NAMES:
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
            # A plain run of digits. `int()` also accepts `1_3`, `+13` and Arabic-Indic
            # digits, so a record could write something that does not read as the number it
            # counts and still be summed as one.
            digits = row[count_at].strip("*` ")
            if not re.fullmatch(r"[0-9]+", digits):
                # A negative count is worth naming as such: it is the shape that lets one
                # category's findings cancel another's, and "not a number" would hide that.
                if re.fullmatch(r"[-+]?[0-9]+", digits):
                    problems.append(
                        f"{display} gives '{name}' the finding count {int(digits)}; a count "
                        f"cannot be negative, and negative counts let the totals cancel out"
                    )
                    continue
                raise ValueError(digits)
            count = int(digits)
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
    # Matched as a word rather than as the whole cell: `fail - not applicable` and
    # `not applicable (fail)` both say the change failed, and comparing the stripped string
    # only to `fail` let either be recorded while the gate still exited 0.
    for row in rows:
        if len(row) <= verdict_at:
            continue
        if "fail" in re.findall(r"[a-z]+", row[verdict_at].strip("*` ").lower()):
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
        # Recognition keys on the severity column, not on the row-number column's name. Keying on
        # the name let a table headed `Rank` -- or anything else unrecognised -- be discarded
        # silently, and every finding in it with it, including an open critical one.
        if "severity" not in header_f:
            continue
        # A table with a severity column but no resolution column is not findings -- it is a
        # legend, or an honest "no findings" table written header-only -- and merging its rows in
        # turned a correct record into a malformed one. A header-only table with no rows at all
        # is the latter and is accepted; one with rows is a legend and is skipped.
        if "resolution" not in header_f:
            if findings_table is None and not any(row for row in rows_f if row):
                findings_table = (header_f, [])
            continue
        if findings_table is None or "resolution" not in findings_table[0]:
            findings_table = (header_f, [])
            all_finding_rows = list(rows_f)
            continue
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
        if "resolution" not in header_f:
            # A table with a severity column but no resolution column is a legend or a summary,
            # not the findings table. Merging it in made a correct record fail for having both.
            problems.append(
                f"{display} has a table with a 'severity' column but no 'resolution' column; "
                f"only the findings table may carry a severity column"
            )
            return
        severity_at = header_f.index("severity")
        resolution_at = header_f.index("resolution") if "resolution" in header_f else None
        if resolution_at is None:
            problems.append(f"{display} findings table has no 'resolution' column")
            return
        number_at = next(
            (header_f.index(name) for name in ("#", "id", "no", "num", "number")
             if name in header_f), None)
        if number_at is not None:
            # A roll-up row sits in the number column with a word in it. It is not a finding, so
            # it is not numbered; counting it here rejected `| Total | | 55 |`, which is the
            # ordinary way a table states its own sum.
            listed = [row[number_at].strip("*` ") for row in rows_f
                      if len(row) > number_at
                      and row[number_at].strip("*` ").lower() not in ROLL_UP_NAMES]
            if len(set(listed)) != len(listed):
                problems.append(
                    f"{display} numbers its findings, but two rows share a number; a reader "
                    f"cannot tell which finding a resolution answers"
                )
            # `[0-9]`, not `\d`: `\d` matches Arabic-Indic digits, so `\u0661\u0663` read as
            # the number thirteen and a row numbered in a script no reader expects was accepted.
            if any(not re.fullmatch(r"[0-9]+", value) for value in listed):
                problems.append(
                    f"{display} has a findings row whose number is not a number: "
                    + ", ".join(v or "''" for v in listed if not re.fullmatch(r"[0-9]+", v))
                )
        # Roll-up rows are excluded before the count is compared, not only before the per-row
        # checks: `| Total | | 55 |` inside the findings table made a correct record claim 56.
        counted_rows = [row for row in rows_f
                        if not (row and row[0].strip("*` ").lower() in ROLL_UP_NAMES)]
        if len(counted_rows) != declared_findings:
            problems.append(
                f"{display} counts {declared_findings} finding(s) in its category table but "
                f"lists {len(counted_rows)}; one of the two is wrong"
            )
        for row in rows_f:
            # A roll-up row summarises the table; it carries no severity and needs no
            # resolution, so it is not a malformed finding. `| Total | | 55 |` is the ordinary
            # way a table states its own sum.
            if row and row[0].strip("*` ").lower() in ROLL_UP_NAMES:
                continue
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
                reason = cites_a_commit(root, resolution, allow_shallow)
                if absent or deferred or reason:
                    detail = (
                        "the resolution cell is empty" if absent
                        else "the resolution reads as a deferral" if deferred
                        else reason
                    )
                    problems.append(
                        f"{display} leaves a {severity} finding unresolved: "
                        f"'{' | '.join(row)[:90]}' -- {detail}. ADR-0059 requires critical and "
                        f"high findings to be resolved and validated in the pull request that "
                        f"found them, with a commit on this branch as the evidence"
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

    actual = the_pull_request_this_run_belongs_to()
    if actual is not None and actual != number:
        # Refused rather than preferring `actual`: a run that names one pull request and validates
        # another is misconfigured, and silently correcting it would hide the misconfiguration --
        # while silently accepting it is the bypass this guard exists to stop.
        print(
            f"check-ai-review: error: this run is for pull request #{actual}, but it was asked to "
            f"validate #{number}. The gate validates the pull request it runs in; remove the "
            f"override.",
            file=sys.stderr,
        )
        return 1

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
