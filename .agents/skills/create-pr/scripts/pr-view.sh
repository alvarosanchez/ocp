#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

gh pr view --json number,url,headRefName,baseRefName,state,statusCheckRollup 2>/dev/null || true
