package com.github.alvarosanchez.ocp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeCreatesMissingParentDirectoriesAndPersistsContent() throws IOException {
        Path targetFile = tempDir.resolve("nested/config/opencode.json");

        AtomicFileWriter.write(targetFile, "{\"theme\":\"dark\"}");

        assertEquals("{\"theme\":\"dark\"}", Files.readString(targetFile));
        assertFalse(hasTemporarySibling(targetFile));
    }

    @Test
    void writeReplacesExistingFileWithoutLeavingTemporaryFilesBehind() throws IOException {
        Path targetFile = tempDir.resolve("repository.json");
        Files.writeString(targetFile, "old-content");

        AtomicFileWriter.write(targetFile, "new-content");

        assertEquals("new-content", Files.readString(targetFile));
        assertFalse(hasTemporarySibling(targetFile));
    }

    private boolean hasTemporarySibling(Path targetFile) throws IOException {
        try (var siblings = Files.list(targetFile.getParent())) {
            return siblings
                .map(Path::getFileName)
                .map(Path::toString)
                .anyMatch(name -> name.startsWith(targetFile.getFileName().toString()) && name.endsWith(".tmp"));
        }
    }
}
