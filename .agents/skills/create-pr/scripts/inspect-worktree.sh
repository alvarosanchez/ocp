#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

git status --short
printf '\n--- unstaged ---\n'
git diff --stat || true
printf '\n--- staged ---\n'
git diff --staged --stat || true
