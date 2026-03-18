## Summary
- add multi-parent profile inheritance across CLI, service resolution, and interactive TUI flows, including ordered `extends_from` support and startup migration from legacy scalar metadata
- update hierarchy/detail rendering, parent navigation, and read-only merged-file behavior so deep-merged parent contributions are displayed and traversed correctly
- fix deep-merged preview consistency by hardening bat invocation (`--file-name` + JSON/JSONC handling) and reapplying active dependent profiles when parent files are edited in the TUI

## Verification
- `./gradlew test`
- `./gradlew nativeTest`
- `./gradlew build`
- `./gradlew nativeCompile`
