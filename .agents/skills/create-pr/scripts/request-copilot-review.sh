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
repo_ref="$owner/$repo"
head_sha="$(gh pr view -R "$repo_ref" "$pr_number" --json headRefOid --jq '.headRefOid')"

confirm_requested_reviewer() {
  local reviewer="${1#@}"
  local requests
  requests="$(gh pr view -R "$repo_ref" "$pr_number" --json reviewRequests --jq '.reviewRequests[].login' 2>/dev/null || true)"
  printf '%s\n' "$requests" | grep -Fxq "$reviewer"
}

report_success() {
  local reviewer="${1#@}"
  printf 'requested_reviewer=%s\n' "$reviewer"
  printf 'pr_number=%s\n' "$pr_number"
  printf 'head_sha=%s\n' "$head_sha"
}

# remove/add workaround first
gh pr edit -R "$repo_ref" "$pr_number" --remove-reviewer "@copilot" >/dev/null 2>&1 || true
if gh pr edit -R "$repo_ref" "$pr_number" --add-reviewer "@copilot" >/dev/null 2>&1; then
  if confirm_requested_reviewer '@copilot'; then
    report_success '@copilot'
    exit 0
  fi
fi

reviewers=(
  'Copilot'
  'github-copilot[bot]'
  'copilot-pull-request-reviewer[bot]'
  'github-copilot'
)

for reviewer in "${reviewers[@]}"; do
  if gh api --method POST -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "repos/$owner/$repo/pulls/$pr_number/requested_reviewers" -F "reviewers[]=$reviewer" >/dev/null 2>&1; then
    if confirm_requested_reviewer "$reviewer"; then
      report_success "$reviewer"
      exit 0
    fi
  fi
done

echo "ERROR: unable to confirm accepted Copilot reviewer request on PR after request attempt" >&2
printf 'pr_number=%s\n' "$pr_number" >&2
printf 'head_sha=%s\n' "$head_sha" >&2
exit 1
