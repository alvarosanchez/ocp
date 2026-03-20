Output "docs/demo/interactive-mode.gif"

Require sh
Require env
Require mkdir
Require cat
Require script
Require bat

Set Shell "bash"
Set FontSize 10
Set Width 1080
Set Height 720
Set WindowBar Colorful
Set Margin 10
Set Padding 5
Set BorderRadius 20
Set TypingSpeed 100ms
Set PlaybackSpeed 0.5
Set Theme "Catppuccin Mocha"

Hide
Type "build/vhs-demo/bin/ocp" Enter
Sleep 2s
Show

# Import existing config. Default yes
Enter
Sleep 2500ms

# Create onboarding repo
Type "demo-repo" Sleep 150ms Enter
Sleep 2500ms

# Crate onboarding profile
Type "base" Sleep 150ms Enter
Sleep 2500ms

# Initialize Git? Default yes, move to no
Down
Sleep 1s
Enter

# Create profile
Type "c"
Sleep 1800ms
Type "child" Sleep 150ms Enter
Sleep 2500ms

# Extends from: down to select base
Down
Sleep 1s
Enter
Sleep 1800ms

# Enter to select none additional profiles to extend from
Enter
Sleep 2500ms

# Trigger delete confirmation overlay on selected profile
Type "d"
Sleep 2500ms

# Cancel overlay and continue
Escape
Sleep 1500ms

# Create file
Type "f" Sleep 900ms

# Filename
Type "opencode.json" Sleep 150ms Enter
Sleep 1800ms

# New file editing
Type "{" Sleep 150ms Enter
Type '  "autoupdate": true' Sleep 150ms Enter
Type "}" Sleep 150ms
Sleep 2500ms

# Save
Ctrl+S
Sleep 4s
