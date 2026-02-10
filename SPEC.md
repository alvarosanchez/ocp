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
- Supporting profile inheritance/templating.

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

### `config.json` schema

```json
{
  "config": {
    "profileVersionCheck": true
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

- `config.profileVersionCheck` defaults to `true`.
- `repositories[*].uri` is required.
- `repositories[*].name` may be omitted; when omitted, name is derived from URI basename without `.git`.
- `repositories[*].localPath` is derived from cache directory and repository name.

### `repository.json` schema

```json
{
  "profiles": [
    { "name": "my-company" },
    { "name": "oss" }
  ]
}
```

Rules:

- `profiles` defaults to an empty list.
- Empty/blank profile names are ignored.
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
| `ocp profile list` | Implemented | List profiles from all repositories in sorted order; fail on duplicate names. |
| `ocp profile` | Planned | Print currently active profile. |
| `ocp profile create <name>` | Planned behavior, placeholder implemented | Create profile folder and register it in repository metadata. |
| `ocp profile use <name>` | Planned behavior, placeholder implemented | Switch active profile by linking profile files to OpenCode config location. |
| `ocp profile refresh <name>` | Planned | Pull latest changes for profile repository. |
| `ocp repository add <uri>` | Planned behavior, placeholder implemented | Clone repository into local cache and register it in `config.json`. |
| `ocp repository delete <name>` | Planned behavior, placeholder implemented | Remove repository entry from registry and delete local clone. |
| `ocp repository create <name> [--profile-name <profile>]` | Planned | Initialize new profile repository with `repository.json` and initial profile. |

## Operational semantics

### Repository name normalization

- Derive repository name from URI basename split by `/` or `:`.
- Strip `.git` suffix when present.
- Trim URI whitespace before parsing.

### Profile uniqueness

- During profile discovery (`profile list` and future `profile use` resolution), duplicate names across repositories are an error.
- Error message must include duplicate profile names.

### Profile version checks

- Controlled by `config.profileVersionCheck`.
- Default is enabled.
- Target behavior: on each invocation, check whether local repository state is behind remote and print a non-fatal update hint.
- Version-check failures (for example offline network) must not block normal command execution.

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
  - with multiple repositories: outputs sorted profile names
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
