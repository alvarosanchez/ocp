1: # ocp - OpenCode Configuration Profiles
2: 
3: `ocp` is a CLI that manages OpenCode configuration profiles stored in Git repositories.
4: 
5: ## Motivation
6: 
7: OpenCode and plugins (for example [oh-my-opencode](https://github.com/code-yeongyu/oh-my-opencode)) read configuration files such as:
8: 
9: - `~/.config/opencode/opencode.json`
10: - `~/.config/opencode/oh-my-opencode.json`
11: 
12: Many users need to switch between different model/provider setups depending on context (for example company-managed vs personal/open-source usage). `ocp` enables this by selecting a profile and linking its configuration files into the expected OpenCode locations.
13: 
14: ## Goals
15: 
16: - Manage profile repositories.
17: - Discover available profiles across repositories.
18: - Switch active OpenCode configuration by profile name.
19: - Keep profile metadata simple and explicit.
20: - Provide deterministic CLI behavior with testable output and exit codes.
21: 
22: ## Non-goals (current scope)
23: 
24: - Managing secrets directly.
25: - Editing OpenCode JSON contents.
26: 
27: ## Key terms
28: 
29: - Repository: Git repository or local directory containing `repository.json` and one directory per profile.
30: - Profile: named set of OpenCode config files (for example `opencode.json`).
31: - Registry: local `ocp` configuration file listing added repositories.
32: 
33: ## Quick usage
34: 
35: ```bash
36: ocp profile list
37: ocp repository add git@github.com:my-company/my-repo.git --name my-repo
38: ocp repository add /absolute/path/to/my-repo --name my-local-repo
39: ocp profile use my-company
40: ```
41: 
42: ## Configuration and storage
43: 
44: ### Default paths
45: 
46: - OCP registry: `~/.config/ocp/config.json`
47: - OCP storage root: `~/.config/ocp`
48: - Repository clone directory: `~/.config/ocp/repositories/<repo-name>`
49: - Repository metadata file: `~/.config/ocp/repositories/<repo-name>/repository.json`
50: - Resolved merged profile directory: `~/.config/ocp/resolved-profiles/<profile-name>/`
51: 
52: ### Path overrides (for tests and advanced usage)
53: 
54: - Config directory override: JVM system property `ocp.config.dir`
55: - Legacy storage directory override: JVM system property `ocp.cache.dir`
56: - OpenCode config directory override: JVM system property `ocp.opencode.config.dir`
57: - Working directory override for local create commands: JVM system property `ocp.working.dir`
58: 
59: ### `config.json` schema
60: 
61: ```json
62: {
63:   "config": {
64:     "activeProfile": "my-company",
65:     "lastOcpVersionCheckEpochSeconds": 1741262400,
66:     "latestOcpVersion": "0.2.0"
67:   },
68:   "repositories": [
69:     {
70:       "name": "my-repo",
71:       "uri": "git@github.com:my-company/my-repo.git",
72:       "localPath": "/home/user/.config/ocp/repositories/my-repo"
73:     }
74:   ]
75: }
76: ```
77: 
78: Rules:
79: 
80: - `config.activeProfile` defaults to `null` (no active profile selected).
81: - `config.lastOcpVersionCheckEpochSeconds` defaults to `null` and records the last CLI update check attempt.
82: - `config.latestOcpVersion` defaults to `null` and stores the latest successful CLI release lookup.
83: - `repositories[*].uri` is optional (`null` for file-based repositories).
84: - `repositories[*].name` is required.
85: - `repositories[*].localPath` is required after normalization.
86:   - Git-backed repository: derived from repository storage directory and repository name when no explicit local path is stored.
87:   - File-based repository: normalized absolute path provided by the user.
88:   - A repository entry may store both `uri` and `localPath` when a local repository later gains a remote Git URI; in that case `localPath` remains authoritative.
89: 
90: ### `repository.json` schema
91: 
92: ```json
93: {
94:   "profiles": [
95:     { "name": "my-company", "description": "Company defaults" },
96:     { "name": "oss", "description": "Open-source profile", "extends_from": "my-company" }
97:   ]
98: }
99: ```
100: 
101: Rules:
102: 
103: - `profiles` defaults to an empty list.
104: - Empty/blank profile names are ignored.
105: - `description` is optional and may be omitted or null.
106: - `extends_from` is optional and references another profile name.
107: - Profile names must be globally unique across all configured repositories.
108: 
109: ## Repository structure
110: 
111: Each profile repository contains:
112: 
113: - `/repository.json`
114: - `/<profile-name>/...` with files to link into `~/.config/opencode/`
115: 
116: Example:
117: 
118: ```text
119: repository.json
120: my-company/opencode.json
121: my-company/oh-my-opencode.json
122: oss/opencode.json
123: ```
124: 
125: ## CLI contract
126: 
127: ### Global behavior
128: 
129: - `ocp` verifies required system dependencies at startup. Current required dependency: `git`.
130: - `ocp` checks for a newer CLI release at startup in both interactive and non-interactive flows, but reuses cached update metadata until at least 24 hours have passed since the previous check attempt.
131: - Success messages are written to stdout.
132: - Errors are written to stderr.
133: - Help output is available through Picocli help commands.
134: 
135: ### Exit codes
136: 
137: - `0`: success
138: - `1`: runtime/validation failure (for example duplicate profile names, failed git command, invalid JSON, missing dependency)
139: - `2`: CLI usage error (invalid command arguments/options)
140: 
141: ### Command matrix
142: 
143: | Command | Status | Behavior |
144: | --- | --- | --- |
145: | `ocp` | Implemented | Launch interactive TUI mode when running in an interactive terminal; otherwise print root usage. |
146: | `ocp help <command>` | Implemented | Print help for command/subcommand. |
147: | `ocp profile list` | Implemented | Print a table of profiles with name, description, local commit metadata, and non-fatal remote update hints; include repository name when width budget allows. |
148: | `ocp profile` | Implemented | Print currently active profile with repository/version metadata and update hints. |
149: | `ocp profile create [name] [--extends-from <parent-1,parent-2,...>]` | Implemented | Create profile folder and register it in repository metadata, optionally extending from existing profiles. Defaults to `default` when no name is provided. |
150: | `ocp profile use <name>` | Implemented | Switch active profile by linking profile files to OpenCode config location. |
151: | `ocp repository add <uri-or-path> --name <name>` | Implemented | Add a repository from a Git URI or local path; Git URIs are cloned into local storage and local paths are registered directly. |
152: | `ocp repository list` | Implemented | Print configured repositories as rounded CLI boxes with name, URI, local clone path, and resolved profile names from each repository metadata file. |
153: | `ocp repository delete <name> [--force] [--delete-local-path]` | Implemented | Remove repository entry from registry. Git-backed repos require `--force` when local changes exist; file-based repos keep the local folder unless `--delete-local-path` is provided. |
154: | `ocp repository create <name> [--profile-name <profile>]` | Implemented | Initialize new profile repository with `repository.json` and initial profile. |
155: | `ocp repository refresh [name]` | Implemented | Pull latest changes for git-backed repositories. For file-based repositories, refresh is a no-op with a user-facing message. Reapplies active profile resolution when refreshed repository data affects the active profile lineage. |
156: 
157: ## Operational semantics
158: 
159: ### Repository registration normalization
160: 
161: - `ocp repository add` requires a source string and repository name.
162: - Trim source and repository name before validation and persistence.
163: - Source detection:
164:   - Git URI-like values are treated as remote repositories and cloned.
165:   - Local path-like values (including `.`) are resolved to absolute paths and registered as file-based repositories.
166: - For Git-backed repositories, compute `localPath` from repository storage directory and repository name when `localPath` is absent; preserve explicit configured `localPath` when present.
167: - For file-based repositories, persist `uri = null` and `localPath = <normalized absolute path>`.
168: 
169: ### Repository deletion semantics
170: 
171: - `ocp repository delete <name>` removes the registry entry in all cases.
172: - Git-backed repositories:
173:   - If local git changes exist, deletion fails unless `--force` is provided.
174:   - With clean working tree, or with `--force`, local clone path is deleted.
175: - File-based repositories:
176:   - By default, the local folder is preserved.
177:   - `--delete-local-path` also removes the local folder.
178: 
179: ### Profile uniqueness
180: 
181: - During profile discovery (`profile list` and future `profile use` resolution), duplicate names across repositories are an error.
182: - Error message must include duplicate profile names.
183: 
184: ### Profile version checks
185: 
186: - Executed during `ocp profile list` and `ocp profile` (active profile view).
187: - `VERSION` displays the latest local short SHA; when remote has newer commits, a footnote is printed and table rows include a `❄` marker.
188: - Version-check failures (for example offline network) must not block profile output and are reported as non-fatal notes.
189: - Profile tables use width from `$COLUMNS` when exported.
190: - If `$COLUMNS` is unavailable, interactive sessions use detected terminal width; otherwise rendering falls back to 120 columns.
191: - When profile tables exceed the width budget, `DESCRIPTION` and `MESSAGE` cells are wrapped to fit.
192: - If the table still cannot fit, the `REPOSITORY` column is omitted.
193: 
194: ### Interactive root mode
195: 
196: - Running `ocp` without a subcommand starts an interactive full-screen terminal UI when `System.console()` is available and `TERM` is not `dumb`.
197: - Interactive mode exposes profile and repository operations (`use`, `create`, `add`, `delete`, and refresh actions) and uses the same service layer semantics as subcommands.
197.1: - In interactive mode, profile create prompts for optional inheritance using a selectable list of all resolvable profile names across configured repositories; the prompt allows adding multiple parents in order by repeatedly selecting additional parents.
198: - In interactive mode, `d` (delete) is context-sensitive: on repository nodes it deletes the repository, and on profile/file/directory nodes it deletes the selected profile.
199: - In interactive mode, repository deletion prompts are context-aware:
200:   - Git-backed repos with local changes show a warning and require explicit force confirmation.
201:   - File-based repos ask whether to also delete the local folder.
202: - In interactive mode, `c` (create profile) creates the profile inside the currently selected repository context (repository, profile, or file node), and prompts for optional inheritance using a selectable list of all resolvable profile names across configured repositories.
203: - In interactive mode, action keys are explicit: `r` refresh selected repository, `R` refresh all repositories, `u` use selected profile, `e` edit selected file, `o` edit the OCP registry config.json (respects `ocp.config.dir` when set), `y` copy the selected file's absolute path, and `p` jump to the selected profile's parent; `Enter` does not trigger these actions. Saving the OCP config file reloads the tree when repository entries change.
204: - In interactive mode, selecting a file-based repository node also exposes `m` to migrate that repository into the shared Git/GitHub post-creation flow.
205: - In interactive mode, selecting a git-backed repository node with local uncommitted changes also exposes `g` to prompt for a commit message, commit all local changes, and push them to the tracked remote branch.
206: - In interactive mode, git-backed repository nodes with local uncommitted changes are visually marked in the tree.
207: - In interactive mode, refresh (`r`) is shown only when the selected repository context is git-backed; file-based repositories do not offer refresh actions.
208: - In interactive mode, the tree/detail content split favors the detail pane at roughly one-third / two-thirds width.
209: - In interactive mode, keyboard shortcuts are rendered in a dedicated full-width shortcuts pane above the status bar instead of being split between the tree and detail panes.
210: - Interactive tree profile nodes visually show inheritance using a relationship marker (`👤 child ⇢ 👤 parent`).
211: - Interactive tree includes inherited parent-only files under child profiles as read-only file nodes with subdued styling; inherited files cannot be edited.
212: - When an inherited file node is selected, `p` jumps to the corresponding file in the parent profile instead of only selecting the parent profile root.
213: - Interactive tree shows overlapping inherited JSON/JSONC files that resolve via deep merge with a distinct `⛙` icon and subdued styling; selecting them previews the resolved merged contents with a `(deep-merged)` title suffix, while editing still opens the child profile file.
214: - In interactive mode, repository scaffold creation prompts for directory name, location path, and optional initial profile name; after scaffolding, the repository is automatically added to the registry as a file-based repository.
215: - In interactive mode, after local repository add/create and onboarding import, OCP runs a shared optional post-creation flow:
216:   - Offers git initialization with an initial commit (default: `yes` for interactive create and onboarding, `no` for adding existing local repositories).
217:   - Skips git initialization prompt when the repository already contains `.git`.
218:   - Offers optional GitHub publish via `gh repo create <name> --source <path> --remote origin --push --private|--public` (default: `no`, visibility default: `private`) only when `gh auth status` succeeds and `origin` does not already exist.
219:   - On successful publish, persists the discovered `origin` URI into the configured repository entry while preserving the explicit `localPath`.
220:   - If a file-based repository already has a configured `origin` remote when that flow starts, OCP persists that existing `origin` URI immediately instead of prompting for GitHub publish again.
221:   - Failure in the optional post-creation flow does not roll back a successful add/create/onboarding operation.
222: - File preview syntax highlighting in interactive mode uses external `bat` when available; if `bat` is unavailable or fails, preview falls back to plain text.
223: - On first interactive launch, when either `config.json` does not exist or it exists but contains no repositories and no active profile (only version-check metadata), and `~/.config/opencode/` contains whitelisted top-level config files (`opencode.json`, `opencode.jsonc`, `tui.json`, `tui.jsonc`, `oh-my-opencode.json`, `oh-my-opencode.jsonc`), the UI offers to import those files into a newly created local repository under `~/.config/ocp/repositories/`, asks for a repository name and profile name, activates that profile, and then offers the same optional post-creation Git/GitHub flow.
224: - If interactive UI initialization fails, behavior falls back to standard Picocli root usage output.
225: 
226: ### Profile switching and backups (target behavior)
227: 
228: - Source directory: `~/.config/ocp/repositories/<repo-name>/<profile-name>/`
229: - Target directory: `~/.config/opencode/`
230: - For each profile file:
231:   - If target does not exist: create symlink.
232:   - If target exists and is a symlink: replace symlink.
233:   - If target exists and is not a symlink: move to backup before linking.
234: - Backup location: `~/.config/ocp/backups/<timestamp>/<filename>`
235: - `ocp profile use <name>` must print a user-config notice for `~/.config/opencode/` and indicate whether files were updated or only processed.
236: - When backups are created, CLI output must include the backup location under `~/.config/ocp/backups/<timestamp>/`.
237: - Switching must be transactional at file level:
238:   - If linking one file fails, already-processed files must be restored from backups.
239: - Profile inheritance and merge behavior:
240:   - `extends_from` profiles are resolved parent-first.
241:   - Parent-only files are inherited as-is.
242:   - For overlapping JSON/JSONC files (`*.json` and `*.jsonc`), the resulting file is deep-merged recursively.
243:   - Child values override parent values for matching keys at any nesting level.
244:   - Arrays and non-object JSON values are replaced by the child value.
245:   - Child and parent must use the same extension for the same logical JSON file (`.json` vs `.jsonc`), or `profile use` fails.
246: 
247: ## Technology stack
248: 
249: - Java 25
250: - Micronaut 4.10
251: - Micronaut Picocli integration
252: - Gradle build
253: - TamboUI toolkit for terminal rendering
254: - GraalVM native executable (`ocp`)
255: - Homebrew distribution target
256: 
257: ## Verification and acceptance criteria
258: 
259: ### Required verification tasks
260: 
261: - `./gradlew test`
262: - `./gradlew nativeTest`
263: 
264: ### Command-level acceptance criteria
265: 
266: - `ocp help`
267:   - exits `0`
268:   - output contains `Usage: ocp`
269: - `ocp`
270:   - in interactive first-run mode when there are no configured repositories and no active profile (even if `config.json` exists only with cached version-check metadata) and importable whitelisted OpenCode config files, offers onboarding to import them into a local repository, create a named profile, activate it, and then offer the optional post-creation Git/GitHub flow
271: - `ocp profile list`
272:   - with no repositories/profiles: exits `0`, prints helpful empty-state message
273:   - with multiple repositories: outputs a table sorted by profile name with columns `NAME`, `DESCRIPTION`, `ACTIVE`, `VERSION`, `LAST UPDATED`, `MESSAGE`, and includes `REPOSITORY` when it fits within the width budget (repository name)
274:   - with duplicate profile names: exits `1`, prints duplicate-name error on stderr
275: - `ocp profile use <name>`
276:   - exits `0`, prints profile switch success message
277:   - prints a user-config notice for `~/.config/opencode/`
278:   - when files are linked/replaced/removed, notice states files were updated
279:   - when no file changes are needed, notice states files were processed
280:   - when existing files are moved, prints backup notice with backup path
281: - `ocp repository refresh [name]`
282:   - exits `0`, prints refresh success message
283:   - when active profile files are reapplied, prints user-config notice (updated vs processed) and backup notice when applicable
284:   - when merged active-profile files in `~/.config/opencode/` were locally edited, prompts to discard those merged-file edits or abort; abort exits `1` and leaves files untouched
285: - `ocp repository list`
286:   - with no repositories: exits `0`, prints helpful empty-state message
287:   - with configured repositories: outputs one rounded box per repository with fields `Name`, `URI`, `Local path`, and `Resolved profiles`
288:   - if a repository metadata file is invalid JSON: exits `1`, reports metadata read failure
289: - Repository config loading
290:   - missing `config.json`: treated as empty repository list
291:   - invalid `config.json`: exits `1`, reports registry read failure
292: - repository source/name normalization: trims source and name, computes or normalizes `localPath`
293: - Git operations
294:   - clone command failure: exits `1`, includes exit-code detail
295:   - interrupted git operation: thread interrupt flag is restored and command fails cleanly
296: 
297: ### Test types
298: 
299: - Unit tests for models/services/clients
300: - Command tests for stdout/stderr/exit code behavior
301: - Integration tests with Git server container for clone/discovery flows
302: - Native tests to validate behavior parity in compiled binary
