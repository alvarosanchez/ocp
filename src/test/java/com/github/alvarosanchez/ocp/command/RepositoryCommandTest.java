package com.github.alvarosanchez.ocp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.model.OcpConfigFile;
import com.github.alvarosanchez.ocp.model.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.model.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.model.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.model.RepositoryConfigFile.ProfileEntry;
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
        Path remote = createRemoteRepository();

        CommandResult result = execute("repository", "add", remote.toUri().toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Added repository `remote`"));

        Path localClone = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "remote");
        assertTrue(Files.exists(localClone.resolve(".git")));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertEquals(1, configFile.repositories().size());
        assertEquals("remote", configFile.repositories().get(0).name());
    }

    @Test
    void deleteRemovesRepositoryFromConfigAndDeletesLocalClone() throws IOException {
        Path localClone = Path.of(System.getProperty("ocp.cache.dir"), "repositories", "repo-one");
        Files.createDirectories(localClone);
        Files.writeString(localClone.resolve("repository.json"), serializeAsJson(new RepositoryConfigFile(List.of())));

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(true),
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
    void createInitializesRepositoryWithOptionalProfile() throws IOException {
        Files.createDirectories(Path.of(System.getProperty("ocp.working.dir")));

        CommandResult result = execute("repository", "create", "team-repo", "--profile-name", "team");

        assertEquals(0, result.exitCode());
        Path createdRepository = Path.of(System.getProperty("ocp.working.dir")).resolve("team-repo");
        assertTrue(Files.exists(createdRepository.resolve(".git")));
        assertTrue(Files.isDirectory(createdRepository.resolve("team")));

        RepositoryConfigFile repositoryConfigFile = readRepositoryConfig(createdRepository.resolve("repository.json"));
        assertEquals(List.of(new ProfileEntry("team")), repositoryConfigFile.profiles());
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

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
