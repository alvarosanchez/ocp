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

gh pr view "$pr_number" --json number,url,statusCheckRollup
gh pr checks "$pr_number" || true
gh api -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "repos/$owner/$repo/pulls/$pr_number/comments"
gh api graphql -f query='query($owner: String!, $repo: String!, $number: Int!) { repository(owner: $owner, name: $repo) { pullRequest(number: $number) { reviews(first: 100) { nodes { id state submittedAt author { login } } } reviewThreads(first: 100) { nodes { id isResolved isOutdated comments(first: 20) { nodes { id databaseId body url author { login } createdAt } } } } statusCheckRollup { contexts(first: 100) { nodes { __typename ... on CheckRun { name status conclusion detailsUrl } ... on StatusContext { context state targetUrl } } } } } } }' -F owner="$owner" -F repo="$repo" -F number="$pr_number"
