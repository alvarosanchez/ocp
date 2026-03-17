## 2026-03-17 — Task 1 startup migration learnings

- Running startup migration through `OcpCommand.main`-equivalent flow required introducing `OcpCommand.execute(String[] args)` so tests can assert startup ordering without `System.exit` terminating the JVM.
- Micronaut `ObjectMapper` migration logic was reliable when decoding legacy metadata as `Map.class`; decoding as `Object.class` did not produce a mutable `Map` shape suitable for scalar-to-array rewrites.
- Normalization assertions are more stable when parsing rewritten JSON and checking `extends_from` node type/value rather than matching raw serialized substrings.
- Real `./gradlew run --args=help` validation showed migration already rewrites to canonical array form; naive raw-substring checks can report false negatives because serializer emits spaced/pretty JSON.
- Startup tests are more trustworthy when they fork a child JVM and invoke `OcpCommand.main` directly, so exit code semantics and `System.exit(...)` behavior are covered on the real entry path.

## 2026-03-17 — Task 2 ordered parent list learnings

- `RepositoryConfigFile.ProfileEntry` can preserve single-parent call sites by keeping a compatibility constructor (`String extendsFrom`) and a compatibility accessor (`extendsFrom()` returning first parent) while the serialized model stores `List<String>`.
- Normalizing parent arrays in both metadata model and create-service input paths keeps behavior consistent: trim entries, preserve insertion order, reject blanks, and reject duplicates after trim.
- Existing CLI/TUI single-parent flows continue to compile without parser changes when service entrypoints wrap optional parent strings into `List.of(parent)` or `List.of()`.

## 2026-03-17 — Task 3 multi-parent lineage learnings

- `effectiveProfileFilesByLogicalRelativePath(...)` already applies lineage in-order, so changing lineage generation alone is enough to enforce precedence (`earlier parents -> later parents -> child`) without touching merge semantics.
- Left-to-right DFS post-order with a global `appended` set gives deterministic DAG flattening and prevents duplicate parent application when branches converge on a shared ancestor.
- Validation must run per parent edge (`self` and `unknown parent`) while cycle detection uses the active `visiting` path so cycles are detected on non-first-parent branches too.

## 2026-03-17 — Stabilization task legacy scalar compatibility learnings

- Micronaut Serde in this project does not support `@JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)`; using it fails compilation during annotation processing.
- Backward compatibility for legacy scalar `extends_from` works reliably with a `@JsonCreator` factory that accepts `Object extendsFrom` and normalizes scalar-or-array inputs before record validation.
- To preserve Task 2/3 list validation semantics while keeping old fixtures working, scalar `extends_from` values should be treated as legacy input (`trim`, ignore blank) while array entries continue through strict blank/duplicate validation.

## 2026-03-17 — Task 4 effective-file provenance and parent-only merge learnings

- Parent-only JSON/JSONC overlap can be enabled without changing merge semantics by treating every JSON contributor in lineage order as part of a contributor list; once a second JSON contributor appears, the node is merged regardless of whether the child contributes.
- `resolvedFilePreview(...)` must match source selection against all contributors of the effective logical file (not just the final winner path) so selecting an earlier parent file still resolves to merged preview content.
- Keeping relative path/extension from the last contributor preserves existing extension precedence and extension-mismatch safeguards while still materializing merged parent-only outputs under `resolved-profiles/<profile>/...`.
- Refresh/reapply checks already traverse full profile lineage via profileLineageFor(activeProfileName, profilesByName); multi-parent ancestor refresh behavior is preserved by locking it in with command-level regressions.

## 2026-03-17 — Task 6 CLI CSV extends-from learnings

- The `--extends-from` CLI flag can accept comma-separated parent names when whitespace is trimmed and blanks/duplicates are rejected before calling the service, keeping the UI surface simple while preserving the ordered inheritance API.
- Normalizing the parent list at the CLI allowed us to reuse the new `createProfileWithParents` service entry point and print the same `extends from` success message as before (only the formatted string changes when multiple parents appear).
- Interactive layer now carries ordered parent lists via Map<String, List<String>>; labels/details join with ", " while preserving declared metadata order.
- Interactive create-profile now treats each non-blank parent choice as a signal to append another optional parent field, so parent ordering is captured directly from prompt progression.
- Parent-only JSON/JSONC inherited nodes now carry both read-only and deep-merged UI state so previews can stay resolved without enabling edits.
- Navigation now resolves immediate parent from the last declared profile parent, and file-level parent navigation uses existing contributorProfileNames metadata to jump to the last contributing parent file for inherited/read-only merged nodes.
