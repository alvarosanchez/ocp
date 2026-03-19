Output "docs/demo/interactive-mode.gif"

Require sh
Require env
Require mkdir
Require cat
Require script
Require bat

Set Shell "bash"
Set FontSize 26
Set Width 2970
Set Height 1890
Set TypingSpeed 90ms
Set Framerate 18
Set PlaybackSpeed 1.0
Set Theme "Catppuccin Mocha"

Hide
Type "rm -rf build/vhs-demo && mkdir -p build/vhs-demo/bin build/vhs-demo/config build/vhs-demo/cache build/vhs-demo/opencode build/vhs-demo/workspace" Sleep 300ms Enter
Type "cat > build/vhs-demo/opencode/opencode.json <<'JSON'" Sleep 300ms Enter
Type "{" Sleep 150ms Enter
Type '  "$schema": "https://opencode.ai/config.json",' Sleep 150ms Enter
Type '  "model": "anthropic/claude-sonnet-4-5"' Sleep 150ms Enter
Type "}" Sleep 150ms Enter
Type "JSON" Sleep 150ms Enter
Type "cat > build/vhs-demo/bin/ocp <<'SH'" Sleep 300ms Enter
Type "#!/bin/sh" Sleep 120ms Enter
Type "exec build/vhs-demo/bin/ocp-demo" Sleep 120ms Enter
Type "SH" Sleep 120ms Enter
Type "chmod +x build/vhs-demo/bin/ocp" Sleep 200ms Enter
Type "cat > build/vhs-demo/bin/ocp-demo <<'SH'" Sleep 300ms Enter
Type "#!/bin/sh" Sleep 120ms Enter
Type "env OCP_NO_VERSION_CHECK=1 OCP_CONFIG_DIR=build/vhs-demo/config OCP_CACHE_DIR=build/vhs-demo/cache OCP_OPENCODE_DIR=build/vhs-demo/opencode OCP_WORKING_DIR=build/vhs-demo/workspace TERM=xterm-256color COLORTERM=truecolor CLICOLOR_FORCE=1 script -q /dev/null build/native/nativeCompile/ocp" Sleep 150ms Enter
Type "SH" Sleep 120ms Enter
Type "chmod +x build/vhs-demo/bin/ocp-demo" Sleep 200ms Enter
Type "export PATH=build/vhs-demo/bin:$PATH" Sleep 250ms Enter
Type "clear" Sleep 200ms Enter
Show
Sleep 1800ms

Type "ocp" Sleep 250ms Enter
Sleep 4s

Enter
Sleep 1500ms
Type "demo-repo" Sleep 150ms Enter
Sleep 1500ms
Type "base" Sleep 150ms Enter
Sleep 4s

Type "m" Sleep 1000ms
Enter
Sleep 1800ms
Type "n" Sleep 150ms Enter
Sleep 4s

Type "c" Sleep 900ms
Type "child" Sleep 150ms Enter
Sleep 1200ms
Down
Sleep 1200ms
Enter
Sleep 2200ms
Enter
Sleep 4s

Type "f" Sleep 900ms
Type "opencode.json" Sleep 150ms Enter
Sleep 2200ms
Type "{" Sleep 150ms Enter
Type '  "autoupdate": true' Sleep 150ms Enter
Type "}" Sleep 150ms
Sleep 1500ms
Ctrl+S
Sleep 5000ms
