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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
    void loadPreservesExplicitLocalPathWhenUriIsConfigured() throws IOException {
        Path explicitPath = tempDir.resolve("explicit-path").toAbsolutePath().normalize();
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("alpha-repo", "git@github.com:acme/alpha.git", explicitPath.toString()))
            )
        );

        List<RepositoryEntry> repositories = repositoryService.load();

        assertEquals(1, repositories.size());
        assertEquals(explicitPath.toString(), repositories.getFirst().localPath());
    }

    @Test
    void loadResolvesRelativeExplicitLocalPathAgainstWorkingDirectoryWhenUriIsConfigured() throws IOException {
        Path workingDirectory = Path.of(System.getProperty("ocp.working.dir")).toAbsolutePath().normalize();
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("alpha-repo", "git@github.com:acme/alpha.git", "relative/repo-path"))
            )
        );

        List<RepositoryEntry> repositories = repositoryService.load();

        assertEquals(1, repositories.size());
        assertEquals(workingDirectory.resolve("relative/repo-path").normalize().toString(), repositories.getFirst().localPath());
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
    void loadResolvesRelativeFileBasedLocalPathAgainstWorkingDirectory() throws IOException {
        Path workingDirectory = Path.of(System.getProperty("ocp.working.dir")).toAbsolutePath().normalize();
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("local", null, "relative/local-repo"))
            )
        );

        List<RepositoryEntry> repositories = repositoryService.load();

        assertEquals(1, repositories.size());
        assertEquals(workingDirectory.resolve("relative/local-repo").normalize().toString(), repositories.getFirst().localPath());
    }

    @Test
    void loadRejectsRepositoryEntryWithUnsafeName() throws IOException {
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("../repo", "git@github.com:acme/repo.git", null))
            )
        );

        IllegalStateException thrown = assertThrows(IllegalStateException.class, repositoryService::load);

        assertTrue(thrown.getMessage().contains("single safe path segment"));
    }

    @Test
    void loadNormalizesGitRepositoryLocalPathToAbsoluteWhenCacheDirIsRelative() throws IOException {
        String previousCacheDir = System.getProperty("ocp.cache.dir");
        Path relativeCacheDirectory = tempDir.resolve("relative-cache-dir");
        System.setProperty("ocp.cache.dir", relativePathFromProjectRoot(relativeCacheDirectory));
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
                relativeCacheDirectory.resolve("repositories").resolve("alpha-repo").toAbsolutePath().normalize().toString(),
                repositories.getFirst().localPath()
            );
        } finally {
            if (previousCacheDir == null) {
                System.clearProperty("ocp.cache.dir");
            } else {
                System.setProperty("ocp.cache.dir", previousCacheDir);
            }
        }
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
        assertTrue(Files.notExists(created.resolve(".git")));
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
        assertTrue(Files.notExists(expectedLocalPath.resolve(".git")));
        assertTrue(Files.exists(expectedLocalPath.resolve("repository.json")));
        assertTrue(Files.isDirectory(expectedLocalPath.resolve("work")));

        List<RepositoryEntry> repositories = repositoryService.load();
        assertEquals(1, repositories.size());
        assertEquals("interactive-repo", repositories.getFirst().name());
        assertEquals(null, repositories.getFirst().uri());
        assertEquals(expectedLocalPath.toString(), repositories.getFirst().localPath());
    }

    @Test
    void setRepositoryUriUpdatesUriAndPreservesExplicitLocalPath() throws IOException {
        Path localRepository = tempDir.resolve("local-repository").toAbsolutePath().normalize();
        Files.createDirectories(localRepository);
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("local", null, localRepository.toString()))
            )
        );

        RepositoryEntry updated = repositoryService.setRepositoryUri("local", "git@github.com:acme/local.git");

        assertEquals("local", updated.name());
        assertEquals("git@github.com:acme/local.git", updated.uri());
        assertEquals(localRepository.toString(), updated.localPath());
    }

    @Test
    void setRepositoryUriPreservesUnrelatedRawEntries() throws IOException {
        Path localRepository = tempDir.resolve("local-repository").toAbsolutePath().normalize();
        Files.createDirectories(localRepository);
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(
                    new RepositoryEntry("local", null, localRepository.toString()),
                    new RepositoryEntry("  spaced-name  ", null, "relative/path")
                )
            )
        );

        repositoryService.setRepositoryUri("local", "git@github.com:acme/local.git");

        OcpConfigFile stored = objectMapper.readValue(Files.readString(tempDir.resolve("config/config.json")), OcpConfigFile.class);
        assertEquals(2, stored.repositories().size());
        assertEquals(new RepositoryEntry("local", "git@github.com:acme/local.git", localRepository.toString()), stored.repositories().get(0));
        assertEquals(new RepositoryEntry("  spaced-name  ", null, "relative/path"), stored.repositories().get(1));
    }

    @Test
    void setRepositoryUriReturnsNormalizedEntryWhenStoredNameContainsWhitespace() throws IOException {
        Path localRepository = tempDir.resolve("local-repository").toAbsolutePath().normalize();
        Files.createDirectories(localRepository);
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("  local  ", null, localRepository.toString()))
            )
        );

        RepositoryEntry updated = repositoryService.setRepositoryUri("local", "git@github.com:acme/local.git");

        assertEquals("local", updated.name());
        assertEquals("git@github.com:acme/local.git", updated.uri());
        assertEquals(localRepository.toString(), updated.localPath());
    }

    @Test
    void createAndAddRollsBackCreatedRepositoryWhenAddFails() throws IOException {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
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
    void createAndAddRollsBackCreatedRepositoryWhenCreateFailsAfterDirectoryCreation() throws IOException {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        Path location = tempDir.resolve("location");
        Files.createDirectories(location);

        RepositoryService failingRepositoryService = new RepositoryService(
            objectMapperFailingOnRepositoryConfigWrite(),
            new GitRepositoryClient(new GitProcessExecutor())
        );

        UncheckedIOException thrown = assertThrows(
            UncheckedIOException.class,
            () -> failingRepositoryService.createAndAdd("interactive-repo", "work", location.toString())
        );

        assertTrue(thrown.getMessage().contains("Failed to create repository at"));
        assertTrue(Files.notExists(location.resolve("interactive-repo")));
    }

    @Test
    void addTreatsMissingRelativePathAsLocalPathAndFailsEarly() {
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.add("missing-local-repo", "missing-local")
        );

        assertTrue(thrown.getMessage().contains("Local repository path does not exist"));
    }

    @Test
    void addRejectsRepositoryNameWithPathTraversal() {
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.add("git@github.com:acme/repo.git", "../evil")
        );

        assertTrue(thrown.getMessage().contains("single safe path segment"));
    }

    @Test
    void addRejectsRepositoryNameWithWindowsInvalidCharacter() {
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.add("git@github.com:acme/repo.git", "bad:name")
        );

        assertTrue(thrown.getMessage().contains("single safe path segment"));
    }

    @Test
    void addTreatsSingleBackslashPathAsLocalPathAndFailsEarly() {
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.add("dir\\subdir", "local-repo")
        );

        assertTrue(thrown.getMessage().contains("Local repository path does not exist"));
    }

    @Test
    void addTreatsDriveRelativeWindowsPathAsLocalPathAndFailsEarly() {
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.add("C:repo\\dir", "local-repo")
        );

        assertTrue(thrown.getMessage().contains("Local repository path does not exist"));
    }

    @Test
    void addStoresAbsoluteGitRepositoryLocalPathWhenCacheDirIsRelative() throws IOException, InterruptedException {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        String previousCacheDir = System.getProperty("ocp.cache.dir");
        Path relativeCacheDirectory = tempDir.resolve("relative-cache-dir");
        System.setProperty("ocp.cache.dir", relativePathFromProjectRoot(relativeCacheDirectory));
        try {
            Path remote = createRemoteRepository();

            RepositoryEntry added = repositoryService.add(remote.toUri().toString(), "alpha-repo");

            String expectedLocalPath = relativeCacheDirectory.resolve("repositories").resolve("alpha-repo").toAbsolutePath().normalize().toString();
            assertEquals(expectedLocalPath, added.localPath());

            List<RepositoryEntry> repositories = repositoryService.load();
            assertEquals(expectedLocalPath, repositories.getFirst().localPath());
        } finally {
            if (previousCacheDir == null) {
                System.clearProperty("ocp.cache.dir");
            } else {
                System.setProperty("ocp.cache.dir", previousCacheDir);
            }
        }
    }

    @Test
    void createRejectsRepositoryNameWithPathSeparator() {
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.create("nested/repo", null)
        );

        assertTrue(thrown.getMessage().contains("single safe path segment"));
    }

    @Test
    void createRejectsInitialProfileNameWithPathTraversal() {
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.create("repo", "../evil")
        );

        assertTrue(thrown.getMessage().contains("single safe path segment"));
    }

    @Test
    void deleteRejectsRepositoryNameWithPathTraversal() {
        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.delete("../local", false, false)
        );

        assertTrue(thrown.getMessage().contains("single safe path segment"));
    }

    @Test
    void inspectDeleteMarksGitRepositoryWithLocalChanges() throws IOException, InterruptedException {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
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
    void inspectCommitPushMarksGitRepositoryWithLocalChanges() throws IOException, InterruptedException {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        Path localClone = repositoriesRootDirectory().resolve("repo-git-dirty");
        Files.createDirectories(localClone);
        runCommand(List.of("git", "init", localClone.toString()));
        Files.writeString(localClone.resolve("dirty.txt"), "dirty");

        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-git-dirty", "git@github.com:acme/repo-git-dirty.git", localClone.toString()))
            )
        );

        RepositoryService.RepositoryCommitPushPreview preview = repositoryService.inspectCommitPush("repo-git-dirty");

        assertTrue(preview.gitBacked());
        assertTrue(preview.hasLocalChanges());
    }

    @Test
    void getLocalDiffReturnsDiffForGitBackedRepository() throws IOException {
        Path localClone = repositoriesRootDirectory().resolve("repo-diff");
        Files.createDirectories(localClone.resolve(".git"));
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-diff", "git@github.com:acme/repo-diff.git", localClone.toString()))
            )
        );
        String fakeDiff = "diff --git a/test.txt b/test.txt\n+hello world";
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(
            List.of(new StubProcess(0, fakeDiff))
        );
        RepositoryService service = new RepositoryService(objectMapper, new GitRepositoryClient(processExecutor));

        String diff = service.getLocalDiff("repo-diff");

        assertEquals(fakeDiff, diff);
        assertEquals(
            List.of(List.of("git", "-c", "color.ui=always", "-C", localClone.toString(), "diff", "--color=always")),
            processExecutor.commands()
        );
    }

    @Test
    void getLocalDiffRejectsFileBasedRepository() throws IOException {
        Path localClone = repositoriesRootDirectory().resolve("repo-local");
        Files.createDirectories(localClone);
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-local", null, localClone.toString()))
            )
        );

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.getLocalDiff("repo-local")
        );

        assertTrue(thrown.getMessage().contains("file-based"));
    }

    @Test
    void commitAndPushRunsGitStatusAddCommitAndPush() throws IOException {
        Path localClone = repositoriesRootDirectory().resolve("repo-commit-push");
        Files.createDirectories(localClone.resolve(".git"));
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-commit-push", "git@github.com:acme/repo-commit-push.git", localClone.toString()))
            )
        );
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(
            List.of(
                new StubProcess(0, " M opencode.json\n"),
                new StubProcess(0, ""),
                new StubProcess(0, ""),
                new StubProcess(0, "")
            )
        );
        RepositoryService service = new RepositoryService(objectMapper, new GitRepositoryClient(processExecutor));

        service.commitAndPush("repo-commit-push", "chore: save local changes");

        assertEquals(
            List.of(
                List.of("git", "-C", localClone.toString(), "status", "--porcelain"),
                List.of("git", "-C", localClone.toString(), "add", "-A"),
                List.of(
                    "git",
                    "-C",
                    localClone.toString(),
                    "-c",
                    "user.email=ocp@local",
                    "-c",
                    "user.name=ocp",
                    "commit",
                    "-m",
                    "chore: save local changes"
                ),
                List.of("git", "-C", localClone.toString(), "push")
            ),
            processExecutor.commands()
        );
    }

    @Test
    void inspectCommitPushEntryUsesProvidedEntryWithoutReloadingConfig() throws IOException {
        Path localClone = repositoriesRootDirectory().resolve("repo-preview");
        Files.createDirectories(localClone.resolve(".git"));
        RecordingGitProcessExecutor processExecutor = new RecordingGitProcessExecutor(List.of(new StubProcess(0, " M opencode.json\n")));
        RepositoryService service = new RepositoryService(objectMapper, new GitRepositoryClient(processExecutor));

        RepositoryService.RepositoryCommitPushPreview preview = service.inspectCommitPush(
            new RepositoryEntry("repo-preview", "git@github.com:acme/repo-preview.git", localClone.toString())
        );

        assertEquals("repo-preview", preview.name());
        assertTrue(preview.gitBacked());
        assertTrue(preview.hasLocalChanges());
        assertEquals(
            List.of(List.of("git", "-C", localClone.toString(), "status", "--porcelain")),
            processExecutor.commands()
        );
    }

    @Test
    void commitAndPushRejectsBlankCommitMessage() throws IOException {
        Path localClone = repositoriesRootDirectory().resolve("repo-blank-message");
        Files.createDirectories(localClone.resolve(".git"));
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-blank-message", "git@github.com:acme/repo-blank-message.git", localClone.toString()))
            )
        );

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.commitAndPush("repo-blank-message", "   ")
        );

        assertTrue(thrown.getMessage().contains("Commit message is required"));
    }

    @Test
    void commitAndPushRejectsMissingLocalGitCheckout() throws IOException {
        Path missingClone = repositoriesRootDirectory().resolve("repo-missing-checkout");
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-missing-checkout", "git@github.com:acme/repo-missing-checkout.git", missingClone.toString()))
            )
        );

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.commitAndPush("repo-missing-checkout", "chore: save local changes")
        );

        assertTrue(thrown.getMessage().contains("is not available as a local git checkout"));
    }

    @Test
    void deleteGitRepositoryWithLocalChangesRequiresForce() throws IOException, InterruptedException {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
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
        Files.writeString(localRepository.resolve("repository.json"), objectMapper.writeValueAsString(new RepositoryConfigFile(List.of())));
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

    @Test
    void deleteFileBasedRepositoryRejectsDeletingDirectoryWithoutRepositoryMetadata() throws IOException {
        Path localRepository = tempDir.resolve("local-repository");
        Files.createDirectories(localRepository);
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("local", null, localRepository.toString()))
            )
        );

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.delete("local", false, true)
        );

        assertTrue(thrown.getMessage().contains("repository.json"));
        assertTrue(Files.exists(localRepository));
        assertEquals(1, repositoryService.load().size());
    }

    @Test
    void deleteFileBasedRepositoryRejectsDeletingHomeDirectory() throws IOException {
        Path homeDirectory = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        writeConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("local", null, homeDirectory.toString()))
            )
        );

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> repositoryService.delete("local", false, true)
        );

        assertTrue(thrown.getMessage().contains("Refusing to delete home directory"));
        assertEquals(1, repositoryService.load().size());
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

    private String relativePathFromProjectRoot(Path target) {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Assumptions.assumeTrue(
            projectRoot.getRoot() == null
                ? absoluteTarget.getRoot() == null
                : projectRoot.getRoot().equals(absoluteTarget.getRoot()),
            "Project root and target are on different filesystem roots; cannot relativize safely"
        );
        return projectRoot.relativize(absoluteTarget).toString();
    }

    private ObjectMapper objectMapperFailingOnRepositoryConfigWrite() {
        InvocationHandler handler = (proxy, method, args) -> {
            if (
                method.getName().equals("writeValueAsString")
                    && args != null
                    && args.length == 1
                    && args[0] instanceof RepositoryConfigFile
            ) {
                throw new IOException("Injected repository config write failure");
            }
            try {
                return method.invoke(objectMapper, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (ObjectMapper) Proxy.newProxyInstance(
            ObjectMapper.class.getClassLoader(),
            new Class<?>[] {ObjectMapper.class},
            handler
        );
    }

    private Path createRemoteRepository() throws IOException, InterruptedException {
        Path seedRepository = tempDir.resolve("seed");
        runCommand(List.of("git", "init", seedRepository.toString()));
        Files.writeString(seedRepository.resolve("repository.json"), objectMapper.writeValueAsString(new RepositoryConfigFile(List.of())));
        runCommand(List.of("git", "-C", seedRepository.toString(), "add", "repository.json"));
        runCommand(List.of(
            "git",
            "-C",
            seedRepository.toString(),
            "-c",
            "user.email=test@example.com",
            "-c",
            "user.name=test",
            "commit",
            "-m",
            "seed"
        ));

        Path remoteRepository = tempDir.resolve("remote.git");
        runCommand(List.of("git", "init", "--bare", remoteRepository.toString()));
        runCommand(List.of("git", "-C", seedRepository.toString(), "remote", "add", "origin", remoteRepository.toString()));
        runCommand(List.of("git", "-C", seedRepository.toString(), "push", "origin", "HEAD"));
        return remoteRepository;
    }

    private static void runCommand(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + output);
        }
    }

    private static boolean isGitAvailable() {
        try {
            runCommand(List.of("git", "--version"));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static final class RecordingGitProcessExecutor extends GitProcessExecutor {

        private final Deque<Process> processes;
        private final List<List<String>> commands = new ArrayList<>();

        RecordingGitProcessExecutor(List<Process> processes) {
            this.processes = new ArrayDeque<>(processes);
        }

        @Override
        public Process start(List<String> command) throws IOException {
            commands.add(List.copyOf(command));
            if (processes.isEmpty()) {
                throw new IOException("No process available");
            }
            return processes.removeFirst();
        }

        List<List<String>> commands() {
            return List.copyOf(commands);
        }
    }

    private static class StubProcess extends Process {

        private final int exitCode;
        private final String output;

        StubProcess(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
