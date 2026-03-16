package com.github.alvarosanchez.ocp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.git.GhProcessExecutor;
import com.github.alvarosanchez.ocp.git.GitHubRepositoryClient;
import com.github.alvarosanchez.ocp.git.GitHubRepositoryClient.RepositoryVisibility;
import com.github.alvarosanchez.ocp.git.GitProcessExecutor;
import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryPostCreationServiceTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private String previousConfigDir;
    private String previousCacheDir;
    private String previousWorkingDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
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
    void capabilitiesEnableGitInitializationForNonGitRepository() throws IOException {
        StubGitProcessExecutor gitProcessExecutor = new StubGitProcessExecutor(List.of());
        StubGhProcessExecutor ghProcessExecutor = new StubGhProcessExecutor(List.of());
        RepositoryPostCreationService service = postCreationService(gitProcessExecutor, ghProcessExecutor);
        Path repositoryPath = tempDir.resolve("repo-no-git");
        Files.createDirectories(repositoryPath);

        RepositoryPostCreationService.PostCreationCapabilities capabilities = service.capabilities(repositoryPath);

        assertEquals(false, capabilities.gitInitialized());
        assertEquals(false, capabilities.hasOriginRemote());
        assertEquals(false, capabilities.canPublishWithGh());
    }

    @Test
    void capabilitiesAllowGitHubPublishWhenGhAuthSucceedsBeforeGitInit() throws IOException {
        StubGitProcessExecutor gitProcessExecutor = new StubGitProcessExecutor(List.of());
        StubGhProcessExecutor ghProcessExecutor = new StubGhProcessExecutor(List.of(new StubProcess(0, "ok")));
        RepositoryPostCreationService service = postCreationService(gitProcessExecutor, ghProcessExecutor);
        Path repositoryPath = tempDir.resolve("repo-no-git-authenticated");
        Files.createDirectories(repositoryPath);

        RepositoryPostCreationService.PostCreationCapabilities capabilities = service.capabilities(repositoryPath);

        assertEquals(false, capabilities.gitInitialized());
        assertEquals(true, capabilities.canPublishWithGh());
    }

    @Test
    void runInitializesGitAndChecksForInitialCommitWhenRequested() throws IOException {
        StubGitProcessExecutor gitProcessExecutor = new StubGitProcessExecutor(
            List.of(new StubProcess(0, ""), new StubProcess(0, " M repository.json\n"), new StubProcess(0, ""))
        );
        StubGhProcessExecutor ghProcessExecutor = new StubGhProcessExecutor(List.of());
        RepositoryPostCreationService service = postCreationService(gitProcessExecutor, ghProcessExecutor);
        Path repositoryPath = tempDir.resolve("repo-init");
        Files.createDirectories(repositoryPath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{}\n");

        RepositoryPostCreationService.PostCreationResult result = service.run(
            "repo-init",
            repositoryPath,
            new RepositoryPostCreationService.PostCreationRequest(true, false, RepositoryVisibility.PRIVATE)
        );

        assertTrue(result.initializedGit());
        assertEquals(false, result.publishedToGitHub());
        assertEquals(
            List.of(
                List.of("git", "-C", repositoryPath.toString(), "init", "--quiet"),
                List.of("git", "-C", repositoryPath.toString(), "add", "-A"),
                List.of("git", "-C", repositoryPath.toString(), "status", "--porcelain")
            ),
            gitProcessExecutor.commands()
        );
    }

    @Test
    void runPublishesToGitHubAndPersistsOriginUri() throws IOException {
        StubGitProcessExecutor gitProcessExecutor = new StubGitProcessExecutor(
            List.of(new StubProcess(2, "No such remote 'origin'\n"), new StubProcess(0, "git@github.com:acme/repo-publish.git\n"))
        );
        StubGhProcessExecutor ghProcessExecutor = new StubGhProcessExecutor(List.of(new StubProcess(0, "created")));
        RepositoryService repositoryService = repositoryService(gitProcessExecutor);
        RepositoryPostCreationService service = new RepositoryPostCreationService(
            new GitRepositoryClient(gitProcessExecutor),
            new GitHubRepositoryClient(ghProcessExecutor),
            repositoryService
        );
        Path repositoryPath = tempDir.resolve("repo-publish");
        Files.createDirectories(repositoryPath.resolve(".git"));
        repositoryService.add(repositoryPath.toString(), "repo-publish");

        RepositoryPostCreationService.PostCreationResult result = service.run(
            "repo-publish",
            repositoryPath,
            new RepositoryPostCreationService.PostCreationRequest(false, true, RepositoryVisibility.PRIVATE)
        );

        assertEquals(false, result.initializedGit());
        assertTrue(result.publishedToGitHub());
        assertEquals("git@github.com:acme/repo-publish.git", result.persistedRepositoryUri());

        RepositoryEntry stored = repositoryService.load().getFirst();
        assertEquals("repo-publish", stored.name());
        assertEquals("git@github.com:acme/repo-publish.git", stored.uri());
        assertEquals(repositoryPath.toAbsolutePath().normalize().toString(), stored.localPath());
    }

    @Test
    void runReportsPublishedRemoteWhenRegistryUpdateFails() throws IOException {
        StubGitProcessExecutor gitProcessExecutor = new StubGitProcessExecutor(
            List.of(new StubProcess(2, "No such remote 'origin'\n"), new StubProcess(0, "git@github.com:acme/repo-publish.git\n"))
        );
        StubGhProcessExecutor ghProcessExecutor = new StubGhProcessExecutor(List.of(new StubProcess(0, "created")));
        RepositoryService repositoryService = failingRepositoryService(gitProcessExecutor);
        RepositoryPostCreationService service = new RepositoryPostCreationService(
            new GitRepositoryClient(gitProcessExecutor),
            new GitHubRepositoryClient(ghProcessExecutor),
            repositoryService
        );
        Path repositoryPath = tempDir.resolve("repo-publish");
        Files.createDirectories(repositoryPath.resolve(".git"));
        repositoryService.add(repositoryPath.toString(), "repo-publish");

        IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> service.run(
                "repo-publish",
                repositoryPath,
                new RepositoryPostCreationService.PostCreationRequest(false, true, RepositoryVisibility.PRIVATE)
            )
        );

        assertTrue(thrown.getMessage().contains("Published repository `repo-publish` to GitHub as `git@github.com:acme/repo-publish.git`"));
        RepositoryEntry stored = repositoryService.load().getFirst();
        assertEquals(null, stored.uri());
        assertEquals(repositoryPath.toAbsolutePath().normalize().toString(), stored.localPath());
    }

    @Test
    void persistExistingOriginStoresOriginUriForFileBasedRepository() throws IOException {
        StubGitProcessExecutor gitProcessExecutor = new StubGitProcessExecutor(
            List.of(new StubProcess(0, ""), new StubProcess(0, "git@github.com:acme/repo-persist.git\n"))
        );
        StubGhProcessExecutor ghProcessExecutor = new StubGhProcessExecutor(List.of());
        RepositoryService repositoryService = repositoryService(gitProcessExecutor);
        RepositoryPostCreationService service = new RepositoryPostCreationService(
            new GitRepositoryClient(gitProcessExecutor),
            new GitHubRepositoryClient(ghProcessExecutor),
            repositoryService
        );
        Path repositoryPath = tempDir.resolve("repo-persist");
        Files.createDirectories(repositoryPath.resolve(".git"));
        repositoryService.add(repositoryPath.toString(), "repo-persist");

        String originUri = service.persistExistingOrigin("repo-persist", repositoryPath);

        assertEquals("git@github.com:acme/repo-persist.git", originUri);
        RepositoryEntry stored = repositoryService.load().getFirst();
        assertEquals("git@github.com:acme/repo-persist.git", stored.uri());
        assertEquals(repositoryPath.toAbsolutePath().normalize().toString(), stored.localPath());
    }

    @Test
    void persistExistingOriginFailsWhenOriginRemoteIsMissing() throws IOException {
        StubGitProcessExecutor gitProcessExecutor = new StubGitProcessExecutor(List.of(new StubProcess(2, "No such remote 'origin'\n")));
        RepositoryPostCreationService service = postCreationService(gitProcessExecutor, new StubGhProcessExecutor(List.of()));
        Path repositoryPath = tempDir.resolve("repo-no-origin");
        Files.createDirectories(repositoryPath.resolve(".git"));

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> service.persistExistingOrigin("repo-no-origin", repositoryPath)
        );

        assertTrue(thrown.getMessage().contains("does not have an `origin` remote"));
    }

    @Test
    void runRejectsPublishingWhenRepositoryIsNotGitInitializedAndInitializeGitIsFalse() throws IOException {
        RepositoryPostCreationService service = postCreationService(new StubGitProcessExecutor(List.of()), new StubGhProcessExecutor(List.of()));
        Path repositoryPath = tempDir.resolve("repo-no-git-publish");
        Files.createDirectories(repositoryPath);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> service.run(
                "repo-no-git-publish",
                repositoryPath,
                new RepositoryPostCreationService.PostCreationRequest(false, true, RepositoryVisibility.PRIVATE)
            )
        );

        assertTrue(thrown.getMessage().contains("must be initialized as a git repository before it can be published to GitHub"));
    }

    private RepositoryPostCreationService postCreationService(StubGitProcessExecutor gitProcessExecutor, StubGhProcessExecutor ghProcessExecutor) {
        RepositoryService repositoryService = repositoryService(gitProcessExecutor);
        GitRepositoryClient gitRepositoryClient = new GitRepositoryClient(gitProcessExecutor);
        GitHubRepositoryClient gitHubRepositoryClient = new GitHubRepositoryClient(ghProcessExecutor);
        return new RepositoryPostCreationService(gitRepositoryClient, gitHubRepositoryClient, repositoryService);
    }

    private RepositoryService repositoryService(StubGitProcessExecutor gitProcessExecutor) {
        ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
        return new RepositoryService(objectMapper, new GitRepositoryClient(gitProcessExecutor));
    }

    private RepositoryService failingRepositoryService(StubGitProcessExecutor gitProcessExecutor) {
        ObjectMapper objectMapper = applicationContext.getBean(ObjectMapper.class);
        return new RepositoryService(
            objectMapper,
            new GitRepositoryClient(gitProcessExecutor)
        ).withConfigWriter(new RepositoryService.ConfigWriter() {
                @Override
                public String writeConfig(com.github.alvarosanchez.ocp.config.OcpConfigFile configFile) throws IOException {
                    if (!configFile.repositories().isEmpty() && configFile.repositories().stream().anyMatch(repository -> repository.uri() != null)) {
                        throw new IOException("Injected registry write failure");
                    }
                    return objectMapper.writeValueAsString(configFile);
                }

                @Override
                public String writeRepositoryConfig(com.github.alvarosanchez.ocp.config.RepositoryConfigFile configFile) throws IOException {
                    return objectMapper.writeValueAsString(configFile);
                }
            });
    }

    private static final class StubGitProcessExecutor extends GitProcessExecutor {

        private final Deque<Process> processes;
        private final List<List<String>> commands;

        StubGitProcessExecutor(List<Process> processes) {
            this.processes = new ArrayDeque<>(processes);
            this.commands = new ArrayList<>();
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

    private static final class StubGhProcessExecutor extends GhProcessExecutor {

        private final Deque<Process> processes;

        StubGhProcessExecutor(List<Process> processes) {
            this.processes = new ArrayDeque<>(processes);
        }

        @Override
        public Process start(List<String> command) throws IOException {
            if (processes.isEmpty()) {
                throw new IOException("No process available");
            }
            return processes.removeFirst();
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

        @Override
        public java.util.concurrent.CompletableFuture<Process> onExit() {
            return java.util.concurrent.CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }
    }
}
