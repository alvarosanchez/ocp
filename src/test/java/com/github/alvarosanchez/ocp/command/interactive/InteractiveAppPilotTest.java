package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.alvarosanchez.ocp.command.Cli;
import com.github.alvarosanchez.ocp.config.OcpConfigFile;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.OcpConfigOptions;
import com.github.alvarosanchez.ocp.config.OcpConfigFile.RepositoryEntry;
import com.github.alvarosanchez.ocp.service.OnboardingService;
import com.github.alvarosanchez.ocp.service.ProfileService;
import com.github.alvarosanchez.ocp.service.RepositoryPostCreationService;
import com.github.alvarosanchez.ocp.service.RepositoryService;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.elements.TreeElement;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.widgets.tree.TreeNode;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveAppPilotTest {

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
    void createFileOpensEditorInsteadOfPreview() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Files.createDirectories(profilePath);
        Files.writeString(profilePath.resolve("f1.json"), "{}\n");
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.onStart();

        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(app::render)) {
            Pilot pilot = test.pilot();

            pilot.press(KeyCode.RIGHT);
            pilot.pause();
            pilot.press(KeyCode.DOWN);
            pilot.pause();
            assertEquals(2, readTreeSelectionIndex(app));
            pilot.press('e');
            pilot.pause();

            assertTrue(readStatus(app).contains("Editing f1.json."));

            pilot.press(KeyCode.ESCAPE);
            pilot.pause();
            pilot.press(KeyCode.LEFT);
            pilot.pause();
            pilot.press(KeyCode.UP);
            pilot.pause();
            pilot.press(KeyCode.RIGHT);
            pilot.pause();
            pilot.press('f');
            pilot.pause();
            for (char character : "pilot.json".toCharArray()) {
                pilot.press(character);
            }
            pilot.pause();
            pilot.press(KeyCode.ENTER);
            pilot.pause();

            assertTrue(readTreeSelectionIndex(app) >= 2);
            assertTrue(pilot.hasElement("file-editor"));
            assertFalse(pilot.hasElement("detail-pane"));
            assertTrue(readEditMode(app));
            assertEquals(3, readTreeSelectionIndex(app), "Unexpected tree selection index after create-file");
            assertEquals("pilot.json", readSelectedNodeFileName(app));
            assertEquals("pilot.json", readSelectedTreeNodeFileName(app));
            assertTrue(readStatus(app).contains("Editing pilot.json."));
            assertFalse(readStatus(app).contains("Editing f1.json."));
        }
    }

    @Test
    void inheritedFileSupportsParentNavigationAndBlocksEditing() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path baseProfilePath = repositoryPath.resolve("base");
        Path childProfilePath = repositoryPath.resolve("child");
        Path inheritedFilePath = baseProfilePath.resolve("opencode.json");
        Files.createDirectories(baseProfilePath);
        Files.createDirectories(childProfilePath);
        Files.writeString(
            repositoryPath.resolve("repository.json"),
            "{\"profiles\":[{\"name\":\"base\"},{\"name\":\"child\",\"extends_from\":\"base\"}]}"
        );
        Files.writeString(inheritedFilePath, "{\"base\":true}\n");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.onStart();

        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(app::render)) {
            Pilot pilot = test.pilot();

            pressDown(pilot, 4);
            assertEquals("opencode.json", readSelectedNodeFileName(app));
            assertTrue(readSelectedNode(app).inherited());

            pilot.press('e');
            pilot.pause();
            assertFalse(readEditMode(app));
            assertFalse(pilot.hasElement("file-editor"));
            assertTrue(readStatus(app).contains("Inherited file is read-only"));

            pilot.press('p');
            pilot.pause();
            assertEquals("base", readSelectedNode(app).profileName());
            assertEquals(inheritedFilePath, readSelectedNode(app).path());
            assertFalse(readSelectedNode(app).inherited());
            assertEquals("Selected inherited parent file from profile base.", readStatus(app));
        }
    }

    @Test
    void navigateToParentFromReadOnlyMergedFileSelectsLastContributingParentFile() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path baseOneRootPath = repositoryPath.resolve("base-one-root");
        Path baseOnePath = repositoryPath.resolve("base-one");
        Path baseTwoRootPath = repositoryPath.resolve("base-two-root");
        Path baseTwoPath = repositoryPath.resolve("base-two");
        Path childProfilePath = repositoryPath.resolve("child");
        Files.createDirectories(baseOneRootPath);
        Files.createDirectories(baseOnePath);
        Files.createDirectories(baseTwoRootPath);
        Files.createDirectories(baseTwoPath);
        Files.createDirectories(childProfilePath);
        Path baseOneRootFile = baseOneRootPath.resolve("opencode.json");
        Path baseTwoRootFile = baseTwoRootPath.resolve("opencode.json");
        Files.writeString(baseOneRootFile, "{\"baseOne\":true}\n");
        Files.writeString(baseTwoRootFile, "{\"baseTwo\":true}\n");
        Files.writeString(
            repositoryPath.resolve("repository.json"),
            "{\"profiles\":["
                + "{\"name\":\"base-one-root\"},"
                + "{\"name\":\"base-one\",\"extends_from\":\"base-one-root\"},"
                + "{\"name\":\"base-two-root\"},"
                + "{\"name\":\"base-two\",\"extends_from\":\"base-two-root\"},"
                + "{\"name\":\"child\",\"extends_from\":[\"base-one\",\"base-two\"]}"
                + "]}"
        );
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.onStart();

        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(app::render)) {
            Pilot pilot = test.pilot();

            boolean selected = app.testSelectTreeNodeAndSync(node ->
                node.kind() == NodeKind.FILE
                    && "child".equals(node.profileName())
                    && node.readOnly()
                    && node.parentOnlyMerged()
            );
            assertTrue(selected, "Expected merged child file to be selected");
            NodeRef childMergedNode = readSelectedNode(app);
            assertEquals("child", childMergedNode.profileName());
            assertTrue(childMergedNode.readOnly());
            assertTrue(childMergedNode.parentOnlyMerged());
            assertEquals(
                List.of("base-one-root", "base-two-root"),
                childMergedNode.contributorProfileNames(),
                "Contributor order should track full declared parent branch precedence"
            );

            pilot.press('p');
            pilot.pause();

            NodeRef parentNode = readSelectedNode(app);
            assertEquals("base-two-root", parentNode.profileName());
            assertEquals(baseTwoRootFile, parentNode.path());
            assertFalse(parentNode.readOnly());
            assertFalse(parentNode.parentOnlyMerged());
            assertEquals("Selected inherited parent file from profile base-two-root.", readStatus(app));
        }
    }

    @Test
    void refreshConflictOverlayKeepsFooterPanelsMounted() throws Exception {
        InteractiveApp app = createApp();
        app.onStart();
        app.testSetPendingRefreshOperation(RefreshOperation.singleRepository("repo-a"));
        app.testSetRefreshConflict(
            RefreshConflictState.forRepository(
                ProfileService.testRepositoryRefreshConflict("repo-a", tempDir.resolve("repo-a").toString(), "diff --git a/a b/a\n+foo")
            )
        );

        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(app::render)) {
            Pilot pilot = test.pilot();

            assertTrue(pilot.hasElement("shortcuts-panel"));
            assertTrue(pilot.hasElement("status-panel"));
            assertTrue(app.testRefreshConflict() != null);
        }
    }

    @Test
    void navigateToParentFromNestedReadOnlyMergedFileSelectsLastDeclaredParentBranchContributor() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path baseOneRootPath = repositoryPath.resolve("base-one-root");
        Path baseOnePath = repositoryPath.resolve("base-one");
        Path baseTwoRootPath = repositoryPath.resolve("base-two-root");
        Path baseTwoPath = repositoryPath.resolve("base-two");
        Path childProfilePath = repositoryPath.resolve("child");
        Files.createDirectories(baseOneRootPath);
        Files.createDirectories(baseOnePath);
        Files.createDirectories(baseTwoRootPath);
        Files.createDirectories(baseTwoPath);
        Files.createDirectories(childProfilePath);
        Path baseOneRootFile = baseOneRootPath.resolve("opencode.json");
        Path baseTwoRootFile = baseTwoRootPath.resolve("opencode.json");
        Files.writeString(baseOneRootFile, "{\"baseOne\":true}\n");
        Files.writeString(baseTwoRootFile, "{\"baseTwo\":true}\n");
        Files.writeString(
            repositoryPath.resolve("repository.json"),
            "{\"profiles\":["
                + "{\"name\":\"base-one-root\"},"
                + "{\"name\":\"base-one\",\"extends_from\":\"base-one-root\"},"
                + "{\"name\":\"base-two-root\"},"
                + "{\"name\":\"base-two\",\"extends_from\":\"base-two-root\"},"
                + "{\"name\":\"child\",\"extends_from\":[\"base-one\",\"base-two\"]}"
                + "]}"
        );
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.onStart();

        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(app::render)) {
            Pilot pilot = test.pilot();

            boolean selected = app.testSelectTreeNodeAndSync(node ->
                node.kind() == NodeKind.FILE
                    && "child".equals(node.profileName())
                    && node.readOnly()
                    && node.parentOnlyMerged()
            );
            assertTrue(selected, "Expected nested merged child file to be selected");
            NodeRef childMergedNode = readSelectedNode(app);
            assertEquals("child", childMergedNode.profileName());
            assertTrue(childMergedNode.readOnly());
            assertTrue(childMergedNode.parentOnlyMerged());

            assertEquals(
                List.of("base-one-root", "base-two-root"),
                childMergedNode.contributorProfileNames(),
                "Contributor order should track declared parent branch precedence"
            );

            pilot.press('p');
            pilot.pause();

            NodeRef parentNode = readSelectedNode(app);
            assertEquals("base-two-root", parentNode.profileName());
            assertEquals(baseTwoRootFile, parentNode.path());
            assertFalse(parentNode.readOnly());
            assertFalse(parentNode.parentOnlyMerged());
            assertEquals("Selected inherited parent file from profile base-two-root.", readStatus(app));
        }
    }

    @Test
    void deepMergedChildFileNavigatingParentFallsBackToProfileSelection() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path baseProfilePath = repositoryPath.resolve("base");
        Path childProfilePath = repositoryPath.resolve("child");
        Path parentFile = baseProfilePath.resolve("opencode.json");
        Path childFile = childProfilePath.resolve("opencode.json");
        Files.createDirectories(baseProfilePath);
        Files.createDirectories(childProfilePath);
        Files.writeString(parentFile, "{\"base\":true}\n");
        Files.writeString(childFile, "{\"child\":true}\n");
        Files.writeString(
            repositoryPath.resolve("repository.json"),
            "{\"profiles\":[{\"name\":\"base\"},{\"name\":\"child\",\"extends_from\":\"base\"}]}"
        );
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.onStart();

        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(app::render)) {
            Pilot pilot = test.pilot();

            boolean selected = app.testSelectTreeNodeAndSync(node ->
                node.kind() == NodeKind.FILE
                    && "child".equals(node.profileName())
                    && node.deepMerged()
                    && !node.readOnly()
            );
            assertTrue(selected, "Expected deep-merged child file to be selected");

            pilot.press('p');
            pilot.pause();

            NodeRef parentProfileNode = readSelectedNode(app);
            assertEquals(NodeKind.PROFILE, parentProfileNode.kind());
            assertEquals("base", parentProfileNode.profileName());
            assertEquals("Selected parent profile base.", readStatus(app));
        }
    }

    @Test
    void fileBasedRepositorySupportsRefreshShortcut() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Files.createDirectories(repositoryPath.resolve("default"));
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.onStart();

        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(app::render)) {
            Pilot pilot = test.pilot();

            pilot.press(KeyCode.DOWN);
            pilot.pause();
            pilot.press(KeyCode.UP);
            pilot.pause();
            assertEquals("repo-a", readSelectedNode(app).repositoryName());

            pilot.press('r');
            pilot.pause();
            assertEquals("Repository repo-a is file-based; nothing to refresh.", readStatus(app));

        }
    }

    @Test
    void profileSelectionSupportsUseAndDeleteFlows() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path defaultProfilePath = repositoryPath.resolve("default");
        Path otherProfilePath = repositoryPath.resolve("other");
        Files.createDirectories(defaultProfilePath);
        Files.createDirectories(otherProfilePath);
        Files.writeString(
            repositoryPath.resolve("repository.json"),
            "{\"profiles\":[{\"name\":\"default\"},{\"name\":\"other\"}]}"
        );
        Files.writeString(defaultProfilePath.resolve("opencode.json"), "{\"name\":\"default\"}\n");
        Files.writeString(otherProfilePath.resolve("opencode.json"), "{\"name\":\"other\"}\n");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.onStart();

        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(app::render)) {
            Pilot pilot = test.pilot();

            pilot.press(KeyCode.DOWN);
            pilot.pause();
            assertEquals("default", readSelectedNode(app).profileName());

            pilot.press('u');
            pilot.pause();
            assertEquals("Switched to profile default.", readStatus(app));

            pilot.press('d');
            pilot.pause();
            assertTrue(waitForPromptAction(app, PromptAction.DELETE_PROFILE));
            assertTrue(readStatus(app).contains("Switched to profile default."));

            pressText(pilot, "default");
            pilot.press(KeyCode.ENTER);
            pilot.pause();
            waitForPromptToClear(app);

            assertFalse(Files.exists(defaultProfilePath));
            assertEquals("Deleted profile default from repository repo-a.", readStatus(app));
            NodeRef selectedNode = readSelectedNode(app);
            assertEquals(NodeKind.PROFILE, selectedNode.kind());
            assertEquals("default", selectedNode.profileName());
            assertFalse(Files.exists(repositoryPath.resolve(selectedNode.profileName())));
        }
    }

    private InteractiveApp createApp() {
        return new InteractiveApp(
            applicationContext.getBean(ProfileService.class),
            applicationContext.getBean(RepositoryService.class),
            applicationContext.getBean(OnboardingService.class),
            applicationContext.getBean(RepositoryPostCreationService.class),
            objectMapper
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

    private static void pressDown(Pilot pilot, int count) throws Exception {
        for (int index = 0; index < count; index++) {
            pilot.press(KeyCode.DOWN);
            pilot.pause();
        }
    }

    private static void pressText(Pilot pilot, String value) throws Exception {
        for (char character : value.toCharArray()) {
            pilot.press(character);
        }
        pilot.pause();
    }

    private static boolean readEditMode(InteractiveApp app) throws Exception {
        return app.testEditMode();
    }

    private static String readStatus(InteractiveApp app) throws Exception {
        return app.testStatus();
    }

    private static NodeRef readSelectedNode(InteractiveApp app) throws Exception {
        return app.testSelectedNode();
    }

    private static String readSelectedNodeFileName(InteractiveApp app) throws Exception {
        NodeRef selectedNode = readSelectedNode(app);
        return selectedNode == null || selectedNode.path() == null ? null : selectedNode.path().getFileName().toString();
    }

    @SuppressWarnings("unchecked")
    private static String readSelectedTreeNodeFileName(InteractiveApp app) throws Exception {
        TreeElement<NodeRef> hierarchyTree = app.testHierarchyTree();
        TreeNode<NodeRef> selectedTreeNode = hierarchyTree.selectedNode();
        if (selectedTreeNode == null || selectedTreeNode.data() == null || selectedTreeNode.data().path() == null) {
            return null;
        }
        return selectedTreeNode.data().path().getFileName().toString();
    }

    @SuppressWarnings("unchecked")
    private static int readTreeSelectionIndex(InteractiveApp app) throws Exception {
        TreeElement<NodeRef> hierarchyTree = app.testHierarchyTree();
        return hierarchyTree.selected();
    }

    private static void waitForPromptToClear(InteractiveApp app) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (app.testPrompt() == null) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for prompt to clear");
    }

    private static boolean waitForPromptAction(InteractiveApp app, PromptAction action) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            PromptState prompt = app.testPrompt();
            if (prompt != null && prompt.action == action) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
