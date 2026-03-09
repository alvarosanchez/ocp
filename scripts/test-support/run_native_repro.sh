#!/usr/bin/env bash
set -euo pipefail
WORK_DIR="${1:?work dir required}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE_BIN="$ROOT_DIR/scripts/test-support/fixtures/bin"
export PATH="$FIXTURE_BIN:$PATH"
CONFIG_DIR="${OCP_CONFIG_DIR:-$WORK_DIR/home/.config/ocp}"
CACHE_DIR="${OCP_CACHE_DIR:-$WORK_DIR/home/.config/ocp}"
OPENCODE_DIR="${OCP_OPENCODE_CONFIG_DIR:-$WORK_DIR/home/.config/opencode}"
WORKING_DIR="${OCP_WORKING_DIR:-$WORK_DIR/workspace}"
exec "$ROOT_DIR/build/native/nativeCompile/ocp" \
  "-Docp.config.dir=$CONFIG_DIR" \
  "-Docp.cache.dir=$CACHE_DIR" \
  "-Docp.opencode.config.dir=$OPENCODE_DIR" \
  "-Docp.working.dir=$WORKING_DIR"
