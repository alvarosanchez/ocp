package com.github.alvarosanchez.ocp.command.interactive;

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
    ADD_REPOSITORY,
    DELETE_REPOSITORY,
    DELETE_REPOSITORY_FORCE,
    DELETE_REPOSITORY_FILE_BASED,
    DELETE_PROFILE,
    CREATE_REPOSITORY
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
    String inheritedFromProfile
) {
    static NodeRef repository(String repositoryName, Path path) {
        return new NodeRef(NodeKind.REPOSITORY, repositoryName, null, path, false, null);
    }

    static NodeRef profile(String repositoryName, String profileName, Path path) {
        return new NodeRef(NodeKind.PROFILE, repositoryName, profileName, path, false, null);
    }

    static NodeRef directory(String repositoryName, String profileName, Path path) {
        return new NodeRef(NodeKind.DIRECTORY, repositoryName, profileName, path, false, null);
    }

    static NodeRef file(String repositoryName, String profileName, Path path) {
        return new NodeRef(NodeKind.FILE, repositoryName, profileName, path, false, null);
    }

    static NodeRef inheritedFile(
        String repositoryName,
        String profileName,
        Path path,
        String inheritedFromProfile
    ) {
        return new NodeRef(NodeKind.FILE, repositoryName, profileName, path, true, inheritedFromProfile);
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
