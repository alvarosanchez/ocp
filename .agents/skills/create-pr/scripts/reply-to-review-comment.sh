#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <owner> <repo> <pr-number> <comment-id> <reply-body>" >&2
  exit 2
fi

owner="$1"
repo="$2"
pr_number="$3"
comment_id="$4"
reply_body="$5"

output_file="$(mktemp)"
error_file="$(mktemp)"
trap 'rm -f "$output_file" "$error_file"' EXIT

if gh api --method POST   -H "Accept: application/vnd.github+json"   -H "X-GitHub-Api-Version: 2022-11-28"   "repos/$owner/$repo/pulls/$pr_number/comments"   -f body="$reply_body"   -F in_reply_to="$comment_id" >"$output_file" 2>"$error_file"; then
  cat "$output_file"
  exit 0
fi

echo "ERROR: unable to create review-thread reply for comment $comment_id on PR $pr_number" >&2
echo "REST failure:" >&2
cat "$error_file" >&2
exit 1
