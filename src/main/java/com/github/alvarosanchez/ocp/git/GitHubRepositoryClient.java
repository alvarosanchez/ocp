package com.github.alvarosanchez.ocp.git;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

@Singleton
public final class GitHubRepositoryClient {

    private final GhProcessExecutor processExecutor;

    public GitHubRepositoryClient(GhProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
    }

    public boolean isAuthenticated() {
        try {
            Process process = processExecutor.start(List.of("gh", "auth", "status"));
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking GitHub CLI authentication.", e);
        }
    }

    public void createRepositoryFromSource(String repositoryName, Path localPath, RepositoryVisibility visibility) {
        String visibilityFlag = visibility == RepositoryVisibility.PUBLIC ? "--public" : "--private";
        List<String> command = List.of(
            "gh",
            "repo",
            "create",
            repositoryName,
            "--source",
            localPath.toString(),
            "--remote",
            "origin",
            "--push",
            "--confirm",
            visibilityFlag
        );
        try {
            Process process = processExecutor.start(command);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("gh repo create failed for " + repositoryName + " (exit code " + exitCode + "): " + output.trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to publish repository " + repositoryName + " with gh.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing repository " + repositoryName + " with gh.", e);
        }
    }

    public enum RepositoryVisibility {
        PRIVATE,
        PUBLIC
    }
}
