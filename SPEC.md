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

## Features (commands)

- `ocp profile list` – lists profiles based on the added repositories.
- `ocp profile create` – creates a new configuration profile folder, with the profile metadata (`profiles.json`).
- `ocp profile use my-profile` – switches to a given profile by symlinking configuration files.
- `ocp repository add git@github.com:my-company/my-repo.git` – adds a profile repository, cloning it to a local, known
  folder, and registers it in the `ocp` configuration file (`~/.config/ocp/ocp.json`) such that the tool is aware of
  which repositories have been added and their local paths.
- `ocp repository delete my-repo` – deletes an added repository.

## Technology stack

`ocp` is a command-line application written in Java 25, using Micronaut 4.10 and the Micronaut PicoCLI integration. It 
is built with Gradle and compiled as a GraalVM native executable. Will be installed with HomeBrew.

Each component in the stack will use the most recent stable version.

