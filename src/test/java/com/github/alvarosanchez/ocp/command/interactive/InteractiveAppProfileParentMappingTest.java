package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile.ProfileEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InteractiveAppProfileParentMappingTest {

    @Test
    void profileParentByNameSkipsBlankNamesAndTrimsValues() {
        RepositoryConfigFile repositoryConfig = new RepositoryConfigFile(
            List.of(
                new ProfileEntry(null, null, " base "),
                new ProfileEntry("   ", null, "base"),
                new ProfileEntry("child", null, "   "),
                new ProfileEntry(" child ", null, " parent ")
            )
        );

        Map<String, String> parentByName = InteractiveApp.profileParentByName("repo-a", repositoryConfig);

        assertEquals(Map.of("repo-a/child", "parent"), parentByName);
    }
}
