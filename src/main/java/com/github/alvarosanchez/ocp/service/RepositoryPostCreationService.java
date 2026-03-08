package com.github.alvarosanchez.ocp.service;

import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.git.GitHubRepositoryClient;
import com.github.alvarosanchez.ocp.git.GitHubRepositoryClient.RepositoryVisibility;
import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
public final class RepositoryPostCreationService {

    private static final String ORIGIN_REMOTE_NAME = "origin";
    private static final String INITIAL_COMMIT_MESSAGE = "chore: initial commit";

    private final GitRepositoryClient gitRepositoryClient;
    private final GitHubRepositoryClient gitHubRepositoryClient;
    private final RepositoryService repositoryService;

    RepositoryPostCreationService(
        GitRepositoryClient gitRepositoryClient,
        GitHubRepositoryClient gitHubRepositoryClient,
        RepositoryService repositoryService
    ) {
        this.gitRepositoryClient = gitRepositoryClient;
        this.gitHubRepositoryClient = gitHubRepositoryClient;
        this.repositoryService = repositoryService;
    }

    public PostCreationCapabilities capabilities(Path repositoryPath) {
        boolean gitInitialized = Files.exists(repositoryPath.resolve(".git"));
        boolean hasOriginRemote = gitInitialized && gitRepositoryClient.hasRemote(repositoryPath, ORIGIN_REMOTE_NAME);
        boolean canPublishWithGh = !hasOriginRemote && gitHubRepositoryClient.isAuthenticated();
        return new PostCreationCapabilities(gitInitialized, hasOriginRemote, canPublishWithGh);
    }

    public String persistExistingOrigin(String repositoryName, Path repositoryPath) {
        if (!gitRepositoryClient.hasRemote(repositoryPath, ORIGIN_REMOTE_NAME)) {
            throw new IllegalStateException("Repository `" + repositoryName + "` does not have an `origin` remote.");
        }
        String originUri = gitRepositoryClient.remoteUri(repositoryPath, ORIGIN_REMOTE_NAME);
        persistRepositoryUri(
            repositoryName,
            originUri,
            "Repository `" + repositoryName + "` already has origin `" + originUri + "`, but failed to update the OCP registry."
        );
        return originUri;
    }

    public PostCreationResult run(String repositoryName, Path repositoryPath, PostCreationRequest request) {
        boolean initializedGit = false;
        boolean publishedToGitHub = false;
        String persistedRepositoryUri = null;

        boolean gitInitialized = Files.exists(repositoryPath.resolve(".git"));
        if (request.initializeGit() && !gitInitialized) {
            gitRepositoryClient.init(repositoryPath);
            gitRepositoryClient.createInitialCommit(repositoryPath, INITIAL_COMMIT_MESSAGE);
            gitInitialized = true;
            initializedGit = true;
        }

        boolean hasOriginRemote = request.publishToGitHub()
            && gitInitialized
            && gitRepositoryClient.hasRemote(repositoryPath, ORIGIN_REMOTE_NAME);
        if (request.publishToGitHub() && gitInitialized && !hasOriginRemote) {
            gitHubRepositoryClient.createRepositoryFromSource(repositoryName, repositoryPath, request.visibility());
            String originUri = gitRepositoryClient.remoteUri(repositoryPath, ORIGIN_REMOTE_NAME);
            persistRepositoryUri(
                repositoryName,
                originUri,
                "Published repository `"
                    + repositoryName
                    + "` to GitHub as `"
                    + originUri
                    + "`, but failed to update the OCP registry."
            );
            persistedRepositoryUri = originUri;
            publishedToGitHub = true;
        }

        return new PostCreationResult(initializedGit, publishedToGitHub, persistedRepositoryUri);
    }

    public record PostCreationCapabilities(boolean gitInitialized, boolean hasOriginRemote, boolean canPublishWithGh) {
    }

    public record PostCreationRequest(boolean initializeGit, boolean publishToGitHub, RepositoryVisibility visibility) {
    }

    public record PostCreationResult(boolean initializedGit, boolean publishedToGitHub, String persistedRepositoryUri) {
    }

    private void persistRepositoryUri(String repositoryName, String originUri, String errorMessage) {
        try {
            repositoryService.setRepositoryUri(repositoryName, originUri);
        } catch (RuntimeException e) {
            throw new IllegalStateException(errorMessage, e);
        }
    }
}
