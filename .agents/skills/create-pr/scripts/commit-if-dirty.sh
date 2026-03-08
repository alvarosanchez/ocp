#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <commit-message> <file> [<file> ...]" >&2
  exit 2
fi

commit_message="$1"
shift

if [[ -z "$(git status --short)" ]]; then
  echo "Worktree already clean. No commit created."
  exit 0
fi

git add "$@"
git status --short
git commit -m "$commit_message"
