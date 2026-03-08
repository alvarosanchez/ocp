package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile.ProfileEntry;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jakarta.inject.Singleton;

/**
 * Service that manages configured profile repositories.
 */
@Singleton
public final class RepositoryService {

    private static final int MAX_LOCAL_DIFF_LINES = 400;
    private static final int MAX_LOCAL_DIFF_CHARS = 32_000;
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;\\d]*m");

    private final ObjectMapper objectMapper;
    private final GitRepositoryClient gitRepositoryClient;

    RepositoryService(ObjectMapper objectMapper, GitRepositoryClient gitRepositoryClient) {
        this.objectMapper = objectMapper;
        this.gitRepositoryClient = gitRepositoryClient;
    }

    /**
     * Loads configured repositories from the local registry.
     *
     * @return normalized repository entries
     */
    public List<RepositoryEntry> load() {
        OcpConfigFile configFile = loadConfigFile();
        return normalizeEntries(configFile.repositories());
    }

    /**
     * Returns configured repositories enriched with local profile metadata.
     *
     * @return repositories sorted by name
     */
    public List<ConfiguredRepository> listConfiguredRepositories() {
        List<ConfiguredRepository> repositories = new ArrayList<>();
        for (RepositoryEntry entry : load()) {
            repositories.add(
                new ConfiguredRepository(
                    entry.name(),
                    entry.uri(),
                    entry.localPath(),
                    resolvedProfilesFor(entry)
                )
            );
        }
        repositories.sort(Comparator.comparing(ConfiguredRepository::name));
        return List.copyOf(repositories);
    }

    /**
     * Adds a repository to the registry and clones it locally.
     *
     * @param repositoryReference repository URI or local path to add
     * @param repositoryName repository name to register
     * @return added repository entry
     */
    public RepositoryEntry add(String repositoryReference, String repositoryName) {
        String source = repositoryReference == null ? "" : repositoryReference.trim();
        if (source.isBlank()) {
            throw new IllegalStateException("Repository URI or local path is required.");
        }
        String name = normalizeRepositoryName(repositoryName);

        RepositorySource repositorySource = resolveRepositorySource(source);
        Path configuredLocalPath = repositorySource.uri() == null
            ? repositorySource.localPath()
            : repositoriesDirectory().resolve(name);
        String normalizedConfiguredLocalPath = normalizeAbsolutePath(configuredLocalPath).toString();

        List<RepositoryEntry> repositories = new ArrayList<>(load());
        for (RepositoryEntry repository : repositories) {
            if (repository.name().equals(name)) {
                throw new IllegalStateException("Repository `" + name + "` is already configured.");
            }
            if (repositorySource.uri() != null && repositorySource.uri().equals(repository.uri())) {
                throw new IllegalStateException("Repository URI `" + repositorySource.uri() + "` is already configured.");
            }
            if (normalizedConfiguredLocalPath.equals(normalizeAbsolutePath(Path.of(repository.localPath())).toString())) {
                throw new IllegalStateException("Repository local path `" + normalizedConfiguredLocalPath + "` is already configured.");
            }
        }

        RepositoryEntry added;
        if (repositorySource.uri() == null) {
            added = new RepositoryEntry(name, null, normalizedConfiguredLocalPath);
        } else {
            Path localPath = configuredLocalPath;
            added = new RepositoryEntry(name, repositorySource.uri(), normalizedConfiguredLocalPath);
            if (Files.exists(localPath)) {
                deleteRecursively(localPath);
            }
            gitRepositoryClient.clone(repositorySource.uri(), localPath);
        }

        repositories.add(added);
        saveConfig(new OcpConfigFile(loadConfigFile().config(), repositories));

        return added;
    }

    public RepositoryEntry delete(String repositoryName) {
        return delete(repositoryName, false, false);
    }

    public RepositoryEntry delete(String repositoryName, boolean force, boolean deleteLocalPathForFileBased) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);

        List<RepositoryEntry> repositories = new ArrayList<>(load());
        RepositoryEntry deletedRepository = findConfiguredRepository(normalizedRepositoryName, repositories);
        Path localPath = Path.of(deletedRepository.localPath());
        boolean gitBacked = deletedRepository.isGitBacked();
        if (gitBacked && !force && hasGitLocalChanges(localPath)) {
            throw new IllegalStateException(
                "Repository `"
                    + normalizedRepositoryName
                    + "` has local git changes in `"
                    + localPath
                    + "`. Retry with `--force` to delete it."
            );
        }

        List<RepositoryEntry> remaining = new ArrayList<>();
        for (RepositoryEntry repository : repositories) {
            if (repository.name().equals(normalizedRepositoryName)) {
                continue;
            }
            remaining.add(repository);
        }

        boolean deleteLocalPath = gitBacked || deleteLocalPathForFileBased;
        if (deleteLocalPath) {
            if (!gitBacked && deleteLocalPathForFileBased) {
                validateFileBasedDeletionTarget(deletedRepository, localPath);
            }
            deleteRecursively(localPath);
        }
        saveConfig(new OcpConfigFile(loadConfigFile().config(), remaining));

        return deletedRepository;
    }

    public RepositoryDeletePreview inspectDelete(String repositoryName) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);
        RepositoryEntry repository = findConfiguredRepository(normalizedRepositoryName, load());
        boolean gitBacked = repository.isGitBacked();
        boolean hasLocalChanges = gitBacked && hasGitLocalChanges(Path.of(repository.localPath()));
        return new RepositoryDeletePreview(
            repository.name(),
            repository.uri(),
            repository.localPath(),
            gitBacked,
            hasLocalChanges
        );
    }

    public RepositoryCommitPushPreview inspectCommitPush(String repositoryName) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);
        RepositoryEntry repository = findConfiguredRepository(normalizedRepositoryName, load());
        return inspectCommitPush(repository);
    }

    public RepositoryCommitPushPreview inspectCommitPush(RepositoryEntry repository) {
        String localPath = normalizeBlankToNull(repository.localPath());
        if (localPath == null) {
            throw new IllegalStateException("Repository `" + repository.name() + "` does not define a local path.");
        }
        boolean gitBacked = repository.isGitBacked();
        Path path = Path.of(localPath);
        if (gitBacked && (!Files.isDirectory(path) || !Files.exists(path.resolve(".git")))) {
            throw new IllegalStateException("Repository `" + repository.name() + "` is not available as a local git checkout at " + path + ".");
        }
        boolean hasLocalChanges = gitBacked && hasGitLocalChanges(path);
        return new RepositoryCommitPushPreview(
            repository.name(),
            repository.uri(),
            localPath,
            gitBacked,
            hasLocalChanges
        );
    }

    public String getLocalDiff(String repositoryName) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);
        RepositoryEntry repository = findConfiguredRepository(normalizedRepositoryName, load());
        if (!repository.isGitBacked()) {
            throw new IllegalStateException("Repository `" + normalizedRepositoryName + "` is file-based.");
        }
        Path localPath = Path.of(repository.localPath());
        if (!Files.isDirectory(localPath) || !Files.exists(localPath.resolve(".git"))) {
            throw new IllegalStateException("Repository `" + normalizedRepositoryName + "` is not available as a local git checkout at " + localPath + ".");
        }
        String diff = stripAnsi(gitRepositoryClient.localDiff(localPath));
        if (diff.length() <= MAX_LOCAL_DIFF_CHARS && diff.lines().count() <= MAX_LOCAL_DIFF_LINES) {
            return diff;
        }
        String truncated = diff.lines().limit(MAX_LOCAL_DIFF_LINES).collect(Collectors.joining("\n"));
        if (truncated.length() > MAX_LOCAL_DIFF_CHARS) {
            truncated = truncated.substring(0, MAX_LOCAL_DIFF_CHARS);
        }
        String suffix = "\n... diff truncated for preview ...";
        if (truncated.length() + suffix.length() > MAX_LOCAL_DIFF_CHARS) {
            truncated = truncated.substring(0, Math.max(0, MAX_LOCAL_DIFF_CHARS - suffix.length()));
        }
        return truncated + suffix;
    }

    private String stripAnsi(String diff) {
        return ANSI_ESCAPE_PATTERN.matcher(diff).replaceAll("");
    }

    public void commitAndPush(String repositoryName, String commitMessage) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);
        String normalizedCommitMessage = normalizeBlankToNull(commitMessage);
        if (normalizedCommitMessage == null) {
            throw new IllegalStateException("Commit message is required.");
        }

        RepositoryEntry repository = findConfiguredRepository(normalizedRepositoryName, load());
        if (!repository.isGitBacked()) {
            throw new IllegalStateException("Repository `" + normalizedRepositoryName + "` is file-based; nothing to push.");
        }

        Path localPath = Path.of(repository.localPath());
        if (!Files.isDirectory(localPath) || !Files.exists(localPath.resolve(".git"))) {
            throw new IllegalStateException(
                "Repository `" + normalizedRepositoryName + "` is not available as a local git checkout at " + localPath + "."
            );
        }
        if (!hasGitLocalChanges(localPath)) {
            throw new IllegalStateException("Repository `" + normalizedRepositoryName + "` has no local changes to commit.");
        }

        gitRepositoryClient.commitLocalChangesAndPush(localPath, normalizedCommitMessage);
    }

    /**
     * Creates a new repository scaffold in the working directory.
     *
     * @param repositoryName repository directory name
     * @param profileName optional initial profile name
     * @return absolute path of the created repository
     */
    public Path create(String repositoryName, String profileName) {
        return create(repositoryName, profileName, null);
    }

    public Path create(String repositoryName, String profileName, String repositoryLocation) {
        return createScaffold(repositoryName, profileName, repositoryLocation);
    }

    public Path createScaffold(String repositoryName, String profileName, String repositoryLocation) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);

        Path repositoryPath = plannedRepositoryPath(normalizedRepositoryName, repositoryLocation);
        if (Files.exists(repositoryPath)) {
            throw new IllegalStateException("Directory already exists: " + repositoryPath);
        }

        try {
            Files.createDirectories(repositoryPath);
            List<ProfileEntry> profiles = new ArrayList<>();
            if (profileName != null && !profileName.isBlank()) {
                String normalizedProfileName = PathSegmentValidator.requireSinglePathSegment(profileName, "Profile name");
                profiles.add(new ProfileEntry(normalizedProfileName));
                Files.createDirectories(repositoryPath.resolve(normalizedProfileName));
            }

            String content = objectMapper.writeValueAsString(new RepositoryConfigFile(profiles));
            Files.writeString(repositoryPath.resolve("repository.json"), content);
            return repositoryPath;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create repository at " + repositoryPath, e);
        }
    }

    public RepositoryEntry createAndAdd(String repositoryName, String profileName, String repositoryLocation) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);
        Path createdRepository = plannedRepositoryPath(normalizedRepositoryName, repositoryLocation);
        boolean repositoryPathExistedBefore = Files.exists(createdRepository);
        try {
            Path repositoryPath = createScaffold(normalizedRepositoryName, profileName, repositoryLocation);
            return add(repositoryPath.toString(), normalizedRepositoryName);
        } catch (RuntimeException e) {
            if (!repositoryPathExistedBefore && Files.exists(createdRepository)) {
                deleteRecursively(createdRepository);
            }
            throw e;
        }
    }

    public RepositoryEntry setRepositoryUri(String repositoryName, String repositoryUri) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);
        String normalizedRepositoryUri = normalizeBlankToNull(repositoryUri);
        if (normalizedRepositoryUri == null) {
            throw new IllegalStateException("Repository URI is required.");
        }

        OcpConfigFile configFile = loadConfigFile();
        List<RepositoryEntry> normalizedRepositories = new ArrayList<>(load());
        findConfiguredRepository(normalizedRepositoryName, normalizedRepositories);
        for (RepositoryEntry repository : normalizedRepositories) {
            if (repository.name().equals(normalizedRepositoryName)) {
                continue;
            }
            if (normalizedRepositoryUri.equals(repository.uri())) {
                throw new IllegalStateException("Repository URI `" + normalizedRepositoryUri + "` is already configured.");
            }
        }

        List<RepositoryEntry> updated = new ArrayList<>();
        for (RepositoryEntry repository : configFile.repositories()) {
            if (!normalizeRepositoryName(repository.name()).equals(normalizedRepositoryName)) {
                updated.add(repository);
                continue;
            }
            updated.add(new RepositoryEntry(repository.name(), normalizedRepositoryUri, repository.localPath()));
        }
        saveConfig(new OcpConfigFile(configFile.config(), updated));
        return findConfiguredRepository(normalizedRepositoryName, load());
    }

    private Path plannedRepositoryPath(String normalizedRepositoryName, String repositoryLocation) {
        return resolveCreateLocation(repositoryLocation)
            .resolve(normalizedRepositoryName)
            .toAbsolutePath()
            .normalize();
    }

    OcpConfigFile loadConfigFile() {
        Path file = repositoriesFile();
        if (!Files.exists(file)) {
            return new OcpConfigFile(new OcpConfigOptions(), List.of());
        }
        try {
            String content = Files.readString(file);
            return objectMapper.readValue(content, OcpConfigFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read repository registry", e);
        }
    }

    void saveConfig(OcpConfigFile configFile) {
        try {
            Files.createDirectories(configDirectory());
            Files.writeString(repositoriesFile(), objectMapper.writeValueAsString(configFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write repository registry", e);
        }
    }

    private List<RepositoryEntry> normalizeEntries(List<RepositoryEntry> entries) {
        List<RepositoryEntry> normalized = new ArrayList<>();
        for (RepositoryEntry entry : entries) {
            String name = entry.name() == null ? "" : entry.name().trim();
            if (name.isBlank()) {
                continue;
            }
            PathSegmentValidator.validateSinglePathSegment(name, "Repository name");

            String uri = normalizeBlankToNull(entry.uri());
            String localPath = normalizeBlankToNull(entry.localPath());
            if (uri != null) {
                if (localPath == null) {
                    localPath = normalizeAbsolutePath(repositoriesDirectory().resolve(name)).toString();
                } else {
                    localPath = resolveLocalPath(localPath).toString();
                }
            } else {
                if (localPath == null) {
                    continue;
                }
                localPath = resolveLocalPath(localPath).toString();
            }

            normalized.add(
                new RepositoryEntry(
                    name,
                    uri,
                    localPath
                )
            );
        }
        return normalized;
    }

    private RepositorySource resolveRepositorySource(String source) {
        if (!looksLikeLocalPath(source)) {
            return new RepositorySource(source, null);
        }

        Path localPath = resolveLocalPath(source);
        if (!Files.exists(localPath)) {
            throw new IllegalStateException("Local repository path does not exist: " + localPath);
        }
        if (!Files.isDirectory(localPath)) {
            throw new IllegalStateException("Local repository path is not a directory: " + localPath);
        }
        return new RepositorySource(null, localPath);
    }

    private boolean looksLikeLocalPath(String source) {
        if (source.equals(".") || source.equals("..") || source.startsWith("./") || source.startsWith("../")) {
            return true;
        }
        if (source.startsWith("/") || source.startsWith("~/") || source.matches("^[A-Za-z]:[\\\\/].*")) {
            return true;
        }
        if (source.matches("^[A-Za-z]:.*")) {
            return true;
        }
        if (source.contains("://") || source.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*") || source.matches("^[^\\s@]+@[^\\s:]+:.*")) {
            return false;
        }
        if (source.contains("/") || source.contains("\\")) {
            return true;
        }
        try {
            resolveLocalPath(source);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private Path resolveLocalPath(String source) {
        String resolvedSource = source;
        if (source.startsWith("~/")) {
            resolvedSource = Path.of(System.getProperty("user.home")).resolve(source.substring(2)).toString();
        }

        try {
            Path candidate = Path.of(resolvedSource);
            if (candidate.isAbsolute()) {
                return normalizeAbsolutePath(candidate);
            }
            return normalizeAbsolutePath(workingDirectory().resolve(candidate));
        } catch (InvalidPathException e) {
            throw new IllegalStateException("Invalid local repository path: " + source, e);
        }
    }

    private Path resolveCreateLocation(String repositoryLocation) {
        String location = repositoryLocation == null ? "" : repositoryLocation.trim();
        if (location.isBlank()) {
            return workingDirectory().toAbsolutePath().normalize();
        }
        return resolveLocalPath(location);
    }

    private Path normalizeAbsolutePath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private String normalizeBlankToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeRepositoryName(String repositoryName) {
        return PathSegmentValidator.requireSinglePathSegment(repositoryName, "Repository name");
    }

    private RepositoryEntry findConfiguredRepository(String repositoryName, List<RepositoryEntry> repositories) {
        for (RepositoryEntry repository : repositories) {
            if (repository.name().equals(repositoryName)) {
                return repository;
            }
        }
        throw new IllegalStateException("Repository `" + repositoryName + "` is not configured.");
    }

    private boolean hasGitLocalChanges(Path localPath) {
        if (!Files.exists(localPath) || !Files.isDirectory(localPath)) {
            return false;
        }
        if (!Files.exists(localPath.resolve(".git"))) {
            return false;
        }
        return gitRepositoryClient.hasLocalChanges(localPath);
    }

    private void validateFileBasedDeletionTarget(RepositoryEntry repository, Path localPath) {
        Path normalizedLocalPath = normalizeAbsolutePath(localPath);
        Path homeDirectory = normalizeAbsolutePath(Path.of(System.getProperty("user.home")));
        if (normalizedLocalPath.getParent() == null) {
            throw new IllegalStateException(
                "Refusing to delete local path `" + normalizedLocalPath + "` for file-based repository `" + repository.name() + "`."
            );
        }
        if (normalizedLocalPath.equals(homeDirectory)) {
            throw new IllegalStateException(
                "Refusing to delete home directory `" + normalizedLocalPath + "` for file-based repository `" + repository.name() + "`."
            );
        }
        Path metadataFile = normalizedLocalPath.resolve("repository.json");
        if (Files.exists(normalizedLocalPath) && !Files.isRegularFile(metadataFile)) {
            throw new IllegalStateException(
                "Refusing to delete local path `"
                    + normalizedLocalPath
                    + "` for file-based repository `"
                    + repository.name()
                    + "` because `repository.json` was not found."
            );
        }
    }

    private record RepositorySource(String uri, Path localPath) {
    }

    public record RepositoryDeletePreview(
        String name,
        String uri,
        String localPath,
        boolean gitBacked,
        boolean hasLocalChanges
    ) {
    }

    public record RepositoryCommitPushPreview(
        String name,
        String uri,
        String localPath,
        boolean gitBacked,
        boolean hasLocalChanges
    ) {
    }

    private List<String> resolvedProfilesFor(RepositoryEntry repositoryEntry) {
        Path metadataFile = Path.of(repositoryEntry.localPath()).resolve("repository.json");
        if (!Files.exists(metadataFile)) {
            return List.of();
        }

        try {
            RepositoryConfigFile metadata = objectMapper.readValue(Files.readString(metadataFile), RepositoryConfigFile.class);
            return metadata
                .profiles()
                .stream()
                .map(ProfileEntry::name)
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read profile metadata from " + metadataFile, e);
        }
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths
                .sorted(Comparator.reverseOrder())
                .forEach(candidate -> {
                    try {
                        Files.deleteIfExists(candidate);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to delete " + candidate, e);
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete repository at " + path, e);
        } catch (UncheckedIOException e) {
            throw e;
        }
    }

    /**
     * Render-ready repository view model used by repository list command.
     *
     * @param name normalized repository name
     * @param uri configured repository URI
     * @param localPath normalized local clone path
     * @param resolvedProfiles repository profile names discovered from metadata
     */
    public record ConfiguredRepository(
        String name,
        String uri,
        String localPath,
        List<String> resolvedProfiles
    ) {

        /**
         * Creates an immutable configured repository projection.
         *
         * @param name normalized repository name
         * @param uri configured repository URI
         * @param localPath normalized local clone path
         * @param resolvedProfiles repository profile names
         */
        public ConfiguredRepository {
            resolvedProfiles = resolvedProfiles == null ? List.of() : List.copyOf(resolvedProfiles);
        }

        public boolean isGitBacked() {
            return uri != null && !uri.isBlank();
        }
    }

    private Path repositoriesFile() {
        return configDirectory().resolve("config.json");
    }

    private Path repositoriesDirectory() {
        return repositoryStorageDirectory().resolve("repositories");
    }

    private Path configDirectory() {
        String configuredPath = System.getProperty("ocp.config.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.home"), ".config", "ocp");
    }

    private Path repositoryStorageDirectory() {
        String configuredPath = System.getProperty("ocp.cache.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return configDirectory();
    }

    private Path workingDirectory() {
        String configuredPath = System.getProperty("ocp.working.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.dir"));
    }
}
