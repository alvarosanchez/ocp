package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.model.OcpConfigFile;
import com.github.alvarosanchez.ocp.model.RepositoryEntry;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jakarta.inject.Singleton;

@Singleton
final class RepositoryService {

    private final ObjectMapper objectMapper;

    RepositoryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<RepositoryEntry> load() {
        Path file = repositoriesFile();
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            String content = Files.readString(file);
            OcpConfigFile configFile = objectMapper.readValue(content, OcpConfigFile.class);
            return normalizeEntries(configFile.repositories());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read repository registry", e);
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

    private String repositoryNameFromUri(String uri) {
        int separator = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf(':'));
        String raw = separator >= 0 ? uri.substring(separator + 1) : uri;
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
}
