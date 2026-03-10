#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <run-id>" >&2
  exit 2
fi

run_id="$1"

gh run view "$run_id"
gh run view "$run_id" --log
