package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.command.Cli;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import dev.tamboui.toolkit.elements.TreeElement;
import dev.tamboui.widgets.tree.TreeNode;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveAppFileActionsTest {

    @TempDir
    Path tempDir;

    private ApplicationContext applicationContext;
    private ObjectMapper objectMapper;
    private String previousConfigDir;
    private String previousCacheDir;
    private String previousOpenCodeConfigDir;
    private String previousWorkingDir;

    @BeforeEach
    void setUp() {
        applicationContext = ApplicationContext.run();
        objectMapper = applicationContext.getBean(ObjectMapper.class);
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
        restoreProperty("ocp.config.dir", previousConfigDir);
        restoreProperty("ocp.cache.dir", previousCacheDir);
        restoreProperty("ocp.opencode.config.dir", previousOpenCodeConfigDir);
        restoreProperty("ocp.working.dir", previousWorkingDir);
    }

    @Test
    void createFileFromProfileSelectionCreatesEmptyFileAndEntersEditMode() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.profile("repo-a", "default", profilePath));

        PromptState prompt = PromptState.single(PromptAction.CREATE_FILE, "Create file", "File name");
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "new.json");
        app.testSetPrompt(prompt);

        app.testApplyPrompt();

        Path createdFile = profilePath.resolve("new.json");
        assertTrue(Files.exists(createdFile));
        assertEquals("", Files.readString(createdFile));
        assertEquals(createdFile, app.testSelectedNode().path());
        assertTrue(app.testSelectTreeNode(node -> createdFile.equals(node.path())));
        assertEquals(createdFile, app.testSelectedNode().path());
        assertTrue(app.testEditMode());
        assertEquals(Pane.DETAIL, app.testActivePane());
        assertEquals("Editing new.json. Ctrl+S to save, Esc to cancel.", app.testStatus());
        assertEquals("", app.testEditorState().text());
    }

    @Test
    void createFileKeepsCreatedFileAsSelectedTreeNode() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.profile("repo-a", "default", profilePath));

        PromptState prompt = PromptState.single(PromptAction.CREATE_FILE, "Create file", "File name");
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "selected.json");
        app.testSetPrompt(prompt);

        app.testApplyPrompt();

        NodeRef selectedNode = app.testSelectedNode();
        assertNotNull(selectedNode);
        assertEquals(NodeKind.FILE, selectedNode.kind());
        assertEquals(profilePath.resolve("selected.json"), selectedNode.path());
        assertEquals("default", selectedNode.profileName());
        assertTrue(app.testSelectTreeNode(node -> profilePath.resolve("selected.json").equals(node.path())));
        assertEquals(profilePath.resolve("selected.json"), app.testSelectedNode().path());
    }

    @Test
    void createFileFromFileSelectionCreatesSiblingFileInSameProfile() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path existingFile = profilePath.resolve("opencode.json");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(existingFile, "{}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.file("repo-a", "default", existingFile));

        PromptState prompt = PromptState.single(PromptAction.CREATE_FILE, "Create file", "File name");
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "extra.json");
        app.testSetPrompt(prompt);

        app.testApplyPrompt();

        assertTrue(Files.exists(profilePath.resolve("extra.json")));
        assertEquals(profilePath.resolve("extra.json"), app.testSelectedNode().path());
        assertTrue(app.testSelectTreeNode(node -> profilePath.resolve("extra.json").equals(node.path())));
        assertEquals(profilePath.resolve("extra.json"), app.testSelectedNode().path());
        assertTrue(app.testEditMode());
    }

    @Test
    void deleteFileRemovesEditableFileAndReturnsSelectionToProfile() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("opencode.json");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(filePath, "{}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.file("repo-a", "default", filePath));

        PromptState prompt = PromptState.single(PromptAction.DELETE_FILE, "Delete file", "Type file name to confirm: opencode.json");
        prompt.expectedConfirmation = "opencode.json";
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "opencode.json");
        app.testSetPrompt(prompt);

        app.testApplyPrompt();

        assertFalse(Files.exists(filePath));
        NodeRef selectedNode = app.testSelectedNode();
        assertNotNull(selectedNode);
        assertEquals(NodeKind.PROFILE, selectedNode.kind());
        assertEquals("default", selectedNode.profileName());
        assertEquals(profilePath, selectedNode.path());
        assertEquals("Deleted file opencode.json.", app.testStatus());
        assertFalse(app.testEditMode());
    }

    @Test
    void deleteFileMismatchLeavesFileUntouched() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("opencode.json");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(filePath, "{}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.file("repo-a", "default", filePath));

        PromptState prompt = PromptState.single(PromptAction.DELETE_FILE, "Delete file", "Type file name to confirm: opencode.json");
        prompt.expectedConfirmation = "opencode.json";
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "wrong.json");
        app.testSetPrompt(prompt);

        app.testApplyPrompt();

        assertTrue(Files.exists(filePath));
        assertEquals("Delete cancelled: file name mismatch.", app.testStatus());
    }

    @Test
    void copySelectedPathCopiesAbsoluteNormalizedPathForFileNode() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("subdir").resolve("opencode.json");
        Files.createDirectories(filePath.getParent());
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(filePath, "{}\n");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        AtomicReference<String> copiedValue = new AtomicReference<>();
        InteractiveApp app = createApp(new InteractiveClipboardClient() {
            @Override
            void copy(String value) {
                copiedValue.set(value);
            }
        });
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.file("repo-a", "default", filePath));

        app.testCopySelectedPath();

        String expectedPath = filePath.toAbsolutePath().normalize().toString();
        assertEquals(expectedPath, copiedValue.get());
        assertEquals("Copied path " + expectedPath + " to the clipboard.", app.testStatus());
    }

    @Test
    void copySelectedPathRejectsNonFileSelection() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.profile("repo-a", "default", profilePath));

        app.testCopySelectedPath();

        assertEquals("Select a file first.", app.testStatus());
    }

    @Test
    void copySelectedPathReportsClipboardUnavailableMessage() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("opencode.json");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(filePath, "{}\n");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp(new InteractiveClipboardClient() {
            @Override
            void copy(String value) {
                throw new IllegalStateException("Clipboard is unavailable for tests.");
            }
        });
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.file("repo-a", "default", filePath));

        app.testCopySelectedPath();

        assertEquals("Clipboard is unavailable for tests.", app.testStatus());
    }

    private InteractiveApp createApp() {
        return createApp(new InteractiveClipboardClient());
    }

    private InteractiveApp createApp(InteractiveClipboardClient clipboardClient) {
        return new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            objectMapper,
            new BatPreviewRenderer(),
            clipboardClient
        );
    }

    private void writeConfig(RepositoryEntry repositoryEntry) throws IOException {
        Path configDir = Path.of(System.getProperty("ocp.config.dir"));
        Files.createDirectories(configDir);
        Files.writeString(
            configDir.resolve("config.json"),
            objectMapper.writeValueAsString(new OcpConfigFile(new OcpConfigOptions(), List.of(repositoryEntry)))
        );
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
