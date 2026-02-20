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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jakarta.inject.Singleton;

/**
 * Service that manages configured profile repositories.
 */
@Singleton
public final class RepositoryService {

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
     * @param repositoryUri repository URI to add
     * @param repositoryName repository name to register
     * @return added repository entry
     */
    public RepositoryEntry add(String repositoryUri, String repositoryName) {
        String uri = repositoryUri == null ? "" : repositoryUri.trim();
        if (uri.isBlank()) {
            throw new IllegalStateException("Repository URI is required.");
        }
        String name = repositoryName == null ? "" : repositoryName.trim();
        if (name.isBlank()) {
            throw new IllegalStateException("Repository name is required.");
        }

        List<RepositoryEntry> repositories = new ArrayList<>(load());
        for (RepositoryEntry repository : repositories) {
            if (repository.uri().equals(uri)) {
                throw new IllegalStateException("Repository URI `" + uri + "` is already configured.");
            }
            if (repository.name().equals(name)) {
                throw new IllegalStateException("Repository `" + name + "` is already configured.");
            }
        }

        RepositoryEntry added = new RepositoryEntry(name, uri, repositoriesDirectory().resolve(name).toString());
        Path localPath = Path.of(added.localPath());
        if (Files.exists(localPath)) {
            deleteRecursively(localPath);
        }
        gitRepositoryClient.clone(uri, localPath);

        repositories.add(added);
        saveConfig(new OcpConfigFile(loadConfigFile().config(), repositories));

        return added;
    }

    /**
     * Deletes a configured repository and removes its local clone.
     *
     * @param repositoryName repository name to delete
     * @return deleted repository entry
     */
    public RepositoryEntry delete(String repositoryName) {
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalStateException("Repository name is required.");
        }

        List<RepositoryEntry> repositories = new ArrayList<>(load());
        RepositoryEntry deletedRepository = null;
        List<RepositoryEntry> remaining = new ArrayList<>();
        for (RepositoryEntry repository : repositories) {
            if (repository.name().equals(repositoryName)) {
                deletedRepository = repository;
                continue;
            }
            remaining.add(repository);
        }

        if (deletedRepository == null) {
            throw new IllegalStateException("Repository `" + repositoryName + "` is not configured.");
        }

        saveConfig(new OcpConfigFile(loadConfigFile().config(), remaining));
        deleteRecursively(Path.of(deletedRepository.localPath()));

        return deletedRepository;
    }

    /**
     * Creates a new repository scaffold in the working directory.
     *
     * @param repositoryName repository directory name
     * @param profileName optional initial profile name
     * @return absolute path of the created repository
     */
    public Path create(String repositoryName, String profileName) {
        if (repositoryName == null || repositoryName.isBlank()) {
            throw new IllegalStateException("Repository name is required.");
        }

        Path repositoryPath = workingDirectory().resolve(repositoryName.trim()).toAbsolutePath();
        if (Files.exists(repositoryPath)) {
            throw new IllegalStateException("Directory already exists: " + repositoryPath);
        }

        try {
            Files.createDirectories(repositoryPath);
            List<ProfileEntry> profiles = new ArrayList<>();
            if (profileName != null && !profileName.isBlank()) {
                String normalizedProfileName = profileName.trim();
                profiles.add(new ProfileEntry(normalizedProfileName));
                Files.createDirectories(repositoryPath.resolve(normalizedProfileName));
            }

            String content = objectMapper.writeValueAsString(new RepositoryConfigFile(profiles));
            Files.writeString(repositoryPath.resolve("repository.json"), content);
            gitRepositoryClient.init(repositoryPath);
            return repositoryPath;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create repository at " + repositoryPath, e);
        }
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
            String uri = entry.uri() == null ? "" : entry.uri().trim();
            if (uri.isBlank()) {
                continue;
            }
            String name = entry.name() == null ? "" : entry.name().trim();
            if (name.isBlank()) {
                continue;
            }
            Path localPath = repositoriesDirectory().resolve(name);
            normalized.add(
                new RepositoryEntry(
                    name,
                    uri,
                    localPath.toString()
                )
            );
        }
        return normalized;
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
