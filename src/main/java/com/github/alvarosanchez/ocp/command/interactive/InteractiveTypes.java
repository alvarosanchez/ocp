package com.github.alvarosanchez.ocp.command.interactive;

import com.github.alvarosanchez.ocp.git.GitHubRepositoryClient.RepositoryVisibility;
import com.github.alvarosanchez.ocp.service.ProfileService;

import java.nio.file.Path;

enum Pane {
    TREE,
    DETAIL
}

enum NodeKind {
    REPOSITORY,
    PROFILE,
    DIRECTORY,
    FILE
}

enum PromptAction {
    ONBOARD_EXISTING_CONFIG_CONFIRM,
    ONBOARD_EXISTING_CONFIG_REPOSITORY_NAME,
    ONBOARD_EXISTING_CONFIG_PROFILE_NAME,
    CREATE_PROFILE,
    CREATE_FILE,
    ADD_REPOSITORY,
    DELETE_REPOSITORY,
    DELETE_REPOSITORY_FORCE,
    DELETE_REPOSITORY_FILE_BASED,
    COMMIT_AND_PUSH_REPOSITORY,
    DELETE_PROFILE,
    DELETE_FILE,
    CREATE_REPOSITORY,
    POST_CREATION_GIT_INIT,
    POST_CREATION_PUBLISH_GITHUB,
    POST_CREATION_GITHUB_VISIBILITY
}

record CommitConfirmState(String repositoryName, String diff) {
}

enum RefreshScope {
    SINGLE_REPOSITORY,
    ALL_REPOSITORIES
}

enum RefreshConflictKind {
    REPOSITORY,
    MERGED_FILES
}

record NodeRef(
    NodeKind kind,
    String repositoryName,
    String profileName,
    Path path,
    boolean inherited,
    String inheritedFromProfile,
    boolean deepMerged,
    boolean readOnly,
    boolean parentOnlyMerged,
    java.util.List<String> contributorProfileNames,
    java.util.List<Path> contributorSourcePaths
) {
    static NodeRef repository(String repositoryName, Path path) {
        return new NodeRef(NodeKind.REPOSITORY, repositoryName, null, path, false, null, false, false, false, java.util.List.of(), java.util.List.of());
    }

    static NodeRef profile(String repositoryName, String profileName, Path path) {
        return new NodeRef(NodeKind.PROFILE, repositoryName, profileName, path, false, null, false, false, false, java.util.List.of(), java.util.List.of());
    }

    static NodeRef directory(String repositoryName, String profileName, Path path) {
        return new NodeRef(NodeKind.DIRECTORY, repositoryName, profileName, path, false, null, false, false, false, java.util.List.of(), java.util.List.of());
    }

    static NodeRef file(String repositoryName, String profileName, Path path) {
        return new NodeRef(NodeKind.FILE, repositoryName, profileName, path, false, null, false, false, false, java.util.List.of(), java.util.List.of());
    }

    static NodeRef deepMergedFile(String repositoryName, String profileName, Path path) {
        return new NodeRef(NodeKind.FILE, repositoryName, profileName, path, false, null, true, false, false, java.util.List.of(profileName), java.util.List.of(path));
    }

    static NodeRef inheritedFile(
        String repositoryName,
        String profileName,
        Path path,
        String inheritedFromProfile
    ) {
        return new NodeRef(
            NodeKind.FILE,
            repositoryName,
            profileName,
            path,
            true,
            inheritedFromProfile,
            false,
            true,
            false,
            java.util.List.of(inheritedFromProfile),
            java.util.List.of(path)
        );
    }

    static NodeRef mergedReadOnlyFile(
        String repositoryName,
        String profileName,
        Path path,
        String inheritedFromProfile,
        java.util.List<String> contributorProfileNames,
        java.util.List<Path> contributorSourcePaths,
        boolean parentOnlyMerged
    ) {
        return new NodeRef(
            NodeKind.FILE,
            repositoryName,
            profileName,
            path,
            true,
            inheritedFromProfile,
            true,
            true,
            parentOnlyMerged,
            contributorProfileNames == null ? java.util.List.of() : java.util.List.copyOf(contributorProfileNames),
            contributorSourcePaths == null ? java.util.List.of() : java.util.List.copyOf(contributorSourcePaths)
        );
    }
}

record RefreshOperation(RefreshScope scope, String repositoryName) {
    static RefreshOperation singleRepository(String repositoryName) {
        return new RefreshOperation(RefreshScope.SINGLE_REPOSITORY, repositoryName);
    }

    static RefreshOperation allRepositories() {
        return new RefreshOperation(RefreshScope.ALL_REPOSITORIES, null);
    }
}

record RefreshConflictState(
    RefreshConflictKind kind,
    ProfileService.ProfileRefreshConflictException repositoryConflict,
    ProfileService.ProfileRefreshUserConfigConflictException mergedFilesConflict
) {
    static RefreshConflictState forRepository(ProfileService.ProfileRefreshConflictException conflict) {
        return new RefreshConflictState(RefreshConflictKind.REPOSITORY, conflict, null);
    }

    static RefreshConflictState forMergedFiles(ProfileService.ProfileRefreshUserConfigConflictException conflict) {
        return new RefreshConflictState(RefreshConflictKind.MERGED_FILES, null, conflict);
    }
}

enum PostCreationFlowSource {
    ADD_REPOSITORY,
    CREATE_REPOSITORY,
    ONBOARDING,
    MIGRATE_REPOSITORY
}

record PostCreationFlowState(
    PostCreationFlowSource source,
    String repositoryName,
    Path repositoryPath,
    String successMessage,
    boolean canInitializeGit,
    boolean initializeGit,
    boolean canPublishToGitHub,
    boolean publishToGitHub,
    RepositoryVisibility visibility
) {
    PostCreationFlowState withInitializeGit(boolean value) {
        return new PostCreationFlowState(
            source,
            repositoryName,
            repositoryPath,
            successMessage,
            canInitializeGit,
            value,
            canPublishToGitHub,
            publishToGitHub,
            visibility
        );
    }

    PostCreationFlowState withPublishToGitHub(boolean value) {
        return new PostCreationFlowState(
            source,
            repositoryName,
            repositoryPath,
            successMessage,
            canInitializeGit,
            initializeGit,
            canPublishToGitHub,
            value,
            visibility
        );
    }

    PostCreationFlowState withVisibility(RepositoryVisibility value) {
        return new PostCreationFlowState(
            source,
            repositoryName,
            repositoryPath,
            successMessage,
            canInitializeGit,
            initializeGit,
            canPublishToGitHub,
            publishToGitHub,
            value
        );
    }
}
