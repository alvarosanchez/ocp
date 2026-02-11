package com.github.alvarosanchez.ocp.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.git.GitProcessExecutor;
import com.github.alvarosanchez.ocp.git.GitRepositoryClient;
import com.github.alvarosanchez.ocp.model.OcpConfigFile;
import com.github.alvarosanchez.ocp.model.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.model.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.model.RepositoryConfigFile;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileVersionCheckServiceTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private ObjectMapper objectMapper;
    private String previousConfigDir;
    private String previousCacheDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        objectMapper = applicationContext.getBean(ObjectMapper.class);
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
    void printUpdateHintsShowsHintWhenRepositoryIsBehindRemote() throws IOException, InterruptedException {
        Path remoteRepository = createRemoteRepository();
        Path localRepository = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-one");

        runCommand(List.of("git", "clone", remoteRepository.toString(), localRepository.toString()));

        Path updateWorktree = tempDir.resolve("update-worktree");
        runCommand(List.of("git", "clone", remoteRepository.toString(), updateWorktree.toString()));
        Files.writeString(updateWorktree.resolve("next.txt"), "update");
        runCommand(List.of("git", "-C", updateWorktree.toString(), "add", "next.txt"));
        runCommand(List.of(
            "git",
            "-C",
            updateWorktree.toString(),
            "-c",
            "user.email=test@example.com",
            "-c",
            "user.name=test",
            "commit",
            "-m",
            "update"
        ));
        runCommand(List.of("git", "-C", updateWorktree.toString(), "push", "origin", "HEAD"));

        writeConfig(true, List.of(new RepositoryEntry("repo-one", remoteRepository.toUri().toString(), null)));

        RepositoryService repositoryService = new RepositoryService(objectMapper, new GitRepositoryClient(new GitProcessExecutor()));
        ProfileVersionCheckService versionCheckService = new ProfileVersionCheckService(
            repositoryService,
            new GitRepositoryClient(new GitProcessExecutor())
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        versionCheckService.printUpdateHints(new PrintStream(output));

        assertTrue(output.toString().contains("repository `repo-one` is behind remote"));
    }

    @Test
    void printUpdateHintsSkipsWhenProfileVersionCheckIsDisabled() throws IOException {
        writeConfig(false, List.of(new RepositoryEntry("repo-disabled", "git@github.com:acme/repo.git", null)));

        RepositoryService repositoryService = new RepositoryService(objectMapper, new GitRepositoryClient(new GitProcessExecutor()));
        ProfileVersionCheckService versionCheckService = new ProfileVersionCheckService(
            repositoryService,
            new GitRepositoryClient(new GitProcessExecutor())
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        versionCheckService.printUpdateHints(new PrintStream(output));

        assertTrue(output.toString().isEmpty());
    }

    @Test
    void printUpdateHintsDoesNotFailWhenCheckErrors() throws IOException {
        Path invalidRepository = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-error");
        Files.createDirectories(invalidRepository);
        Files.writeString(invalidRepository.resolve(".git"), "not-a-real-git-directory");

        writeConfig(true, List.of(new RepositoryEntry("repo-error", "git@github.com:acme/repo-error.git", null)));

        RepositoryService repositoryService = new RepositoryService(objectMapper, new GitRepositoryClient(new GitProcessExecutor()));
        ProfileVersionCheckService versionCheckService = new ProfileVersionCheckService(
            repositoryService,
            new GitRepositoryClient(new GitProcessExecutor())
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        versionCheckService.printUpdateHints(new PrintStream(output));

        assertTrue(output.toString().contains("skipped version checks for repositories: repo-error"));
    }

    @Test
    void printUpdateHintsDoesNotFailWhenConfigFileCannotBeParsed() throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), "not-json");

        RepositoryService repositoryService = new RepositoryService(objectMapper, new GitRepositoryClient(new GitProcessExecutor()));
        ProfileVersionCheckService versionCheckService = new ProfileVersionCheckService(
            repositoryService,
            new GitRepositoryClient(new GitProcessExecutor())
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> versionCheckService.printUpdateHints(new PrintStream(output)));
        assertTrue(output.toString().contains("Hint: skipped version checks:"));
    }

    private Path createRemoteRepository() throws IOException, InterruptedException {
        Path seedRepository = tempDir.resolve("seed");
        runCommand(List.of("git", "init", seedRepository.toString()));
        Files.writeString(seedRepository.resolve("repository.json"), serializeAsJson(new RepositoryConfigFile(List.of())));
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

    private void writeConfig(boolean profileVersionCheck, List<RepositoryEntry> repositories) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        OcpConfigFile configFile = new OcpConfigFile(new OcpConfigOptions(profileVersionCheck), repositories);
        Files.writeString(configDir.resolve("config.json"), objectMapper.writeValueAsString(configFile));
    }

    private String serializeAsJson(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
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
