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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

        setPrompt(app, prompt);
        invokeApplyPrompt(app);

        PromptState postCreationPrompt = readPrompt(app);
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

        setPrompt(app, prompt);
        invokeApplyPrompt(app);

        PromptState postCreationPrompt = readPrompt(app);
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
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.file("repo-a", "default", filePath));

        PromptState prompt = PromptState.multi(PromptAction.CREATE_REPOSITORY, "Create repository", java.util.List.of("Repository name", "Repository location path", "Initial profile name (optional)"));
        prompt.values.set(0, "team-repo");
        prompt.values.set(1, "");
        prompt.values.set(2, "");
        setPrompt(app, prompt);

        invokeApplyPrompt(app);

        PromptState postCreationPrompt = readPrompt(app);
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

        setPrompt(app, prompt);
        invokeApplyPrompt(app);

        PromptState postCreationPrompt = readPrompt(app);
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
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.repository("existing-local-repository", localRepository));

        invokeMigrateSelectedRepository(app);

        PromptState postCreationPrompt = readPrompt(app);
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
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.repository("existing-origin-repository", localRepository));

        invokeMigrateSelectedRepository(app);

        assertNull(readPrompt(app));
        assertTrue(readStatus(app).contains("Saved origin URI git@github.com:acme/existing-origin-repository.git"));
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
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.profile("profile-selected-repository", "profile", localRepository.resolve("profile")));

        invokeMigrateSelectedRepository(app);

        assertNull(readPrompt(app));
        assertEquals("Select a repository, profile, or file first.", readStatus(app));
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
        setPrompt(app, prompt);
        invokeApplyPrompt(app);

        PromptState gitInitPrompt = readPrompt(app);
        assertNotNull(gitInitPrompt);
        assertEquals(PromptAction.POST_CREATION_GIT_INIT, gitInitPrompt.action);
        gitInitPrompt.values.set(0, "yes");
        setPrompt(app, gitInitPrompt);
        invokeApplyPrompt(app);

        PromptState nextPrompt = readPrompt(app);
        if (nextPrompt != null) {
            assertEquals(PromptAction.POST_CREATION_PUBLISH_GITHUB, nextPrompt.action);
            nextPrompt.values.set(0, "no");
            setPrompt(app, nextPrompt);
            invokeApplyPrompt(app);
            assertNull(readPrompt(app));
        }

        String finalStatus = readStatus(app);
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
        setPrompt(app, prompt);
        invokeApplyPrompt(app);

        PromptState postCreationPrompt = readPrompt(app);
        assertNotNull(postCreationPrompt);
        assertEquals(PromptAction.POST_CREATION_GIT_INIT, postCreationPrompt.action);
        postCreationPrompt.values.set(0, "no");
        setPrompt(app, postCreationPrompt);
        invokeApplyPrompt(app);

        NodeRef selectedNode = readSelectedNode(app);
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
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.repository("delete-me", localRepository));

        PromptState prompt = PromptState.multiWithOptions(
            PromptAction.DELETE_REPOSITORY_FILE_BASED,
            "Delete file-based repository",
            List.of("Type repository name to confirm: delete-me", "Delete local folder as well?"),
            List.of(List.of(), List.of("no", "yes"))
        );
        prompt.expectedConfirmation = "delete-me";
        prompt.values.set(0, "delete-me");
        prompt.values.set(1, "yes");
        setPrompt(app, prompt);

        invokeApplyPrompt(app);

        assertEquals("Deleted repository delete-me and local folder.", readStatus(app));
    }

    @Test
    void inspectDeleteForDirtyGitRepositoryRequiresForcePromptVariant() throws Exception {
        Assumptions.assumeTrue(isGitAvailable(), "git executable is required for this test");
        Path localRepository = tempDir.resolve("dirty-repo");
        Files.createDirectories(localRepository);
        runCommand(List.of("git", "init", localRepository.toString()));
        Files.writeString(localRepository.resolve("dirty.txt"), "changes\n");

        RepositoryService repositoryService = applicationContext.getBean(RepositoryService.class);
        writeConfig(new RepositoryEntry("dirty-repo", "git@github.com:acme/dirty-repo.git", localRepository.toString()));

        RepositoryService.RepositoryDeletePreview deletePreview = repositoryService.inspectDelete("dirty-repo");

        assertTrue(deletePreview.gitBacked());
        assertTrue(deletePreview.hasLocalChanges());

        PromptState prompt = PromptState.single(
            PromptAction.DELETE_REPOSITORY_FORCE,
            "Delete repository (local changes detected)",
            "Type repository name to force delete: dirty-repo"
        );
        prompt.expectedConfirmation = "dirty-repo";

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

        RepositoryService repositoryService = applicationContext.getBean(RepositoryService.class);
        writeConfig(new RepositoryEntry("clean-repo", "git@github.com:acme/clean-repo.git", localRepository.toString()));

        RepositoryService.RepositoryDeletePreview deletePreview = repositoryService.inspectDelete("clean-repo");

        assertTrue(deletePreview.gitBacked());
        assertFalse(deletePreview.hasLocalChanges());

        PromptState prompt = PromptState.single(
            PromptAction.DELETE_REPOSITORY,
            "Delete repository",
            "Type repository name to confirm: clean-repo"
        );
        prompt.expectedConfirmation = "clean-repo";

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

        RepositoryService.RepositoryDeletePreview deletePreview = repositoryService.inspectDelete("file-based-repo");

        assertFalse(deletePreview.gitBacked());
        assertFalse(deletePreview.hasLocalChanges());

        PromptState prompt = PromptState.multiWithOptions(
            PromptAction.DELETE_REPOSITORY_FILE_BASED,
            "Delete file-based repository",
            List.of("Type repository name to confirm: file-based-repo", "Delete local folder as well?"),
            List.of(List.of(), List.of("no", "yes"))
        );
        prompt.expectedConfirmation = "file-based-repo";

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

    private static void invokeApplyPrompt(InteractiveApp app) throws Exception {
        Method applyPrompt = InteractiveApp.class.getDeclaredMethod("applyPrompt");
        applyPrompt.setAccessible(true);
        applyPrompt.invoke(app);
    }

    private static void invokeMigrateSelectedRepository(InteractiveApp app) throws Exception {
        Method migrateSelectedRepository = InteractiveApp.class.getDeclaredMethod("migrateSelectedRepository");
        migrateSelectedRepository.setAccessible(true);
        migrateSelectedRepository.invoke(app);
    }

    private static void invokeReloadState(InteractiveApp app) throws Exception {
        Method reloadState = InteractiveApp.class.getDeclaredMethod("reloadState");
        reloadState.setAccessible(true);
        reloadState.invoke(app);
    }

    private static void setPrompt(InteractiveApp app, PromptState prompt) throws Exception {
        Field promptField = InteractiveApp.class.getDeclaredField("prompt");
        promptField.setAccessible(true);
        promptField.set(app, prompt);
    }

    private static PromptState readPrompt(InteractiveApp app) throws Exception {
        Field promptField = InteractiveApp.class.getDeclaredField("prompt");
        promptField.setAccessible(true);
        return (PromptState) promptField.get(app);
    }

    private static void setSelectedNode(InteractiveApp app, NodeRef nodeRef) throws Exception {
        Field selectedNodeField = InteractiveApp.class.getDeclaredField("selectedNode");
        selectedNodeField.setAccessible(true);
        selectedNodeField.set(app, nodeRef);
    }


    private static NodeRef readSelectedNode(InteractiveApp app) throws Exception {
        Field selectedNodeField = InteractiveApp.class.getDeclaredField("selectedNode");
        selectedNodeField.setAccessible(true);
        return (NodeRef) selectedNodeField.get(app);
    }

    private static String readStatus(InteractiveApp app) throws Exception {
        Field statusField = InteractiveApp.class.getDeclaredField("status");
        statusField.setAccessible(true);
        return (String) statusField.get(app);
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
