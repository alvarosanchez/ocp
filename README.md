# ocp

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

`ocp` (OpenCode Configuration Profiles) is a CLI to manage OpenCode config profiles stored in Git repositories.

It helps you switch between different OpenCode setups (for example, work vs personal) by linking the selected profile files into your OpenCode config directory.

## Installation

### Homebrew (recommended)

TBD. Homebrew formula/tap publishing is not ready yet.

### Temporary: run from source

Until Homebrew is available:

```bash
git clone https://github.com/alvarosanchez/ocp.git
cd ocp
./gradlew nativeCompile
./build/native/nativeCompile/ocp help
```

Requirements for source usage:

- Java 25
- Git

## Quick start

```bash
# Add a profile repository
ocp repository add git@github.com:my-org/opencode-profiles.git

# List profiles discovered across configured repositories
ocp profile list

# Switch active profile
ocp profile use my-company

# Show current active profile and metadata
ocp profile

# Refresh repository data
ocp profile refresh
```

## How it works

- `ocp` keeps a local registry of added repositories.
- Each repository provides profile metadata in `repository.json`.
- Each profile is a folder containing files to link into `~/.config/opencode`.
- On `ocp profile use <name>`, `ocp` updates symlinks to the selected profile.
- Existing non-symlink target files are backed up before linking.

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
    { "name": "my-company" },
    { "name": "oss" }
  ]
}
```

## Commands

- `ocp` - print root help.
- `ocp help <command>` - print command help.
- `ocp profile list` - list discovered profiles.
- `ocp profile` - show active profile metadata.
- `ocp profile create [name]` - create a profile in the current repository (`default` if omitted).
- `ocp profile use <name>` - switch to profile by name.
- `ocp profile refresh [name]` - pull latest changes for one profile's repository or all repositories.
- `ocp repository add <uri>` - clone and register a repository.
- `ocp repository delete <name>` - remove repository and local clone.
- `ocp repository create <name> [--profile-name <profile>]` - scaffold a new profile repository.

## File locations

Default paths:

- Registry: `~/.config/ocp/config.json`
- Cache root: `~/.cache/ocp`
- Local clones: `~/.cache/ocp/repositories/<repo-name>`
- Backups: `~/.config/ocp/backups/<timestamp>/...`
- OpenCode config target: `~/.config/opencode`

Optional JVM system property overrides:

- `ocp.config.dir`
- `ocp.cache.dir`
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
