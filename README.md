# ocp - OpenCode Configuration Profiles

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

`ocp` (OpenCode Configuration Profiles) is a CLI to manage OpenCode config profiles stored in Git repositories.

It helps you switch between different OpenCode setups (for example, work vs personal) by linking the selected profile files into your OpenCode config directory.

## Installation

### Homebrew (recommended)

```bash
brew install alvarosanchez/tap/ocp
```

Requirements:

- `git` available in your `PATH`

## Quick start

```bash
# Add a profile repository
ocp repository add git@github.com:my-org/opencode-profiles.git --name my-org-opencode-profiles

# List configured repositories and discovered profiles
ocp repository list

# List profiles discovered across configured repositories
ocp profile list

# Switch active profile
ocp profile use my-company

# Show current active profile and metadata
ocp profile

# Refresh repository data
ocp repository refresh
```

## How it works

- `ocp` keeps a local registry of added repositories.
- Each repository provides profile metadata in `repository.json`.
- Each profile is a folder containing files to link into `~/.config/opencode`.
- On `ocp profile use <name>`, `ocp` updates symlinks to the selected profile.
- Existing non-symlink target files are backed up before linking.
- Repository names are provided explicitly when adding a repository (`--name`).

## Repository format

Each profile repository should look like:

```text
repository.json
my-company/opencode.json
my-company/oh-my-opencode.json
oss/opencode.json
```

`repository.json` example:

```json
{
  "profiles": [
    { "name": "my-company", "description": "Company defaults" },
    { "name": "oss", "description": "Open-source profile", "extends_from": "my-company" }
  ]
}
```

When `extends_from` is set, parent profiles are resolved first. Shared JSON/JSONC files are deep-merged recursively, while parent-only files are inherited unchanged.

## Commands

- `ocp` - print root help.
- `ocp help <command>` - print command help.
- `ocp profile list` - list discovered profiles.
- `ocp profile` - show active profile metadata.
- `ocp profile create [name]` - create a profile in the current repository (`default` if omitted).
- `ocp profile use <name>` - switch to profile by name.
- `ocp repository add <uri> --name <name>` - clone and register a repository.
- `ocp repository list` - list configured repositories with URI, local path, and resolved profiles.
- `ocp repository delete <name>` - remove repository and local clone.
- `ocp repository create <name> [--profile-name <profile>]` - scaffold a new profile repository.
- `ocp repository refresh [name]` - pull latest changes for one repository by name or all repositories.

## File locations

Default paths:

- Registry: `~/.config/ocp/config.json`
- Repository storage root: `~/.config/ocp`
- Local clones: `~/.config/ocp/repositories/<repo-name>` (using the configured repository name)
- Resolved merged profiles: `~/.config/ocp/resolved-profiles/<profile-name>/`
- Backups: `~/.config/ocp/backups/<timestamp>/...`
- OpenCode config target: `~/.config/opencode`

Optional JVM system property overrides:

- `ocp.config.dir`
- `ocp.cache.dir` (legacy storage override)
- `ocp.opencode.config.dir`
- `ocp.working.dir`

## Exit codes

- `0` success
- `1` runtime/validation error
- `2` command usage error

## Common errors

- `Missing required dependency git`: install Git and ensure it is in your `PATH`.
- `duplicate profile names found`: profile names must be unique across all added repositories.
- `No active profile selected yet`: run `ocp profile use <name>` first.
