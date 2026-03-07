# ocp - OpenCode Configuration Profiles

`ocp` is a CLI that manages OpenCode configuration profiles stored in Git repositories.

## Motivation

OpenCode and plugins (for example [oh-my-opencode](https://github.com/code-yeongyu/oh-my-opencode)) read configuration files such as:

- `~/.config/opencode/opencode.json`
- `~/.config/opencode/oh-my-opencode.json`

Many users need to switch between different model/provider setups depending on context (for example company-managed vs personal/open-source usage). `ocp` enables this by selecting a profile and linking its configuration files into the expected OpenCode locations.

## Goals

- Manage profile repositories.
- Discover available profiles across repositories.
- Switch active OpenCode configuration by profile name.
- Keep profile metadata simple and explicit.
- Provide deterministic CLI behavior with testable output and exit codes.

## Non-goals (current scope)

- Managing secrets directly.
- Editing OpenCode JSON contents.

## Key terms

- Repository: Git repository or local directory containing `repository.json` and one directory per profile.
- Profile: named set of OpenCode config files (for example `opencode.json`).
- Registry: local `ocp` configuration file listing added repositories.

## Quick usage

```bash
ocp profile list
ocp repository add git@github.com:my-company/my-repo.git --name my-repo
ocp repository add /absolute/path/to/my-repo --name my-local-repo
ocp profile use my-company
```

## Configuration and storage

### Default paths

- OCP registry: `~/.config/ocp/config.json`
- OCP storage root: `~/.config/ocp`
- Repository clone directory: `~/.config/ocp/repositories/<repo-name>`
- Repository metadata file: `~/.config/ocp/repositories/<repo-name>/repository.json`
- Resolved merged profile directory: `~/.config/ocp/resolved-profiles/<profile-name>/`

### Path overrides (for tests and advanced usage)

- Config directory override: JVM system property `ocp.config.dir`
- Legacy storage directory override: JVM system property `ocp.cache.dir`
- OpenCode config directory override: JVM system property `ocp.opencode.config.dir`
- Working directory override for local create commands: JVM system property `ocp.working.dir`

### `config.json` schema

```json
{
  "config": {
    "activeProfile": "my-company",
    "lastOcpVersionCheckEpochSeconds": 1741262400,
    "latestOcpVersion": "0.2.0"
  },
  "repositories": [
    {
      "name": "my-repo",
      "uri": "git@github.com:my-company/my-repo.git",
      "localPath": "/home/user/.config/ocp/repositories/my-repo"
    }
  ]
}
```

Rules:

- `config.activeProfile` defaults to `null` (no active profile selected).
- `config.lastOcpVersionCheckEpochSeconds` defaults to `null` and records the last CLI update check attempt.
- `config.latestOcpVersion` defaults to `null` and stores the latest successful CLI release lookup.
- `repositories[*].uri` is optional (`null` for file-based repositories).
- `repositories[*].name` is required.
- `repositories[*].localPath` is required after normalization.
  - Git-backed repository: derived from repository storage directory and repository name.
  - File-based repository: normalized absolute path provided by the user.

### `repository.json` schema

```json
{
  "profiles": [
    { "name": "my-company", "description": "Company defaults" },
    { "name": "oss", "description": "Open-source profile", "extends_from": "my-company" }
  ]
}
```

Rules:

- `profiles` defaults to an empty list.
- Empty/blank profile names are ignored.
- `description` is optional and may be omitted or null.
- `extends_from` is optional and references another profile name.
- Profile names must be globally unique across all configured repositories.

## Repository structure

Each profile repository contains:

- `/repository.json`
- `/<profile-name>/...` with files to link into `~/.config/opencode/`

Example:

```text
repository.json
my-company/opencode.json
my-company/oh-my-opencode.json
oss/opencode.json
```

## CLI contract

### Global behavior

- `ocp` verifies required system dependencies at startup. Current required dependency: `git`.
- `ocp` checks for a newer CLI release at startup in both interactive and non-interactive flows, but reuses cached update metadata until at least 24 hours have passed since the previous check attempt.
- Success messages are written to stdout.
- Errors are written to stderr.
- Help output is available through Picocli help commands.

### Exit codes

- `0`: success
- `1`: runtime/validation failure (for example duplicate profile names, failed git command, invalid JSON, missing dependency)
- `2`: CLI usage error (invalid command arguments/options)

### Command matrix

| Command | Status | Behavior |
| --- | --- | --- |
| `ocp` | Implemented | Launch interactive TUI mode when running in an interactive terminal; otherwise print root usage. |
| `ocp help <command>` | Implemented | Print help for command/subcommand. |
| `ocp profile list` | Implemented | Print a table of profiles with name, description, local commit metadata, and non-fatal remote update hints; include repository name when width budget allows. |
| `ocp profile` | Implemented | Print currently active profile with repository/version metadata and update hints. |
| `ocp profile create [name] [--extends-from <parent>]` | Implemented | Create profile folder and register it in repository metadata, optionally extending from an existing profile. Defaults to `default` when no name is provided. |
| `ocp profile use <name>` | Implemented | Switch active profile by linking profile files to OpenCode config location. |
| `ocp repository add <uri-or-path> --name <name>` | Implemented | Add a repository from a Git URI or local path; Git URIs are cloned into local storage and local paths are registered directly. |
| `ocp repository list` | Implemented | Print configured repositories as rounded CLI boxes with name, URI, local clone path, and resolved profile names from each repository metadata file. |
| `ocp repository delete <name> [--force] [--delete-local-path]` | Implemented | Remove repository entry from registry. Git-backed repos require `--force` when local changes exist; file-based repos keep the local folder unless `--delete-local-path` is provided. |
| `ocp repository create <name> [--profile-name <profile>]` | Implemented | Initialize new profile repository with `repository.json` and initial profile. |
| `ocp repository refresh [name]` | Implemented | Pull latest changes for git-backed repositories. For file-based repositories, refresh is a no-op with a user-facing message. Reapplies active profile resolution when refreshed repository data affects the active profile lineage. |

## Operational semantics

### Repository registration normalization

- `ocp repository add` requires a source string and repository name.
- Trim source and repository name before validation and persistence.
- Source detection:
  - Git URI-like values are treated as remote repositories and cloned.
  - Local path-like values (including `.`) are resolved to absolute paths and registered as file-based repositories.
- For Git-backed repositories, compute `localPath` from repository storage directory and repository name.
- For file-based repositories, persist `uri = null` and `localPath = <normalized absolute path>`.

### Repository deletion semantics

- `ocp repository delete <name>` removes the registry entry in all cases.
- Git-backed repositories:
  - If local git changes exist, deletion fails unless `--force` is provided.
  - With clean working tree, or with `--force`, local clone path is deleted.
- File-based repositories:
  - By default, the local folder is preserved.
  - `--delete-local-path` also removes the local folder.

### Profile uniqueness

- During profile discovery (`profile list` and future `profile use` resolution), duplicate names across repositories are an error.
- Error message must include duplicate profile names.

### Profile version checks

- Executed during `ocp profile list` and `ocp profile` (active profile view).
- `VERSION` displays the latest local short SHA; when remote has newer commits, a footnote is printed and table rows include a `❄` marker.
- Version-check failures (for example offline network) must not block profile output and are reported as non-fatal notes.
- Profile tables use width from `$COLUMNS` when exported.
- If `$COLUMNS` is unavailable, interactive sessions use detected terminal width; otherwise rendering falls back to 120 columns.
- When profile tables exceed the width budget, `DESCRIPTION` and `MESSAGE` cells are wrapped to fit.
- If the table still cannot fit, the `REPOSITORY` column is omitted.

### Interactive root mode

- Running `ocp` without a subcommand starts an interactive full-screen terminal UI when `System.console()` is available and `TERM` is not `dumb`.
- Interactive mode exposes profile and repository operations (`use`, `create`, `add`, `delete`, and refresh actions) and uses the same service layer semantics as subcommands.
- In interactive mode, `d` (delete) is context-sensitive: on repository nodes it deletes the repository, and on profile/file/directory nodes it deletes the selected profile.
- In interactive mode, repository deletion prompts are context-aware:
  - Git-backed repos with local changes show a warning and require explicit force confirmation.
  - File-based repos ask whether to also delete the local folder.
- In interactive mode, `c` (create profile) creates the profile inside the currently selected repository context (repository, profile, or file node), and prompts for optional inheritance using a selectable list of all resolvable profile names across configured repositories.
- In interactive mode, action keys are explicit: `r` refresh selected repository, `R` refresh all repositories, `u` use selected profile, `e` edit selected file, and `p` jump to the selected profile's parent; `Enter` does not trigger these actions.
- In interactive mode, refresh (`r`) is shown only when the selected repository context is git-backed; file-based repositories do not offer refresh actions.
- Interactive tree profile nodes visually show inheritance using a relationship marker (`👤 child ⇢ 👤 parent`).
- Interactive tree includes inherited parent-only files under child profiles as read-only file nodes with subdued styling; inherited files cannot be edited.
- In interactive mode, repository scaffold creation prompts for directory name, location path, and optional initial profile name; after scaffolding, the repository is automatically added to the registry.
- File preview syntax highlighting in interactive mode uses external `bat` when available; if `bat` is unavailable or fails, preview falls back to plain text.
- If interactive UI initialization fails, behavior falls back to standard Picocli root usage output.

### Profile switching and backups (target behavior)

- Source directory: `~/.config/ocp/repositories/<repo-name>/<profile-name>/`
- Target directory: `~/.config/opencode/`
- For each profile file:
  - If target does not exist: create symlink.
  - If target exists and is a symlink: replace symlink.
  - If target exists and is not a symlink: move to backup before linking.
- Backup location: `~/.config/ocp/backups/<timestamp>/<filename>`
- `ocp profile use <name>` must print a user-config notice for `~/.config/opencode/` and indicate whether files were updated or only processed.
- When backups are created, CLI output must include the backup location under `~/.config/ocp/backups/<timestamp>/`.
- Switching must be transactional at file level:
  - If linking one file fails, already-processed files must be restored from backups.
- Profile inheritance and merge behavior:
  - `extends_from` profiles are resolved parent-first.
  - Parent-only files are inherited as-is.
  - For overlapping JSON/JSONC files (`*.json` and `*.jsonc`), the resulting file is deep-merged recursively.
  - Child values override parent values for matching keys at any nesting level.
  - Arrays and non-object JSON values are replaced by the child value.
  - Child and parent must use the same extension for the same logical JSON file (`.json` vs `.jsonc`), or `profile use` fails.

## Technology stack

- Java 25
- Micronaut 4.10
- Micronaut Picocli integration
- Gradle build
- TamboUI toolkit for terminal rendering
- GraalVM native executable (`ocp`)
- Homebrew distribution target

## Verification and acceptance criteria

### Required verification tasks

- `./gradlew test`
- `./gradlew nativeTest`

### Command-level acceptance criteria

- `ocp help`
  - exits `0`
  - output contains `Usage: ocp`
- `ocp profile list`
  - with no repositories/profiles: exits `0`, prints helpful empty-state message
  - with multiple repositories: outputs a table sorted by profile name with columns `NAME`, `DESCRIPTION`, `ACTIVE`, `VERSION`, `LAST UPDATED`, `MESSAGE`, and includes `REPOSITORY` when it fits within the width budget (repository name)
  - with duplicate profile names: exits `1`, prints duplicate-name error on stderr
- `ocp profile use <name>`
  - exits `0`, prints profile switch success message
  - prints a user-config notice for `~/.config/opencode/`
  - when files are linked/replaced/removed, notice states files were updated
  - when no file changes are needed, notice states files were processed
  - when existing files are moved, prints backup notice with backup path
- `ocp repository refresh [name]`
  - exits `0`, prints refresh success message
  - when active profile files are reapplied, prints user-config notice (updated vs processed) and backup notice when applicable
  - when merged active-profile files in `~/.config/opencode/` were locally edited, prompts to discard those merged-file edits or abort; abort exits `1` and leaves files untouched
- `ocp repository list`
  - with no repositories: exits `0`, prints helpful empty-state message
  - with configured repositories: outputs one rounded box per repository with fields `Name`, `URI`, `Local path`, and `Resolved profiles`
  - if a repository metadata file is invalid JSON: exits `1`, reports metadata read failure
- Repository config loading
  - missing `config.json`: treated as empty repository list
  - invalid `config.json`: exits `1`, reports registry read failure
- repository source/name normalization: trims source and name, computes or normalizes `localPath`
- Git operations
  - clone command failure: exits `1`, includes exit-code detail
  - interrupted git operation: thread interrupt flag is restored and command fails cleanly

### Test types

- Unit tests for models/services/clients
- Command tests for stdout/stderr/exit code behavior
- Integration tests with Git server container for clone/discovery flows
- Native tests to validate behavior parity in compiled binary
