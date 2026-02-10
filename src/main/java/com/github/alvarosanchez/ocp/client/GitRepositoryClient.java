package com.github.alvarosanchez.ocp.client;

import java.io.IOException;
import java.util.List;
import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
final class GitRepositoryClient {

    private final GitProcessExecutor processExecutor;

    GitRepositoryClient(GitProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
    }

    void clone(String uri, Path localPath) {
        try {
            Path parent = localPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Process process = processExecutor.start(List.of("git", "clone", "--quiet", uri, localPath.toString()));
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("git clone failed for " + uri + " (exit code " + exitCode + ")");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clone git repository " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while cloning git repository " + uri, e);
        }
    }
}
