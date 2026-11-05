#!/usr/bin/env bash
# Apply main-branch protection for Oqthara/oqthara.
#
# Idempotent: creates the "main-protection" ruleset once, then updates it in
# place. Requires a token with admin rights on the repository.
#
# Usage: scripts/apply-branch-protection.sh [--dry-run] [--repo OWNER/NAME]
set -euo pipefail

REPO="${REPO:-Oqthara/oqthara}"
RULESET_NAME="main-protection"
DRY_RUN=false

usage() {
  echo "usage: $0 [--dry-run] [--repo OWNER/NAME]" >&2
  echo "  --dry-run  print the ruleset payload and exit without calling the API" >&2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=true ;;
    --repo) REPO="${2:?--repo needs OWNER/NAME}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
  shift
done

payload=$(cat <<JSON
{
  "name": "${RULESET_NAME}",
  "target": "branch",
  "enforcement": "active",
  "conditions": {
    "ref_name": {
      "include": ["refs/heads/main"],
      "exclude": []
    }
  },
  "bypass_actors": [],
  "rules": [
    {"type": "deletion"},
    {"type": "non_fast_forward"},
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": true,
        "do_not_enforce_on_create": false,
        "required_status_checks": [
          {"context": "ubuntu-latest / JDK 21"},
          {"context": "ubuntu-latest / JDK 25"},
          {"context": "windows-latest / JDK 21"},
          {"context": "windows-latest / JDK 25"},
          {"context": "Bytecode backend (JDK 25)"},
          {"context": "Standalone jar"},
          {"context": "Build and run the image"},
          {"context": "analyze"},
          {"context": "ci/circleci: macOS / JDK 21"},
          {"context": "ci/circleci: macOS / JDK 25"},
          {"context": "ci/circleci: jazzer-fuzz"}
        ]
      }
    }
  ]
}
JSON
)

if [ "$DRY_RUN" = true ]; then
  printf '%s\n' "$payload"
  echo "dry run: no ruleset was applied" >&2
  exit 0
fi

admin=$(gh api "repos/${REPO}" --jq '.permissions.admin // false')
if [ "$admin" != "true" ]; then
  echo "error: the authenticated gh user needs admin rights on ${REPO}" >&2
  exit 1
fi

ruleset_id=$(gh api "repos/${REPO}/rulesets" --paginate \
  --jq ".[] | select(.name == \"${RULESET_NAME}\") | .id" | head -n 1)

if [ -n "$ruleset_id" ]; then
  echo "updating ruleset ${RULESET_NAME} (${ruleset_id})" >&2
  gh api --method PUT "repos/${REPO}/rulesets/${ruleset_id}" --input - <<<"$payload" >/dev/null
else
  echo "creating ruleset ${RULESET_NAME}" >&2
  ruleset_id=$(gh api --method POST "repos/${REPO}/rulesets" --input - <<<"$payload" --jq '.id')
fi

# Squash-only merges; delete the head branch once merged.
gh api --method PATCH "repos/${REPO}" \
  -F allow_squash_merge=true \
  -F allow_merge_commit=false \
  -F allow_rebase_merge=false \
  -F delete_branch_on_merge=true >/dev/null

echo "applied ruleset ${ruleset_id}:" >&2
gh api "repos/${REPO}/rulesets/${ruleset_id}"
