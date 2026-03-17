1: ## 2026-03-17 — Task 1 startup migration learnings
2: 
3: - Running startup migration through `OcpCommand.main`-equivalent flow required introducing `OcpCommand.execute(String[] args)` so tests can assert startup ordering without `System.exit` terminating the JVM.
4: - Micronaut `ObjectMapper` migration logic was reliable when decoding legacy metadata as `Map.class`; decoding as `Object.class` did not produce a mutable `Map` shape suitable for scalar-to-array rewrites.
5: - Normalization assertions are more stable when parsing rewritten JSON and checking `extends_from` node type/value rather than matching raw serialized substrings.
6: - Real `./gradlew run --args=help` validation showed migration already rewrites to canonical array form; naive raw-substring checks can report false negatives because serializer emits spaced/pretty JSON.
7: - Startup tests are more trustworthy when they fork a child JVM and invoke `OcpCommand.main` directly, so exit code semantics and `System.exit(...)` behavior are covered on the real entry path.
8: 
9: ## 2026-03-17 — Task 2 ordered parent list learnings
10: 
11: - `RepositoryConfigFile.ProfileEntry` can preserve single-parent call sites by keeping a compatibility constructor (`String extendsFrom`) and a compatibility accessor (`extendsFrom()` returning first parent) while the serialized model stores `List<String>`.
12: - Normalizing parent arrays in both metadata model and create-service input paths keeps behavior consistent: trim entries, preserve insertion order, reject blanks, and reject duplicates after trim.
13: - Existing CLI/TUI single-parent flows continue to compile without parser changes when service entrypoints wrap optional parent strings into `List.of(parent)` or `List.of()`.
14: 
15: ## 2026-03-17 — Task 3 multi-parent lineage learnings
16: 
17: - `effectiveProfileFilesByLogicalRelativePath(...)` already applies lineage in-order, so changing lineage generation alone is enough to enforce precedence (`earlier parents -> later parents -> child`) without touching merge semantics.
18: - Left-to-right DFS post-order with a global `appended` set gives deterministic DAG flattening and prevents duplicate parent application when branches converge on a shared ancestor.
19: - Validation must run per parent edge (`self` and `unknown parent`) while cycle detection uses the active `visiting` path so cycles are detected on non-first-parent branches too.
20: 
21: ## 2026-03-17 — Stabilization task legacy scalar compatibility learnings
22: 
23: - Micronaut Serde in this project does not support `@JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)`; using it fails compilation during annotation processing.
24: - Backward compatibility for legacy scalar `extends_from` works reliably with a `@JsonCreator` factory that accepts `Object extendsFrom` and normalizes scalar-or-array inputs before record validation.
25: - To preserve Task 2/3 list validation semantics while keeping old fixtures working, scalar `extends_from` values should be treated as legacy input (`trim`, ignore blank) while array entries continue through strict blank/duplicate validation.
26: 
27: ## 2026-03-17 — Task 4 effective-file provenance and parent-only merge learnings
28: 
29: - Parent-only JSON/JSONC overlap can be enabled without changing merge semantics by treating every JSON contributor in lineage order as part of a contributor list; once a second JSON contributor appears, the node is merged regardless of whether the child contributes.
30: - `resolvedFilePreview(...)` must match source selection against all contributors of the effective logical file (not just the final winner path) so selecting an earlier parent file still resolves to merged preview content.
31: - Keeping relative path/extension from the last contributor preserves existing extension precedence and extension-mismatch safeguards while still materializing merged parent-only outputs under `resolved-profiles/<profile>/...`.
32: - Refresh/reapply checks already traverse full profile lineage via profileLineageFor(activeProfileName, profilesByName); multi-parent ancestor refresh behavior is preserved by locking it in with command-level regressions.
33: 
34: ## 2026-03-17 — Task 6 CLI CSV extends-from learnings
35: 
36: - The `--extends-from` CLI flag can accept comma-separated parent names when whitespace is trimmed and blanks/duplicates are rejected before calling the service, keeping the UI surface simple while preserving the ordered inheritance API.
37: - Normalizing the parent list at the CLI allowed us to reuse the new `createProfileWithParents` service entry point and print the same `extends from` success message as before (only the formatted string changes when multiple parents appear).
38: - Interactive layer now carries ordered parent lists via Map<String, List<String>>; labels/details join with ", " while preserving declared metadata order.
39: - Interactive create-profile now treats each non-blank parent choice as a signal to append another optional parent field, so parent ordering is captured directly from prompt progression.
40: - Parent-only JSON/JSONC inherited nodes now carry both read-only and deep-merged UI state so previews can stay resolved without enabling edits.
41: - Navigation now resolves immediate parent from the last declared profile parent, and file-level parent navigation uses existing contributorProfileNames metadata to jump to the last contributing parent file for inherited/read-only merged nodes.
