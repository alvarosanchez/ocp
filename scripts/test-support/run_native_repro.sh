#!/usr/bin/env bash
set -euo pipefail
WORK_DIR="${1:?work dir required}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE_BIN="$ROOT_DIR/scripts/test-support/fixtures/bin"
export HOME="$WORK_DIR/home"
export PATH="$FIXTURE_BIN:$PATH"
exec "$ROOT_DIR/build/native/nativeCompile/ocp"
