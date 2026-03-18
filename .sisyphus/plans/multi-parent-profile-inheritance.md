# Ordered Multi-Parent Profile Inheritance

## TL;DR
> **Summary**: Replace single-parent inheritance with ordered multi-parent inheritance across config, service, CLI, and TUI, while eagerly migrating legacy scalar `extends_from` metadata on every invocation.
> **Deliverables**:
> - Ordered `extends_from` arrays with startup migration from legacy strings
> - Deterministic multi-parent lineage, merge, provenance, and refresh behavior
> - CSV-based CLI create flow plus repeated TUI parent selection
> - Multi-parent tree/detail/navigation UX and updated docs/tests
> **Effort**: Large
> **Parallel**: YES - 3 waves
> **Critical Path**: 1 → 2 → 3 → 4 → 8/9/10 → 13

## Context
### Original Request
- Support profiles extending from more than one parent.
- Change `extends_from` from a string to an ordered array.
- Merge earlier parents first, then later parents, then the child.
- Migrate legacy scalar `extends_from` values to one-element arrays on app startup.
- Show multiple parents in the interactive tree.
- Repeat the interactive parent-selection modal until the user selects none.
- Make `p` navigate to the most immediate parent for multi-parent profiles.
- Review the spec for missing gaps/corner cases and resolve them explicitly.

### Interview Summary
- CLI decision: `ocp profile create` keeps `--extends-from`, but now accepts a comma-separated value (for example `--extends-from A,B`).
- Migration decision: run eager legacy migration on **all invocations**, fail fast before version-check / command execution if any metadata migration fails.
- Parent-only merged JSON/JSONC node decision: read-only (`🔒` semantics), `p` opens the most immediate contributing parent file, and `e` stays disabled.
- Test decision: TDD.
- Defaults applied:
  - Reject duplicate parent names after trimming.
  - `extends_from` omitted or `[]` means no parents.
  - Blank array entries are invalid, not silently dropped.
  - Canonical JSON rewrite is acceptable for startup migration; preserving original spacing/order beyond semantic equivalence is not required.
  - Ordered lineage rule is left-to-right DFS post-order with global de-duplication: resolve each declared parent in order, recursively resolve its parents first, append each profile once, then append the child.
  - Non-JSON path conflicts across parents use the same precedence as the lineage order: later parents win over earlier parents; the child wins over every parent.
  - For parent-only merged JSON/JSONC nodes, keep merged preview content but treat the node as read-only; tree/icon logic must prefer read-only over editable deep-merge affordances.

### Metis Review (gaps addressed)
- Centralize legacy scalar-to-array migration instead of scattering repair logic across individual readers.
- Keep four concepts distinct: declared parent list, resolved lineage order, file provenance, and `p` navigation target.
- Do not widen scope into generic metadata auto-healing.
- Make acceptance criteria explicit for duplicates, blanks, repeated ancestors, parent-only merged nodes, and cross-repository parent graphs.
- Preserve single-parent behavior as the array-of-one degenerate case.

## Work Objectives
### Core Objective
Implement decision-complete ordered multi-parent inheritance without changing unrelated merge semantics, uniqueness rules, or non-requested deletion behavior.

### Deliverables
- `RepositoryConfigFile.ProfileEntry` stores ordered parent lists.
- A startup migration path rewrites legacy scalar `extends_from` metadata to arrays on every invocation.
- `ProfileService` resolves ordered DAG inheritance, validates parents, preserves precedence, and tracks file provenance.
- CLI create/profile flows accept and persist multiple parents in order.
- TUI create/profile/tree/detail/navigation flows expose the new semantics consistently.
- `SPEC.md`, `README.md`, and command help text reflect the final behavior.
- Focused regression coverage exists for model, service, CLI, and TUI paths.

### Definition of Done (verifiable conditions with commands)
- `./gradlew test --tests com.github.alvarosanchez.ocp.command.OcpCommandTest`
- `./gradlew test --tests com.github.alvarosanchez.ocp.service.ProfileServiceTest`
- `./gradlew test --tests com.github.alvarosanchez.ocp.command.ProfileCommandTest`
- `./gradlew test --tests com.github.alvarosanchez.ocp.command.interactive.InteractiveAppCreateProfileTest`
- `./gradlew test --tests com.github.alvarosanchez.ocp.command.interactive.InteractiveAppSelectionRefreshTest`
- `./gradlew test --tests com.github.alvarosanchez.ocp.command.interactive.InteractiveAppPilotTest`
- `./gradlew test --tests com.github.alvarosanchez.ocp.command.interactive.HierarchyTreeBuilderTest`
- `./gradlew test --tests com.github.alvarosanchez.ocp.command.interactive.DetailPaneRendererTest`
- `./gradlew test --tests com.github.alvarosanchez.ocp.command.interactive.TreeShortcutHintsTest`
- `./gradlew test`
- `./gradlew nativeTest`

### Must Have
- Preserve declared parent order end-to-end: CLI parsing, metadata persistence, lineage traversal, tree/detail display, and navigation target selection.
- Run migration before `runStartupVersionCheck(...)` and `CommandLine.execute(...)` in `OcpCommand.main(...)`.
- Rewrite only legacy scalar `extends_from` values; already-normalized arrays must remain untouched.
- Fail fast on invalid parent arrays (blank entries, duplicates, unknown parents, cycles, extension mismatches).
- Treat CLI forms like `--extends-from ""` and `--extends-from base,,team` as invalid input, not as shorthand for “no parents”.
- Keep single-parent semantics unchanged when the array has one element.
- Keep child-local deep-merged JSON/JSONC files editable; only parent-only merged nodes become read-only.

### Must NOT Have (guardrails, AI slop patterns, scope boundaries)
- Must NOT introduce a generic migration framework.
- Must NOT silently drop blank or duplicate parents.
- Must NOT change array/scalar JSON merge semantics beyond widening parent inputs.
- Must NOT change duplicate-profile-name handling across repositories.
- Must NOT block or redesign profile deletion just because children now may have multiple parents.
- Must NOT add UI affordances for cycling through all contributors; only the defined immediate-parent behavior is in scope.

## Verification Strategy
> ZERO HUMAN INTERVENTION — all verification is agent-executed.
- Test decision: TDD + JUnit 5 / Micronaut test / existing TamboUI test fixtures
- QA policy: Every task includes agent-executed happy-path and failure/edge-case scenarios.
- Evidence: `.sisyphus/evidence/task-{N}-{slug}.{ext}`

## Execution Strategy
### Parallel Execution Waves
> Target: 5-8 tasks per wave. Shared semantics land first; CLI/TUI/doc layers follow after the service contract is stable.

Wave 1: foundation semantics and migration (Tasks 1-5)
- metadata shape and eager startup migration
- list-based creation/discovery normalization
- ordered DAG lineage and validation
- effective-file merge/provenance model
- refresh/reapply regression coverage

Wave 2: public interfaces and interactive behavior (Tasks 6-10)
- CLI CSV parsing and messages
- repeated TUI parent selection flow
- list-based parent maps and multi-parent labels
- read-only parent-only merged node rendering
- immediate-parent navigation / shortcut behavior

Wave 3: spec/docs and repository-wide verification (Tasks 11-13)
- SPEC/help contract updates
- README/example sync
- full JVM/native verification and evidence capture

### Dependency Matrix (full, all tasks)
- 1: none
- 2: 1
- 3: 2
- 4: 3
- 5: 3, 4
- 6: 2
- 7: 2
- 8: 2, 4
- 9: 4, 8
- 10: 8, 9
- 11: 1, 6, 7, 8, 9, 10
- 12: 11
- 13: 5, 6, 7, 8, 9, 10, 11, 12

### Agent Dispatch Summary (wave → task count → categories)
- Wave 1 → 5 tasks → `deep`, `unspecified-high`
- Wave 2 → 5 tasks → `unspecified-high`, `quick`
- Wave 3 → 3 tasks → `writing`, `unspecified-high`

## TODOs
> Implementation + Test = ONE task. Never separate.
> EVERY task MUST have: Agent Profile + Parallelization + QA Scenarios.

- [x] 1. Implement eager legacy `extends_from` startup migration on all invocations

  **What to do**: Add TDD coverage and a single startup migration path that scans configured repositories on every invocation, rewrites legacy scalar `extends_from` values to canonical one-element arrays, and fails fast before `runStartupVersionCheck(...)` or `CommandLine.execute(...)` if any migration fails. Keep the rewrite idempotent and limited to legacy scalar normalization.
  **Must NOT do**: Do not migrate unrelated metadata fields; do not defer this work to command-specific readers; do not skip `help`, `--version`, or root usage invocations.

  **Recommended Agent Profile**:
  - Category: `deep` — Reason: startup sequencing + metadata rewrite must be exact and cross-cutting.
  - Skills: `[]` — No specialized external skill is needed.
  - Omitted: `["git-master"]` — No git/history work is required.

  **Parallelization**: Can Parallel: NO | Wave 1 | Blocks: 2, 6, 7, 11, 12, 13 | Blocked By: none

  **References** (executor has NO interview context — be exhaustive):
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/OcpCommand.java:62-79` — current startup order: dependencies → context start → version check → command execution.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/config/RepositoryConfigFile.java:12-50` — current repository metadata model still encodes one parent.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:1195-1211` — canonical metadata read/write helpers already serialize `RepositoryConfigFile` via `objectMapper.writeValueAsString(...)`.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/command/OcpCommandTest.java:33-80` — startup/help/version command test patterns.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `./gradlew test --tests '*OcpCommandTest.helpInvocationMigratesLegacyExtendsFromBeforeExecution'` passes.
  - [ ] `./gradlew test --tests '*OcpCommandTest.versionInvocationMigratesLegacyExtendsFromBeforeExecution'` passes.
  - [ ] `./gradlew test --tests '*OcpCommandTest.startupMigrationFailsFastBeforeCommandExecutionWhenRepositoryMetadataRewriteFails'` passes.

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: Legacy scalar metadata is normalized before help executes
    Tool: Bash
    Steps: Run ./gradlew test --tests '*OcpCommandTest.helpInvocationMigratesLegacyExtendsFromBeforeExecution'
    Expected: Exit 0; the test verifies `repository.json` is rewritten from `"extends_from":"base"` to `"extends_from":["base"]` before help output proceeds.
    Evidence: .sisyphus/evidence/task-1-startup-migration.txt

  Scenario: Rewrite failure aborts startup
    Tool: Bash
    Steps: Run ./gradlew test --tests '*OcpCommandTest.startupMigrationFailsFastBeforeCommandExecutionWhenRepositoryMetadataRewriteFails'
    Expected: Exit 0; the test verifies the command path is not executed and stderr reports the migration failure.
    Evidence: .sisyphus/evidence/task-1-startup-migration-error.txt
  ```

  **Commit**: YES | Message: `feat(config): migrate legacy extends_from arrays at startup` | Files: `src/main/java/com/github/alvarosanchez/ocp/command/OcpCommand.java`, `src/main/java/com/github/alvarosanchez/ocp/config/RepositoryConfigFile.java`, new/updated startup migration tests

- [x] 2. Refactor metadata normalization and profile creation/discovery to ordered parent lists

  **What to do**: Change `RepositoryConfigFile.ProfileEntry` and `ProfileService` create/discovery paths to use immutable ordered parent lists instead of single strings. Normalize by trimming each parent, rejecting blanks and duplicates after trim, preserving order, and treating omitted/empty arrays as no parents. Update service APIs so callers hand in normalized parent lists rather than single names.
  **Must NOT do**: Do not keep a long-lived parallel single-string codepath; do not silently drop invalid parent entries; do not reorder valid parents.

  **Recommended Agent Profile**:
  - Category: `deep` — Reason: this task changes the core metadata contract used by service, CLI, and TUI.
  - Skills: `[]` — Repo-local work only.
  - Omitted: `["git-master"]` — No git workflow required.

  **Parallelization**: Can Parallel: NO | Wave 1 | Blocks: 3, 6, 7, 8 | Blocked By: 1

  **References** (executor has NO interview context — be exhaustive):
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/config/RepositoryConfigFile.java:12-50` — replace scalar `extendsFrom` with ordered immutable parent list.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:121-149` — public create APIs currently take a single parent string.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:278-314` — profile creation persists a single parent today.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:1058-1070` — discovered profile model still stores one parent.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:1216-1223` — read-path normalization currently trims names and passes through one parent.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/service/ProfileServiceTest.java:160-208` — current create-with-parent validation patterns.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/service/ProfileServiceTest.java:295-323` — cross-repository parent creation pattern.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `./gradlew test --tests '*ProfileServiceTest.createProfileStoresOrderedParentListWhenInheritanceIsConfigured'` passes.
  - [ ] `./gradlew test --tests '*ProfileServiceTest.createProfileAllowsOrderedParentsFromMultipleRepositories'` passes.
  - [ ] `./gradlew test --tests '*ProfileServiceTest.createProfileRejectsBlankOrDuplicateParentsAfterTrim'` passes.

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: Service persists ordered parent arrays exactly as provided
    Tool: Bash
    Steps: Run ./gradlew test --tests '*ProfileServiceTest.createProfileStoresOrderedParentListWhenInheritanceIsConfigured'
    Expected: Exit 0; the test verifies `repository.json` stores `"extends_from":["base","team"]` in declared order.
    Evidence: .sisyphus/evidence/task-2-parent-list-normalization.txt

  Scenario: Invalid parent arrays are rejected deterministically
    Tool: Bash
    Steps: Run ./gradlew test --tests '*ProfileServiceTest.createProfileRejectsBlankOrDuplicateParentsAfterTrim'
    Expected: Exit 0; the test verifies blank entries and duplicates fail with actionable messages instead of being dropped.
    Evidence: .sisyphus/evidence/task-2-parent-list-normalization-error.txt
  ```

  **Commit**: YES | Message: `refactor(service): normalize ordered parent lists` | Files: `src/main/java/com/github/alvarosanchez/ocp/config/RepositoryConfigFile.java`, `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java`, updated service tests

- [x] 3. Implement deterministic ordered DAG lineage and multi-parent validation

  **What to do**: Replace single-chain lineage resolution with ordered left-to-right DFS post-order traversal plus a global resolved set and a visiting set. For each profile: recursively resolve each declared parent in list order, append each profile once after its parents, and raise clear errors for self-reference, unknown parents, and cycles across any branch.
  **Must NOT do**: Do not topologically sort in a way that loses declared order; do not append repeated ancestors more than once; do not weaken existing unknown-parent or extension-mismatch failures.

  **Recommended Agent Profile**:
  - Category: `deep` — Reason: correctness of every downstream merge depends on a precise lineage algorithm.
  - Skills: `[]` — Internal algorithmic work only.
  - Omitted: `["git-master"]` — No git skill benefit.

  **Parallelization**: Can Parallel: NO | Wave 1 | Blocks: 4, 5, 8, 9, 10, 13 | Blocked By: 2

  **References** (executor has NO interview context — be exhaustive):
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:742-849` — current lineage + effective-file merge loop; preserve parent-before-child behavior while broadening the input graph.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:1058-1083` — profile discovery currently builds the single-parent discovered model.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/service/ProfileServiceTest.java:419-540` — existing inheritance and merged-preview service tests.
  - Spec: `SPEC.md:239-245` — current inheritance rules to preserve (child precedence, deep merge, extension mismatch failure).

  **Acceptance Criteria** (agent-executable only):
  - [ ] `./gradlew test --tests '*ProfileServiceTest.useProfileResolvesParentsLeftToRightAcrossNestedBranches'` passes.
  - [ ] `./gradlew test --tests '*ProfileServiceTest.useProfileRejectsCyclesAcrossMultipleParents'` passes.
  - [ ] `./gradlew test --tests '*ProfileServiceTest.useProfileRejectsUnknownParentInParentArray'` passes.

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: Nested parent DAG resolves in declared order
    Tool: Bash
    Steps: Run ./gradlew test --tests '*ProfileServiceTest.useProfileResolvesParentsLeftToRightAcrossNestedBranches'
    Expected: Exit 0; the test verifies the flattened lineage order is stable and later parents override earlier parents.
    Evidence: .sisyphus/evidence/task-3-dag-lineage.txt

  Scenario: Cross-branch cycle is rejected
    Tool: Bash
    Steps: Run ./gradlew test --tests '*ProfileServiceTest.useProfileRejectsCyclesAcrossMultipleParents'
    Expected: Exit 0; the test verifies cycle detection names the offending traversal path and aborts resolution.
    Evidence: .sisyphus/evidence/task-3-dag-lineage-error.txt
  ```

  **Commit**: YES | Message: `feat(service): resolve ordered multi-parent lineage` | Files: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java`, updated service tests

- [x] 4. Refactor effective-file resolution to track provenance and parent-only merged nodes

  **What to do**: Rework the effective-file model so it can distinguish: child-local deep-merged JSON/JSONC files, inherited parent-only files, and read-only parent-only merged JSON/JSONC files. Track enough provenance to know the last contributing parent profile/file for a selected node while preserving current materialization of merged output under `resolved-profiles/`.
  **Must NOT do**: Do not collapse parent-only merged nodes into plain inherited nodes with raw content; do not lose child editability for child-local deep-merged files; do not change non-JSON precedence rules.

  **Recommended Agent Profile**:
  - Category: `deep` — Reason: this is the semantic bridge between lineage correctness and UI/navigation correctness.
  - Skills: `[]` — No extra skill needed.
  - Omitted: `["git-master"]` — No history work.

  **Parallelization**: Can Parallel: NO | Wave 1 | Blocks: 5, 8, 9, 10, 13 | Blocked By: 3

  **References** (executor has NO interview context — be exhaustive):
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:705-740` — merged files are materialized from `EffectiveProfileFile` into `resolved-profiles/`.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:742-804` — current effective-file merge loop must grow provenance, not just overwrite paths.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:178-235` — `resolvedFilePreview(...)` currently assumes a simpler inherited-vs-merged distinction.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:930-945` — preserve current recursive JSON merge semantics.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/service/ProfileServiceTest.java:419-540` — existing merged-file behavior patterns.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `./gradlew test --tests '*ProfileServiceTest.useProfileDeepMergesParentOnlySharedJsonAcrossMultipleParents'` passes.
  - [ ] `./gradlew test --tests '*ProfileServiceTest.useProfileLetsLaterParentWinForParentOnlyNonJsonConflicts'` passes.
  - [ ] `./gradlew test --tests '*ProfileServiceTest.resolvedFilePreviewReturnsMergedContentForParentOnlyMergedJson'` passes.

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: Parent-only overlapping JSON is materialized as merged output
    Tool: Bash
    Steps: Run ./gradlew test --tests '*ProfileServiceTest.useProfileDeepMergesParentOnlySharedJsonAcrossMultipleParents'
    Expected: Exit 0; the test verifies parent A + parent B merge into one resolved child file even when the child has no local copy.
    Evidence: .sisyphus/evidence/task-4-provenance-merge.txt

  Scenario: Parent-only overlapping non-JSON follows lineage precedence
    Tool: Bash
    Steps: Run ./gradlew test --tests '*ProfileServiceTest.useProfileLetsLaterParentWinForParentOnlyNonJsonConflicts'
    Expected: Exit 0; the test verifies the later parent wins and no accidental deep merge occurs.
    Evidence: .sisyphus/evidence/task-4-provenance-merge-error.txt
  ```

  **Commit**: YES | Message: `feat(service): track multi-parent file provenance` | Files: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java`, updated service tests

- [x] 5. Preserve refresh/reapply semantics for active profiles with multi-parent ancestry

  **What to do**: Extend refresh/reapply logic so active profiles are considered affected when any direct parent or ancestor repository changes. Keep merged-file drift detection aligned with the new multi-parent effective-file model.
  **Must NOT do**: Do not narrow refresh detection to the last parent only; do not skip merged-file conflict checks for parent-only merged outputs.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` — Reason: bounded service regression work with existing patterns to follow.
  - Skills: `[]` — Repo-local.
  - Omitted: `["git-master"]` — Not needed.

  **Parallelization**: Can Parallel: YES | Wave 1 | Blocks: 13 | Blocked By: 3, 4

  **References** (executor has NO interview context — be exhaustive):
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:364-520` — refresh flows and merged-file conflict checks.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:1025-1055` — active-profile reapply hooks after repository refresh.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/command/ProfileCommandTest.java:731-973` — existing refresh/reapply command tests for inherited profiles.
  - Spec: `SPEC.md:155`, `SPEC.md:281-284` — refreshed repositories must reapply active profile resolution and preserve merged-file conflict handling.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `./gradlew test --tests '*ProfileCommandTest.refreshReappliesActiveProfileWhenAffectedByLaterParentBranch'` passes.
  - [ ] `./gradlew test --tests '*ProfileCommandTest.refreshPreservesMergedFileConflictDetectionForMultiParentProfile'` passes.

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: Refresh re-applies an active profile affected through a parent branch
    Tool: Bash
    Steps: Run ./gradlew test --tests '*ProfileCommandTest.refreshReappliesActiveProfileWhenAffectedByLaterParentBranch'
    Expected: Exit 0; the test verifies repository refresh detects multi-parent ancestry and reapplies the active profile.
    Evidence: .sisyphus/evidence/task-5-refresh-regression.txt

  Scenario: Local drift in merged output still blocks unsafe refresh
    Tool: Bash
    Steps: Run ./gradlew test --tests '*ProfileCommandTest.refreshPreservesMergedFileConflictDetectionForMultiParentProfile'
    Expected: Exit 0; the test verifies merged-file conflict handling still aborts or prompts as designed.
    Evidence: .sisyphus/evidence/task-5-refresh-regression-error.txt
  ```

  **Commit**: YES | Message: `fix(service): reapply multi-parent active profiles on refresh` | Files: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java`, updated command/service tests

- [x] 6. Extend `ocp profile create` to accept CSV parent lists and persist ordered arrays

  **What to do**: Keep the public flag name `--extends-from`, but parse comma-separated values, trim whitespace, reject blank/duplicate segments, preserve order, call the list-based create API, and update success/error messages to reflect the normalized parent list.
  **Must NOT do**: Do not switch to repeated flags; do not accept empty CSV segments like `base,,team`; do not emit array syntax in user-facing success messages unless explicitly specified.

  **Recommended Agent Profile**:
  - Category: `quick` — Reason: bounded CLI parsing/output change once list-based service support exists.
  - Skills: `[]` — No special skill required.
  - Omitted: `["git-master"]` — Not needed.

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: 11, 13 | Blocked By: 2

  **References** (executor has NO interview context — be exhaustive):
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/ProfileCreateCommand.java:19-40` — current single-string option parsing and success messaging.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/service/ProfileService.java:121-149` — create APIs now need the list-based call path.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/command/ProfileCommandTest.java:155-173` — current `--extends-from` command-test shape.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `./gradlew test --tests '*ProfileCommandTest.createSupportsCommaSeparatedExtendsFromParameter'` passes.
  - [ ] `./gradlew test --tests '*ProfileCommandTest.createRejectsBlankOrDuplicateCsvParents'` passes.

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: CSV parent list persists in declared order
    Tool: Bash
    Steps: Run ./gradlew test --tests '*ProfileCommandTest.createSupportsCommaSeparatedExtendsFromParameter'
    Expected: Exit 0; the test verifies `--extends-from base,team` persists `["base","team"]` and prints `Created profile \`child\` extending from \`base, team\`.`
    Evidence: .sisyphus/evidence/task-6-cli-csv.txt

  Scenario: Invalid CSV segments fail cleanly
    Tool: Bash
    Steps: Run ./gradlew test --tests '*ProfileCommandTest.createRejectsBlankOrDuplicateCsvParents'
    Expected: Exit 0; the test verifies blank/duplicate entries return exit code 1 and do not create the profile directory.
    Evidence: .sisyphus/evidence/task-6-cli-csv-error.txt
  ```

  **Commit**: YES | Message: `feat(cli): accept csv parent lists for profile create` | Files: `src/main/java/com/github/alvarosanchez/ocp/command/ProfileCreateCommand.java`, updated command tests

- [x] 7. Replace the one-shot TUI create-profile prompt with repeated ordered parent selection

  **What to do**: Convert interactive create-profile into a two-stage flow: capture profile name first, then repeatedly show a single parent-selection prompt whose options are `"" + remaining resolvable parents` until the user selects blank. Persist the chosen ordered list through the list-based service API.
  **Must NOT do**: Do not keep the old single multi-field parent selector; do not allow re-selecting an already chosen parent; do not reorder the chosen list.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` — Reason: prompt-state refactor with flow control and regression coverage.
  - Skills: `[]` — TUI is repo-local, no browser automation needed.
  - Omitted: `["frontend-ui-ux", "playwright"]` — terminal UI tests are not browser tasks.

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: 11, 13 | Blocked By: 2

  **References** (executor has NO interview context — be exhaustive):
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveApp.java:553-629` — current create-profile apply path assumes one parent field.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveApp.java:1354-1428` — current prompt builder/open path for one optional parent.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/PromptState.java:6-128` — existing prompt model only tracks labels/values/options; extend it deliberately for repeated parent selection state.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveAppCreateProfileTest.java:63-158` — create-profile prompt and apply patterns.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `./gradlew test --tests '*InteractiveAppCreateProfileTest.createProfilePromptRepeatsParentSelectionUntilBlank'` passes.
  - [ ] `./gradlew test --tests '*InteractiveAppCreateProfileTest.createProfilePromptExcludesAlreadySelectedParents'` passes.
  - [ ] `./gradlew test --tests '*InteractiveAppCreateProfileTest.applyPromptCreatesProfileWithOrderedParents'` passes.

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: User can pick A, then B, then none
    Tool: Bash
    Steps: Run ./gradlew test --tests '*InteractiveAppCreateProfileTest.createProfilePromptRepeatsParentSelectionUntilBlank'
    Expected: Exit 0; the test verifies the TUI stores `["A","B"]` in order after the final blank selection.
    Evidence: .sisyphus/evidence/task-7-tui-create-flow.txt

  Scenario: Previously chosen parents disappear from later prompts
    Tool: Bash
    Steps: Run ./gradlew test --tests '*InteractiveAppCreateProfileTest.createProfilePromptExcludesAlreadySelectedParents'
    Expected: Exit 0; the test verifies selecting `A` removes `A` from subsequent parent options and prevents duplicates.
    Evidence: .sisyphus/evidence/task-7-tui-create-flow-error.txt
  ```

  **Commit**: YES | Message: `feat(tui): support repeated parent selection for profile create` | Files: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveApp.java`, `src/main/java/com/github/alvarosanchez/ocp/command/interactive/PromptState.java`, updated interactive tests

- [x] 8. Convert interactive parent maps and labels from single-parent to ordered list semantics

  **What to do**: Replace `Map<String, String>` parent mappings in the interactive layer with `Map<String, List<String>>`, trim/preserve order at load time, update `selectedProfileHasParent()` to check non-empty lists, render profile labels as `child ⇢ 👤 A, B`, and render the detail-pane `Inherits from` field as a comma-separated list in declared order.
  **Must NOT do**: Do not derive a fresh parent order from sorting; do not keep one-parent rendering shortcuts; do not change profile sorting or other unrelated tree structure.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` — Reason: cross-file TUI state and rendering refactor with bounded scope.
  - Skills: `[]` — No external skill required.
  - Omitted: `["playwright", "frontend-ui-ux"]` — terminal rendering, not browser or styling work.

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: 9, 10, 11, 13 | Blocked By: 2, 4

  **References** (executor has NO interview context — be exhaustive):
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveApp.java:1583-1625` — tree build/load path currently passes a one-parent map.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/HierarchyTreeBuilder.java:139-153` — current profile label rendering uses one parent string.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/DetailPaneRenderer.java:69-80` — profile details currently show one parent string.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveAppProfileParentMappingTest.java:13-27` — current mapping test pattern.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/command/interactive/HierarchyTreeBuilderTest.java:121-160` — profile-label rendering assertion pattern.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `./gradlew test --tests '*InteractiveAppProfileParentMappingTest.profileParentByNameReturnsTrimmedOrderedParentLists'` passes.
  - [ ] `./gradlew test --tests '*HierarchyTreeBuilderTest.renderTreeNodeRendersCommaSeparatedParentsInOrder'` passes.
  - [ ] `./gradlew test --tests '*DetailPaneRendererTest.renderDetailPaneShowsCommaSeparatedParentsInOrder'` passes.

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: Parent maps retain declared order exactly
    Tool: Bash
    Steps: Run ./gradlew test --tests '*InteractiveAppProfileParentMappingTest.profileParentByNameReturnsTrimmedOrderedParentLists'
    Expected: Exit 0; the test verifies `repo-a/child -> ["A","B"]` rather than a sorted or deduped-by-first-string map.
    Evidence: .sisyphus/evidence/task-8-parent-maps.txt

  Scenario: Tree/detail rendering shows the same ordered parent list
    Tool: Bash
    Steps: Run ./gradlew test --tests '*HierarchyTreeBuilderTest.renderTreeNodeRendersCommaSeparatedParentsInOrder' && ./gradlew test --tests '*DetailPaneRendererTest.renderDetailPaneShowsCommaSeparatedParentsInOrder'
    Expected: Exit 0; both tests verify `child ⇢ 👤 A, B` and `Inherits from: A, B` in declared order.
    Evidence: .sisyphus/evidence/task-8-parent-maps-rendering.txt
  ```

  **Commit**: YES | Message: `feat(tui): render ordered parent lists` | Files: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveApp.java`, `src/main/java/com/github/alvarosanchez/ocp/command/interactive/HierarchyTreeBuilder.java`, `src/main/java/com/github/alvarosanchez/ocp/command/interactive/DetailPaneRenderer.java`, updated TUI tests

- [x] 9. Represent parent-only merged JSON/JSONC nodes as read-only merged previews with provenance

  **What to do**: Extend interactive node/file metadata so a file can be both read-only and merged. For parent-only merged JSON/JSONC nodes, prefer the read-only (`🔒`) affordance, keep merged preview content/title semantics, and store the last contributing parent profile/file so `p` can target it later.
  **Must NOT do**: Do not make parent-only merged nodes editable; do not lose `(deep-merged)` preview labeling; do not force child-local merged nodes into read-only mode.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` — Reason: nuanced TUI semantics spanning node metadata, tree builder, and detail rendering.
  - Skills: `[]` — Repo-local.
  - Omitted: `["playwright"]` — tested via existing TamboUI/JUnit harness.

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: 10, 11, 13 | Blocked By: 4, 8

  **References** (executor has NO interview context — be exhaustive):
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveTypes.java:52-89` — `NodeRef` currently cannot represent read-only + merged simultaneously.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/HierarchyTreeBuilder.java:279-288, 406-412` — tree nodes currently choose either inherited or deep-merged file metadata, never both.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/DetailPaneRenderer.java:148-183` — status/hints assume inherited and merged are mutually exclusive UX paths.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/command/interactive/HierarchyTreeBuilderTest.java:331-369` — current deep-merged node assertions.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/command/interactive/DetailPaneRendererTest.java:79-97,132-140,206-229` — current hint/status/title coverage.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `./gradlew test --tests '*HierarchyTreeBuilderTest.buildHierarchyTreeMarksParentOnlyMergedJsonAsReadOnlyDeepMerged'` passes.
  - [ ] `./gradlew test --tests '*DetailPaneRendererTest.detailHintDisablesEditForParentOnlyMergedNode'` passes.
  - [ ] `./gradlew test --tests '*InteractiveAppSelectionRefreshTest.selectedFilePreviewShowsMergedContentForReadOnlyParentOnlyMergedNode'` passes.

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: Parent-only merged JSON renders as read-only but still shows merged preview semantics
    Tool: Bash
    Steps: Run ./gradlew test --tests '*HierarchyTreeBuilderTest.buildHierarchyTreeMarksParentOnlyMergedJsonAsReadOnlyDeepMerged' && ./gradlew test --tests '*DetailPaneRendererTest.detailHintDisablesEditForParentOnlyMergedNode'
    Expected: Exit 0; the tests verify lock-style behavior, no edit affordance, and retained deep-merged preview messaging.
    Evidence: .sisyphus/evidence/task-9-readonly-merged-node.txt

  Scenario: Preview content is the merged parent result, not a raw parent file
    Tool: Bash
    Steps: Run ./gradlew test --tests '*InteractiveAppSelectionRefreshTest.selectedFilePreviewShowsMergedContentForReadOnlyParentOnlyMergedNode'
    Expected: Exit 0; the test verifies the preview shows merged JSON content while the selected node remains read-only.
    Evidence: .sisyphus/evidence/task-9-readonly-merged-node-error.txt
  ```

  **Commit**: YES | Message: `feat(tui): expose read-only merged parent nodes` | Files: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveTypes.java`, `src/main/java/com/github/alvarosanchez/ocp/command/interactive/HierarchyTreeBuilder.java`, `src/main/java/com/github/alvarosanchez/ocp/command/interactive/DetailPaneRenderer.java`, updated interactive tests

- [x] 10. Update immediate-parent navigation, shortcut gating, and pilot coverage

  **What to do**: Make `p` choose the last declared parent for profile/directory nodes, and the last contributing parent file for inherited/read-only merged file nodes. Update shortcut gating and status text so editable child-local merged files still show `e`, while inherited/read-only merged nodes do not.
  **Must NOT do**: Do not navigate to the first parent; do not add contributor-cycling UX; do not remove `p` from nodes that still have a valid immediate parent target.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` — Reason: navigation logic and shortcut gating have cross-file regressions to guard.
  - Skills: `[]` — Existing TamboUI test harness is sufficient.
  - Omitted: `["playwright", "frontend-ui-ux"]` — terminal UI only.

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: 11, 13 | Blocked By: 8, 9

  **References** (executor has NO interview context — be exhaustive):
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveApp.java:2082-2141` — current `selectedProfileHasParent()` and `navigateToParentProfile()` assume one parent string.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/TreeShortcutHints.java:12-124` — `e`/`p` gating logic currently keys only off `inherited()` and single-parent booleans.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveAppSelectionRefreshTest.java:122-153` — current direct navigation-unit pattern.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveAppPilotTest.java:136-158` — current pilot-style end-to-end parent-file navigation.
  - Test: `src/test/java/com/github/alvarosanchez/ocp/command/interactive/TreeShortcutHintsTest.java:75-169` — shortcut visibility assertions.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `./gradlew test --tests '*InteractiveAppSelectionRefreshTest.navigateToParentFromMultiParentProfileSelectsLastDeclaredParent'` passes.
  - [ ] `./gradlew test --tests '*InteractiveAppPilotTest.navigateToParentFromReadOnlyMergedFileSelectsLastContributingParentFile'` passes.
  - [ ] `./gradlew test --tests '*TreeShortcutHintsTest.shortcutHintsOmitEditForReadOnlyMergedNode'` passes.

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: Profile-level `p` picks the most immediate declared parent
    Tool: Bash
    Steps: Run ./gradlew test --tests '*InteractiveAppSelectionRefreshTest.navigateToParentFromMultiParentProfileSelectsLastDeclaredParent'
    Expected: Exit 0; the test verifies `child -> [A,B]` navigates to `B`.
    Evidence: .sisyphus/evidence/task-10-parent-navigation.txt

  Scenario: Read-only merged file navigation targets the last contributing parent file
    Tool: Bash
    Steps: Run ./gradlew test --tests '*InteractiveAppPilotTest.navigateToParentFromReadOnlyMergedFileSelectsLastContributingParentFile' && ./gradlew test --tests '*TreeShortcutHintsTest.shortcutHintsOmitEditForReadOnlyMergedNode'
    Expected: Exit 0; the tests verify `p` opens the parent file, `e` is hidden, and status text confirms the selected contributor.
    Evidence: .sisyphus/evidence/task-10-parent-navigation-error.txt
  ```

  **Commit**: YES | Message: `feat(tui): navigate to immediate multi-parent ancestors` | Files: `src/main/java/com/github/alvarosanchez/ocp/command/interactive/InteractiveApp.java`, `src/main/java/com/github/alvarosanchez/ocp/command/interactive/TreeShortcutHints.java`, updated navigation tests

- [x] 11. Update `SPEC.md` and command help text to the final multi-parent contract

  **What to do**: Revise `SPEC.md` and `ProfileCreateCommand` help text so the public contract matches the implementation: ordered arrays in `repository.json`, CSV `--extends-from`, all-invocation startup migration (including help/version/root usage), left-to-right parent precedence, read-only parent-only merged nodes, repeated TUI parent selection, and immediate-parent navigation.
  **Must NOT do**: Do not leave single-parent examples behind; do not document repeated flags; do not omit the all-invocation migration behavior chosen in interview.

  **Recommended Agent Profile**:
  - Category: `writing` — Reason: product-contract update across spec and help text.
  - Skills: `[]` — No extra skill required.
  - Omitted: `["git-master"]` — Not relevant.

  **Parallelization**: Can Parallel: YES | Wave 3 | Blocks: 12, 13 | Blocked By: 1, 6, 7, 8, 9, 10

  **References** (executor has NO interview context — be exhaustive):
  - Spec: `SPEC.md:92-107` — repository schema example and inheritance rules.
  - Spec: `SPEC.md:149, 202-245` — command matrix + interactive + merge semantics.
  - Spec: `SPEC.md:264-302` — acceptance criteria / test types.
  - Pattern: `src/main/java/com/github/alvarosanchez/ocp/command/ProfileCreateCommand.java:22-25` — option description currently says one parent.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `grep -n '"extends_from": \["my-company"\]' SPEC.md`
  - [ ] `grep -n 'ocp profile create \[name\] \[--extends-from <parent-1,parent-2,...>\]' SPEC.md`
  - [ ] `grep -n 'all invocations' SPEC.md`
  - [ ] `grep -n 'comma-separated' src/main/java/com/github/alvarosanchez/ocp/command/ProfileCreateCommand.java`

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: Spec examples and command contract reflect the new syntax
    Tool: Bash
    Steps: Run grep -n '"extends_from": \["my-company"\]' SPEC.md && grep -n 'ocp profile create \[name\] \[--extends-from <parent-1,parent-2,...>\]' SPEC.md
    Expected: Both commands return matches showing array syntax and CSV CLI syntax.
    Evidence: .sisyphus/evidence/task-11-spec-contract.txt

  Scenario: Help text no longer describes a single parent
    Tool: Bash
    Steps: Run grep -n 'comma-separated' src/main/java/com/github/alvarosanchez/ocp/command/ProfileCreateCommand.java
    Expected: The option description explicitly documents CSV parent input.
    Evidence: .sisyphus/evidence/task-11-spec-contract-error.txt
  ```

  **Commit**: YES | Message: `docs(spec): document ordered multi-parent inheritance` | Files: `SPEC.md`, `src/main/java/com/github/alvarosanchez/ocp/command/ProfileCreateCommand.java`

- [x] 12. Sync `README.md` examples and user guidance with the final contract

  **What to do**: Update the README repository example, inheritance description, and command summary to show array-based metadata and CSV `--extends-from` usage. Keep README less exhaustive than the spec, but fully consistent with it.
  **Must NOT do**: Do not duplicate every spec corner case; do not leave any scalar `extends_from` examples; do not document repeated flags.

  **Recommended Agent Profile**:
  - Category: `writing` — Reason: bounded doc sync work.
  - Skills: `[]` — No specialized skill needed.
  - Omitted: `["git-master"]` — Not needed.

  **Parallelization**: Can Parallel: YES | Wave 3 | Blocks: 13 | Blocked By: 11

  **References** (executor has NO interview context — be exhaustive):
  - Pattern: `README.md` repository-format example and command summary currently still show scalar `extends_from` and single-parent `--extends-from`.
  - Spec: `SPEC.md:92-107, 149, 239-245` — README must align with the final public contract from the spec.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `grep -n '"extends_from": \[' README.md`
  - [ ] `grep -n -- '--extends-from <parent-1,parent-2,...>' README.md`

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: README example uses array-based inheritance metadata
    Tool: Bash
    Steps: Run grep -n '"extends_from": \[' README.md
    Expected: The README repository example matches the array syntax used in the implementation/spec.
    Evidence: .sisyphus/evidence/task-12-readme-sync.txt

  Scenario: README command docs match the CSV CLI contract
    Tool: Bash
    Steps: Run grep -n -- '--extends-from <parent-1,parent-2,...>' README.md
    Expected: The README command section documents the CSV form, not the legacy single-parent wording.
    Evidence: .sisyphus/evidence/task-12-readme-sync-error.txt
  ```

  **Commit**: YES | Message: `docs(readme): update multi-parent inheritance examples` | Files: `README.md`

- [x] 13. Run the full JVM/native verification sweep and capture acceptance evidence

  **What to do**: After all code/docs changes land, run the repository-wide test suite, native tests, and any targeted reruns needed to resolve regressions. Capture outputs for the acceptance surface introduced by this feature.
  **Must NOT do**: Do not stop after focused tests only; do not skip `nativeTest`; do not change docs or behavior in this task except fixes required to make the verification pass.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` — Reason: broad regression execution and triage.
  - Skills: `[]` — Standard build/test flow only.
  - Omitted: `["git-master"]` — No history work needed.

  **Parallelization**: Can Parallel: NO | Wave 3 | Blocks: none | Blocked By: 5, 6, 7, 8, 9, 10, 11, 12

  **References** (executor has NO interview context — be exhaustive):
  - Build: `build.gradle.kts:63-65,108-115` — JVM and native test task configuration.
  - Spec: `SPEC.md:257-263` — required verification tasks are `./gradlew test` and `./gradlew nativeTest`.
  - Repo policy: `AGENTS.md` — minimum verification expectations after behavior changes.

  **Acceptance Criteria** (agent-executable only):
  - [ ] `./gradlew test`
  - [ ] `./gradlew nativeTest`
  - [ ] `./gradlew check`

  **QA Scenarios** (MANDATORY — task incomplete without these):
  ```
  Scenario: Full JVM/native regression sweep passes
    Tool: Bash
    Steps: Run ./gradlew test && ./gradlew nativeTest && ./gradlew check
    Expected: All commands exit 0 with no failing tests or verification tasks.
    Evidence: .sisyphus/evidence/task-13-full-verification.txt

  Scenario: Any failure is triaged to a focused regression before rerunning full verification
    Tool: Bash
    Steps: If the full sweep fails, rerun the named failing class with ./gradlew test --tests '<failing class or method>', fix the issue, then rerun ./gradlew test && ./gradlew nativeTest && ./gradlew check
    Expected: The focused rerun identifies the root cause, and the final full sweep returns to green.
    Evidence: .sisyphus/evidence/task-13-full-verification-error.txt
  ```

  **Commit**: NO | Message: `n/a` | Files: `verification outputs only`

## Final Verification Wave (4 parallel agents, ALL must APPROVE)
- [x] F1. Plan Compliance Audit — oracle
- [x] F2. Code Quality Review — unspecified-high
- [x] F3. Real Manual QA — unspecified-high (+ playwright if UI)
- [x] F4. Scope Fidelity Check — deep

## Commit Strategy
- Commit 1: metadata + startup migration + model normalization
- Commit 2: lineage / provenance / refresh semantics
- Commit 3: CLI create CSV support
- Commit 4: TUI create-flow updates
- Commit 5: TUI tree/detail/navigation updates
- Commit 6: spec + README updates
- Commit 7: full verification fixes only

## Success Criteria
- Legacy scalar metadata is rewritten to canonical arrays on every invocation before command execution.
- Help/version/root invocations participate in the same startup migration gate before their normal output path.
- Multi-parent resolution is deterministic, cycle-safe, cross-repository aware, and preserves declared order.
- Parent-only merged JSON/JSONC nodes are read-only, preview merged content, and navigate to the most immediate contributing parent.
- CLI and TUI profile creation persist the same ordered parent list.
- Tree/detail/help/docs examples all describe the same semantics.
- Full JVM and native verification pass.
