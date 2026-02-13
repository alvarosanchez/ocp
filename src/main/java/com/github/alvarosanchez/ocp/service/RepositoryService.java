package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
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
            if (repository.uri().equals(uri)) {
                throw new IllegalStateException("Repository URI `" + uri + "` is already configured.");
            }
            if (repository.name().equals(repositoryName)) {
                throw new IllegalStateException("Repository `" + repositoryName + "` is already configured.");
            }
        }

        RepositoryEntry added = new RepositoryEntry(repositoryName, uri, repositoriesDirectory().resolve(repositoryName).toString());
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

            String content = objectMapper.writeValueAsString(new com.github.alvarosanchez.ocp.config.RepositoryConfigFile(profiles));
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
            String name = entry.name() == null || entry.name().isBlank() ? repositoryNameFromUri(uri) : entry.name();
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
        String normalizedUri = trimTrailingSeparators(uri);
        if (normalizedUri.isBlank()) {
            return "";
        }

        RepositoryPathInfo repositoryPathInfo = repositoryPathInfoFromUri(normalizedUri);
        if (repositoryPathInfo.segments().isEmpty()) {
            return "";
        }

        String repositorySegment = normalizeSegment(
            stripGitSuffix(repositoryPathInfo.segments().get(repositoryPathInfo.segments().size() - 1))
        );
        if (repositorySegment.isBlank()) {
            return "";
        }

        if (!repositoryPathInfo.namespaced() || repositoryPathInfo.segments().size() == 1) {
            return repositorySegment;
        }

        List<String> namespaceSegments = new ArrayList<>();
        for (int index = 0; index < repositoryPathInfo.segments().size() - 1; index++) {
            String namespaceSegment = normalizeSegment(repositoryPathInfo.segments().get(index));
            if (!namespaceSegment.isBlank()) {
                namespaceSegments.add(namespaceSegment);
            }
        }

        if (namespaceSegments.isEmpty()) {
            return repositorySegment;
        }

        return String.join("-", namespaceSegments) + "-" + repositorySegment;
    }

    private String trimTrailingSeparators(String value) {
        String normalizedValue = value == null ? "" : value.trim();
        while (normalizedValue.endsWith("/") || normalizedValue.endsWith("\\")) {
            normalizedValue = normalizedValue.substring(0, normalizedValue.length() - 1);
        }
        return normalizedValue;
    }

    private RepositoryPathInfo repositoryPathInfoFromUri(String uri) {
        if (isScpLikeUri(uri)) {
            int separator = uri.indexOf(':');
            return new RepositoryPathInfo(pathSegments(uri.substring(separator + 1)), true);
        }

        int schemeSeparator = uri.indexOf("://");
        if (schemeSeparator > 0 && isUriScheme(uri.substring(0, schemeSeparator))) {
            String scheme = uri.substring(0, schemeSeparator);
            String authorityAndPath = uri.substring(schemeSeparator + 3);
            int pathSeparator = authorityAndPath.indexOf('/');
            if (pathSeparator < 0) {
                return new RepositoryPathInfo(List.of(), false);
            }

            String path = authorityAndPath.substring(pathSeparator + 1);
            boolean namespaced = !"file".equalsIgnoreCase(scheme);
            return new RepositoryPathInfo(pathSegments(path), namespaced);
        }

        return new RepositoryPathInfo(pathSegments(uri), false);
    }

    private boolean isScpLikeUri(String uri) {
        if (uri.contains("://")) {
            return false;
        }
        if (isWindowsDrivePath(uri)) {
            return false;
        }

        int separator = uri.indexOf(':');
        if (separator <= 0 || separator == uri.length() - 1) {
            return false;
        }

        String candidateHost = uri.substring(0, separator);
        if (candidateHost.contains("/") || candidateHost.contains("\\")) {
            return false;
        }

        return true;
    }

    private boolean isWindowsDrivePath(String value) {
        return value.length() >= 3
            && Character.isLetter(value.charAt(0))
            && value.charAt(1) == ':'
            && (value.charAt(2) == '\\' || value.charAt(2) == '/');
    }

    private boolean isUriScheme(String value) {
        if (value.isBlank() || !Character.isLetter(value.charAt(0))) {
            return false;
        }

        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '+' && character != '-' && character != '.') {
                return false;
            }
        }
        return true;
    }

    private List<String> pathSegments(String path) {
        String normalizedPath = path.replace('\\', '/');
        String[] rawSegments = normalizedPath.split("/");
        List<String> segments = new ArrayList<>();
        for (String rawSegment : rawSegments) {
            String segment = rawSegment.trim();
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private String stripGitSuffix(String value) {
        return value.endsWith(".git") ? value.substring(0, value.length() - 4) : value;
    }

    private String normalizeSegment(String value) {
        String segment = value == null ? "" : value.trim();
        while (segment.startsWith("~")) {
            segment = segment.substring(1);
        }
        if (segment.isBlank()) {
            return "";
        }

        StringBuilder normalized = new StringBuilder();
        boolean lastWasDash = false;
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '-' || character == '_' || character == '.') {
                normalized.append(character);
                lastWasDash = false;
            } else if (!lastWasDash) {
                normalized.append('-');
                lastWasDash = true;
            }
        }

        String normalizedSegment = normalized.toString();
        while (normalizedSegment.startsWith("-")) {
            normalizedSegment = normalizedSegment.substring(1);
        }
        while (normalizedSegment.endsWith("-")) {
            normalizedSegment = normalizedSegment.substring(0, normalizedSegment.length() - 1);
        }
        return normalizedSegment;
    }

    private record RepositoryPathInfo(List<String> segments, boolean namespaced) {
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
