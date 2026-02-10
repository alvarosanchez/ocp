package com.github.alvarosanchez.ocp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.alvarosanchez.ocp.model.OcpConfigFile;
import com.github.alvarosanchez.ocp.model.OcpConfigOptions;
import com.github.alvarosanchez.ocp.model.ProfileEntry;
import com.github.alvarosanchez.ocp.model.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.model.RepositoryEntry;
import com.github.sparsick.testcontainers.gitserver.http.GitHttpServerContainer;
import io.micronaut.configuration.picocli.PicocliRunner;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.DockerImageName;

class ProfileCommandTest {

    @TempDir
    Path tempDir;

    private String previousConfigDir;
    private String previousCacheDir;

    @BeforeEach
    void setUp() {
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("ocp-config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("ocp-cache").toString());
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
    }

    @Test
    void listShowsMessageWhenNoProfilesAreAvailable() {
        CommandResult result = execute("profile", "list");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("No profiles available yet"));
    }

    @Test
    void listProfilesFromLocalRepositoryMetadata() throws IOException {
        writeRepositoryMetadata(
            "repo-local",
            new RepositoryConfigFile(
                List.of(
                    new ProfileEntry("corporate"),
                    new ProfileEntry("oss")
                )
            )
        );

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(true),
                List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null))
            )
        );

        CommandResult result = execute("profile", "list");

        assertEquals(0, result.exitCode());
        assertEquals("corporate\noss", result.stdout().trim());
    }

    @Test
    void listProfilesMergesAndSortsAcrossRepositories() throws IOException {
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
                    new ProfileEntry("delta")
                )
            )
        );

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(true),
                List.of(
                    new RepositoryEntry("repo-one", "git@github.com:acme/repo-one.git", null),
                    new RepositoryEntry("repo-two", "git@github.com:acme/repo-two.git", null)
                )
            )
        );

        CommandResult result = execute("profile", "list");

        assertEquals(0, result.exitCode());
        assertEquals("alpha\nbeta\ndelta\ngamma", result.stdout().trim());
    }

    @Test
    void listFailsWhenDuplicateProfilesExistAcrossRepositories() throws IOException {
        writeRepositoryMetadata(
            "repo-one",
            new RepositoryConfigFile(List.of(new ProfileEntry("shared"), new ProfileEntry("alpha")))
        );
        writeRepositoryMetadata(
            "repo-two",
            new RepositoryConfigFile(List.of(new ProfileEntry("shared"), new ProfileEntry("beta")))
        );

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(true),
                List.of(
                    new RepositoryEntry("repo-one", "git@github.com:acme/repo-one.git", null),
                    new RepositoryEntry("repo-two", "git@github.com:acme/repo-two.git", null)
                )
            )
        );

        CommandResult result = execute("profile", "list");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("duplicate profile names found"));
    }

    @Test
    @DisabledInNativeImage
    void listClonesMissingRepositoryFromGitContainer() throws IOException, InterruptedException {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        try (GitHttpServerContainer gitServer = new GitHttpServerContainer(
            DockerImageName.parse("rockstorm/git-server:2.38")
        )) {
            gitServer.start();
            waitForHttpServer(gitServer);

            Path seedRepository = tempDir.resolve("seed-repository");
            runCommand(List.of("git", "clone", gitServer.getGitRepoURIAsHttp().toString(), seedRepository.toString()));
            Files.writeString(
                seedRepository.resolve("repository.json"),
                serializeAsJson(new RepositoryConfigFile(List.of(new ProfileEntry("remote-team"))))
            );
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
            runCommand(List.of("git", "-C", seedRepository.toString(), "push", "origin", "HEAD"));

            Path localClone = repositoriesCacheDir().resolve("repo-remote");
            Files.createDirectories(repositoriesCacheDir());
            runCommand(List.of("git", "clone", gitServer.getGitRepoURIAsHttp().toString(), localClone.toString()));

            writeOcpConfig(
                new OcpConfigFile(
                    new OcpConfigOptions(true),
                    List.of(new RepositoryEntry("repo-remote", gitServer.getGitRepoURIAsHttp().toString(), null))
                )
            );

            CommandResult result = execute("profile", "list");

            assertEquals(0, result.exitCode());
            assertEquals("remote-team", result.stdout().trim());
            assertTrue(Files.exists(localClone.resolve(".git")));
        }
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
        Path repositoryPath = repositoriesCacheDir().resolve(repositoryName);
        Files.createDirectories(repositoryPath);
        Files.writeString(repositoryPath.resolve("repository.json"), serializeAsJson(repositoryConfigFile));
    }

    private Path repositoriesCacheDir() {
        return Path.of(System.getProperty("ocp.cache.dir"), "repositories");
    }

    private String serializeAsJson(Object value) throws IOException {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            return objectMapper.writeValueAsString(value);
        }
    }

    private static void waitForHttpServer(GitHttpServerContainer gitServer) throws InterruptedException {
        URI uri = gitServer.getGitRepoURIAsHttp();
        int attempts = 120;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 500);
                return;
            } catch (IOException ignored) {
                Thread.sleep(500L);
            }
        }

        throw new IllegalStateException(
            "Git HTTP server did not become reachable at " + uri + "\nContainer logs:\n" + gitServer.getLogs()
        );
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
