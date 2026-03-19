Output "docs/demo/interactive-mode.gif"

Require sh
Require env
Require mkdir
Require cat
Require script
Require bat

Set Shell "bash"
Set FontSize 26
Set Width 2400
Set Height 1530
Set WindowBar Colorful
Set Margin 20
Set BorderRadius 20
Set TypingSpeed 100ms
Set PlaybackSpeed 0.2
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
Type "env OCP_NO_VERSION_CHECK=1 OCP_CONFIG_DIR=build/vhs-demo/config OCP_CACHE_DIR=build/vhs-demo/cache OCP_OPENCODE_DIR=build/vhs-demo/opencode OCP_WORKING_DIR=build/vhs-demo/workspace TERM=xterm-256color COLORTERM=truecolor CLICOLOR_FORCE=1 sh -c 'if script -q -c true /dev/null >/dev/null 2>&1; then exec script -q -c "build/native/nativeCompile/ocp" /dev/null; else exec script -q /dev/null build/native/nativeCompile/ocp; fi'" Sleep 150ms Enter
Type "SH" Sleep 120ms Enter
Type "chmod +x build/vhs-demo/bin/ocp-demo" Sleep 200ms Enter
Type "export PATH=build/vhs-demo/bin:$PATH" Sleep 250ms Enter
Type "clear" Sleep 200ms Enter
Show
Sleep 1800ms

Type "ocp" Sleep 250ms Enter
Sleep 5s

# Import existing config. Default yes
Enter
Sleep 5s

# Create onboarding repo
Type "demo-repo" Sleep 150ms Enter
Sleep 5s

# Crate onboarding profile
Type "base" Sleep 150ms Enter
Sleep 5s

# Initialize Git? Default yes, move to no
Down
Sleep 1s
Enter

# Create profile
Type "c"
Sleep 3s
Type "child" Sleep 150ms Enter
Sleep 5s

# Extends from: down to select base
Down
Sleep 1s
Enter
Sleep 3s

# Enter to select none additional profiles to extend from
Enter
Sleep 5s

# Create file
Type "f" Sleep 900ms

# Filename
Type "opencode.json" Sleep 150ms Enter
Sleep 3s

# New file editing
Type "{" Sleep 150ms Enter
Type '  "autoupdate": true' Sleep 150ms Enter
Type "}" Sleep 150ms
Sleep 5s

# Save
Ctrl+S
Sleep 10s
