#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <pr-number>" >&2
  exit 2
fi

pr_number="$1"

gh pr checks "$pr_number"
gh pr view "$pr_number" --json statusCheckRollup
gh run list --branch "$(git branch --show-current)" --limit 10
