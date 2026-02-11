package com.github.alvarosanchez.ocp.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client for running git operations against profile repositories.
 */
@Singleton
public final class GitRepositoryClient {

    private final GitProcessExecutor processExecutor;

    /**
     * Creates a git repository client.
     *
     * @param processExecutor process launcher used to run git commands
     */
    public GitRepositoryClient(GitProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
    }

    /**
     * Clones a git repository into a local path.
     *
     * @param uri repository URI to clone
     * @param localPath local destination path for the clone
     */
    public void clone(String uri, Path localPath) {
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

    /**
     * Pulls latest changes for a local repository using fast-forward mode.
     *
     * @param localPath local repository path
     */
    public void pull(Path localPath) {
        runInRepository(localPath, "pull", List.of("git", "-C", localPath.toString(), "pull", "--ff-only", "--quiet"));
    }

    /**
     * Returns the number of commits the local repository is behind upstream.
     *
     * @param localPath local repository path
     * @return number of commits behind upstream
     */
    public int commitsBehindRemote(Path localPath) {
        runInRepository(localPath, "fetch", List.of("git", "-C", localPath.toString(), "fetch", "--quiet"));
        String output = runAndCapture(
            localPath,
            "rev-list",
            List.of("git", "-C", localPath.toString(), "rev-list", "--count", "HEAD..@{upstream}")
        );
        String trimmedOutput = output.trim();
        if (trimmedOutput.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(trimmedOutput);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Failed to parse git rev-list output for " + localPath + ": " + output, e);
        }
    }

    /**
     * Initializes a git repository in the provided local path.
     *
     * @param localPath local repository path
     */
    public void init(Path localPath) {
        try {
            Files.createDirectories(localPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create repository directory " + localPath, e);
        }
        runInRepository(localPath, "init", List.of("git", "-C", localPath.toString(), "init", "--quiet"));
    }

    private void runInRepository(Path localPath, String operation, List<String> command) {
        runAndCapture(localPath, operation, command);
    }

    private String runAndCapture(Path localPath, String operation, List<String> command) {
        try {
            Process process = processExecutor.start(command);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("git " + operation + " failed for " + localPath + " (exit code " + exitCode + ")");
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to " + operation + " git repository " + localPath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git " + operation + " for " + localPath, e);
        }
    }
}
