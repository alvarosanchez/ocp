package com.github.alvarosanchez.ocp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.git.GitProcessExecutor;
import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile.ProfileEntry;
import com.github.alvarosanchez.ocp.model.Profile;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileServiceTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private ObjectMapper objectMapper;
    private ProfileService profileService;
    private String previousConfigDir;
    private String previousCacheDir;
    private String previousOpenCodeConfigDir;

    private RepositoryService repositoryService;
    private GitRepositoryClient gitRepositoryClient;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        objectMapper = applicationContext.getBean(ObjectMapper.class);
        gitRepositoryClient = new GitRepositoryClient(new GitProcessExecutor());
        repositoryService = new RepositoryService(objectMapper, gitRepositoryClient);
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        previousOpenCodeConfigDir = System.getProperty("ocp.opencode.config.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("cache").toString());
        System.setProperty("ocp.opencode.config.dir", tempDir.resolve("opencode").toString());
    }

    @AfterEach
    void tearDown() {
        applicationContext.close();
        if (previousConfigDir == null) {
            System.clearProperty("ocp.config.dir");
        } else {
            System.setProperty("ocp.config.dir", previousConfigDir);
        }
        if (previousCacheDir == null) {
            System.clearProperty("ocp.cache.dir");
        } else {
            System.setProperty("ocp.cache.dir", previousCacheDir);
        }
        if (previousOpenCodeConfigDir == null) {
            System.clearProperty("ocp.opencode.config.dir");
        } else {
            System.setProperty("ocp.opencode.config.dir", previousOpenCodeConfigDir);
        }
    }

    @Test
    void getAllProfilesReturnsSortedUniqueNames() throws IOException {
        writeRepositoryMetadata("repo-one", List.of("beta", "alpha"));
        writeRepositoryMetadata("repo-two", List.of("gamma", "alpha2"));
        writeConfig(List.of(
            new RepositoryEntry("repo-one", "git@github.com:acme/repo-one.git", null),
            new RepositoryEntry("repo-two", "git@github.com:acme/repo-two.git", null)
        ));

        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        List<Profile> profiles = profileService.getAllProfiles();

        assertEquals(List.of("alpha", "alpha2", "beta", "gamma"), profiles.stream().map(Profile::name).toList());
    }

    @Test
    void getAllProfilesThrowsWhenDuplicateNamesExistAcrossRepositories() throws IOException {
        writeRepositoryMetadata("repo-one", List.of("shared", "alpha"));
        writeRepositoryMetadata("repo-two", List.of("shared", "beta"));
        writeConfig(List.of(
            new RepositoryEntry("repo-one", "git@github.com:acme/repo-one.git", null),
            new RepositoryEntry("repo-two", "git@github.com:acme/repo-two.git", null)
        ));

        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        ProfileService.DuplicateProfilesException thrown = assertThrows(
            ProfileService.DuplicateProfilesException.class,
            profileService::getAllProfiles
        );

        assertTrue(thrown.duplicateProfileNames().contains("shared"));
    }

    @Test
    void getAllProfilesIncludesDescriptionFromRepositoryMetadata() throws IOException {
        writeRepositoryMetadata(
            "repo-one",
            new RepositoryConfigFile(List.of(new ProfileEntry("work", "Team profile")))
        );
        writeConfig(List.of(new RepositoryEntry("repo-one", "git@github.com:acme/repo-one.git", null)));

        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        List<Profile> profiles = profileService.getAllProfiles();

        assertEquals(1, profiles.size());
        assertEquals("Team profile", profiles.getFirst().description());
    }

    @Test
    void getAllProfilesIgnoresRepositoriesWithoutRepositoryJson() throws IOException {
        writeConfig(List.of(new RepositoryEntry("repo-missing", "git@github.com:acme/repo-missing.git", null)));

        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        List<Profile> profiles = profileService.getAllProfiles();

        assertTrue(profiles.isEmpty());
    }

    @Test
    void useProfileMergesSharedJsonAndKeepsParentOnlyJson() throws IOException {
        writeRepositoryMetadata(
            "repo-local",
            new RepositoryConfigFile(
                List.of(
                    new ProfileEntry("oca"),
                    new ProfileEntry("oca-oh-my-opencode", null, "oca")
                )
            )
        );
        Path repositoryDir = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-local");
        Path parentDir = repositoryDir.resolve("oca");
        Path childDir = repositoryDir.resolve("oca-oh-my-opencode");
        Files.createDirectories(parentDir);
        Files.createDirectories(childDir);
        Files.writeString(
            parentDir.resolve("opencode.json"),
            "{\"some_config\":\"parent\",\"some_other_config\":\"foo\"}"
        );
        Files.writeString(parentDir.resolve("oh-my-opencode.json"), "{\"plugin\":\"from-parent\"}");
        Files.writeString(
            childDir.resolve("opencode.json"),
            "{\"some_config\":\"child\",\"another_config\":\"bar\"}"
        );

        writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.useProfile("oca-oh-my-opencode"));

        Path openCodeDir = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Path opencodeFile = openCodeDir.resolve("opencode.json");
        Path ohMyFile = openCodeDir.resolve("oh-my-opencode.json");
        assertTrue(Files.isSymbolicLink(opencodeFile));
        assertTrue(Files.isSymbolicLink(ohMyFile));
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = objectMapper.readValue(Files.readString(opencodeFile), Map.class);
        assertEquals("child", merged.get("some_config"));
        assertEquals("foo", merged.get("some_other_config"));
        assertEquals("bar", merged.get("another_config"));
        assertEquals(parentDir.resolve("oh-my-opencode.json").toAbsolutePath(), Files.readSymbolicLink(ohMyFile));
    }

    @Test
    void useProfileMergesJsoncWhenChildContainsComments() throws IOException {
        writeRepositoryMetadata(
            "repo-local",
            new RepositoryConfigFile(
                List.of(
                    new ProfileEntry("oca"),
                    new ProfileEntry("oca-personal", null, "oca")
                )
            )
        );
        Path repositoryDir = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-local");
        Path parentDir = repositoryDir.resolve("oca");
        Path childDir = repositoryDir.resolve("oca-personal");
        Files.createDirectories(parentDir);
        Files.createDirectories(childDir);
        Files.writeString(parentDir.resolve("opencode.jsonc"), "{\"theme\":\"dark\",\"plugin\":[\"oh-my-opencode\"]}");
        Files.writeString(
            childDir.resolve("opencode.jsonc"),
            """
            {
              "plugin": [
                "oh-my-opencode",
                "@simonwjackson/opencode-direnv",
                "file:///Users/alvaro/Dev/numman-ali/opencode-openai-codex-auth/dist"
                // "opencode-openai-codex-auth"
              ]
            }
            """
        );

        writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.useProfile("oca-personal"));

        Path opencodeFile = Path.of(System.getProperty("ocp.opencode.config.dir")).resolve("opencode.jsonc");
        assertTrue(Files.isSymbolicLink(opencodeFile));
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = objectMapper.readValue(Files.readString(opencodeFile), Map.class);
        assertEquals("dark", merged.get("theme"));
        assertEquals(
            List.of(
                "oh-my-opencode",
                "@simonwjackson/opencode-direnv",
                "file:///Users/alvaro/Dev/numman-ali/opencode-openai-codex-auth/dist"
            ),
            merged.get("plugin")
        );
    }

    private void writeConfig(List<RepositoryEntry> repositories) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        OcpConfigFile configFile = new OcpConfigFile(new OcpConfigOptions(), repositories);
        Files.writeString(configDir.resolve("config.json"), objectMapper.writeValueAsString(configFile));
    }

    private void writeRepositoryMetadata(String repositoryName, List<String> profileNames) throws IOException {
        writeRepositoryMetadata(
            repositoryName,
            new RepositoryConfigFile(profileNames.stream().map(ProfileEntry::new).toList())
        );
    }

    private void writeRepositoryMetadata(String repositoryName, RepositoryConfigFile configFile) throws IOException {
        Path repositoryDir = Path.of(System.getProperty("ocp.cache.dir"), "repositories", repositoryName);
        Files.createDirectories(repositoryDir);
        Files.writeString(repositoryDir.resolve("repository.json"), objectMapper.writeValueAsString(configFile));
    }
}
