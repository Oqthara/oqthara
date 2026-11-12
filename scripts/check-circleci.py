#!/usr/bin/env python3
"""Fail-closed release gate for the CI jobs that gate a release.

Every required job reports a result to GitHub, either as a commit status
(CircleCI) or as a check run (GitHub Actions). This script polls both APIs for
the tagged commit and only succeeds when every requirement is ``success``.
There is no skip path and no optional credential.

A requirement is a *group* of acceptable contexts. macOS is required twice, once
per JDK, and either provider may report it: GitHub Actions is primary and
CircleCI is the fallback (docs/adr/0049-macos-ci-on-github-actions.md). A
``failure``/``error`` from either provider fails the gate even when the other
provider succeeded, so a flake can never be masked by a second opinion that
happens to be green.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPOSITORY = "Oqthara/oqthara"

# Each group is satisfied by any one of its contexts; see the module docstring.
REQUIRED_CONTEXTS: tuple[tuple[str, ...], ...] = (
    ("ci/circleci: jazzer-fuzz",),
    ("macos-latest / JDK 21", "ci/circleci: macOS / JDK 21"),
    ("macos-latest / JDK 25", "ci/circleci: macOS / JDK 25"),
)
STATUS_URL = "https://api.github.com/repos/{repo}/commits/{sha}/status"
CHECK_RUNS_URL = "https://api.github.com/repos/{repo}/commits/{sha}/check-runs"
CIRCLECI_API = "https://circleci.com/api/v2"


def _get_json(url: str, headers: dict[str, str], timeout: float = 30.0) -> dict:
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def _known_contexts() -> set[str]:
    return {context for group in REQUIRED_CONTEXTS for context in group}


def fetch_github_statuses(sha: str, token: str) -> dict[str, dict]:
    headers = {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    base = STATUS_URL.format(repo=REPOSITORY, sha=urllib.parse.quote(sha, safe=""))
    known = _known_contexts()

    # The combined status lists every status for the ref; keep the newest
    # entry per required context. A busy commit can exceed one page, so pages
    # are followed until the API returns a short page.
    latest: dict[str, dict] = {}
    for page in range(1, 6):
        data = _get_json(base + "?per_page=100&page={page}".format(page=page), headers)
        entries = data.get("statuses", [])
        for entry in entries:
            context = entry.get("context")
            if context not in known:
                continue
            previous = latest.get(context)
            if previous is None or entry.get("updated_at", "") >= previous.get("updated_at", ""):
                latest[context] = entry
        if len(entries) < 100:
            break
    return latest


def fetch_check_runs(sha: str, token: str) -> dict[str, dict]:
    """Return the newest check run per known context, in the commit-status shape.

    GitHub Actions reports check runs rather than commit statuses, so the two
    APIs have to be merged into one view before a requirement can be evaluated.
    """
    headers = {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    base = CHECK_RUNS_URL.format(repo=REPOSITORY, sha=urllib.parse.quote(sha, safe=""))
    known = _known_contexts()

    latest: dict[str, dict] = {}
    for page in range(1, 11):
        data = _get_json(
            base + "?per_page=100&filter=all&page={page}".format(page=page), headers
        )
        entries = data.get("check_runs", [])
        for entry in entries:
            name = entry.get("name")
            if name not in known:
                continue
            state = _check_run_state(entry)
            previous = latest.get(name)
            stamp = entry.get("completed_at") or entry.get("started_at") or ""
            previous_stamp = (
                (previous.get("updated_at") or "") if previous is not None else ""
            )
            if previous is None or stamp >= previous_stamp:
                latest[name] = {
                    "context": name,
                    "state": state,
                    "target_url": entry.get("html_url") or "",
                    "updated_at": stamp,
                }
        if len(entries) < 100:
            break
    return latest


def _check_run_state(entry: dict) -> str:
    """Map a check run onto commit-status vocabulary.

    Only an explicit ``success`` conclusion counts as success. A run still in
    progress has no conclusion, and ``neutral``/``skipped`` are not evidence
    that the job passed, so both are reported as ``pending``.
    """
    if entry.get("status") != "completed":
        return "pending"
    conclusion = entry.get("conclusion")
    if conclusion == "success":
        return "success"
    if conclusion in ("failure", "timed_out", "cancelled", "action_required", "startup_failure"):
        return "failure"
    return "pending"


def _circleci_get(path: str, token: str) -> dict:
    headers = {"Circle-Token": token, "Accept": "application/json"}
    return _get_json(CIRCLECI_API + path, headers)


def fetch_circleci_jobs(sha: str, token: str) -> dict[str, dict]:
    pipelines = _circleci_get(
        "/project/gh/{repo}/pipeline".format(repo=REPOSITORY), token
    )
    pipeline = next(
        (
            item
            for item in pipelines.get("items", [])
            if item.get("vcs", {}).get("revision") == sha
        ),
        None,
    )
    if pipeline is None:
        raise RuntimeError("no CircleCI pipeline found for revision {sha}".format(sha=sha))

    jobs: dict[str, dict] = {}
    workflows = _circleci_get(
        "/pipeline/{id}/workflow".format(id=pipeline["id"]), token
    )
    for workflow in workflows.get("items", []):
        workflow_jobs = _circleci_get(
            "/workflow/{id}/job".format(id=workflow["id"]), token
        )
        for job in workflow_jobs.get("items", []):
            jobs[job["name"]] = job
    return jobs


def cross_check_circleci(sha: str, token: str) -> list[str]:
    """Return disagreements between the CircleCI jobs that ran and their statuses.

    Only the contexts that the gate actually satisfied via CircleCI are
    cross-checked. Under the primary/fallback arrangement the macOS jobs may not
    have run on CircleCI at all, and their absence is not a disagreement: the
    gate already accepted the GitHub Actions evidence for those requirements.
    """
    problems: list[str] = []
    try:
        jobs = fetch_circleci_jobs(sha, token)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError, RuntimeError) as exc:
        return ["CircleCI cross-check unavailable: {exc}".format(exc=exc)]

    for job_name, job in sorted(jobs.items()):
        if not job_name.startswith("macOS / JDK ") and job_name != "jazzer-fuzz":
            continue
        if job.get("status") != "success":
            problems.append(
                "ci/circleci: {name}: CircleCI job status is {status!r}, not 'success'".format(
                    name=job_name, status=job.get("status")
                )
            )
    return problems


def _group_state(statuses: dict[str, dict], group: tuple[str, ...]) -> tuple[str, str]:
    """Return (state, context) for one requirement group.

    A failure from any member wins over a success from another, so a flaky
    provider cannot be masked by a green one. Otherwise the first success wins.
    """
    failed = [
        context
        for context in group
        if statuses.get(context) is not None
        and statuses[context].get("state") in ("failure", "error")
    ]
    if failed:
        return "failed", failed[0]
    for context in group:
        entry = statuses.get(context)
        if entry is not None and entry.get("state") == "success":
            return "success", context
    return "waiting", group[0]


def evaluate(statuses: dict[str, dict]) -> str:
    verdicts = [_group_state(statuses, group)[0] for group in REQUIRED_CONTEXTS]
    if "failed" in verdicts:
        return "failed"
    if all(verdict == "success" for verdict in verdicts):
        return "success"
    return "waiting"


def describe(statuses: dict[str, dict]) -> list[str]:
    lines = []
    for group in REQUIRED_CONTEXTS:
        state, context = _group_state(statuses, group)
        if len(group) == 1:
            lines.append("  {context}: {state}".format(context=group[0], state=state))
            continue
        alternatives = " or ".join(group)
        lines.append(
            "  {alternatives}: {state} (via {context})".format(
                alternatives=alternatives, state=state, context=context
            )
        )
    return lines


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Fail-closed release gate on CircleCI commit statuses.",
        epilog=(
            "exit codes: 0 all required contexts succeeded; 1 missing, pending "
            "or failed evidence; 2 invalid invocation or missing GITHUB_TOKEN."
        ),
    )
    parser.add_argument("--sha", required=True, help="commit SHA to check")
    parser.add_argument("--timeout-minutes", type=float, default=30.0)
    parser.add_argument("--interval-seconds", type=float, default=20.0)
    parser.add_argument(
        "--circleci-token",
        default=os.environ.get("CIRCLECI_TOKEN") or None,
        help="optional cross-check of CircleCI job detail; never required",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the resolved requirement without any network access",
    )
    args = parser.parse_args(argv)

    if args.timeout_minutes <= 0:
        parser.error("--timeout-minutes must be greater than zero")
    if args.interval_seconds <= 0:
        parser.error("--interval-seconds must be greater than zero")

    if args.dry_run:
        print("release gate requirement (dry run, no network):")
        print("  repository: {repo}".format(repo=REPOSITORY))
        print("  commit:     {sha}".format(sha=args.sha))
        print("  required contexts (each group needs one success):")
        for group in REQUIRED_CONTEXTS:
            print("    - {group}".format(group=" or ".join(group)))
        print("  credential: GITHUB_TOKEN (required; missing exits 2)")
        print("  timeout:    {timeout:g} minutes".format(timeout=args.timeout_minutes))
        print("  interval:   {interval:g} seconds".format(interval=args.interval_seconds))
        print("  cross-check: --circleci-token (optional, never required)")
        return 0

    github_token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not github_token:
        print(
            "error: GITHUB_TOKEN is required. The release gate is fail-closed "
            "and cannot skip CircleCI evidence.",
            file=sys.stderr,
        )
        return 2

    deadline = time.monotonic() + args.timeout_minutes * 60.0
    statuses: dict[str, dict] = {}
    last_error: Exception | None = None

    while True:
        try:
            # Both APIs are read every poll: a requirement may be satisfied by a
            # commit status (CircleCI) or by a check run (GitHub Actions).
            statuses = fetch_github_statuses(args.sha, github_token)
            statuses.update(fetch_check_runs(args.sha, github_token))
            verdict = evaluate(statuses)
            last_error = None
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError) as exc:
            statuses = {}
            verdict = "waiting"
            last_error = exc

        if verdict == "success":
            if args.circleci_token:
                problems = cross_check_circleci(args.sha, args.circleci_token)
                if problems:
                    for problem in problems:
                        print("FAIL {problem}".format(problem=problem), file=sys.stderr)
                    return 1
                print("CircleCI cross-check: job statuses agree with GitHub")
            for group in REQUIRED_CONTEXTS:
                state, context = _group_state(statuses, group)
                assert state == "success"
                entry = statuses[context]
                target = entry.get("target_url") or "(no target url)"
                print("success {context} {target}".format(context=context, target=target))
            return 0

        if verdict == "failed":
            for group in REQUIRED_CONTEXTS:
                state, context = _group_state(statuses, group)
                if state != "failed":
                    continue
                entry = statuses[context]
                target = entry.get("target_url")
                suffix = " {target}".format(target=target) if target else ""
                print(
                    "FAIL {context}: {state}{suffix}".format(
                        context=context, state=entry.get("state"), suffix=suffix
                    ),
                    file=sys.stderr,
                )
            return 1

        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break

        print(
            "waiting for required statuses on {sha} ({remaining:.0f}s left)".format(
                sha=args.sha, remaining=remaining
            )
        )
        for line in describe(statuses):
            print(line)
        if last_error is not None:
            print("  last API error: {exc}".format(exc=last_error))
        time.sleep(min(args.interval_seconds, remaining))

    print(
        "error: release-gate evidence for {sha} did not pass within {timeout:g} minutes".format(
            sha=args.sha, timeout=args.timeout_minutes
        ),
        file=sys.stderr,
    )
    for line in describe(statuses):
        print(line, file=sys.stderr)
    if last_error is not None:
        print("  last API error: {exc}".format(exc=last_error), file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
