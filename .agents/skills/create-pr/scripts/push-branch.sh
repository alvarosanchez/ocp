#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

remote="${1:-origin}"
refspec="${2:-}"
branch="$(git branch --show-current)"
upstream="$(git rev-parse --abbrev-ref --symbolic-full-name @{upstream} 2>/dev/null || true)"

printf 'branch=%s\n' "$branch"
printf 'upstream=%s\n' "$upstream"
printf 'remote=%s\n' "$remote"
if [[ -n "$refspec" ]]; then
  printf 'refspec=%s\n' "$refspec"
  git push "$remote" "$refspec"
elif [[ -z "$upstream" ]]; then
  printf 'refspec=%s:%s\n' "$branch" "$branch"
  git push -u "$remote" "$branch"
else
  printf 'refspec=%s
' "$upstream"
  git push
fi
printf 'head_sha=%s\n' "$(git rev-parse HEAD)"
