# AGENTS.md
Guidance for autonomous coding agents working in this repository.

## 1) Project overview
- Project: `ocp` (OpenCode Configuration Profiles CLI)
- Stack: Java 25, Micronaut, Picocli, Gradle Kotlin DSL, JUnit 5
- Packaging: single-module Gradle project
- Main class: `com.github.alvarosanchez.ocp.command.OcpCommand`
- Root package: `com.github.alvarosanchez.ocp`

## 2) Build / test / verification commands
Run commands from repository root: `/Users/alvaro/Dev/alvarosanchez/ocp`.

### Core commands
- Build: `./gradlew build`
- Full checks: `./gradlew check`
- All JVM tests: `./gradlew test`
- Native binary: `./gradlew nativeCompile`
- Native tests: `./gradlew nativeTest`
- CLI help: `./gradlew run --args="help"`

### Single-test commands (important)
- One test class:
  - `./gradlew test --tests com.github.alvarosanchez.ocp.service.ProfileServiceTest`
- One test method:
  - `./gradlew test --tests com.github.alvarosanchez.ocp.service.ProfileServiceTest.listProfilesReturnsSortedUniqueNames`
- Pattern match:
  - `./gradlew test --tests "*ProfileCommandTest*"`

### Supporting commands
- List all Gradle tasks: `./gradlew tasks --all`
- Compile only (Java type-check proxy): `./gradlew compileJava`
- Clean outputs: `./gradlew clean`

## 3) Lint and static-analysis reality
- No dedicated lint task configured in `build.gradle.kts`
- No Spotless/PMD/Gradle Checkstyle plugin configured
- IntelliJ Checkstyle settings exist at `.idea/checkstyle-idea.xml`
- Practical local quality baseline: `./gradlew check`

## 4) Source layout
- Production code: `src/main/java/com/github/alvarosanchez/ocp/**`
- Tests: `src/test/java/com/github/alvarosanchez/ocp/**`
- Resources: `src/main/resources/**`
- Build config: `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`
- Product behavior contract: `SPEC.md`

## 5) Architecture boundaries
- `command/`: Picocli commands and CLI entry points
- `service/`: business logic and orchestration
- `client/`: external process calls (`git`)
- `model/`: immutable serde models (records)

Guideline: keep commands thin and place domain logic in services.

## 6) Code style conventions (observed)

### Formatting
- 4-space indentation
- Braces on same line
- Wrap long argument lists vertically
- Keep methods straightforward and readable

### Imports
- No wildcard imports
- Tests use static imports for assertions
- Keep imports explicit and minimal
- Follow local ordering style in edited files

### Types and immutability
- Prefer records for DTO/config models in `model/`
- Prefer immutable collections (`List.of`, `List.copyOf`, `Set.copyOf`)
- Use `final` classes where extension is not intended
- Use package-private visibility by default
- Use `var` sparingly

### Naming
- Classes and records: `PascalCase`
- Methods and variables: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Tests: descriptive `camelCase` method names
- Subcommands often use nested types (for example `ProfileCommand.ListCommand`)

### Error handling
- Never swallow exceptions
- Wrap checked I/O with context (`UncheckedIOException` or `IllegalStateException`)
- Preserve interrupt status on `InterruptedException`
- Include URI/path/operation context in failure messages

### CLI behavior
- Success output goes to `stdout`
- Error output goes to `stderr`
- Return explicit exit codes (`0` success, non-zero failure)
- Root/group commands print usage when no subcommand is provided

### Micronaut / DI
- Use `@Singleton` for stateless services and clients
- Prefer constructor injection
- Keep constructors dependency-only

## 7) Testing conventions
- JUnit 5 (`useJUnitPlatform()`)
- Use `@TempDir` for filesystem isolation
- Use `@BeforeEach` / `@AfterEach` for setup and cleanup
- Assert both behavior and CLI contract (exit code + stdout/stderr)
- Integration tests may use Testcontainers (Docker dependency)
- Native-incompatible tests use `@DisabledInNativeImage`

When writing tests:
- Keep tests deterministic and isolated
- Keep test helpers private to the class
- Follow Arrange/Act/Assert structure

## 8) Filesystem and config conventions
- Default config dir: `~/.config/ocp`
- Default cache dir: `~/.cache/ocp`
- Test override properties:
  - `ocp.config.dir`
  - `ocp.cache.dir`
- Registry file: `config.json`
- Repository metadata file: `repository.json`

## 9) Workflow expectations for agents
Before coding:
- Read nearby files in the same package
- Read related tests before behavior changes
- Check `SPEC.md` for expected behavior and contracts

After coding:
- Minimum: `./gradlew test`
- If startup/native-sensitive behavior changed: `./gradlew nativeTest`
- If build/runtime wiring changed: `./gradlew build`
- If behavior/CLI contract changed, update `SPEC.md` in the same change set

## 10) Do / don't checklist
Do:
- Keep changes small and cohesive
- Add or update tests for behavior changes
- Match existing package boundaries and naming patterns
- Keep exceptions explicit with useful context

Don't:
- Add unrelated refactors
- Introduce silent failure paths
- Add dependencies without concrete need
- Skip `SPEC.md` updates when implemented features change documented behavior
