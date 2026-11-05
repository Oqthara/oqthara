#!/usr/bin/env python3
"""Fail-closed release gate for the CircleCI jobs that gate a release.

CircleCI reports one commit status per job to GitHub. Those statuses are the
mandatory evidence for the release: this script polls the GitHub commit-status
API for the tagged commit and only succeeds when every required context is
``success``. There is no skip path and no optional credential.
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
REQUIRED_CONTEXTS = (
    "ci/circleci: macOS / JDK 21",
    "ci/circleci: macOS / JDK 25",
    "ci/circleci: jazzer-fuzz",
)
STATUS_URL = "https://api.github.com/repos/{repo}/commits/{sha}/status"
CIRCLECI_API = "https://circleci.com/api/v2"


def _get_json(url: str, headers: dict[str, str], timeout: float = 30.0) -> dict:
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def fetch_github_statuses(sha: str, token: str) -> dict[str, dict]:
    headers = {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    base = STATUS_URL.format(repo=REPOSITORY, sha=urllib.parse.quote(sha, safe=""))

    # The combined status lists every status for the ref; keep the newest
    # entry per required context. A busy commit can exceed one page, so pages
    # are followed until the API returns a short page.
    latest: dict[str, dict] = {}
    for page in range(1, 6):
        data = _get_json(base + "?per_page=100&page={page}".format(page=page), headers)
        entries = data.get("statuses", [])
        for entry in entries:
            context = entry.get("context")
            if context not in REQUIRED_CONTEXTS:
                continue
            previous = latest.get(context)
            if previous is None or entry.get("updated_at", "") >= previous.get("updated_at", ""):
                latest[context] = entry
        if len(entries) < 100:
            break
    return latest


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
    """Return disagreements between CircleCI jobs and GitHub statuses."""
    problems: list[str] = []
    try:
        jobs = fetch_circleci_jobs(sha, token)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError, RuntimeError) as exc:
        return ["CircleCI cross-check unavailable: {exc}".format(exc=exc)]

    for context in REQUIRED_CONTEXTS:
        job_name = context.split(": ", 1)[1]
        job = jobs.get(job_name)
        if job is None:
            problems.append("{context}: no matching CircleCI job".format(context=context))
        elif job.get("status") != "success":
            problems.append(
                "{context}: CircleCI job status is {status!r}, not 'success'".format(
                    context=context, status=job.get("status")
                )
            )
    return problems


def evaluate(statuses: dict[str, dict]) -> str:
    for context in REQUIRED_CONTEXTS:
        entry = statuses.get(context)
        if entry is not None and entry.get("state") in ("failure", "error"):
            return "failed"
    if all(
        statuses.get(context) is not None
        and statuses[context].get("state") == "success"
        for context in REQUIRED_CONTEXTS
    ):
        return "success"
    return "waiting"


def describe(statuses: dict[str, dict]) -> list[str]:
    lines = []
    for context in REQUIRED_CONTEXTS:
        entry = statuses.get(context)
        state = entry.get("state") if entry else "missing"
        lines.append("  {context}: {state}".format(context=context, state=state))
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
        print("  required contexts (all must be success):")
        for context in REQUIRED_CONTEXTS:
            print("    - {context}".format(context=context))
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
            statuses = fetch_github_statuses(args.sha, github_token)
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
            for context in REQUIRED_CONTEXTS:
                entry = statuses[context]
                target = entry.get("target_url") or "(no target url)"
                print("success {context} {target}".format(context=context, target=target))
            return 0

        if verdict == "failed":
            for context in REQUIRED_CONTEXTS:
                entry = statuses.get(context)
                if entry is not None and entry.get("state") in ("failure", "error"):
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
            "waiting for CircleCI statuses on {sha} ({remaining:.0f}s left)".format(
                sha=args.sha, remaining=remaining
            )
        )
        for line in describe(statuses):
            print(line)
        if last_error is not None:
            print("  last API error: {exc}".format(exc=last_error))
        time.sleep(min(args.interval_seconds, remaining))

    print(
        "error: CircleCI evidence for {sha} did not pass within {timeout:g} minutes".format(
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
