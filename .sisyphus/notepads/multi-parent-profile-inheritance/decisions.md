1: ## 2026-03-17 — Task 1 startup migration decisions
2: 
3: - Added a dedicated startup service `RepositoryMetadataMigrationService` to keep legacy metadata repair centralized and invoked once per process start.
4: - Startup order in `OcpCommand.execute(...)` is now: dependency verification -> application context -> legacy metadata migration -> startup version check -> command execution.
5: - Migration failure path is fail-fast: startup aborts with exit code `1` and does not execute command handlers (including `help`/`--version` flows).
6: - Legacy normalization scope is intentionally narrow: only scalar `"extends_from": "base"` values are rewritten to one-element arrays; already-array values are untouched.
7: - Task-1 regression tests now execute the real `main` startup path in a subprocess (`java -cp ... OcpCommand ...`) instead of only calling helper methods in-process.
8: 
9: ## 2026-03-17 — Task 2 ordered parent list decisions
10: 
11: - Switched metadata shape to `@JsonProperty("extends_from") List<String> extendsFromProfiles` with immutable copy semantics so canonical storage is always an ordered parent array.
12: - Added minimal compatibility shims instead of broad CLI changes: single-parent create APIs and existing `.extendsFrom()` callers remain supported as list-of-one behavior.
13: - Deferred multi-parent runtime lineage resolution to the later dedicated task; current lineage traversal keeps existing single-parent semantics through first-parent compatibility access.
14: 
15: ## 2026-03-17 — Task 3 multi-parent lineage decisions
16: 
17: - Replaced first-parent-only lineage traversal with ordered parent-array traversal (`extendsFromProfiles`) using left-to-right DFS post-order and global de-duplication.
18: - Kept existing merge implementation intact and made lineage order the only driver of effective file precedence.
19: - Preserved actionable validation messages for self-reference and unknown parents, and expanded cycle detection to include cycles that appear in any parent branch.
20: 
21: ## 2026-03-17 — Stabilization task legacy scalar compatibility decisions
22: 
23: - Implemented compatibility at the metadata model/serde layer (`RepositoryConfigFile.ProfileEntry`) instead of adding TUI-specific fallbacks.
24: - Kept canonical persisted metadata shape as parent arrays (`extends_from: ["..."]`) while accepting legacy scalar reads for direct fixture parsing.
25: - Scoped scalar compatibility normalization to legacy scalar paths only; list/array parent validation remains strict to preserve Task 2 and Task 3 behavior.
26: 
27: ## 2026-03-17 — Task 4 effective-file provenance and parent-only merge decisions
28: 
29: - Expanded `EffectiveProfileFile` to carry contributor provenance (`profileName` + `sourcePath` list) plus child-contribution metadata so service-layer consumers can distinguish inherited parent-only files, child-local merged files, and parent-only merged files.
30: - Parent-only JSON/JSONC overlap now materializes as merged output by reusing the existing deep merge path whenever a logical node has multiple JSON contributors, independent of child participation.
31: - `resolvedFilePreview(...)` now resolves by logical-path contributor membership, returning merged content when the selected source path is any contributing parent file for a merged logical node.
32: - Added command-level regressions only; no service changes were needed because existing refresh and merged-drift detection already evaluate the full multi-parent lineage for the active profile.
33: 
34: ## 2026-03-17 — Task 6 CLI CSV extends-from decisions
35: 
36: - Kept the public `--extends-from` flag name but updated the Picocli option to parse comma-separated parents, then routed the normalized list to the new `ProfileService.createProfileWithParents` helper so the CLI can print comma-separated success text while the service keeps ordered inheritance validation.
37: - Added regression tests that cover valid multi-parent syntax and invalid blank/duplicate segments so CLI-level validation and formatting stay aligned with the service-level list contract.
38: - Kept InteractiveApp navigation behavior minimal by reading only the first parent for existing parent-navigation logic, while selectedProfileHasParent now checks for non-empty parent lists.
39: - Kept the TUI change scoped to PromptState dynamic option fields plus InteractiveApp prompt advancement, leaving rendering/navigation untouched while delegating persistence to the existing list-based ProfileService API.
40: - Interactive node metadata now preserves contributor profile names from tree resolution so later parent-navigation work can choose among immediate contributors without recomputing provenance in the UI layer.
41: - Kept read-only edit blocking/status behavior unchanged; only shortcut visibility/gating was updated so deep-merged child-local files stay editable while inherited and parent-only merged file nodes hide/block edit.

## 2026-03-17 — Task 13 verification sweep decisions

- Kept the startup-migration subprocess assertions as JVM-only coverage with `@DisabledInNativeImage` instead of weakening their assertions or broadening production changes.
- Added a defensive fallback in `OcpCommandTest.javaBinaryPath()` to resolve the current executable from `ProcessHandle` when `java.home` is unavailable, preserving robustness for JVM subprocess runs.
