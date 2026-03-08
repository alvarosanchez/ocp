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

gh api --method POST -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "repos/$owner/$repo/pulls/comments/$comment_id/replies" -f body="$reply_body"
