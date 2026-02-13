package com.github.alvarosanchez.ocp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile;
import com.github.alvarosanchez.ocp.config.RepositoryConfigFile.ProfileEntry;
import com.github.sparsick.testcontainers.gitserver.http.GitHttpServerContainer;
import io.micronaut.configuration.picocli.PicocliRunner;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    private String previousWorkingDir;
    private String previousOpenCodeConfigDir;

    @BeforeEach
    void setUp() {
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        previousWorkingDir = System.getProperty("ocp.working.dir");
        previousOpenCodeConfigDir = System.getProperty("ocp.opencode.config.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("ocp-config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("ocp-cache").toString());
        System.setProperty("ocp.working.dir", tempDir.resolve("working-repository").toString());
        System.setProperty("ocp.opencode.config.dir", tempDir.resolve("opencode-config").toString());
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
        if (previousOpenCodeConfigDir == null) {
            System.clearProperty("ocp.opencode.config.dir");
        } else {
            System.setProperty("ocp.opencode.config.dir", previousOpenCodeConfigDir);
        }
    }

    @Test
    void listShowsMessageWhenNoProfilesAreAvailable() {
        CommandResult result = execute("profile", "list");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("No profiles available yet"));
    }

    @Test
    void listHandlesInvalidRegistryWithoutBubblingExceptionStackTrace() throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), "not-json");

        CommandResult result = execute("profile", "list");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Failed to read repository registry"));
        assertFalse(result.stderr().contains("UncheckedIOException"));
        assertFalse(result.stderr().contains("at com.github.alvarosanchez.ocp"));
    }

    @Test
    void profileWithoutSubcommandPrintsActiveProfileState() {
        CommandResult result = execute("profile");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("No active profile selected yet"));
    }

    @Test
    void listShowsActiveColumnWithAsciiMarkerForCurrentProfile() throws IOException {
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
                new OcpConfigOptions("corporate"),
                List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null))
            )
        );

        CommandResult result = execute("profile", "list");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ACTIVE"));
        assertTrue(result.stdout().contains("✓"));
    }

    @Test
    void createCreatesProfileDirectoryAndRepositoryMetadata() throws IOException {
        Path repositoryRoot = Path.of(System.getProperty("ocp.working.dir"));
        Files.createDirectories(repositoryRoot);

        CommandResult result = execute("profile", "create", "team");

        assertEquals(0, result.exitCode());
        assertTrue(Files.isDirectory(repositoryRoot.resolve("team")));

        RepositoryConfigFile metadata = readRepositoryMetadata(repositoryRoot.resolve("repository.json"));
        assertTrue(metadata.profiles().stream().anyMatch(profile -> profile.name().equals("team")));
    }

    @Test
    void useLinksProfileFilesBacksUpExistingFileAndSetsActiveProfile() throws IOException {
        writeRepositoryMetadata("repo-local", new RepositoryConfigFile(List.of(new ProfileEntry("corporate"))));
        Path sourceProfileDir = repositoriesCacheDir().resolve("repo-local").resolve("corporate");
        Files.createDirectories(sourceProfileDir);
        Files.writeString(sourceProfileDir.resolve("opencode.json"), "{\"profile\":\"corporate\"}");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null))
            )
        );

        Path openCodeDir = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDir);
        Files.writeString(openCodeDir.resolve("opencode.json"), "legacy");

        CommandResult result = execute("profile", "use", "corporate");

        assertEquals(0, result.exitCode());
        assertTrue(Files.isSymbolicLink(openCodeDir.resolve("opencode.json")));
        assertEquals(
            sourceProfileDir.resolve("opencode.json").toAbsolutePath(),
            Files.readSymbolicLink(openCodeDir.resolve("opencode.json"))
        );

        Path backupsDir = Path.of(System.getProperty("ocp.config.dir")).resolve("backups");
        assertTrue(Files.exists(backupsDir));

        CommandResult activeProfile = execute("profile");
        assertEquals(0, activeProfile.exitCode());
        String normalizedOutput = removeAnsiCodes(activeProfile.stdout());
        assertTrue(normalizedOutput.contains("NAME"));
        assertTrue(normalizedOutput.contains("DESCRIPTION"));
        assertTrue(normalizedOutput.contains("ACTIVE"));
        assertTrue(normalizedOutput.contains("✓"));
        assertTrue(normalizedOutput.contains("corporate"));
        assertFalse(normalizedOutput.contains("REPOSITORY"));
        assertTrue(normalizedOutput.contains("VERSION"));
        assertTrue(normalizedOutput.matches("(?s).*LAST\\s+UPDATED.*"));
        assertTrue(normalizedOutput.contains("MESSAGE"));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertEquals("corporate", configFile.config().activeProfile());
    }

    @Test
    void useRemovesFilesThatBelongOnlyToPreviousProfile() throws IOException {
        writeRepositoryMetadata(
            "repo-local",
            new RepositoryConfigFile(List.of(new ProfileEntry("corporate"), new ProfileEntry("oss")))
        );

        Path repositoryDir = repositoriesCacheDir().resolve("repo-local");
        Path corporateDir = repositoryDir.resolve("corporate");
        Path ossDir = repositoryDir.resolve("oss");
        Files.createDirectories(corporateDir);
        Files.createDirectories(ossDir);

        Files.writeString(corporateDir.resolve("opencode.json"), "{\"profile\":\"corporate\"}");
        Files.writeString(corporateDir.resolve("legacy.json"), "legacy");
        Files.writeString(ossDir.resolve("opencode.json"), "{\"profile\":\"oss\"}");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null))
            )
        );

        Path openCodeDir = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDir);

        CommandResult firstSwitch = execute("profile", "use", "corporate");
        assertEquals(0, firstSwitch.exitCode());
        assertTrue(Files.isSymbolicLink(openCodeDir.resolve("legacy.json")));

        CommandResult secondSwitch = execute("profile", "use", "oss");

        assertEquals(0, secondSwitch.exitCode());
        assertTrue(Files.notExists(openCodeDir.resolve("legacy.json")));
        assertTrue(Files.isSymbolicLink(openCodeDir.resolve("opencode.json")));
        assertEquals(ossDir.resolve("opencode.json").toAbsolutePath(), Files.readSymbolicLink(openCodeDir.resolve("opencode.json")));
    }

    @Test
    void useTreatsJsonAndJsoncAsSameLogicalFileAcrossProfiles() throws IOException {
        writeRepositoryMetadata(
            "repo-local",
            new RepositoryConfigFile(List.of(new ProfileEntry("corporate"), new ProfileEntry("oss")))
        );

        Path repositoryDir = repositoriesCacheDir().resolve("repo-local");
        Path corporateDir = repositoryDir.resolve("corporate");
        Path ossDir = repositoryDir.resolve("oss");
        Files.createDirectories(corporateDir);
        Files.createDirectories(ossDir);

        Files.writeString(corporateDir.resolve("opencode.json"), "{\"profile\":\"corporate\"}");
        Files.writeString(corporateDir.resolve("oh-my-opencode.jsonc"), "{\"profile\":\"corporate\"}");
        Files.writeString(ossDir.resolve("opencode.jsonc"), "{\"profile\":\"oss\"}");
        Files.writeString(ossDir.resolve("oh-my-opencode.json"), "{\"profile\":\"oss\"}");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null))
            )
        );

        Path openCodeDir = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDir);

        CommandResult firstSwitch = execute("profile", "use", "corporate");
        assertEquals(0, firstSwitch.exitCode());

        CommandResult secondSwitch = execute("profile", "use", "oss");

        assertEquals(0, secondSwitch.exitCode());
        assertTrue(Files.notExists(openCodeDir.resolve("opencode.json")));
        assertTrue(Files.isSymbolicLink(openCodeDir.resolve("opencode.jsonc")));
        assertEquals(ossDir.resolve("opencode.jsonc").toAbsolutePath(), Files.readSymbolicLink(openCodeDir.resolve("opencode.jsonc")));
        assertTrue(Files.notExists(openCodeDir.resolve("oh-my-opencode.jsonc")));
        assertTrue(Files.isSymbolicLink(openCodeDir.resolve("oh-my-opencode.json")));
        assertEquals(
            ossDir.resolve("oh-my-opencode.json").toAbsolutePath(),
            Files.readSymbolicLink(openCodeDir.resolve("oh-my-opencode.json"))
        );
    }

    @Test
    void useInheritedProfileMergesSharedJsonAndKeepsParentOnlyJson() throws IOException {
        writeRepositoryMetadata(
            "repo-local",
            new RepositoryConfigFile(
                List.of(
                    new ProfileEntry("oca"),
                    new ProfileEntry("oca-oh-my-opencode", null, "oca")
                )
            )
        );

        Path repositoryDir = repositoriesCacheDir().resolve("repo-local");
        Path baseDir = repositoryDir.resolve("oca");
        Path childDir = repositoryDir.resolve("oca-oh-my-opencode");
        Files.createDirectories(baseDir);
        Files.createDirectories(childDir);
        Files.writeString(
            baseDir.resolve("opencode.json"),
            "{\"some_config\":\"parent\",\"some_other_config\":\"foo\"}"
        );
        Files.writeString(baseDir.resolve("oh-my-opencode.json"), "{\"plugin\":\"from-parent\"}");
        Files.writeString(
            childDir.resolve("opencode.json"),
            "{\"some_config\":\"child\",\"another_config\":\"bar\"}"
        );

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null))
            )
        );

        CommandResult result = execute("profile", "use", "oca-oh-my-opencode");

        assertEquals(0, result.exitCode());
        Path openCodeDir = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Path opencodeFile = openCodeDir.resolve("opencode.json");
        Path ohMyFile = openCodeDir.resolve("oh-my-opencode.json");
        assertTrue(Files.isSymbolicLink(opencodeFile));
        assertTrue(Files.isSymbolicLink(ohMyFile));
        Map<String, Object> merged = readJsonMap(opencodeFile);
        assertEquals("child", merged.get("some_config"));
        assertEquals("foo", merged.get("some_other_config"));
        assertEquals("bar", merged.get("another_config"));
        assertEquals(baseDir.resolve("oh-my-opencode.json").toAbsolutePath(), Files.readSymbolicLink(ohMyFile));
    }

    @Test
    void useInheritedProfileParsesJsoncCommentsInChildFile() throws IOException {
        writeRepositoryMetadata(
            "repo-local",
            new RepositoryConfigFile(
                List.of(
                    new ProfileEntry("oca"),
                    new ProfileEntry("oca-personal", null, "oca")
                )
            )
        );

        Path repositoryDir = repositoriesCacheDir().resolve("repo-local");
        Path baseDir = repositoryDir.resolve("oca");
        Path childDir = repositoryDir.resolve("oca-personal");
        Files.createDirectories(baseDir);
        Files.createDirectories(childDir);
        Files.writeString(baseDir.resolve("opencode.jsonc"), "{\"theme\":\"dark\",\"plugin\":[\"oh-my-opencode\"]}");
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

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null))
            )
        );

        CommandResult result = execute("profile", "use", "oca-personal");

        assertEquals(0, result.exitCode());
        Path opencodeFile = Path.of(System.getProperty("ocp.opencode.config.dir")).resolve("opencode.jsonc");
        assertTrue(Files.isSymbolicLink(opencodeFile));
        Map<String, Object> merged = readJsonMap(opencodeFile);
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
    void useFailsWhenInheritedJsonExtensionDoesNotMatchParent() throws IOException {
        writeRepositoryMetadata(
            "repo-local",
            new RepositoryConfigFile(List.of(new ProfileEntry("base"), new ProfileEntry("child", null, "base")))
        );

        Path repositoryDir = repositoriesCacheDir().resolve("repo-local");
        Path baseDir = repositoryDir.resolve("base");
        Path childDir = repositoryDir.resolve("child");
        Files.createDirectories(baseDir);
        Files.createDirectories(childDir);
        Files.writeString(baseDir.resolve("opencode.jsonc"), "{\"base\":true}");
        Files.writeString(childDir.resolve("opencode.json"), "{\"child\":true}");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null))
            )
        );

        CommandResult result = execute("profile", "use", "child");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("must use the same extension as its parent"));
    }

    @Test
    void useBacksUpJsonAndJsoncVariantsAsTheSameLogicalFile() throws IOException {
        writeRepositoryMetadata("repo-local", new RepositoryConfigFile(List.of(new ProfileEntry("corporate"))));

        Path sourceProfileDir = repositoriesCacheDir().resolve("repo-local").resolve("corporate");
        Files.createDirectories(sourceProfileDir);
        Files.writeString(sourceProfileDir.resolve("opencode.jsonc"), "{\"profile\":\"corporate\"}");
        Files.writeString(sourceProfileDir.resolve("oh-my-opencode.json"), "{\"profile\":\"corporate\"}");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null))
            )
        );

        Path openCodeDir = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDir);
        Files.writeString(openCodeDir.resolve("opencode.json"), "legacy-json");
        Files.writeString(openCodeDir.resolve("oh-my-opencode.jsonc"), "legacy-jsonc");

        CommandResult result = execute("profile", "use", "corporate");

        assertEquals(0, result.exitCode());
        assertTrue(Files.notExists(openCodeDir.resolve("opencode.json")));
        assertTrue(Files.isSymbolicLink(openCodeDir.resolve("opencode.jsonc")));
        assertEquals(
            sourceProfileDir.resolve("opencode.jsonc").toAbsolutePath(),
            Files.readSymbolicLink(openCodeDir.resolve("opencode.jsonc"))
        );

        assertTrue(Files.notExists(openCodeDir.resolve("oh-my-opencode.jsonc")));
        assertTrue(Files.isSymbolicLink(openCodeDir.resolve("oh-my-opencode.json")));
        assertEquals(
            sourceProfileDir.resolve("oh-my-opencode.json").toAbsolutePath(),
            Files.readSymbolicLink(openCodeDir.resolve("oh-my-opencode.json"))
        );

        Path backupsDir = Path.of(System.getProperty("ocp.config.dir")).resolve("backups");
        assertTrue(Files.exists(backupsDir));
        try (var backupEntries = Files.list(backupsDir)) {
            Path backupRoot = backupEntries.findFirst().orElseThrow();
            assertEquals("legacy-json", Files.readString(backupRoot.resolve("opencode.json")));
            assertEquals("legacy-jsonc", Files.readString(backupRoot.resolve("oh-my-opencode.jsonc")));
        }
    }

    @Test
    void useRestoresAlreadyProcessedFilesWhenSwitchFailsMidway() throws IOException {
        writeRepositoryMetadata("repo-local", new RepositoryConfigFile(List.of(new ProfileEntry("broken"))));
        Path sourceProfileDir = repositoriesCacheDir().resolve("repo-local").resolve("broken");
        Files.createDirectories(sourceProfileDir.resolve("nested"));
        Files.writeString(sourceProfileDir.resolve("aaa.json"), "{\"profile\":\"broken\"}");
        Files.writeString(sourceProfileDir.resolve("nested").resolve("config.json"), "{\"nested\":true}");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null))
            )
        );

        Path openCodeDir = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDir);
        Path existingTargetFile = openCodeDir.resolve("aaa.json");
        Files.writeString(existingTargetFile, "legacy-content");
        Files.writeString(openCodeDir.resolve("nested"), "this blocks nested directory creation");

        CommandResult result = execute("profile", "use", "broken");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Failed to switch active profile files"));
        assertTrue(Files.exists(existingTargetFile));
        assertTrue(Files.isRegularFile(existingTargetFile, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.isSymbolicLink(existingTargetFile));
        assertEquals("legacy-content", Files.readString(existingTargetFile));
        assertTrue(Files.isRegularFile(openCodeDir.resolve("nested"), LinkOption.NOFOLLOW_LINKS));

        OcpConfigFile configFile = readOcpConfig(Path.of(System.getProperty("ocp.config.dir"), "config.json"));
        assertEquals(null, configFile.config().activeProfile());
    }

    @Test
    void refreshPullsLatestChangesForProfileRepository() throws IOException, InterruptedException {
        RemoteRepositoryState state = createRemoteProfileRepository("ops");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-refresh", state.remoteUri(), null))
            )
        );

        runCommand(List.of("git", "clone", state.remoteUri(), state.localClone().toString()));
        Path outdatedFile = state.localClone().resolve("new-file.txt");
        assertTrue(Files.notExists(outdatedFile));

        Path updateWorktree = tempDir.resolve("update-worktree");
        runCommand(List.of("git", "clone", state.remoteUri(), updateWorktree.toString()));
        Files.writeString(updateWorktree.resolve("new-file.txt"), "new");
        runCommand(List.of("git", "-C", updateWorktree.toString(), "add", "new-file.txt"));
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

        CommandResult result = execute("profile", "refresh", "ops");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Refreshed profile `ops`"));
        assertTrue(Files.exists(outdatedFile));
    }

    @Test
    void refreshWithoutProfileNameRefreshesAllRepositories() throws IOException, InterruptedException {
        RemoteRepositoryState state = createRemoteProfileRepository("ops");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-refresh", state.remoteUri(), null))
            )
        );

        runCommand(List.of("git", "clone", state.remoteUri(), state.localClone().toString()));
        Path outdatedFile = state.localClone().resolve("new-file.txt");
        assertTrue(Files.notExists(outdatedFile));

        Path updateWorktree = tempDir.resolve("update-worktree-all");
        runCommand(List.of("git", "clone", state.remoteUri(), updateWorktree.toString()));
        Files.writeString(updateWorktree.resolve("new-file.txt"), "new");
        runCommand(List.of("git", "-C", updateWorktree.toString(), "add", "new-file.txt"));
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

        CommandResult result = execute("profile", "refresh");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Refreshed all repositories"));
        assertTrue(Files.exists(outdatedFile));
    }

    @Test
    void refreshPromptsWhenRepositoryHasLocalChangesAndCanDiscardThenRefresh() throws IOException, InterruptedException {
        RemoteRepositoryState state = createRemoteProfileRepository("ops");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-refresh", state.remoteUri(), null))
            )
        );

        runCommand(List.of("git", "clone", state.remoteUri(), state.localClone().toString()));
        Path localProfileFile = state.localClone().resolve("ops").resolve("opencode.json");
        Files.writeString(localProfileFile, "local-edit");

        CommandResult result = executeWithInput("1\n", "profile", "refresh", "ops");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Choose how to proceed"));
        assertTrue(result.stdout().contains("Diff:"));
        assertTrue(result.stdout().contains("Discarding local changes in repository `repo-refresh` and retrying refresh"));
        assertTrue(result.stdout().contains("Discarded local changes and refreshed profile `ops`."));
        assertEquals("{}", Files.readString(localProfileFile));
    }

    @Test
    void refreshPromptsWhenRepositoryHasLocalChangesAndCanCommitForcePushThenRefresh() throws IOException, InterruptedException {
        RemoteRepositoryState state = createRemoteProfileRepository("ops");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-refresh", state.remoteUri(), null))
            )
        );

        runCommand(List.of("git", "clone", state.remoteUri(), state.localClone().toString()));
        Path localProfileFile = state.localClone().resolve("ops").resolve("opencode.json");
        Files.writeString(localProfileFile, "local-edit");

        CommandResult result = executeWithInput("2\n", "profile", "refresh", "ops");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Choose how to proceed"));
        assertTrue(result.stdout().contains("Committing local changes and force-pushing repository `repo-refresh`"));
        assertTrue(result.stdout().contains("Committed local changes, force-pushed, and refreshed profile `ops`."));
    }

    @Test
    void refreshPromptsWhenRepositoryHasLocalChangesAndCanAbortWithoutChanges() throws IOException, InterruptedException {
        RemoteRepositoryState state = createRemoteProfileRepository("ops");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-refresh", state.remoteUri(), null))
            )
        );

        runCommand(List.of("git", "clone", state.remoteUri(), state.localClone().toString()));
        Path localProfileFile = state.localClone().resolve("ops").resolve("opencode.json");
        Files.writeString(localProfileFile, "local-edit");

        CommandResult result = executeWithInput("3\n", "profile", "refresh", "ops");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Choose how to proceed"));
        assertTrue(result.stderr().contains("Refresh cancelled"));
        assertEquals("local-edit", Files.readString(localProfileFile));
    }

    @Test
    void listShowsTableWithCommitMetadataAndUpdateFootnote() throws IOException, InterruptedException {
        RemoteRepositoryState state = createRemoteProfileRepository("ops");

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-refresh", state.remoteUri(), null))
            )
        );

        runCommand(List.of("git", "clone", state.remoteUri(), state.localClone().toString()));

        Path updateWorktree = tempDir.resolve("update-worktree-list");
        runCommand(List.of("git", "clone", state.remoteUri(), updateWorktree.toString()));
        Files.writeString(updateWorktree.resolve("new-file.txt"), "new");
        runCommand(List.of("git", "-C", updateWorktree.toString(), "add", "new-file.txt"));
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

        CommandResult result = execute("profile", "list");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("NAME"));
        assertTrue(result.stdout().contains("DESCRIPTION"));
        assertFalse(result.stdout().contains("REPOSITORY"));
        assertTrue(result.stdout().contains("VERSION"));
        assertTrue(result.stdout().contains("LAST UPDATED"));
        assertTrue(result.stdout().contains("MESSAGE"));
        assertTrue(result.stdout().contains("ops"));
        assertFalse(result.stdout().contains(state.remoteUri()));
        assertTrue(result.stdout().contains("❄"));
        assertTrue(result.stdout().contains("❄ Newer commits are available in remote repositories."));
    }

    @Test
    void listProfilesFromLocalRepositoryMetadata() throws IOException {
        writeRepositoryMetadata(
            "repo-local",
            new RepositoryConfigFile(
                List.of(
                    new ProfileEntry("corporate"),
                    new ProfileEntry("oss", "Open source setup")
                )
            )
        );

        writeOcpConfig(
            new OcpConfigFile(
                new OcpConfigOptions(),
                List.of(new RepositoryEntry("repo-local", "git@github.com:acme/repo-local.git", null))
            )
        );

        CommandResult result = execute("profile", "list");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("NAME"));
        assertTrue(result.stdout().contains("DESCRIPTION"));
        assertTrue(result.stdout().contains("corporate"));
        assertTrue(result.stdout().contains("oss"));
        assertTrue(result.stdout().contains("Open source"));
        assertTrue(result.stdout().contains("setup"));
        assertFalse(result.stdout().contains("git@github.com:acme/repo-local.git"));
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
                new OcpConfigOptions(),
                List.of(
                    new RepositoryEntry("repo-one", "git@github.com:acme/repo-one.git", null),
                    new RepositoryEntry("repo-two", "git@github.com:acme/repo-two.git", null)
                )
            )
        );

        CommandResult result = execute("profile", "list");

        assertEquals(0, result.exitCode());
        int alphaPosition = result.stdout().indexOf("alpha");
        int betaPosition = result.stdout().indexOf("beta");
        int deltaPosition = result.stdout().indexOf("delta");
        int gammaPosition = result.stdout().indexOf("gamma");
        assertTrue(alphaPosition > 0);
        assertTrue(alphaPosition < betaPosition);
        assertTrue(betaPosition < deltaPosition);
        assertTrue(deltaPosition < gammaPosition);
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
                new OcpConfigOptions(),
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
                new OcpConfigOptions(),
                    List.of(new RepositoryEntry("repo-remote", gitServer.getGitRepoURIAsHttp().toString(), null))
                )
            );

            CommandResult result = execute("profile", "list");

            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains("remote-team"));
            assertTrue(result.stdout().contains("NAME"));
            assertTrue(Files.exists(localClone.resolve(".git")));
        }
    }

    private CommandResult execute(String... args) {
        return executeWithInput("", args);
    }

    private CommandResult executeWithInput(String input, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        InputStream originalIn = System.in;

        try {
            System.setOut(new PrintStream(stdout));
            System.setErr(new PrintStream(stderr));
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            int exitCode = PicocliRunner.execute(OcpCommand.class, args);
            return new CommandResult(exitCode, stdout.toString(), stderr.toString());
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            System.setIn(originalIn);
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

    private RepositoryConfigFile readRepositoryMetadata(Path repositoryConfigFile) throws IOException {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            return objectMapper.readValue(Files.readString(repositoryConfigFile), RepositoryConfigFile.class);
        }
    }

    private OcpConfigFile readOcpConfig(Path configPath) throws IOException {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            return objectMapper.readValue(Files.readString(configPath), OcpConfigFile.class);
        }
    }

    private Map<String, Object> readJsonMap(Path jsonPath) throws IOException {
        try (ApplicationContext context = ApplicationContext.run()) {
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            return objectMapper.readValue(Files.readString(jsonPath), Map.class);
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

    private static String removeAnsiCodes(String output) {
        return output.replaceAll("\\u001B\\[[;\\d]*m", "");
    }

    private RemoteRepositoryState createRemoteProfileRepository(String profileName) throws IOException, InterruptedException {
        Path seedRepository = tempDir.resolve("seed-refresh");
        runCommand(List.of("git", "init", seedRepository.toString()));
        Files.createDirectories(seedRepository.resolve(profileName));
        Files.writeString(
            seedRepository.resolve("repository.json"),
            serializeAsJson(new RepositoryConfigFile(List.of(new ProfileEntry(profileName))))
        );
        Files.writeString(seedRepository.resolve(profileName).resolve("opencode.json"), "{}");
        runCommand(List.of("git", "-C", seedRepository.toString(), "add", "repository.json", profileName + "/opencode.json"));
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

        Path remoteRepository = tempDir.resolve("repo-refresh.git");
        runCommand(List.of("git", "init", "--bare", remoteRepository.toString()));
        runCommand(List.of("git", "-C", seedRepository.toString(), "remote", "add", "origin", remoteRepository.toString()));
        runCommand(List.of("git", "-C", seedRepository.toString(), "push", "origin", "HEAD"));

        return new RemoteRepositoryState(remoteRepository.toUri().toString(), repositoriesCacheDir().resolve("repo-refresh"));
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }

    private record RemoteRepositoryState(String remoteUri, Path localClone) {
    }
}
