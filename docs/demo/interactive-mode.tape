Output "docs/demo/interactive-mode.gif"

Require sh
Require env
Require mkdir
Require cat
Require script
Require bat

Set Shell "bash"
Set FontSize 26
Set Width 1600
Set Height 1020
Set WindowBar Colorful
Set Margin 20
Set BorderRadius 20
Set TypingSpeed 100ms
Set PlaybackSpeed 0.5
Set Theme "Catppuccin Mocha"

Hide
Show
Sleep 800ms

Type "ocp" Sleep 250ms Enter
Sleep 2500ms

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
