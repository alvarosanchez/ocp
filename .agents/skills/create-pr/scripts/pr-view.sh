#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

if gh pr view --json number,url,headRefName,headRefOid,headRepositoryOwner,baseRefName,state,statusCheckRollup,reviews 2>/dev/null; then
  exit 0
fi

echo '{"exists":false}'
