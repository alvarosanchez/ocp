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
    void createProfileCreatesProfileInConfiguredRepository() throws IOException {
        Path selectedRepository = tempDir.resolve("selected-repository");
        Files.createDirectories(selectedRepository);
        Files.writeString(
            selectedRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(new RepositoryConfigFile(List.of(new ProfileEntry("existing"))))
        );
        writeConfig(List.of(new RepositoryEntry("selected", null, selectedRepository.toString())));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.createProfile("new-profile", "selected"));
        assertTrue(Files.isDirectory(selectedRepository.resolve("new-profile")));

        RepositoryConfigFile metadata = objectMapper.readValue(
            Files.readString(selectedRepository.resolve("repository.json")),
            RepositoryConfigFile.class
        );
        assertEquals(List.of("existing", "new-profile"), metadata.profiles().stream().map(ProfileEntry::name).toList());
    }

    @Test
    void createProfileStoresParentWhenInheritanceIsConfigured() throws IOException {
        Path selectedRepository = tempDir.resolve("selected-repository");
        Files.createDirectories(selectedRepository);
        Files.writeString(
            selectedRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(new RepositoryConfigFile(List.of(new ProfileEntry("base"))))
        );
        writeConfig(List.of(new RepositoryEntry("selected", null, selectedRepository.toString())));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.createProfile("child", "selected", "base"));

        RepositoryConfigFile metadata = objectMapper.readValue(
            Files.readString(selectedRepository.resolve("repository.json")),
            RepositoryConfigFile.class
        );
        ProfileEntry child = metadata.profiles().stream().filter(profile -> profile.name().equals("child")).findFirst().orElseThrow();
        assertEquals("base", child.extendsFrom());
    }

    @Test
    void createProfileThrowsWhenConfiguredRepositoryDoesNotExist() {
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> profileService.createProfile("new-profile", "missing")
        );

        assertTrue(thrown.getMessage().contains("Repository `missing` was not found."));
    }

    @Test
    void createProfileThrowsWhenParentProfileIsMissing() throws IOException {
        Path selectedRepository = tempDir.resolve("selected-repository");
        Files.createDirectories(selectedRepository);
        Files.writeString(
            selectedRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(new RepositoryConfigFile(List.of(new ProfileEntry("existing"))))
        );
        writeConfig(List.of(new RepositoryEntry("selected", null, selectedRepository.toString())));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> profileService.createProfile("child", "selected", "missing-parent")
        );

        assertTrue(thrown.getMessage().contains("Parent profile `missing-parent` was not found"));
    }

    @Test
    void createProfileRejectsParentProfileNameWithPathTraversal() throws IOException {
        Path selectedRepository = tempDir.resolve("selected-repository");
        Files.createDirectories(selectedRepository);
        Files.writeString(
            selectedRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(new RepositoryConfigFile(List.of(new ProfileEntry("existing"))))
        );
        writeConfig(List.of(new RepositoryEntry("selected", null, selectedRepository.toString())));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> profileService.createProfile("child", "selected", "../base")
        );

        assertTrue(thrown.getMessage().contains("single safe path segment"));
    }

    @Test
    void createProfileRejectsRepositoryNameWithPathTraversal() throws IOException {
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> profileService.createProfile("child", "../selected")
        );

        assertTrue(thrown.getMessage().contains("single safe path segment"));
    }

    @Test
    void createProfileRejectsProfileNameWithPathTraversal() throws IOException {
        Path selectedRepository = tempDir.resolve("selected-repository");
        Files.createDirectories(selectedRepository);
        Files.writeString(
            selectedRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(new RepositoryConfigFile(List.of(new ProfileEntry("existing"))))
        );
        writeConfig(List.of(new RepositoryEntry("selected", null, selectedRepository.toString())));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> profileService.createProfile("../outside", "selected")
        );

        assertTrue(thrown.getMessage().contains("single safe path segment"));
    }

    @Test
    void listProfilesInRepositoryReturnsSortedNames() throws IOException {
        Path selectedRepository = tempDir.resolve("selected-repository");
        Files.createDirectories(selectedRepository);
        Files.writeString(
            selectedRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(
                new RepositoryConfigFile(List.of(new ProfileEntry("zeta"), new ProfileEntry("alpha"), new ProfileEntry("beta")))
            )
        );
        writeConfig(List.of(new RepositoryEntry("selected", null, selectedRepository.toString())));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        List<String> profiles = profileService.listProfilesInRepository("selected");

        assertEquals(List.of("alpha", "beta", "zeta"), profiles);
    }

    @Test
    void listResolvableProfileNamesReturnsSortedNamesAcrossRepositories() throws IOException {
        writeRepositoryMetadata("repo-one", List.of("zeta", "alpha"));
        writeRepositoryMetadata("repo-two", List.of("beta"));
        writeConfig(List.of(
            new RepositoryEntry("repo-one", "git@github.com:acme/repo-one.git", null),
            new RepositoryEntry("repo-two", "git@github.com:acme/repo-two.git", null)
        ));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        List<String> profileNames = profileService.listResolvableProfileNames();

        assertEquals(List.of("alpha", "beta", "zeta"), profileNames);
    }

    @Test
    void createProfileAllowsParentFromAnotherRepository() throws IOException {
        Path targetRepository = tempDir.resolve("target-repository");
        Files.createDirectories(targetRepository);
        Files.writeString(
            targetRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(new RepositoryConfigFile(List.of(new ProfileEntry("target-base"))))
        );

        Path sharedRepository = tempDir.resolve("shared-repository");
        Files.createDirectories(sharedRepository);
        Files.writeString(
            sharedRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(new RepositoryConfigFile(List.of(new ProfileEntry("shared-base"))))
        );

        writeConfig(List.of(
            new RepositoryEntry("target", null, targetRepository.toString()),
            new RepositoryEntry("shared", null, sharedRepository.toString())
        ));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.createProfile("child", "target", "shared-base"));

        RepositoryConfigFile targetMetadata = objectMapper.readValue(
            Files.readString(targetRepository.resolve("repository.json")),
            RepositoryConfigFile.class
        );
        ProfileEntry child = targetMetadata.profiles().stream().filter(profile -> profile.name().equals("child")).findFirst().orElseThrow();
        assertEquals("shared-base", child.extendsFrom());
    }

    @Test
    void deleteProfileRemovesProfileDirectoryAndMetadataInConfiguredRepository() throws IOException {
        Path selectedRepository = tempDir.resolve("selected-repository");
        Files.createDirectories(selectedRepository);
        Files.writeString(
            selectedRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(
                new RepositoryConfigFile(
                    List.of(
                        new ProfileEntry("keep", "Keep profile"),
                        new ProfileEntry("remove", "Remove profile")
                    )
                )
            )
        );
        Files.createDirectories(selectedRepository.resolve("keep"));
        Files.createDirectories(selectedRepository.resolve("remove"));
        writeConfig(List.of(new RepositoryEntry("selected", null, selectedRepository.toString())));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.deleteProfile("remove", "selected"));

        assertTrue(Files.isDirectory(selectedRepository.resolve("keep")));
        assertTrue(Files.notExists(selectedRepository.resolve("remove")));

        RepositoryConfigFile metadata = objectMapper.readValue(
            Files.readString(selectedRepository.resolve("repository.json")),
            RepositoryConfigFile.class
        );
        assertEquals(List.of("keep"), metadata.profiles().stream().map(ProfileEntry::name).toList());
    }

    @Test
    void deleteProfileClearsActiveProfileWhenDeletingCurrentOne() throws IOException {
        Path selectedRepository = tempDir.resolve("selected-repository");
        Files.createDirectories(selectedRepository);
        Files.writeString(
            selectedRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(new RepositoryConfigFile(List.of(new ProfileEntry("active"))))
        );
        Files.createDirectories(selectedRepository.resolve("active"));

        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions("active"),
                List.of(new RepositoryEntry("selected", null, selectedRepository.toString()))
            )
        );
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.deleteProfile("active", "selected"));
        assertEquals(null, repositoryService.loadConfigFile().config().activeProfile());
    }

    @Test
    void deleteProfileThrowsWhenProfileIsMissingInConfiguredRepository() throws IOException {
        Path selectedRepository = tempDir.resolve("selected-repository");
        Files.createDirectories(selectedRepository);
        Files.writeString(
            selectedRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(new RepositoryConfigFile(List.of(new ProfileEntry("existing"))))
        );
        writeConfig(List.of(new RepositoryEntry("selected", null, selectedRepository.toString())));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> profileService.deleteProfile("missing", "selected")
        );

        assertTrue(thrown.getMessage().contains("Profile `missing` was not found in repository `selected`."));
    }

    @Test
    void deleteProfileRejectsProfileNameWithPathSeparator() throws IOException {
        Path selectedRepository = tempDir.resolve("selected-repository");
        Files.createDirectories(selectedRepository);
        Files.writeString(
            selectedRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(new RepositoryConfigFile(List.of(new ProfileEntry("existing"))))
        );
        writeConfig(List.of(new RepositoryEntry("selected", null, selectedRepository.toString())));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> profileService.deleteProfile("nested/remove", "selected")
        );

        assertTrue(thrown.getMessage().contains("single safe path segment"));
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
        Path repositoryDir = repositoriesRootDirectory().resolve("repo-local");
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
        Path repositoryDir = repositoriesRootDirectory().resolve("repo-local");
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

    @Test
    void resolvedFilePreviewAllowsInheritedFileOutsideChildDirectory() throws IOException {
        writeRepositoryMetadata(
            "repo-local",
            new RepositoryConfigFile(
                List.of(
                    new ProfileEntry("oca-omo"),
                    new ProfileEntry("oca-omo-personal", null, "oca-omo")
                )
            )
        );
        Path repositoryDir = repositoriesRootDirectory().resolve("repo-local");
        Path parentDir = repositoryDir.resolve("oca-omo");
        Path childDir = repositoryDir.resolve("oca-omo-personal");
        Files.createDirectories(parentDir);
        Files.createDirectories(childDir);
        Path inheritedFile = parentDir.resolve("oh-my-opencode.jsonc");
        Files.writeString(inheritedFile, "{\"plugin\":\"parent\"}");

        writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        ProfileService.ResolvedFilePreview preview = profileService.resolvedFilePreview("oca-omo-personal", inheritedFile);

        assertEquals(Path.of("oh-my-opencode.jsonc"), preview.relativePath());
        assertEquals("{\"plugin\":\"parent\"}", preview.content());
        assertEquals(false, preview.deepMerged());
    }

    @Test
    void useProfileStoresMergedResolvedFilesUnderConfigDirectoryWhenCacheOverrideIsNotConfigured() throws IOException {
        String configuredCacheDir = System.getProperty("ocp.cache.dir");
        if (configuredCacheDir != null) {
            System.clearProperty("ocp.cache.dir");
        }
        try {
            writeRepositoryMetadata(
                "repo-local",
                new RepositoryConfigFile(
                    List.of(
                        new ProfileEntry("parent"),
                        new ProfileEntry("child", null, "parent")
                    )
                )
            );
            Path repositoryDir = repositoriesRootDirectory().resolve("repo-local");
            Path parentDir = repositoryDir.resolve("parent");
            Path childDir = repositoryDir.resolve("child");
            Files.createDirectories(parentDir);
            Files.createDirectories(childDir);
            Files.writeString(parentDir.resolve("opencode.json"), "{\"theme\":\"dark\"}");
            Files.writeString(childDir.resolve("opencode.json"), "{\"model\":\"gpt-5\"}");

            writeConfig(List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null)));
            profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

            assertTrue(profileService.useProfile("child"));

            Path opencodeFile = Path.of(System.getProperty("ocp.opencode.config.dir")).resolve("opencode.json");
            assertTrue(Files.isSymbolicLink(opencodeFile));
            Path target = Files.readSymbolicLink(opencodeFile);
            assertTrue(target.startsWith(Path.of(System.getProperty("ocp.config.dir"), "resolved-profiles", "child")));
        } finally {
            if (configuredCacheDir == null) {
                System.clearProperty("ocp.cache.dir");
            } else {
                System.setProperty("ocp.cache.dir", configuredCacheDir);
            }
        }
    }

    @Test
    void refreshRepositorySkipsGitOperationsForFileBasedRepository() throws IOException {
        Path localRepository = tempDir.resolve("local-file-repository");
        Files.createDirectories(localRepository);
        Files.writeString(
            localRepository.resolve("repository.json"),
            objectMapper.writeValueAsString(new RepositoryConfigFile(List.of(new ProfileEntry("work"))))
        );
        Files.createDirectories(localRepository.resolve("work"));
        Files.writeString(localRepository.resolve("work").resolve("opencode.json"), "{}");

        writeConfig(List.of(new RepositoryEntry("repo-local", null, localRepository.toString())));
        profileService = new ProfileService(objectMapper, repositoryService, gitRepositoryClient);

        assertTrue(profileService.refreshRepository("repo-local"));
        List<Profile> profiles = profileService.getAllProfiles();
        assertEquals(List.of("work"), profiles.stream().map(Profile::name).toList());
    }

    private Path repositoriesRootDirectory() {
        String configuredCacheDir = System.getProperty("ocp.cache.dir");
        if (configuredCacheDir != null && !configuredCacheDir.isBlank()) {
            return Path.of(configuredCacheDir).resolve("repositories");
        }
        return Path.of(System.getProperty("ocp.config.dir"), "repositories");
    }

    private void writeConfig(List<RepositoryEntry> repositories) throws IOException {
        writeConfig(new OcpConfigFile(new OcpConfigOptions(), repositories));
    }

    private void writeConfig(OcpConfigFile configFile) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), objectMapper.writeValueAsString(configFile));
    }

    private void writeRepositoryMetadata(String repositoryName, List<String> profileNames) throws IOException {
        writeRepositoryMetadata(
            repositoryName,
            new RepositoryConfigFile(profileNames.stream().map(ProfileEntry::new).toList())
        );
    }

    private void writeRepositoryMetadata(String repositoryName, RepositoryConfigFile configFile) throws IOException {
        Path repositoryDir = repositoriesRootDirectory().resolve(repositoryName);
        Files.createDirectories(repositoryDir);
        Files.writeString(repositoryDir.resolve("repository.json"), objectMapper.writeValueAsString(configFile));
    }
}
