#!/usr/bin/env bash
set -euo pipefail

export CI=true
export DEBIAN_FRONTEND=noninteractive
export GIT_TERMINAL_PROMPT=0
export GCM_INTERACTIVE=never
export HOMEBREW_NO_AUTO_UPDATE=1
export GIT_EDITOR=:
export EDITOR=:
export VISUAL=''
export GIT_SEQUENCE_EDITOR=:
export GIT_MERGE_AUTOEDIT=no
export GIT_PAGER=cat
export PAGER=cat
export GH_PROMPT_DISABLED=true

json_string() {
  python3 - <<'PY' "$1"
import json
import sys
print(json.dumps(sys.argv[1]))
PY
}
