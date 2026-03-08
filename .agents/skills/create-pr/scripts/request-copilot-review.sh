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

is_review_active() {
  local reviewer="$1"
  local review_requests reviews review_author

  review_requests="$(gh pr view -R "$owner/$repo" "$pr_number" --json reviewRequests --jq '.reviewRequests[].login' 2>/dev/null || true)"
  if printf '%s\n' "$review_requests" | grep -Fxq "$reviewer"; then
    return 0
  fi

  reviews="$(gh pr view -R "$owner/$repo" "$pr_number" --json reviews --jq '.reviews[].author.login' 2>/dev/null || true)"
  while IFS= read -r review_author; do
    if [[ "$review_author" == "$reviewer" ]]; then
      return 0
    fi
  done <<< "$reviews"

  return 1
}

for reviewer in "${reviewers[@]}"; do
  if is_review_active "$reviewer"; then
    printf 'requested_reviewer=%s\n' "$reviewer"
    printf 'pr_number=%s\n' "$pr_number"
    printf 'head_sha=%s\n' "$(gh pr view -R "$owner/$repo" "$pr_number" --json headRefOid --jq '.headRefOid')"
    exit 0
  fi

  if gh api --method POST -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "repos/$owner/$repo/pulls/$pr_number/requested_reviewers" -F "reviewers[]=$reviewer" >/dev/null 2>&1 && is_review_active "$reviewer"; then
    printf 'requested_reviewer=%s\n' "$reviewer"
    printf 'pr_number=%s\n' "$pr_number"
    printf 'head_sha=%s\n' "$(gh pr view -R "$owner/$repo" "$pr_number" --json headRefOid --jq '.headRefOid')"
    exit 0
  fi
done

echo "ERROR: unable to confirm active Copilot reviewer on PR after request attempt" >&2
exit 1
