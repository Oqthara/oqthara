#!/usr/bin/env python3
"""Verify CircleCI macOS and Jazzer checks before publishing an Oqthara release.

GitHub Actions covers Linux and Windows; macOS (JDK 21 and 25) and Jazzer
fuzzing run on CircleCI (docs/adr/0002-ci-split.md, .circleci/config.yml).
This script finds the CircleCI pipeline for a commit, waits for the required
workflows to finish, and fails unless every required job is green, so
release.yml can gate publishing on checks GitHub cannot run.

The optional repository secret CIRCLECI_TOKEN (a CircleCI personal API token)
grants read access to the CircleCI API. When it is absent the check is skipped
with a warning and the script exits 0, so forks can still release; add the
secret to enforce the gate. The token is only sent to circleci.com and is
never printed.

Usage:
  scripts/check-circleci.py --sha "$GITHUB_SHA"
  scripts/check-circleci.py --sha "$GITHUB_SHA" --timeout-minutes 20
  scripts/check-circleci.py --sha "$GITHUB_SHA" --dry-run
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API_ROOT = "https://circleci.com/api/v2"
DASHBOARD = "https://app.circleci.com/pipelines"
DEFAULT_PROJECT = "gh/Oqthara/oqthara"
NO_PIPELINE_GRACE_SECONDS = 300

# Workflow name -> job name fragments that must each have a successful job.
# CircleCI marks a workflow success even when a matrix leg is skipped, so jobs
# are checked by name rather than trusting the workflow status alone.
REQUIRED_CHECKS = {
    "macos": ("JDK 21", "JDK 25"),
    "fuzzing": ("jazzer",),
}
FAILURE_STATUSES = {
    "failed",
    "failing",
    "error",
    "canceled",
    "unauthorized",
    "infrastructure_fail",
    "timedout",
}


class ApiError(Exception):
    """A readable error from the CircleCI API."""

    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def api_get(token, url):
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    if token:
        request.add_header("Circle-Token", token)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace").strip().replace("\n", " ")
        message = f"GET {url} failed with HTTP {error.code} {error.reason}"
        if detail:
            message += f": {detail[:300]}"
        raise ApiError(message, status=error.code) from None
    except (urllib.error.URLError, OSError, ValueError) as error:
        raise ApiError(f"GET {url} failed: {error}") from None


def pipelines_url(project, branch=None):
    url = f"{API_ROOT}/project/{project}/pipeline"
    if branch:
        url += f"?branch={urllib.parse.quote(branch, safe='')}"
    return url


def find_pipelines(token, project, sha, branch=None, max_pages=5):
    base = pipelines_url(project, branch)
    separator = "&" if "?" in base else "?"
    url = base
    matches = []
    for _ in range(max_pages):
        payload = api_get(token, url)
        matches.extend(
            item
            for item in payload.get("items", [])
            if ((item.get("vcs") or {}).get("revision") or "").lower()
            == sha.lower()
        )
        page_token = payload.get("next_page_token")
        if matches or not page_token:
            break
        url = f"{base}{separator}page-token={urllib.parse.quote(page_token, safe='')}"
    return matches


def workflow_url(project, pipeline_number, workflow_id):
    return (
        f"{DASHBOARD}/{project.replace('gh/', 'github/', 1)}"
        f"/{pipeline_number}/workflows/{workflow_id}"
    )


def inspect(token, project, sha, branch, required):
    pipelines = find_pipelines(token, project, sha, branch)
    state = {"pipelines": pipelines, "workflows": {}, "errors": []}
    for pipeline in pipelines:
        state["errors"].extend(
            (error.get("message") or error.get("type", "pipeline error"))
            if isinstance(error, dict)
            else str(error)
            for error in pipeline.get("errors") or []
        )
        workflows = api_get(token, f"{API_ROOT}/pipeline/{pipeline['id']}/workflow")
        for workflow in workflows.get("items", []):
            name = workflow.get("name")
            if name not in required:
                continue
            current = state["workflows"].get(name)
            if current and (current["status"] == "success" or workflow.get("status") != "success"):
                continue
            jobs = api_get(token, f"{API_ROOT}/workflow/{workflow['id']}/job")
            state["workflows"][name] = {
                "id": workflow.get("id"),
                "status": workflow.get("status", "unknown"),
                "jobs": [
                    (job.get("name", "?"), job.get("status", "unknown"))
                    for job in jobs.get("items", [])
                ],
                "pipeline_number": pipeline.get("number"),
                "url": workflow_url(project, pipeline.get("number"), workflow.get("id")),
            }
    return state


def problems(state, required):
    if not state["pipelines"]:
        return ["no CircleCI pipeline found for this commit yet"]
    found = []
    for name, fragments in required.items():
        workflow = state["workflows"].get(name)
        if workflow is None:
            found.append(f"{name}: workflow was not triggered")
            continue
        if workflow["status"] != "success":
            found.append(f"{name}: {workflow['status']}")
            continue
        for fragment in fragments:
            if not any(
                fragment.lower() in job.lower() and status == "success"
                for job, status in workflow["jobs"]
            ):
                found.append(f"{name}: no successful '{fragment}' job")
    return found


def describe(state, required):
    if not state["pipelines"]:
        return "  no CircleCI pipeline found for this commit"
    lines = []
    for pipeline in state["pipelines"][:3]:
        lines.append(f"  pipeline #{pipeline.get('number')} for this commit")
    for name in required:
        workflow = state["workflows"].get(name)
        if workflow is None:
            lines.append(f"  {name}: not triggered")
            continue
        lines.append(f"  {name}: {workflow['status']} ({workflow['url']})")
        for job, status in workflow["jobs"]:
            lines.append(f"    {job}: {status}")
    return "\n".join(lines)


def required_checks(names):
    return {name: REQUIRED_CHECKS.get(name, ()) for name in names}


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description=(
            "Wait for CircleCI workflows for a commit and fail unless they pass. "
            "Uses the optional CIRCLECI_TOKEN repository secret."
        ),
        epilog=(
            "CIRCLECI_TOKEN is optional: when it is unset the check is skipped "
            "with a warning and exits 0."
        ),
    )
    parser.add_argument(
        "--sha",
        default=os.environ.get("GITHUB_SHA", ""),
        help="commit SHA to verify (default: $GITHUB_SHA)",
    )
    parser.add_argument(
        "--project",
        default=DEFAULT_PROJECT,
        help=f"CircleCI project slug (default: {DEFAULT_PROJECT})",
    )
    parser.add_argument(
        "--branch",
        default="",
        help="only accept a pipeline from this branch (default: any branch)",
    )
    parser.add_argument(
        "--workflow",
        action="append",
        dest="workflows",
        help="required CircleCI workflow; repeat to require several "
        f"(default: {', '.join(REQUIRED_CHECKS)})",
    )
    parser.add_argument(
        "--timeout-minutes",
        type=float,
        default=20.0,
        help="maximum time to wait (default: 20)",
    )
    parser.add_argument(
        "--interval-seconds",
        type=float,
        default=30.0,
        help="delay between polls (default: 30)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the resolved URL and exit 0 without network requests",
    )
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    names = tuple(dict.fromkeys(args.workflows or REQUIRED_CHECKS))
    required = required_checks(names)

    if args.timeout_minutes <= 0:
        print("error: --timeout-minutes must be greater than zero", file=sys.stderr)
        return 2
    if args.interval_seconds <= 0:
        print("error: --interval-seconds must be greater than zero", file=sys.stderr)
        return 2

    if args.dry_run:
        print(f"project: {args.project}")
        print(f"branch: {args.branch or 'any'}")
        print(f"commit: {args.sha or '<commit-sha>'}")
        print(f"pipelines URL: {pipelines_url(args.project, args.branch)}")
        print(
            "required: "
            + "; ".join(
                f"{name} ({', '.join(fragments) or 'any successful job'})"
                for name, fragments in required.items()
            )
        )
        print(
            f"timeout: {args.timeout_minutes:g} minutes "
            f"(poll every {args.interval_seconds:g}s)"
        )
        print("dry run: no network requests made")
        return 0

    if not args.sha:
        print("error: --sha is required (or set GITHUB_SHA)", file=sys.stderr)
        return 2
    if len(args.sha) < 7:
        print("error: --sha looks too short to identify a commit", file=sys.stderr)
        return 2

    token = os.environ.get("CIRCLECI_TOKEN", "").strip()
    if not token:
        print("warning: CIRCLECI_TOKEN is not set; skipping CircleCI verification")
        print(
            "warning: set the optional CIRCLECI_TOKEN repository secret to enforce "
            "the macOS and Jazzer checks before release"
        )
        return 0

    started = time.monotonic()
    deadline = started + args.timeout_minutes * 60
    last_state = None
    last_line = None
    last_error = None

    while True:
        try:
            state = inspect(
                token, args.project, args.sha, args.branch, tuple(required)
            )
        except ApiError as error:
            if error.status in (401, 403):
                print(f"error: {error}", file=sys.stderr)
                print(
                    "  CIRCLECI_TOKEN was rejected; regenerate the CircleCI personal "
                    "API token and update the repository secret",
                    file=sys.stderr,
                )
                return 1
            print(f"warning: {error}; retrying", file=sys.stderr)
            last_error = error
            state = last_state

        if state is not None:
            if state is not last_state:
                last_state = state
                line = "; ".join(problems(state, required)) or "all required checks green"
                if line != last_line:
                    print(f"status: {line}")
                    last_line = line

            if (
                not state["pipelines"]
                and time.monotonic() - started > NO_PIPELINE_GRACE_SECONDS
            ):
                grace = NO_PIPELINE_GRACE_SECONDS // 60
                print(
                    f"error: no CircleCI pipeline appeared for {args.sha[:12]} "
                    f"within {grace} minutes",
                    file=sys.stderr,
                )
                print(
                    "  is the CircleCI project enabled and building this commit? "
                    "See .circleci/config.yml and docs/adr/0002-ci-split.md",
                    file=sys.stderr,
                )
                return 1

            issues = problems(state, required)
            if not issues:
                print(f"CircleCI checks passed for {args.sha[:12]}")
                print(describe(state, required))
                return 0

            # A configuration error for this very commit will not fix itself.
            if state["errors"] and not state["workflows"]:
                print(
                    f"error: CircleCI could not run the pipeline for "
                    f"{args.sha[:12]}: {'; '.join(state['errors'])}",
                    file=sys.stderr,
                )
                print(describe(state, required), file=sys.stderr)
                return 1

            failed = [
                f"{name}: {workflow['status']}"
                for name, workflow in state["workflows"].items()
                if workflow["status"] in FAILURE_STATUSES
            ]
            if failed:
                print(
                    f"error: CircleCI checks failed for {args.sha[:12]}: "
                    f"{'; '.join(failed)}",
                    file=sys.stderr,
                )
                print(describe(state, required), file=sys.stderr)
                return 1

        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(args.interval_seconds, remaining))

    where = f"branch {args.branch}" if args.branch else "any branch"
    print(
        f"error: CircleCI checks did not pass within {args.timeout_minutes:g} "
        f"minutes for {args.sha[:12]} ({where})",
        file=sys.stderr,
    )
    if last_state is not None:
        print(describe(last_state, required), file=sys.stderr)
        if not last_state["pipelines"]:
            print(
                "  is the CircleCI project enabled and building this commit? "
                "See .circleci/config.yml and docs/adr/0002-ci-split.md",
                file=sys.stderr,
            )
    if last_error is not None:
        print(f"  last API error: {last_error}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
