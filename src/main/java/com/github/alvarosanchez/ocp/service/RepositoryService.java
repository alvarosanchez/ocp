package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import com.github.alvarosanchez.ocp.model.OcpConfigFile;
import com.github.alvarosanchez.ocp.model.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.model.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.model.RepositoryConfigFile.ProfileEntry;
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
     * Adds a repository to the registry and clones it locally.
     *
     * @param repositoryUri repository URI to add
     * @return added repository entry
     */
    public RepositoryEntry add(String repositoryUri) {
        String uri = repositoryUri == null ? "" : repositoryUri.trim();
        if (uri.isBlank()) {
            throw new IllegalStateException("Repository URI is required.");
        }
        String repositoryName = repositoryNameFromUri(uri);
        if (repositoryName.isBlank()) {
            throw new IllegalStateException("Unable to derive repository name from URI: " + uri);
        }

        List<RepositoryEntry> repositories = new ArrayList<>(load());
        for (RepositoryEntry repository : repositories) {
            if (repository.name().equals(repositoryName)) {
                throw new IllegalStateException("Repository `" + repositoryName + "` is already configured.");
            }
        }

        RepositoryEntry added = new RepositoryEntry(repositoryName, uri, repositoriesDirectory().resolve(repositoryName).toString());
        gitRepositoryClient.clone(uri, Path.of(added.localPath()));

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

            String content = objectMapper.writeValueAsString(new com.github.alvarosanchez.ocp.model.RepositoryConfigFile(profiles));
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
            return new OcpConfigFile(new OcpConfigOptions(true), List.of());
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
            String name = entry.name() == null || entry.name().isBlank() ? repositoryNameFromUri(uri) : entry.name();
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

    private String repositoryNameFromUri(String uri) {
        String normalizedUri = uri;
        while (normalizedUri.endsWith("/") || normalizedUri.endsWith("\\")) {
            normalizedUri = normalizedUri.substring(0, normalizedUri.length() - 1);
        }
        int separator = Math.max(normalizedUri.lastIndexOf('/'), normalizedUri.lastIndexOf(':'));
        String raw = separator >= 0 ? normalizedUri.substring(separator + 1) : normalizedUri;
        return raw.endsWith(".git") ? raw.substring(0, raw.length() - 4) : raw;
    }

    private Path repositoriesFile() {
        return configDirectory().resolve("config.json");
    }

    private Path repositoriesDirectory() {
        return cacheDirectory().resolve("repositories");
    }

    private Path configDirectory() {
        String configuredPath = System.getProperty("ocp.config.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.home"), ".config", "ocp");
    }

    private Path cacheDirectory() {
        String configuredPath = System.getProperty("ocp.cache.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.home"), ".cache", "ocp");
    }

    private Path workingDirectory() {
        String configuredPath = System.getProperty("ocp.working.dir");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.dir"));
    }
}
