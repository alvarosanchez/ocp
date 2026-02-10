# ocp – OpenCode Configuration Profiles

`ocp` is a command-line tool that provides the ability to use configuration profiles for [OpenCode](https://opencode.ai/).

## Motivation

OpenCode (and some plugins, such as [oh-my-opencode](https://github.com/code-yeongyu/oh-my-opencode) have configuration
files in `~/.config/opencode/opencode.json` and `~/.config/opencode/oh-my-opencode.json`. These configuration files
define LLM providers and their models, agents, and a bunch of other elements.

In corporate environments, it is common to have a requirement to use company-provided AI models, whilst users may want 
to use commercial frontier models (such as those provided by OpenAI, Anthropic or Gemini) for personal and/or open-source
projects.

`ocp` helps in this scenario by allowing users to switch between different configuration profiles. The configuration
profiles are essentially Git repositories with the profile metadata and the configuration files.

## Example usage (assuming users have `ocp` already installed, fresh install).

1. List profiles (shows empty list):

        ocp profile list

2. Add a new configuration repository (which contains a `my-company` profile):

        ocp repository add git@github.com:my-company/my-repo.git

3. Switch to a profile (symlinks configuration files to their expected destination):

        ocp profile use my-company

## Configuration files

- `~/.config/ocp/config.json` – contains 2 sections:
  1. `config`: list of global `ocp` configuration options. At the moment, only `profileVersionCheck` (true/false)
  2. `repositories`: list of user-added repositories and their Git URLs.
- `~/.cache/ocp/repositories/<repo>/repository.json` – declares available profiles in this repo.

## Features (commands)

- `ocp profile` – shows the current profile.
- `ocp profile list` – lists profiles based on the user-added repositories. To do so, it loops over the repositories
  declared in `~/.config/ocp/config.json`, and for each of them, reads their `repository.json` config to see which
  profiles are available. Profile names must be unique, so if `ocp` resolves 2 profiles with the same name, it will print
  an error and exit.
- `ocp profile create my-profile` – creates a new configuration profile on an existing repository folder (adds it to
  `repository.json` and creates the subfolder).
- `ocp profile use my-profile` – switches to a given profile by symlinking configuration files. If target files exist and
  are not symlinks, it backs them up and warns the user.
- `ocp profile refresh my-profile` – pulls the latest changes from a profile repository.

- `ocp repository add git@github.com:my-company/my-repo.git` – adds a profile repository, by cloning it in 
  `~/.cache/ocp/repositories/my-repo`, and registers it in the `ocp` configuration file (`~/.config/ocp/config.json`).
- `ocp repository delete my-repo` – deletes an added repository (removes from config, deletes local clone).
- `ocp repository create my-repo` – initialises a new profile repository, with a `repository.json` file and an initial
  profile named the same as the repo. This can be customised with `--profile-name`.

- `ocp help <command>` – provides usage instructions about one command and/or subcommand.

Since profiles may be updated remotely, every `ocp` invocation should check the latest commit of both local and remote 
repositories, showing a message to the user if there is a newer version of a profile. This behaviour should be 
configurable in `~/.config/ocp/config.json`, and is turned on by default.

## Repository structure

A profile repository contains:

- `/repository.json` – repository configuration file. Currently we only store their names, but we may add more information
  in the future.
- For every profile, a `/<profile-name>` folder containing the config files to be symlinked (`opencode.json`, 
  `oh-my-opencode.json`, etc.). For simplicity, we expect the profile name and the subfolder name to match.

## Technology stack

`ocp` is a command-line application written in Java 25, using Micronaut 4.10 and the Micronaut PicoCLI integration. It 
is built with Gradle and compiled as a GraalVM native executable. Will be installed with HomeBrew.

Each component in the stack will use the most recent stable version.

## Verification

- Every feature must have tests covering different scenarios.
- Since the outcome of this project is an `ocp` native executable, `nativeTest` task should be run as part of the
  verification steps for each feature implemented.
