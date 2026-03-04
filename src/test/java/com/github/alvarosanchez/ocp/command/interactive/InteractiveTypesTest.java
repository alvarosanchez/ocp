package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InteractiveTypesTest {

    @Test
    void nodeRefFactoriesAssignExpectedKindsAndFields() {
        Path samplePath = Path.of("repo/profile/file.txt");

        NodeRef repository = NodeRef.repository("repo", samplePath);
        NodeRef profile = NodeRef.profile("repo", "profile", samplePath);
        NodeRef directory = NodeRef.directory("repo", "profile", samplePath);
        NodeRef file = NodeRef.file("repo", "profile", samplePath);

        assertEquals(NodeKind.REPOSITORY, repository.kind());
        assertNull(repository.profileName());
        assertEquals(NodeKind.PROFILE, profile.kind());
        assertEquals("profile", profile.profileName());
        assertEquals(NodeKind.DIRECTORY, directory.kind());
        assertEquals(NodeKind.FILE, file.kind());
        assertEquals(samplePath, file.path());
    }

    @Test
    void refreshOperationFactoriesSetScopeAndRepositoryName() {
        RefreshOperation single = RefreshOperation.singleRepository("repo-a");
        RefreshOperation all = RefreshOperation.allRepositories();

        assertEquals(RefreshScope.SINGLE_REPOSITORY, single.scope());
        assertEquals("repo-a", single.repositoryName());
        assertEquals(RefreshScope.ALL_REPOSITORIES, all.scope());
        assertNull(all.repositoryName());
    }
}
