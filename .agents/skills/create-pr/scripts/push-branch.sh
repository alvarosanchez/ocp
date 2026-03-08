#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

remote="${1:-origin}"
branch="$(git branch --show-current)"
upstream="$(git rev-parse --abbrev-ref --symbolic-full-name @{upstream} 2>/dev/null || true)"

printf 'branch=%s\n' "$branch"
printf 'upstream=%s\n' "$upstream"
git remote -v

if [[ -z "$upstream" ]]; then
  git push -u "$remote" "$branch"
else
  git push
fi

printf 'head_sha=%s\n' "$(git rev-parse HEAD)"
