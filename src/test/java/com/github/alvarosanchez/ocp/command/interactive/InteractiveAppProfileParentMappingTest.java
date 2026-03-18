package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile.ProfileEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InteractiveAppProfileParentMappingTest {

    @Test
    void profileParentByNameReturnsTrimmedOrderedParentLists() {
        RepositoryConfigFile repositoryConfig = new RepositoryConfigFile(
            List.of(
                new ProfileEntry(null, null, List.of(" base ")),
                new ProfileEntry("   ", null, List.of("base")),
                new ProfileEntry("child", null, List.of()),
                new ProfileEntry(" child ", null, List.of(" parent-a ", "parent-b "))
            )
        );

        Map<String, List<String>> parentByName = InteractiveApp.profileParentByName("repo-a", repositoryConfig);

        assertEquals(Map.of("repo-a/child", List.of("parent-a", "parent-b")), parentByName);
    }
}
