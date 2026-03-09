#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 <owner> <repo> <comment-id> <reply-body>" >&2
  exit 2
fi

owner="$1"
repo="$2"
comment_id="$3"
reply_body="$4"
json_body="$(json_string "$reply_body")"

rest_output_file="$(mktemp)"
rest_error_file="$(mktemp)"
trap 'rm -f "$rest_output_file" "$rest_error_file" "$graphql_output_file" "$graphql_error_file"' EXIT

if gh api --method POST   -H "Accept: application/vnd.github+json"   -H "X-GitHub-Api-Version: 2022-11-28"   "repos/$owner/$repo/pulls/comments/$comment_id/replies"   -f body="$reply_body" >"$rest_output_file" 2>"$rest_error_file"; then
  cat "$rest_output_file"
  exit 0
fi

graphql_output_file="$(mktemp)"
graphql_error_file="$(mktemp)"
if gh api graphql   -f query='mutation($subjectId: ID!, $body: String!) { addPullRequestReviewComment(input: {inReplyTo: $subjectId, body: $body}) { comment { id url body } } }'   -F subjectId="$comment_id"   -F body="$reply_body" >"$graphql_output_file" 2>"$graphql_error_file"; then
  cat "$graphql_output_file"
  exit 0
fi

echo "ERROR: unable to create review-thread reply for comment $comment_id" >&2
echo "REST failure:" >&2
cat "$rest_error_file" >&2
echo "GraphQL fallback failure:" >&2
cat "$graphql_error_file" >&2
exit 1
