# AGENTS.md
Guidance for autonomous coding agents working in this repository.

## 1) Project snapshot
- Project: `ocp` (OpenCode Configuration Profiles CLI)
- Stack: Java 25, Micronaut 4.x, Picocli, Gradle Kotlin DSL, JUnit 5
- Packaging: single-module Gradle application
- Main class: `com.github.alvarosanchez.ocp.command.OcpCommand`
- Source root package: `com.github.alvarosanchez.ocp`
- Behavior contract: `SPEC.md` is the product truth source

## 2) Repository policy files
- Cursor rules: no `.cursorrules` and no `.cursor/rules/**` found
- Copilot rules: no `.github/copilot-instructions.md` found
- If these files are added later, treat them as higher-priority agent instructions

## 3) Build, test, and verification commands
Run from repo root: `/Users/alvaro/Dev/alvarosanchez/ocp`.

### Core lifecycle
- Build: `./gradlew build`
- Full verification: `./gradlew check`
- JVM tests: `./gradlew test`
- Native binary compile: `./gradlew nativeCompile`
- Native tests: `./gradlew nativeTest`
- Show CLI help through Gradle app plugin: `./gradlew run --args="help"`

### Single test execution (important)
- One class:
  - `./gradlew test --tests com.github.alvarosanchez.ocp.service.ProfileServiceTest`
- One method:
  - `./gradlew test --tests com.github.alvarosanchez.ocp.service.ProfileServiceTest.getAllProfilesReturnsSortedUniqueNames`
- Pattern selection:
  - `./gradlew test --tests "*ProfileCommandTest*"`

### Useful support commands
- List tasks: `./gradlew tasks --all`
- Compile only (quick type-check proxy): `./gradlew compileJava`
- Clean outputs: `./gradlew clean`

## 4) Lint and static-analysis reality
- No dedicated lint task is configured in `build.gradle.kts`
- No Spotless / PMD / Gradle Checkstyle plugin is configured
- IntelliJ Checkstyle plugin config exists at `.idea/checkstyle-idea.xml`
- Practical quality baseline for agents: `./gradlew check`

## 5) Directory map
- Production Java: `src/main/java/com/github/alvarosanchez/ocp/**`
- Tests: `src/test/java/com/github/alvarosanchez/ocp/**`
- Resources: `src/main/resources/**`
- Build config: `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`
- Product spec and acceptance criteria: `SPEC.md`

## 6) Architecture boundaries and responsibilities
- `command/`: Picocli commands, argument handling, exit-code orchestration, user messages
- `service/`: domain logic and orchestration across repositories/profiles
- `git/`: external `git` process execution wrappers
- `config/`: serde models for registry/repository metadata files
- `model/`: immutable read models for command rendering

Rule of thumb: keep command classes thin; move business logic to services.

## 7) Code style conventions (observed in source)

### Formatting and structure
- 4-space indentation, braces on same line
- Prefer straightforward loops/conditionals over clever abstractions
- Keep methods readable; extract helpers for repeated logic
- Avoid wildcard imports

### Imports
- Standard ordering in this repo is mixed; preserve local file ordering style
- Use explicit imports; static imports are common in tests for assertions
- Avoid introducing unused imports and large import churn

### Types, immutability, and visibility
- Prefer `record` for DTO/config projections (`config/`, `model/`, and local helper records)
- Normalize immutable collections with `List.copyOf` / `Set.copyOf` / `List.of`
- Use `final` classes for core services and utility types when extension is not intended
- Default to package-private constructors/methods unless API must be public

### Naming
- Types/records/enums: `PascalCase`
- Methods/fields/locals: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Tests: descriptive `camelCase` method names
- Command names and messages should align with `SPEC.md` CLI terminology

### Javadoc and API documentation
- Public classes and public methods generally include Javadoc
- Preserve this standard when adding new public API surface
- Keep Javadoc concise and behavior-focused (params, return, failure mode)

## 8) Error handling conventions
- Never swallow exceptions silently
- Wrap I/O failures with context (`UncheckedIOException` or `IllegalStateException`)
- Include operation and path/URI in exception messages where possible
- For `InterruptedException`, always restore interrupt status with `Thread.currentThread().interrupt()`
- Commands catch domain/runtime exceptions, print to `stderr`, and return non-zero

## 9) CLI output and exit-code conventions
- Success/info output uses `stdout`
- Errors use `stderr`
- Expected exit codes:
  - `0`: success
  - `1`: runtime/validation failure
  - `2`: command usage error
- Root/group commands should print usage/help when no subcommand is provided

## 10) Micronaut and DI conventions
- Stateless services/clients use `@Singleton`
- Prefer constructor injection (including package-private constructors)
- Keep constructors dependency-only and avoid side effects

## 11) Test conventions
- JUnit 5 platform (`useJUnitPlatform()`)
- `@TempDir` is standard for filesystem isolation
- Use explicit setup/teardown for system properties in tests
- Validate both behavior and CLI contract (exit code + stdout/stderr content)
- Keep tests deterministic and independent
- Use private helper methods inside each test class for fixtures

## 12) Filesystem and config assumptions
- Default registry file: `~/.config/ocp/config.json`
- Default cache root: `~/.cache/ocp`
- Default OpenCode config target: `~/.config/opencode`
- Common system property overrides:
  - `ocp.config.dir`
  - `ocp.cache.dir`
  - `ocp.opencode.config.dir`
  - `ocp.working.dir`

## 13) Agent workflow expectations

### Before coding
- Read nearby classes in the same package and related tests first
- Check `SPEC.md` acceptance criteria for user-visible behavior changes
- Keep changes minimal, cohesive, and scoped to the request
- Before making any file edits, create and switch to a dedicated working branch (for example `git checkout -b agent/<topic>`); never start work on `master` or `main`.


### After coding
- Minimum verification: `./gradlew test`
- If startup/native-sensitive behavior changed: `./gradlew nativeTest`
- If wiring/build behavior changed: `./gradlew build`
- If CLI behavior changed, update `SPEC.md` in the same change set
- Ask the user to review the files. The user is the one who commits changes
- Ask the user if they want to create a PR with the "gh" CLI. If granted permission,
  upon PR creation, wait for the GitHub Copilot review, and address review comments.
  Once all comments have been reviewed, monitor for CI checks, and if they pass, merge
  the PR. Otherwise, investigate failures and fix them.

## 14) Do / don't checklist
Do:
- Keep architecture boundaries intact (`command` thin, `service` rich)
- Add or update tests for behavior changes
- Preserve transactionality and rollback behavior in profile switch flows
- Keep user-facing error messages actionable and context-rich

Don't:
- Add unrelated refactors in bugfix changes
- Introduce silent failure paths or empty catches
- Add dependencies without clear need
- Change documented behavior without updating `SPEC.md`
- Hardcode dependencies in the build script (use the version catalog instead)
- Commit or push unless explicitly requested by the user
