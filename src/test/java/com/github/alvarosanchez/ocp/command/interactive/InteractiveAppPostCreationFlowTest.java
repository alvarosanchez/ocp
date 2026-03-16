package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.command.Cli;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveAppPostCreationFlowTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private String previousConfigDir;
    private String previousCacheDir;
    private String previousOpenCodeConfigDir;
    private String previousWorkingDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        Cli.init();
        previousConfigDir = System.getProperty("ocp.config.dir");
        previousCacheDir = System.getProperty("ocp.cache.dir");
        previousOpenCodeConfigDir = System.getProperty("ocp.opencode.config.dir");
        previousWorkingDir = System.getProperty("ocp.working.dir");
        System.setProperty("ocp.config.dir", tempDir.resolve("config").toString());
        System.setProperty("ocp.cache.dir", tempDir.resolve("cache").toString());
        System.setProperty("ocp.opencode.config.dir", tempDir.resolve("opencode").toString());
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
        if (previousOpenCodeConfigDir == null) {
            System.clearProperty("ocp.opencode.config.dir");
        } else {
            System.setProperty("ocp.opencode.config.dir", previousOpenCodeConfigDir);
        }
        if (previousWorkingDir == null) {
            System.clearProperty("ocp.working.dir");
        } else {
            System.setProperty("ocp.working.dir", previousWorkingDir);
        }
    }

    @Test
    void addLocalRepositoryStartsPostCreationPromptWithGitInitDefaultNo() throws Exception {
        Path localRepository = tempDir.resolve("local-repository");
        Files.createDirectories(localRepository);

        InteractiveApp app = createApp();
        PromptState prompt = PromptState.multi(PromptAction.ADD_REPOSITORY, "Add existing repository", java.util.List.of("Repository URI or local path", "Repository name"));
        prompt.values.set(0, localRepository.toString());
        prompt.values.set(1, "local-repository");

        app.testSetPrompt(prompt);
        app.testApplyPrompt();

        PromptState postCreationPrompt = app.testPrompt();
        assertNotNull(postCreationPrompt);
        assertEquals(PromptAction.POST_CREATION_GIT_INIT, postCreationPrompt.action);
        assertEquals("no", postCreationPrompt.values.getFirst());
    }

    @Test
    void createRepositoryStartsPostCreationPromptWithGitInitDefaultYes() throws Exception {
        Path workspace = Path.of(System.getProperty("ocp.working.dir"));
        Files.createDirectories(workspace);

        InteractiveApp app = createApp();
        PromptState prompt = PromptState.multi(PromptAction.CREATE_REPOSITORY, "Create repository", java.util.List.of("Repository name", "Repository location path", "Initial profile name (optional)"));
        prompt.values.set(0, "team-repo");
        prompt.values.set(1, "");
        prompt.values.set(2, "");

        app.testSetPrompt(prompt);
        app.testApplyPrompt();

        PromptState postCreationPrompt = app.testPrompt();
        assertNotNull(postCreationPrompt);
        assertEquals(PromptAction.POST_CREATION_GIT_INIT, postCreationPrompt.action);
        assertEquals("yes", postCreationPrompt.values.getFirst());
    }

    @Test
    void createRepositoryFromFileSelectionStillStartsPostCreationPrompt() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("opencode.json");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(filePath, "{}");

        RepositoryService repositoryService = applicationContext.getBean(RepositoryService.class);
        repositoryService.add(repositoryPath.toString(), "repo-a");

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.file("repo-a", "default", filePath));

        PromptState prompt = PromptState.multi(PromptAction.CREATE_REPOSITORY, "Create repository", java.util.List.of("Repository name", "Repository location path", "Initial profile name (optional)"));
        prompt.values.set(0, "team-repo");
        prompt.values.set(1, "");
        prompt.values.set(2, "");
        app.testSetPrompt(prompt);
        app.testApplyPrompt();

        PromptState postCreationPrompt = app.testPrompt();
        assertNotNull(postCreationPrompt);
        assertEquals(PromptAction.POST_CREATION_GIT_INIT, postCreationPrompt.action);
        assertEquals("yes", postCreationPrompt.values.getFirst());
    }

    @Test
    void onboardingSuccessTransitionsToSharedPostCreationPrompt() throws Exception {
        Path openCodeDirectory = Path.of(System.getProperty("ocp.opencode.config.dir"));
        Files.createDirectories(openCodeDirectory);
        Files.writeString(openCodeDirectory.resolve("opencode.json"), "{\"model\":\"gpt-5\"}");

        InteractiveApp app = createApp();
        PromptState prompt = PromptState.single(
            PromptAction.ONBOARD_EXISTING_CONFIG_PROFILE_NAME,
            "Create onboarding profile",
            "Profile name"
        );
        prompt.contextRepositoryName = "personal-repo";
        prompt.values.set(0, "personal");

        app.testSetPrompt(prompt);
        app.testApplyPrompt();

        PromptState postCreationPrompt = app.testPrompt();
        assertNotNull(postCreationPrompt);
        assertEquals(PromptAction.POST_CREATION_GIT_INIT, postCreationPrompt.action);
        assertEquals("yes", postCreationPrompt.values.getFirst());
    }

    @Test
    void migrateSelectedFileBasedRepositoryStartsPostCreationPromptWithGitInitDefaultNo() throws Exception {
        Path localRepository = tempDir.resolve("existing-local-repository");
        Files.createDirectories(localRepository);

        RepositoryService repositoryService = applicationContext.getBean(RepositoryService.class);
        repositoryService.add(localRepository.toString(), "existing-local-repository");

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("existing-local-repository", localRepository));

        app.testMigrateSelectedRepository();

        PromptState postCreationPrompt = app.testPrompt();
        assertNotNull(postCreationPrompt);
        assertEquals(PromptAction.POST_CREATION_GIT_INIT, postCreationPrompt.action);
        assertEquals("no", postCreationPrompt.values.getFirst());
    }

    @Test
    void migrateSelectedFileBasedRepositoryPersistsExistingOriginWithoutPrompt() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        Path localRepository = tempDir.resolve("existing-origin-repository");
        Files.createDirectories(localRepository);
        runCommand(List.of("git", "init", localRepository.toString()));
        runCommand(List.of("git", "-C", localRepository.toString(), "remote", "add", "origin", "git@github.com:acme/existing-origin-repository.git"));

        RepositoryService repositoryService = applicationContext.getBean(RepositoryService.class);
        repositoryService.add(localRepository.toString(), "existing-origin-repository");

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("existing-origin-repository", localRepository));

        app.testMigrateSelectedRepository();

        assertNull(app.testPrompt());
        assertTrue(app.testStatus().contains("Saved origin URI git@github.com:acme/existing-origin-repository.git"));
        RepositoryEntry repositoryEntry = repositoryService.load().getFirst();
        assertEquals("git@github.com:acme/existing-origin-repository.git", repositoryEntry.uri());
        assertEquals(localRepository.toAbsolutePath().normalize().toString(), repositoryEntry.localPath());
    }

    @Test
    void migrateDoesNotStartFromProfileSelectionInsideFileBasedRepository() throws Exception {
        Path localRepository = tempDir.resolve("profile-selected-repository");
        Files.createDirectories(localRepository.resolve("profile"));

        RepositoryService repositoryService = applicationContext.getBean(RepositoryService.class);
        repositoryService.add(localRepository.toString(), "profile-selected-repository");

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.profile("profile-selected-repository", "profile", localRepository.resolve("profile")));

        app.testMigrateSelectedRepository();

        assertNull(app.testPrompt());
        assertEquals("Select a repository, profile, or file first.", app.testStatus());
    }

    @Test
    void postCreationFlowExecutesAndWrapsLongStatus() throws Exception {
        Path workspace = Path.of(System.getProperty("ocp.working.dir"));
        Files.createDirectories(workspace);

        InteractiveApp app = createApp();

        PromptState prompt = PromptState.multi(PromptAction.CREATE_REPOSITORY, "Create repository", java.util.List.of("Repository name", "Repository location path", "Initial profile name (optional)"));
        prompt.values.set(0, "test-repo");
        prompt.values.set(1, "");
        prompt.values.set(2, "");
        app.testSetPrompt(prompt);
        app.testApplyPrompt();

        PromptState gitInitPrompt = app.testPrompt();
        assertNotNull(gitInitPrompt);
        assertEquals(PromptAction.POST_CREATION_GIT_INIT, gitInitPrompt.action);
        gitInitPrompt.values.set(0, "yes");
        app.testSetPrompt(gitInitPrompt);
        app.testApplyPrompt();

        PromptState nextPrompt = app.testPrompt();
        if (nextPrompt != null) {
            assertEquals(PromptAction.POST_CREATION_PUBLISH_GITHUB, nextPrompt.action);
            nextPrompt.values.set(0, "no");
            app.testSetPrompt(nextPrompt);
            app.testApplyPrompt();
            assertNull(app.testPrompt());
        }

        String finalStatus = app.testStatus();
        assertTrue(finalStatus.contains("Created and added repository test-repo"));
        assertTrue(finalStatus.contains("Initialized git repository."));
        assertTrue(finalStatus.contains("Created an initial commit."));
    }

    @Test
    void createRepositorySelectsCreatedRepositoryNode() throws Exception {
        Path workspace = Path.of(System.getProperty("ocp.working.dir"));
        Files.createDirectories(workspace);

        InteractiveApp app = createApp();

        PromptState prompt = PromptState.multi(PromptAction.CREATE_REPOSITORY, "Create repository", java.util.List.of("Repository name", "Repository location path", "Initial profile name (optional)"));
        prompt.values.set(0, "selected-repo");
        prompt.values.set(1, "");
        prompt.values.set(2, "");
        app.testSetPrompt(prompt);
        app.testApplyPrompt();

        PromptState postCreationPrompt = app.testPrompt();
        assertNotNull(postCreationPrompt);
        assertEquals(PromptAction.POST_CREATION_GIT_INIT, postCreationPrompt.action);
        postCreationPrompt.values.set(0, "no");
        app.testSetPrompt(postCreationPrompt);
        app.testApplyPrompt();

        NodeRef selectedNode = app.testSelectedNode();
        assertNotNull(selectedNode);
        assertEquals(NodeKind.REPOSITORY, selectedNode.kind());
        assertEquals("selected-repo", selectedNode.repositoryName());
    }

    @Test
    void deleteFileBasedRepositoryWithFolderDoesNotEndWithNotConfiguredError() throws Exception {
        Path localRepository = tempDir.resolve("delete-me");
        Files.createDirectories(localRepository);
        Files.writeString(localRepository.resolve("repository.json"), "{\"profiles\":[]}");

        RepositoryService repositoryService = applicationContext.getBean(RepositoryService.class);
        repositoryService.add(localRepository.toString(), "delete-me");

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("delete-me", localRepository));

        PromptState prompt = PromptState.multiWithOptions(
            PromptAction.DELETE_REPOSITORY_FILE_BASED,
            "Delete file-based repository",
            List.of("Type repository name to confirm: delete-me", "Delete local folder as well?"),
            List.of(List.of(), List.of("no", "yes"))
        );
        prompt.expectedConfirmation = "delete-me";
        prompt.values.set(0, "delete-me");
        prompt.values.set(1, "yes");
        app.testSetPrompt(prompt);
        app.testApplyPrompt();

        assertEquals("Deleted repository delete-me and local folder.", app.testStatus());
    }

    @Test
    void inspectDeleteForDirtyGitRepositoryRequiresForcePromptVariant() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        Path localRepository = tempDir.resolve("dirty-repo");
        Files.createDirectories(localRepository);
        runCommand(List.of("git", "init", localRepository.toString()));
        Files.writeString(localRepository.resolve("dirty.txt"), "changes\n");

        writeConfig(new RepositoryEntry("dirty-repo", "git@github.com:acme/dirty-repo.git", localRepository.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("dirty-repo", localRepository));
        invokeOpenDeletePromptForSelectedNode(app);

        PromptState prompt = app.testPrompt();

        assertEquals(PromptAction.DELETE_REPOSITORY_FORCE, prompt.action);
        assertEquals("dirty-repo", prompt.expectedConfirmation);
        assertEquals("Type repository name to force delete: dirty-repo", prompt.labels.getFirst());
    }

    @Test
    void inspectDeleteForCleanGitRepositoryUsesSimpleDeletePromptVariant() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        Path localRepository = tempDir.resolve("clean-repo");
        Files.createDirectories(localRepository);
        runCommand(List.of("git", "init", localRepository.toString()));

        writeConfig(new RepositoryEntry("clean-repo", "git@github.com:acme/clean-repo.git", localRepository.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("clean-repo", localRepository));
        invokeOpenDeletePromptForSelectedNode(app);

        PromptState prompt = app.testPrompt();

        assertEquals(PromptAction.DELETE_REPOSITORY, prompt.action);
        assertEquals("clean-repo", prompt.expectedConfirmation);
        assertEquals("Type repository name to confirm: clean-repo", prompt.labels.getFirst());
    }

    @Test
    void inspectDeleteForFileBasedRepositoryUsesDeleteLocalFolderPromptVariant() throws Exception {
        Path localRepository = tempDir.resolve("file-based-repo");
        Files.createDirectories(localRepository);
        Files.writeString(localRepository.resolve("repository.json"), "{\"profiles\":[]}");

        RepositoryService repositoryService = applicationContext.getBean(RepositoryService.class);
        repositoryService.add(localRepository.toString(), "file-based-repo");

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.repository("file-based-repo", localRepository));
        invokeOpenDeletePromptForSelectedNode(app);

        PromptState prompt = app.testPrompt();

        assertEquals(PromptAction.DELETE_REPOSITORY_FILE_BASED, prompt.action);
        assertEquals("file-based-repo", prompt.expectedConfirmation);
        assertEquals(List.of("no", "yes"), prompt.options.get(1));
        assertEquals("Delete local folder as well?", prompt.labels.get(1));
    }

    private InteractiveApp createApp() {
        return new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            applicationContext.getBean(ObjectMapper.class)
        );
    }

    private void writeConfig(RepositoryEntry repositoryEntry) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(
            configDir.resolve("config.json"),
            applicationContext.getBean(ObjectMapper.class).writeValueAsString(
                new com.github.alvarosanchez.ocp.config.OcpConfigFile(
                    new com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions(),
                    List.of(repositoryEntry)
                )
            )
        );
    }

    private static void invokeOpenDeletePromptForSelectedNode(InteractiveApp app) throws Exception {
        app.testOpenDeletePromptForSelectedNode();
    }

    private static void runCommand(List<String> command) throws Exception {
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
        } catch (Exception e) {
            return false;
        }
    }
}
