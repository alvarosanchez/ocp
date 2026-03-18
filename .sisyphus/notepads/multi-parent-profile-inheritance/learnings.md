## 2026-03-17 — README sync learnings

- README must show canonical persisted shape: `"extends_from": ["parent1","parent2"]`. SPEC documents array canonicalization and migration from scalar; update README example to match.
- CLI `profile create` uses `--extends-from` as a CSV string. The implementation normalizes by trimming, rejecting blanks and duplicates, and preserving order. README's command summary should show `--extends-from <parent-1,parent-2,...>`.
- Inheritance behavior summary for README: ordered parents, deep-merged JSON/JSONC, parent-only files inherited as-is and read-only, child precedence wins for matching keys, arrays/primitives replaced by child, and extension mismatch causes `profile use` to fail.
- Keep README short; point to SPEC for full contract and edge cases. The README must avoid duplicating every corner case.
- 2026-03-17 cleanup: dropped accidental line-number prefixes from SPEC/README while keeping multi-parent inheritance wording and CLI guidance intact.

## 2026-03-17 — Task 13 verification sweep learnings

- Full JVM verification stayed green (`./gradlew test`).
- Native verification initially failed only in `OcpCommandTest` startup-migration subprocess assertions because those tests resolve and launch a host JVM from inside the GraalVM native test image, which is not a supported native-test scenario.
- Fix was scoped to the regression: mark the three subprocess-based startup migration tests with `@DisabledInNativeImage` and keep their JVM coverage intact, while retaining a safer fallback in `javaBinaryPath()` for regular JVM execution.
- After the fix, the required sweep passed in order: `./gradlew test`, `./gradlew nativeTest`, `./gradlew check`.
