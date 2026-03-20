#!/bin/sh
set -eu

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$REPO_ROOT"

if [ ! -x build/install/ocp/bin/ocp ]; then
  ./gradlew --no-daemon installDist
fi

rm -rf build/vhs-demo
mkdir -p build/vhs-demo/bin build/vhs-demo/config build/vhs-demo/cache build/vhs-demo/opencode build/vhs-demo/workspace

cat > build/vhs-demo/opencode/opencode.json <<'JSON'
{
  "$schema": "https://opencode.ai/config.json",
  "model": "anthropic/claude-sonnet-4-5"
}
JSON

cat > build/vhs-demo/bin/ocp <<'SH'
#!/bin/sh
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$script_dir/ocp-demo" "$@"
SH
chmod +x build/vhs-demo/bin/ocp

cat > build/vhs-demo/bin/ocp-demo <<'SH'
#!/bin/sh
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
ocp_bin="$repo_root/build/install/ocp/bin/ocp"
env \
  OCP_NO_VERSION_CHECK=1 \
  OCP_CONFIG_DIR="$repo_root/build/vhs-demo/config" \
  OCP_CACHE_DIR="$repo_root/build/vhs-demo/cache" \
  OCP_OPENCODE_DIR="$repo_root/build/vhs-demo/opencode" \
  OCP_WORKING_DIR="$repo_root/build/vhs-demo/workspace" \
  TERM=xterm-256color \
  COLORTERM=truecolor \
  CLICOLOR_FORCE=1 \
  sh -c '
    if script -q -c true /dev/null >/dev/null 2>&1; then
      exec script -q -c "$1 \"\$@\"" /dev/null sh "$1" "$@"
    else
      exec script -q /dev/null "$1" "$@"
    fi
  ' sh "$ocp_bin" "$@"
SH
chmod +x build/vhs-demo/bin/ocp-demo
