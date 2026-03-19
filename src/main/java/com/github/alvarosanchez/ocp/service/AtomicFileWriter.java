package com.github.alvarosanchez.ocp.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    static void write(Path targetFile, String content) throws IOException {
        Path parent = targetFile.getParent();
        if (parent == null) {
            Files.writeString(targetFile, content);
            return;
        }

        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, targetFile.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporaryFile, content);
            try {
                Files.move(
                    temporaryFile,
                    targetFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException atomicMoveFailure) {
                Files.move(temporaryFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }
}
