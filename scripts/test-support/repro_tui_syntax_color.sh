#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK_DIR="${1:-$(mktemp -d)}"
OUTPUT_FILE="${2:-$WORK_DIR/tui-output.txt}"
CONFIG_DIR="$WORK_DIR/config"
CACHE_DIR="$WORK_DIR/cache"
OPENCODE_DIR="$WORK_DIR/opencode"
WORKSPACE_DIR="$WORK_DIR/workspace"
REPO_DIR="$WORK_DIR/repo-a"
PROFILE_DIR="$REPO_DIR/default"
EXPECT_SCRIPT="$WORK_DIR/repro.exp"

mkdir -p "$CONFIG_DIR" "$CACHE_DIR" "$OPENCODE_DIR" "$WORKSPACE_DIR" "$PROFILE_DIR"
cat > "$REPO_DIR/repository.json" <<'JSON'
{"profiles":[{"name":"default"}]}
JSON
cat > "$PROFILE_DIR/opencode.json" <<'JSON'
{"theme":"dark","nested":{"x":1}}
JSON
cat > "$CONFIG_DIR/config.json" <<JSON
{"options":{},"repositories":[{"name":"repo-a","uri":null,"local_path":"$REPO_DIR"}]}
JSON

cat > "$EXPECT_SCRIPT" <<'EXPECT'
set timeout 20
set root [lindex $argv 0]
set output [lindex $argv 1]
set env(ocp.config.dir) [lindex $argv 2]
set env(ocp.cache.dir) [lindex $argv 3]
set env(ocp.opencode.config.dir) [lindex $argv 4]
set env(ocp.working.dir) [lindex $argv 5]
cd $root
log_file -noappend $output
stty rows 40 cols 120
spawn build/install/ocp/bin/ocp
expect {
  -re {Repositories / Profiles / Files} {}
  -re {OCP - OpenCode Configuration Profiles} {}
  timeout { exit 2 }
}
after 1500
send -- "
"
after 1500
send -- "[B"
after 800
send -- "
"
after 2500
send -- "q"
expect eof
EXPECT

chmod +x "$EXPECT_SCRIPT"
expect "$EXPECT_SCRIPT" "$ROOT_DIR" "$OUTPUT_FILE" "$CONFIG_DIR" "$CACHE_DIR" "$OPENCODE_DIR" "$WORKSPACE_DIR" >/dev/null 2>&1 || true

python3 - <<'PY2' "$OUTPUT_FILE"
from pathlib import Path
import re
import sys
output = Path(sys.argv[1]).read_text(errors='ignore') if Path(sys.argv[1]).exists() else ''
ansi = bool(re.search(r'\[[0-9;]*m', output))
preview = 'opencode.json' in output or 'theme' in output or 'nested' in output
print(f'ANSI_FOUND={ansi}')
print(f'PREVIEW_TEXT_FOUND={preview}')
PY2
