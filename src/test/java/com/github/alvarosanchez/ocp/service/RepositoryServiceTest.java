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
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryServiceTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private RepositoryService repositoryService;
    private ObjectMapper objectMapper;
    private String previousConfigDir;
    private String previousCacheDir;
    private String previousWorkingDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        objectMapper = applicationContext.getBean(ObjectMapper.class);
        repositoryService = new RepositoryService(objectMapper, new GitRepositoryClient(new GitProcessExecutor()));
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        previousWorkingDir = System.getProperty("ocp.working.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("cache").toString());
        System.setProperty("ocp.working.dir", tempDir.resolve("workspace").toString());
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
        if (previousWorkingDir == null) {
            System.clearProperty("ocp.working.dir");
        } else {
            System.setProperty("ocp.working.dir", previousWorkingDir);
        }
    }

    @Test
    void loadReturnsEmptyWhenConfigFileDoesNotExist() {
        List<RepositoryEntry> repositories = repositoryService.load();

        assertTrue(repositories.isEmpty());
    }

    @Test
    void loadNormalizesEntriesUsingRepositoryNameAndConfiguredStorageDirectory() throws IOException {
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(
                    new RepositoryEntry(" alpha-repo ", " git@github.com:acme/alpha.git ", null),
                    new RepositoryEntry("custom", "https://github.com/acme/beta.git", null),
                    new RepositoryEntry("", "ssh://git@mycompany.com:7999/~alsansan/opencode-configs.git", null),
                    new RepositoryEntry("ignored", "  ", null)
                )
            )
        );

        List<RepositoryEntry> repositories = repositoryService.load();

        assertEquals(2, repositories.size());
        assertEquals("alpha-repo", repositories.get(0).name());
        assertEquals("git@github.com:acme/alpha.git", repositories.get(0).uri());
        assertEquals(
            repositoriesRootDirectory().resolve("alpha-repo").toString(),
            repositories.get(0).localPath()
        );
        assertEquals("custom", repositories.get(1).name());
        assertEquals("https://github.com/acme/beta.git", repositories.get(1).uri());
        assertEquals(
            repositoriesRootDirectory().resolve("custom").toString(),
            repositories.get(1).localPath()
        );
    }

    @Test
    void loadNormalizesEntriesUsingConfigDirectoryWhenCacheOverrideIsNotConfigured() throws IOException {
        String configuredCacheDir = System.getProperty("ocp.cache.dir");
        if (configuredCacheDir != null) {
            System.clearProperty("ocp.cache.dir");
        }
        try {
            writeConfig(
                new OcpConfigFile(
                    new OcpConfigOptions(),
                    List.of(new RepositoryEntry("alpha-repo", "git@github.com:acme/alpha.git", null))
                )
            );

            List<RepositoryEntry> repositories = repositoryService.load();

            assertEquals(1, repositories.size());
            assertEquals(
                Path.of(System.getProperty("ocp.config.dir"), "repositories", "alpha-repo").toString(),
                repositories.getFirst().localPath()
            );
        } finally {
            if (configuredCacheDir == null) {
                System.clearProperty("ocp.cache.dir");
            } else {
                System.setProperty("ocp.cache.dir", configuredCacheDir);
            }
        }
    }

    @Test
    void loadKeepsFileBasedEntriesWithNullUri() throws IOException {
        Path localRepository = tempDir.resolve("local-repo").toAbsolutePath();
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("local", null, localRepository.toString()))
            )
        );

        List<RepositoryEntry> repositories = repositoryService.load();

        assertEquals(1, repositories.size());
        assertEquals("local", repositories.getFirst().name());
        assertEquals(null, repositories.getFirst().uri());
        assertEquals(localRepository.normalize().toString(), repositories.getFirst().localPath());
    }

    @Test
    void loadWrapsReadErrorsAsUncheckedIOException() throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), "not-json");

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, repositoryService::load);

        assertTrue(thrown.getMessage().contains("Failed to read repository registry"));
    }

    @Test
    void listConfiguredRepositoriesIncludesUriLocalPathAndResolvedProfiles() throws IOException {
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(
                    new RepositoryEntry("repo-two", "https://github.com/acme/repo-two.git", null),
                    new RepositoryEntry("repo-one", "git@github.com:acme/repo-one.git", null)
                )
            )
        );

        writeRepositoryMetadata(
            "repo-one",
            new RepositoryConfigFile(
                List.of(
                    new ProfileEntry("beta"),
                    new ProfileEntry("alpha"),
                    new ProfileEntry("alpha")
                )
            )
        );
        writeRepositoryMetadata(
            "repo-two",
            new RepositoryConfigFile(
                List.of(new ProfileEntry("gamma"))
            )
        );

        List<RepositoryService.ConfiguredRepository> repositories = repositoryService.listConfiguredRepositories();

        assertEquals(2, repositories.size());
        assertEquals("repo-one", repositories.get(0).name());
        assertEquals("git@github.com:acme/repo-one.git", repositories.get(0).uri());
        assertEquals(
            repositoriesRootDirectory().resolve("repo-one").toString(),
            repositories.get(0).localPath()
        );
        assertEquals(List.of("alpha", "beta"), repositories.get(0).resolvedProfiles());

        assertEquals("repo-two", repositories.get(1).name());
        assertEquals("https://github.com/acme/repo-two.git", repositories.get(1).uri());
        assertEquals(
            repositoriesRootDirectory().resolve("repo-two").toString(),
            repositories.get(1).localPath()
        );
        assertEquals(List.of("gamma"), repositories.get(1).resolvedProfiles());
    }

    @Test
    void addRegistersCurrentDirectoryAsFileBasedRepository() throws IOException {
        Path workingDirectory = Path.of(System.getProperty("ocp.working.dir"));
        Files.createDirectories(workingDirectory);

        RepositoryEntry added = repositoryService.add(".", "local-repo");

        assertEquals("local-repo", added.name());
        assertEquals(null, added.uri());
        assertEquals(workingDirectory.toAbsolutePath().normalize().toString(), added.localPath());

        List<RepositoryEntry> repositories = repositoryService.load();
        assertEquals(1, repositories.size());
        assertEquals(null, repositories.getFirst().uri());
        assertEquals(workingDirectory.toAbsolutePath().normalize().toString(), repositories.getFirst().localPath());
    }

    @Test
    void createSupportsCustomLocationRelativeToWorkingDirectory() throws IOException {
        Path workingDirectory = Path.of(System.getProperty("ocp.working.dir"));
        Files.createDirectories(workingDirectory.resolve("custom-location"));

        Path created = repositoryService.create("team-repo", "team", "custom-location");

        Path expected = workingDirectory.resolve("custom-location").resolve("team-repo").toAbsolutePath().normalize();
        assertEquals(expected, created);
        assertTrue(Files.exists(created.resolve(".git")));
        assertTrue(Files.exists(created.resolve("repository.json")));
        assertTrue(Files.isDirectory(created.resolve("team")));
    }

    @Test
    void createAndAddCreatesRepositoryAndRegistersItAsFileBased() throws IOException {
        Path location = tempDir.resolve("location");
        Files.createDirectories(location);

        RepositoryEntry added = repositoryService.createAndAdd("interactive-repo", "work", location.toString());

        Path expectedLocalPath = location.resolve("interactive-repo").toAbsolutePath().normalize();
        assertEquals("interactive-repo", added.name());
        assertEquals(null, added.uri());
        assertEquals(expectedLocalPath.toString(), added.localPath());
        assertTrue(Files.exists(expectedLocalPath.resolve(".git")));
        assertTrue(Files.exists(expectedLocalPath.resolve("repository.json")));
        assertTrue(Files.isDirectory(expectedLocalPath.resolve("work")));

        List<RepositoryEntry> repositories = repositoryService.load();
        assertEquals(1, repositories.size());
        assertEquals("interactive-repo", repositories.getFirst().name());
        assertEquals(null, repositories.getFirst().uri());
        assertEquals(expectedLocalPath.toString(), repositories.getFirst().localPath());
    }

    @Test
    void createAndAddRollsBackCreatedRepositoryWhenAddFails() throws IOException {
        Path existingRepository = tempDir.resolve("existing-repository");
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("existing", null, existingRepository.toAbsolutePath().normalize().toString()))
            )
        );

        Path location = tempDir.resolve("location");
        Files.createDirectories(location);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.createAndAdd("existing", null, location.toString())
        );

        assertTrue(thrown.getMessage().contains("Repository `existing` is already configured."));
        assertTrue(Files.notExists(location.resolve("existing")));
    }

    @Test
    void inspectDeleteMarksGitRepositoryWithLocalChanges() throws IOException, InterruptedException {
        Path localClone = repositoriesRootDirectory().resolve("repo-git");
        Files.createDirectories(localClone);
        runCommand(List.of("git", "init", localClone.toString()));
        Files.writeString(localClone.resolve("dirty.txt"), "dirty");

        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-git", "git@github.com:acme/repo-git.git", localClone.toString()))
            )
        );

        RepositoryService.RepositoryDeletePreview preview = repositoryService.inspectDelete("repo-git");

        assertTrue(preview.gitBacked());
        assertTrue(preview.hasLocalChanges());
    }

    @Test
    void deleteGitRepositoryWithLocalChangesRequiresForce() throws IOException, InterruptedException {
        Path localClone = repositoriesRootDirectory().resolve("repo-git");
        Files.createDirectories(localClone);
        runCommand(List.of("git", "init", localClone.toString()));
        Files.writeString(localClone.resolve("dirty.txt"), "dirty");

        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-git", "git@github.com:acme/repo-git.git", localClone.toString()))
            )
        );

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.delete("repo-git", false, false)
        );

        assertTrue(thrown.getMessage().contains("--force"));
        assertTrue(Files.exists(localClone));
        assertEquals(1, repositoryService.load().size());
    }

    @Test
    void deleteFileBasedRepositoryKeepsLocalPathByDefault() throws IOException {
        Path localRepository = tempDir.resolve("local-repository");
        Files.createDirectories(localRepository);
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("local", null, localRepository.toString()))
            )
        );

        RepositoryEntry deleted = repositoryService.delete("local", false, false);

        assertEquals("local", deleted.name());
        assertTrue(Files.exists(localRepository));
        assertTrue(repositoryService.load().isEmpty());
    }

    @Test
    void deleteFileBasedRepositoryDeletesLocalPathWhenRequested() throws IOException {
        Path localRepository = tempDir.resolve("local-repository");
        Files.createDirectories(localRepository);
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("local", null, localRepository.toString()))
            )
        );

        RepositoryEntry deleted = repositoryService.delete("local", false, true);

        assertEquals("local", deleted.name());
        assertTrue(Files.notExists(localRepository));
        assertTrue(repositoryService.load().isEmpty());
    }

    private Path repositoriesRootDirectory() {
        String configuredCacheDir = System.getProperty("ocp.cache.dir");
        if (configuredCacheDir != null && !configuredCacheDir.isBlank()) {
            return Path.of(configuredCacheDir).resolve("repositories");
        }
        return Path.of(System.getProperty("ocp.config.dir"), "repositories");
    }

    private void writeConfig(OcpConfigFile configFile) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), objectMapper.writeValueAsString(configFile));
    }

    private void writeRepositoryMetadata(String repositoryName, RepositoryConfigFile repositoryConfigFile) throws IOException {
        Path repositoryPath = repositoriesRootDirectory().resolve(repositoryName);
        Files.createDirectories(repositoryPath);
        Files.writeString(repositoryPath.resolve("repository.json"), objectMapper.writeValueAsString(repositoryConfigFile));
    }

    private static void runCommand(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + output);
        }
    }
}
