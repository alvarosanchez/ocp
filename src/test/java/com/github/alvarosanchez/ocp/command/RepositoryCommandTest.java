package com.github.alvarosanchez.ocp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile.ProfileEntry;
import io.micronaut.configuration.picocli.PicocliRunner;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryCommandTest {

    @TempDir
    Path tempDir;

    private String previousConfigDir;
    private String previousCacheDir;
    private String previousWorkingDir;

    @BeforeEach
    void setUp() {
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        previousWorkingDir = System.getProperty("ocp.working.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("ocp-config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("ocp-cache").toString());
        System.setProperty("ocp.working.dir", tempDir.resolve("workspace").toString());
    }

    @AfterEach
    void tearDown() {
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
    void addClonesRepositoryAndRegistersItInConfig() throws IOException, InterruptedException {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        Path remote = createRemoteRepository();
        String repositoryName = "team-configs";

        CommandResult result = execute("repository", "add", remote.toUri().toString(), "--name", repositoryName);

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Added repository `team-configs`"));

        Path localClone = Path.of(System.getProperty("ocp.cache.dir"), "repositories", repositoryName);
        assertTrue(Files.exists(localClone.resolve(".git")));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertEquals(1, configFile.repositories().size());
        assertEquals(repositoryName, configFile.repositories().get(0).name());
    }

    @Test
    void addRemovesUnknownCachedCloneWhenConfigIsMissing() throws IOException, InterruptedException {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        Path remote = createRemoteRepository();
        String repositoryName = "stale-repo";
        Path localClone = Path.of(System.getProperty("ocp.cache.dir"), "repositories", repositoryName);

        runCommand(List.of("git", "clone", remote.toUri().toString(), localClone.toString()));
        Path staleMarker = localClone.resolve("stale-marker.txt");
        Files.writeString(staleMarker, "stale");

        CommandResult result = execute("repository", "add", remote.toUri().toString(), "--name", repositoryName);

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Added repository `stale-repo`"));
        assertTrue(Files.exists(localClone.resolve(".git")));
        assertTrue(Files.notExists(staleMarker));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertEquals(1, configFile.repositories().size());
        assertEquals(repositoryName, configFile.repositories().get(0).name());
    }

    @Test
    void addRequiresRepositoryNameOption() throws IOException, InterruptedException {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        Path remote = createRemoteRepository();

        CommandResult result = execute("repository", "add", remote.toUri().toString());

        assertEquals(2, result.exitCode());
        assertTrue(result.stderr().contains("--name"));
    }

    @Test
    void addRegistersLocalPathRepositoryWithNullUri() throws IOException {
        Path localRepository = tempDir.resolve("my-local-repository");
        Files.createDirectories(localRepository);
        Files.writeString(localRepository.resolve("repository.json"), serializeAsJson(new RepositoryConfigFile(List.of())));

        CommandResult result = execute("repository", "add", localRepository.toString(), "--name", "my-local-repository");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Added repository `my-local-repository`"));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertEquals(1, configFile.repositories().size());
        RepositoryEntry entry = configFile.repositories().getFirst();
        assertEquals("my-local-repository", entry.name());
        assertEquals(null, entry.uri());
        assertEquals(localRepository.toAbsolutePath().normalize().toString(), entry.localPath());
    }

    @Test
    void addSupportsCurrentDirectoryShortcutForLocalRepository() throws IOException {
        Path workingDirectory = Path.of(System.getProperty("ocp.working.dir"));
        Files.createDirectories(workingDirectory);
        Files.writeString(workingDirectory.resolve("repository.json"), serializeAsJson(new RepositoryConfigFile(List.of())));

        CommandResult result = execute("repository", "add", ".", "--name", "cwd-repo");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Added repository `cwd-repo`"));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertEquals(1, configFile.repositories().size());
        RepositoryEntry entry = configFile.repositories().getFirst();
        assertEquals("cwd-repo", entry.name());
        assertEquals(null, entry.uri());
        assertEquals(workingDirectory.toAbsolutePath().normalize().toString(), entry.localPath());
    }

    @Test
    void deleteRemovesRepositoryFromConfigAndDeletesLocalClone() throws IOException {
        Path localClone = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-one");
        Files.createDirectories(localClone);
        Files.writeString(localClone.resolve("repository.json"), serializeAsJson(new RepositoryConfigFile(List.of())));

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-one", "git@github.com:acme/repo-one.git", localClone.toString()))
            )
        );

        CommandResult result = execute("repository", "delete", "repo-one");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Deleted repository `repo-one`"));
        assertTrue(Files.notExists(localClone));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertTrue(configFile.repositories().isEmpty());
    }

    @Test
    void deleteGitRepositoryWithLocalChangesFailsWithoutForce() throws IOException, InterruptedException {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        Path localClone = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-dirty");
        Files.createDirectories(localClone);
        runCommand(List.of("git", "init", localClone.toString()));
        Files.writeString(localClone.resolve("dirty.txt"), "dirty");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-dirty", "git@github.com:acme/repo-dirty.git", localClone.toString()))
            )
        );

        CommandResult result = execute("repository", "delete", "repo-dirty");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("--force"));
        assertTrue(Files.exists(localClone));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertEquals(1, configFile.repositories().size());
        assertEquals("repo-dirty", configFile.repositories().getFirst().name());
    }

    @Test
    void deleteGitRepositoryWithLocalChangesSucceedsWithForce() throws IOException, InterruptedException {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        Path localClone = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-dirty");
        Files.createDirectories(localClone);
        runCommand(List.of("git", "init", localClone.toString()));
        Files.writeString(localClone.resolve("dirty.txt"), "dirty");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-dirty", "git@github.com:acme/repo-dirty.git", localClone.toString()))
            )
        );

        CommandResult result = execute("repository", "delete", "repo-dirty", "--force");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Deleted repository `repo-dirty`"));
        assertTrue(Files.notExists(localClone));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertTrue(configFile.repositories().isEmpty());
    }

    @Test
    void deleteFileBasedRepositoryKeepsLocalFolderByDefault() throws IOException {
        Path localRepository = tempDir.resolve("local-repository");
        Files.createDirectories(localRepository);

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("local", null, localRepository.toString()))
            )
        );

        CommandResult result = execute("repository", "delete", "local");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Deleted repository `local`"));
        assertTrue(Files.exists(localRepository));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertTrue(configFile.repositories().isEmpty());
    }

    @Test
    void deleteFileBasedRepositoryDeletesLocalFolderWhenFlagIsProvided() throws IOException {
        Path localRepository = tempDir.resolve("local-repository");
        Files.createDirectories(localRepository);
        Files.writeString(localRepository.resolve("repository.json"), serializeAsJson(new RepositoryConfigFile(List.of())));

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("local", null, localRepository.toString()))
            )
        );

        CommandResult result = execute("repository", "delete", "local", "--delete-local-path");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Deleted repository `local`"));
        assertTrue(Files.notExists(localRepository));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertTrue(configFile.repositories().isEmpty());
    }

    @Test
    void createBuildsRepositoryScaffoldWithOptionalProfile() throws IOException {
        Files.createDirectories(Path.of(System.getProperty("ocp.working.dir")));

        CommandResult result = execute("repository", "create", "team-repo", "--profile-name", "team");

        assertEquals(0, result.exitCode());
        Path createdRepository = Path.of(System.getProperty("ocp.working.dir")).resolve("team-repo");
        assertTrue(Files.notExists(createdRepository.resolve(".git")));
        assertTrue(Files.isDirectory(createdRepository.resolve("team")));

        RepositoryConfigFile repositoryConfigFile = readRepositoryConfig(createdRepository.resolve("repository.json"));
        assertEquals(List.of(new ProfileEntry("team")), repositoryConfigFile.profiles());
    }

    @Test
    void listShowsMessageWhenNoRepositoriesAreConfigured() {
        CommandResult result = execute("repository", "list");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("No repositories available yet"));
    }

    @Test
    void listShowsConfiguredRepositoriesIncludingResolvedProfiles() throws IOException {
        writeRepositoryMetadata(
            "repo-one",
            new RepositoryConfigFile(
                List.of(
                    new ProfileEntry("beta"),
                    new ProfileEntry("alpha")
                )
            )
        );
        writeRepositoryMetadata(
            "repo-two",
            new RepositoryConfigFile(
                List.of(
                    new ProfileEntry("gamma"),
                    new ProfileEntry("alpha")
                )
            )
        );

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(
                    new RepositoryEntry("repo-two", "https://github.com/acme/repo-two.git", null),
                    new RepositoryEntry("repo-one", "git@github.com:acme/repo-one.git", null)
                )
            )
        );

        CommandResult result = execute("repository", "list");

        assertEquals(0, result.exitCode());
        String output = removeAnsiCodes(result.stdout());
        assertTrue(output.contains("╭"));
        assertTrue(output.contains("Name:"));
        assertTrue(output.contains("-> repo-one"));
        assertTrue(output.contains("-> repo-two"));
        assertTrue(output.contains("URI:"));
        assertTrue(output.contains("Local path:"));
        assertTrue(output.contains("Resolved profiles:"));
        assertTrue(output.contains("alpha, beta"));
        assertTrue(output.contains("alpha, gamma"));
    }

    @Test
    void listFailsWhenRepositoryMetadataIsInvalid() throws IOException {
        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-one", "git@github.com:acme/repo-one.git", null))
            )
        );

        Path repositoryPath = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-one");
        Files.createDirectories(repositoryPath);
        Files.writeString(repositoryPath.resolve("repository.json"), "not-json");

        CommandResult result = execute("repository", "list");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Failed to read profile metadata"));
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

    private CommandResult execute(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            System.setOut(new PrintStream(stdout));
            System.setErr(new PrintStream(stderr));
            int exitCode = PicocliRunner.execute(OcpCommand.class, args);
            return new CommandResult(exitCode, stdout.toString(), stderr.toString());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private void writeOcpConfig(OcpConfigFile configFile) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), serializeAsJson(configFile));
    }

    private void writeRepositoryMetadata(String repositoryName, RepositoryConfigFile repositoryConfigFile) throws IOException {
        Path repositoryPath = Path.of(System.getProperty("ocp.cache.dir"), "repositories", repositoryName);
        Files.createDirectories(repositoryPath);
        Files.writeString(repositoryPath.resolve("repository.json"), serializeAsJson(repositoryConfigFile));
    }

    private OcpConfigFile readOcpConfig(Path configPath) throws IOException {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            return objectMapper.readValue(Files.readString(configPath), OcpConfigFile.class);
        }
    }

    private RepositoryConfigFile readRepositoryConfig(Path configPath) throws IOException {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            return objectMapper.readValue(Files.readString(configPath), RepositoryConfigFile.class);
        }
    }

    private String serializeAsJson(Object value) throws IOException {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            return objectMapper.writeValueAsString(value);
        }
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

    private static String removeAnsiCodes(String output) {
        return output.replaceAll("\\u001B\\[[;\\d]*m", "");
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
