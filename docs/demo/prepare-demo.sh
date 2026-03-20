#!/bin/sh
set -eu

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
exec build/vhs-demo/bin/ocp-demo
SH
chmod +x build/vhs-demo/bin/ocp

cat > build/vhs-demo/bin/ocp-demo <<'SH'
#!/bin/sh
env OCP_NO_VERSION_CHECK=1 OCP_CONFIG_DIR=build/vhs-demo/config OCP_CACHE_DIR=build/vhs-demo/cache OCP_OPENCODE_DIR=build/vhs-demo/opencode OCP_WORKING_DIR=build/vhs-demo/workspace TERM=xterm-256color COLORTERM=truecolor CLICOLOR_FORCE=1 sh -c 'if script -q -c true /dev/null >/dev/null 2>&1; then exec script -q -c "./gradlew --no-daemon run" /dev/null; else exec script -q /dev/null ./gradlew --no-daemon run; fi'
SH
chmod +x build/vhs-demo/bin/ocp-demo
