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
        System.setProperty("ocp.config.dir", tempDir.resolve("config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("cache").toString());
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
