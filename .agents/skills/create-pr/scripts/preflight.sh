#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

branch="$(git branch --show-current)"
default_branch="$(gh repo view --json defaultBranchRef --jq '.defaultBranchRef.name')"

printf 'branch=%s\n' "$branch"
printf 'default_branch=%s\n' "$default_branch"
git status --short --branch
gh auth status

if [[ -z "$branch" ]]; then
  echo "ERROR: detached HEAD or empty branch name" >&2
  exit 1
fi

if [[ "$branch" == "$default_branch" ]]; then
  echo "ERROR: current branch matches default branch" >&2
  exit 1
fi
