package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
            writeAtomically(metadataFile, migration.content());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to migrate repository metadata at " + metadataFile, e);
        }
    }

    private void writeAtomically(Path metadataFile, String content) throws IOException {
        Path parent = metadataFile.getParent();
        if (parent == null) {
            Files.writeString(metadataFile, content);
            return;
        }

        Path temporaryFile = Files.createTempFile(parent, metadataFile.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporaryFile, content);
            try {
                Files.move(
                    temporaryFile,
                    metadataFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException atomicMoveFailure) {
                Files.move(temporaryFile, metadataFile, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }
}
