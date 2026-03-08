#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <owner> <repo> <pr-number>" >&2
  exit 2
fi

owner="$1"
repo="$2"
pr_number="$3"

reviewers=(
  'github-copilot[bot]'
  'copilot-pull-request-reviewer[bot]'
  'github-copilot'
)

for reviewer in "${reviewers[@]}"; do
  if gh api --method POST -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "repos/$owner/$repo/pulls/$pr_number/requested_reviewers" -F "reviewers[]=$reviewer"; then
    printf 'requested_reviewer=%s\n' "$reviewer"
    printf 'pr_number=%s\n' "$pr_number"
    printf 'head_sha=%s\n' "$(gh pr view "$pr_number" --json headRefOid --jq '.headRefOid')"
    exit 0
  fi
done

echo "ERROR: unable to request Copilot reviewer" >&2
exit 1
