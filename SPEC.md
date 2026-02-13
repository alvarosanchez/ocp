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

- Repository: Git repository containing `repository.json` and one directory per profile.
- Profile: named set of OpenCode config files (for example `opencode.json`).
- Registry: local `ocp` configuration file listing added repositories.

## Quick usage

```bash
ocp profile list
ocp repository add git@github.com:my-company/my-repo.git
ocp profile use my-company
```

## Configuration and storage

### Default paths

- OCP registry: `~/.config/ocp/config.json`
- OCP cache root: `~/.cache/ocp`
- Repository clone directory: `~/.cache/ocp/repositories/<repo-name>`
- Repository metadata file: `~/.cache/ocp/repositories/<repo-name>/repository.json`

### Path overrides (for tests and advanced usage)

- Config directory override: JVM system property `ocp.config.dir`
- Cache directory override: JVM system property `ocp.cache.dir`
- OpenCode config directory override: JVM system property `ocp.opencode.config.dir`
- Working directory override for local create commands: JVM system property `ocp.working.dir`

### `config.json` schema

```json
{
  "config": {
    "activeProfile": "my-company"
  },
  "repositories": [
    {
      "name": "my-repo",
      "uri": "git@github.com:my-company/my-repo.git",
      "localPath": "/home/user/.cache/ocp/repositories/my-repo"
    }
  ]
}
```

Rules:

- `config.activeProfile` defaults to `null` (no active profile selected).
- `repositories[*].uri` is required.
- `repositories[*].name` may be omitted; when omitted, name is derived from URI path.
  - Hosted remotes use namespace + repository (for example `acme-opencode-config`).
  - Local file paths keep basename behavior (for example `remote`).
- `repositories[*].localPath` is derived from cache directory and repository name.

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
| `ocp` | Implemented | Print root usage when no subcommand is provided. |
| `ocp help <command>` | Implemented | Print help for command/subcommand. |
| `ocp profile list` | Implemented | Print a table of profiles with name, description, local commit metadata, and non-fatal remote update hints; include repository URI when width budget allows. |
| `ocp profile` | Implemented | Print currently active profile with repository/version metadata and update hints. |
| `ocp profile create [name]` | Implemented | Create profile folder and register it in repository metadata. Defaults to `default` when no name is provided. |
| `ocp profile use <name>` | Implemented | Switch active profile by linking profile files to OpenCode config location. |
| `ocp profile refresh [name]` | Implemented | Pull latest changes for a specific profile repository, or for all repositories when no name is provided. |
| `ocp repository add <uri>` | Implemented | Clone repository into local cache and register it in `config.json`. |
| `ocp repository delete <name>` | Implemented | Remove repository entry from registry and delete local clone. |
| `ocp repository create <name> [--profile-name <profile>]` | Implemented | Initialize new profile repository with `repository.json` and initial profile. |

## Operational semantics

### Repository name normalization

- Derive repository name from URI path segments.
- For hosted/scp remotes, include namespace segments plus repository segment (joined by `-`).
- For local file paths, use repository basename.
- Strip `.git` suffix when present.
- Trim URI whitespace before parsing.

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

### Profile switching and backups (target behavior)

- Source directory: `~/.cache/ocp/repositories/<repo-name>/<profile-name>/`
- Target directory: `~/.config/opencode/`
- For each profile file:
  - If target does not exist: create symlink.
  - If target exists and is a symlink: replace symlink.
  - If target exists and is not a symlink: move to backup before linking.
- Backup location: `~/.config/ocp/backups/<timestamp>/<filename>`
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
  - with multiple repositories: outputs a table sorted by profile name with columns `NAME`, `DESCRIPTION`, `ACTIVE`, `VERSION`, `LAST UPDATED`, `MESSAGE`, and includes `REPOSITORY` when it fits within the width budget
  - with duplicate profile names: exits `1`, prints duplicate-name error on stderr
- Repository config loading
  - missing `config.json`: treated as empty repository list
  - invalid `config.json`: exits `1`, reports registry read failure
  - repository URI normalization: trims URI, derives repository name, computes `localPath`
- Git operations
  - clone command failure: exits `1`, includes exit-code detail
  - interrupted git operation: thread interrupt flag is restored and command fails cleanly

### Test types

- Unit tests for models/services/clients
- Command tests for stdout/stderr/exit code behavior
- Integration tests with Git server container for clone/discovery flows
- Native tests to validate behavior parity in compiled binary
