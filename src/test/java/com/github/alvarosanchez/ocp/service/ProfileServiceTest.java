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
import java.util.Map;
import java.util.List;
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
    void getAllProfilesIgnoresRepositoriesWithoutRepositoryJson() throws IOException {
        writeConfig(List.of(new RepositoryEntry("repo-missing", "git@github.com:acme/repo-missing.git", null)));

        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        List<Profile> profiles = profileService.getAllProfiles();

        assertTrue(profiles.isEmpty());
    }

    @Test
    void useProfileMergesJsonFromParentAndChildRecursively() throws IOException {
        writeRepositoryMetadataEntries(
            "repo-local",
            List.of(
                new ProfileEntry("oca"),
                new ProfileEntry("oca-oh-my-opencode", "oca")
            )
        );
        Path repositoryDir = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-local");
        Path parentDir = repositoryDir.resolve("oca");
        Path childDir = repositoryDir.resolve("oca-oh-my-opencode");
        Files.createDirectories(parentDir);
        Files.createDirectories(childDir);
        Files.writeString(
            parentDir.resolve("opencode.json"),
            """
            {
              "some_config": "parent",
              "nested": {
                "same": "parent",
                "parent_only": "yes"
              },
              "array": [1, 2]
            }
            """
        );
        Files.writeString(
            childDir.resolve("opencode.json"),
            """
            {
              "some_config": "child",
              "nested": {
                "same": "child",
                "child_only": "ok"
              },
              "array": [3],
              "another_config": "bar"
            }
            """
        );

        writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.useProfile("oca-oh-my-opencode"));

        Path opencodeJson = Path.of(System.getProperty("ocp.opencode.config.dir")).resolve("opencode.json");
        assertTrue(Files.isSymbolicLink(opencodeJson));
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = objectMapper.readValue(Files.readString(opencodeJson), Map.class);
        assertEquals("child", merged.get("some_config"));
        assertEquals("bar", merged.get("another_config"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) merged.get("nested");
        assertEquals("child", nested.get("same"));
        assertEquals("yes", nested.get("parent_only"));
        assertEquals("ok", nested.get("child_only"));
        assertEquals(List.of(3), merged.get("array"));
    }

    @Test
    void useProfileSupportsMultiLevelInheritance() throws IOException {
        writeRepositoryMetadataEntries(
            "repo-local",
            List.of(
                new ProfileEntry("base"),
                new ProfileEntry("mid", "base"),
                new ProfileEntry("leaf", "mid")
            )
        );
        Path repositoryDir = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-local");
        Files.createDirectories(repositoryDir.resolve("base"));
        Files.createDirectories(repositoryDir.resolve("mid"));
        Files.createDirectories(repositoryDir.resolve("leaf"));
        Files.writeString(repositoryDir.resolve("base").resolve("opencode.json"), "{" + "\"base\":1," + "\"nested\":{\"base\":1}}");
        Files.writeString(repositoryDir.resolve("mid").resolve("opencode.json"), "{" + "\"mid\":2," + "\"nested\":{\"mid\":2}}" );
        Files.writeString(repositoryDir.resolve("leaf").resolve("opencode.json"), "{" + "\"leaf\":3," + "\"nested\":{\"leaf\":3}}" );

        writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.useProfile("leaf"));

        Path opencodeJson = Path.of(System.getProperty("ocp.opencode.config.dir")).resolve("opencode.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = objectMapper.readValue(Files.readString(opencodeJson), Map.class);
        assertEquals(1, merged.get("base"));
        assertEquals(2, merged.get("mid"));
        assertEquals(3, merged.get("leaf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) merged.get("nested");
        assertEquals(1, nested.get("base"));
        assertEquals(2, nested.get("mid"));
        assertEquals(3, nested.get("leaf"));
    }

    @Test
    void useProfileInheritsAndOverridesNonJsonFilesWithoutMerging() throws IOException {
        writeRepositoryMetadataEntries(
            "repo-local",
            List.of(
                new ProfileEntry("base"),
                new ProfileEntry("child", "base")
            )
        );
        Path repositoryDir = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-local");
        Path baseDir = repositoryDir.resolve("base");
        Path childDir = repositoryDir.resolve("child");
        Files.createDirectories(baseDir);
        Files.createDirectories(childDir);
        Files.writeString(baseDir.resolve("README.md"), "from-parent");
        Files.writeString(baseDir.resolve("settings.txt"), "parent");
        Files.writeString(childDir.resolve("settings.txt"), "child");

        writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.useProfile("child"));

        Path openCodeDir = Path.of(System.getProperty("ocp.opencode.config.dir"));
        assertEquals(baseDir.resolve("README.md").toAbsolutePath(), Files.readSymbolicLink(openCodeDir.resolve("README.md")));
        assertEquals(childDir.resolve("settings.txt").toAbsolutePath(), Files.readSymbolicLink(openCodeDir.resolve("settings.txt")));
    }

    @Test
    void useProfileMergesJsoncFromParentAndChildRecursively() throws IOException {
        writeRepositoryMetadataEntries(
            "repo-local",
            List.of(
                new ProfileEntry("base"),
                new ProfileEntry("child", "base")
            )
        );
        Path repositoryDir = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-local");
        Path baseDir = repositoryDir.resolve("base");
        Path childDir = repositoryDir.resolve("child");
        Files.createDirectories(baseDir);
        Files.createDirectories(childDir);
        Files.writeString(
            baseDir.resolve("opencode.jsonc"),
            """
            {
              "parent": true,
              "nested": {
                "from_parent": "yes",
                "same": "parent"
              }
            }
            """
        );
        Files.writeString(
            childDir.resolve("opencode.jsonc"),
            """
            {
              "child": true,
              "nested": {
                "same": "child"
              }
            }
            """
        );

        writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.useProfile("child"));

        Path openCodeDir = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Path resolvedJsonc = openCodeDir.resolve("opencode.jsonc");
        assertTrue(Files.notExists(openCodeDir.resolve("opencode.json")));
        assertTrue(Files.isSymbolicLink(resolvedJsonc));
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = objectMapper.readValue(Files.readString(resolvedJsonc), Map.class);
        assertEquals(true, merged.get("parent"));
        assertEquals(true, merged.get("child"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) merged.get("nested");
        assertEquals("yes", nested.get("from_parent"));
        assertEquals("child", nested.get("same"));
    }

    @Test
    void useProfileFailsWhenJsonExtensionDiffersFromParent() throws IOException {
        writeRepositoryMetadataEntries(
            "repo-local",
            List.of(
                new ProfileEntry("base"),
                new ProfileEntry("child", "base")
            )
        );
        Path repositoryDir = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-local");
        Path baseDir = repositoryDir.resolve("base");
        Path childDir = repositoryDir.resolve("child");
        Files.createDirectories(baseDir);
        Files.createDirectories(childDir);
        Files.writeString(baseDir.resolve("opencode.jsonc"), "{\"parent\":true}");
        Files.writeString(childDir.resolve("opencode.json"), "{\"child\":true}");

        writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> profileService.useProfile("child"));
        assertTrue(thrown.getMessage().contains("must use the same extension as its parent"));
    }

    @Test
    void useProfileFailsWhenParentProfileDoesNotExist() throws IOException {
        writeRepositoryMetadataEntries(
            "repo-local",
            List.of(new ProfileEntry("child", "missing"))
        );
        writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> profileService.useProfile("child"));
        assertTrue(thrown.getMessage().contains("extends unknown profile `missing`"));
    }

    @Test
    void useProfileReadsExtendsFromSnakeCaseField() throws IOException {
        Path repositoryDir = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-local");
        Files.createDirectories(repositoryDir);
        Files.writeString(
            repositoryDir.resolve("repository.json"),
            """
            {
              "profiles": [
                {"name": "base"},
                {"name": "child", "extends_from": "base"}
              ]
            }
            """
        );
        Files.createDirectories(repositoryDir.resolve("base"));
        Files.createDirectories(repositoryDir.resolve("child"));
        Files.writeString(repositoryDir.resolve("base").resolve("opencode.json"), "{\"parent\":true}");
        Files.writeString(repositoryDir.resolve("child").resolve("opencode.json"), "{\"child\":true}");

        writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.useProfile("child"));

        Path opencodeJson = Path.of(System.getProperty("ocp.opencode.config.dir")).resolve("opencode.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = objectMapper.readValue(Files.readString(opencodeJson), Map.class);
        assertEquals(true, merged.get("parent"));
        assertEquals(true, merged.get("child"));
    }

    @Test
    void useProfileFailsWhenInheritanceHasCycle() throws IOException {
        writeRepositoryMetadataEntries(
            "repo-local",
            List.of(
                new ProfileEntry("a", "b"),
                new ProfileEntry("b", "a")
            )
        );
        writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> profileService.useProfile("a"));
        assertTrue(thrown.getMessage().contains("Profile inheritance cycle detected"));
    }

    private void writeConfig(List<RepositoryEntry> repositories) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        OcpConfigFile configFile = new OcpConfigFile(new OcpConfigOptions(), repositories);
        Files.writeString(configDir.resolve("config.json"), objectMapper.writeValueAsString(configFile));
    }

    private void writeRepositoryMetadata(String repositoryName, List<String> profileNames) throws IOException {
        writeRepositoryMetadataEntries(repositoryName, profileNames.stream().map(ProfileEntry::new).toList());
    }

    private void writeRepositoryMetadataEntries(String repositoryName, List<ProfileEntry> profileEntries) throws IOException {
        Path repositoryDir = Path.of(System.getProperty("ocp.cache.dir"), "repositories", repositoryName);
        Files.createDirectories(repositoryDir);
        RepositoryConfigFile configFile = new RepositoryConfigFile(profileEntries);
        Files.writeString(repositoryDir.resolve("repository.json"), objectMapper.writeValueAsString(configFile));
    }
}
