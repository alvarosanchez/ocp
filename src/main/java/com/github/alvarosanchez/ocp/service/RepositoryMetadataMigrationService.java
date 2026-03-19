package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
public final class RepositoryMetadataMigrationService {

    private final RepositoryService repositoryService;
    private final ObjectMapper objectMapper;

    RepositoryMetadataMigrationService(RepositoryService repositoryService, ObjectMapper objectMapper) {
        this.repositoryService = repositoryService;
        this.objectMapper = objectMapper;
    }

    public void migrateLegacyExtendsFromScalars() {
        for (var repositoryEntry : repositoryService.load()) {
            Path metadataFile = Path.of(repositoryEntry.localPath()).resolve("repository.json");
            migrateLegacyMetadata(metadataFile);
        }
    }

    private void migrateLegacyMetadata(Path metadataFile) {
        if (!Files.exists(metadataFile) || !Files.isRegularFile(metadataFile)) {
            return;
        }

        try {
            String content = Files.readString(metadataFile);
            RepositoryConfigFile.LegacyExtendsFromMigration migration = RepositoryConfigFile
                .normalizeLegacyExtendsFromScalars(content, objectMapper);
            if (!migration.migrated()) {
                return;
            }
            AtomicFileWriter.write(metadataFile, migration.content());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to migrate repository metadata at " + metadataFile, e);
        }
    }
}
