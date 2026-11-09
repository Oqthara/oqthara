#!/usr/bin/env node

// Tells the website that new content is available.
//
// The website cannot watch this repository, so a push to `main` or a published release has to
// be announced. A `repository_dispatch` to `Oqthara/Oqthara.github.io` is that announcement.
//
// What this sends is deliberately thin. The payload names the event and the commit; it is not a
// description of the content and the website does not act on it. The receiving workflow re-reads
// this repository's release list and Git tree itself, because a dispatch can be forged, replayed
// or stale, and a mirror that publishes whatever a payload claims is a mirror that can be lied
// to. Treat the payload as "look again", never as "publish this".
//
// The token is read from the environment and never logged. Only the destination, the event type
// and the outcome appear in output, so a workflow log is safe to paste into an issue.

const API = "https://api.github.com";
const REPOSITORY = "Oqthara/Oqthara.github.io";
const EVENT_TYPE_CONTENT = "content-updated";
const EVENT_TYPE_RELEASE = "alpha-release-published";

import { readFileSync } from "node:fs";

/** The token, or an error that names the exact missing configuration. */
function readToken(env) {
  const token = String(env.SITE_DISPATCH_TOKEN ?? "").trim();
  if (!token) {
    throw new Error(
      [
        "SITE_DISPATCH_TOKEN is not configured.",
        "",
        "Create a fine-grained personal access token owned by an account with write access to",
        `${REPOSITORY}:`,
        "",
        "  Repository access  : only Oqthara/Oqthara.github.io",
        "  Permissions        : Contents = Read and write",
        "  Expiry             : 90 days or less",
        "",
        "Then store it here:",
        "",
        "  gh secret set SITE_DISPATCH_TOKEN --repo Oqthara/oqthara",
        "",
        "Nothing was dispatched.",
      ].join("\n"),
    );
  }
  return token;
}

/** Which dispatch this run should send, from the event that triggered it. */
export function chooseDispatch({ eventName, ref, release }) {
  if (eventName === "push") {
    if (ref !== "refs/heads/main") {
      return { send: false, reason: `push to ${ref || "an unknown ref"} is not a publishable change` };
    }
    return {
      send: true,
      eventType: EVENT_TYPE_CONTENT,
      payload: { reason: "push to main", ref: "main" },
    };
  }

  if (eventName === "release") {
    // A draft is not published, so the site cannot see it and announcing one would make it
    // re-read and find nothing new. A prerelease is different: the site's channel selector skips
    // drafts only and mirrors prereleases by design, which is why every Oqthara alpha release is
    // published as a prerelease and still has to be announced.
    if (release?.draft) return { send: false, reason: "the release is a draft" };
    const tag = String(release?.tag_name ?? "").trim();
    if (!tag) return { send: false, reason: "the release has no tag" };
    return {
      send: true,
      eventType: EVENT_TYPE_RELEASE,
      payload: { reason: "release published", tag },
    };
  }

  return { send: false, reason: `event ${eventName || "unknown"} does not announce content` };
}

/**
 * Checks the decision matrix.
 *
 * This repository has no JavaScript test runner and no package manifest, and adding one for a
 * single script would be more machinery than the script is worth. The workflow runs this before
 * dispatching, so the branch that decides whether to announce anything is exercised on every
 * run: a change that makes a non-`main` push announce content, or makes a draft release
 * announce a manual, fails here rather than in a reader's browser.
 */
export function selfTest() {
  const cases = [
    [{ eventName: "push", ref: "refs/heads/main" }, true, "a push to main announces content"],
    [{ eventName: "push", ref: "refs/heads/feature" }, false, "a push to another branch announces nothing"],
    [{ eventName: "push", ref: "refs/tags/v1.0.0" }, false, "a tag push is not a main update"],
    [{ eventName: "release", release: { tag_name: "v1.0.0" } }, true, "a published release announces a channel"],
    [{ eventName: "release", release: { tag_name: "v1.0.0", draft: true } }, false, "a draft is not published"],
    [{ eventName: "release", release: { tag_name: "v1.0.0", prerelease: true } }, true, "a prerelease is announced because the site mirrors prereleases"],
    [{ eventName: "release", release: {} }, false, "a release without a tag announces nothing"],
    [{ eventName: "workflow_dispatch" }, false, "a manual run announces nothing"],
    [{}, false, "an unknown event announces nothing"],
  ];

  let failed = 0;
  for (const [input, expected, label] of cases) {
    const actual = chooseDispatch(input).send;
    if (actual !== expected) {
      console.error(`FAIL ${label}: expected send=${expected}, got send=${actual}`);
      failed += 1;
    }
  }

  if (failed) {
    console.error(`${failed} decision case(s) failed.`);
    return 1;
  }
  console.log(`Decision matrix verified: ${cases.length} cases.`);
  return 0;
}

async function main() {
  const eventName = process.env.GITHUB_EVENT_NAME ?? "";
  const decision = chooseDispatch({
    eventName,
    ref: process.env.GITHUB_REF ?? "",
    release: readReleaseFromEvent(),
  });

  if (!decision.send) {
    console.log(`No dispatch: ${decision.reason}.`);
    return 0;
  }

  const token = readToken(process.env);
  const body = JSON.stringify({
    event_type: decision.eventType,
    client_payload: decision.payload,
  });

  console.log(`Dispatching ${decision.eventType} to ${REPOSITORY}.`);

  const response = await fetch(`${API}/repos/${REPOSITORY}/dispatches`, {
    method: "POST",
    headers: {
      accept: "application/vnd.github+json",
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
      "x-github-api-version": "2022-11-28",
    },
    body,
  });

  if (response.status !== 204) {
    const detail = await response.text().catch(() => "");
    console.error(`::error::GitHub rejected the dispatch with ${response.status}.`);
    if (detail) console.error(detail.slice(0, 2000));
    // 401 and 403 are the two ways a token can be present but unusable: expired, revoked, or
    // scoped to the wrong repository. Naming that is what turns a red run into a fix.
    if (response.status === 401 || response.status === 403) {
      console.error("SITE_DISPATCH_TOKEN is present but cannot write dispatches to the website repository.");
    }
    return 1;
  }

  console.log(`Dispatched ${decision.eventType}. The website will re-read this repository.`);
  return 0;
}

/** The release object from `GITHUB_EVENT_PATH`, or null when this is not a release event. */
function readReleaseFromEvent() {
  const path = process.env.GITHUB_EVENT_PATH;
  if (!path) return null;
  try {
    return JSON.parse(readFileSync(path, "utf8"))?.release ?? null;
  } catch {
    return null;
  }
}

// Importable for tests; runs as a script otherwise.
if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  // `--self-test` runs the decision matrix and exits, so the workflow can verify the logic that
  // decides whether to announce anything before a token is ever read.
  if (process.argv.includes("--self-test")) {
    process.exit(selfTest());
  }
  main().then(
    (code) => process.exit(code),
    (error) => {
      console.error(`::error::${error.message}`);
      process.exit(1);
    },
  );
}
