#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

if [[ $# -lt 3 ]]; then
  echo "Usage: $0 <base-branch> <title> <body-file> [--draft]" >&2
  exit 2
fi

base_branch="$1"
title="$2"
body_file="$3"
shift 3

extra_args=()
if [[ $# -gt 0 ]]; then
  extra_args=("$@")
fi

gh pr create --base "$base_branch" --title "$title" --body-file "$body_file" "${extra_args[@]}"
