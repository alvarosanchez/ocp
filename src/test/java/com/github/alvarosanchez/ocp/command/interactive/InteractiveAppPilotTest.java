package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Files;
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

    private static boolean readEditMode(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("editMode");
        field.setAccessible(true);
        return field.getBoolean(app);
    }

    private static String readStatus(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("status");
        field.setAccessible(true);
        return (String) field.get(app);
    }

    private static String readSelectedNodeFileName(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedNode");
        field.setAccessible(true);
        NodeRef selectedNode = (NodeRef) field.get(app);
        return selectedNode == null || selectedNode.path() == null ? null : selectedNode.path().getFileName().toString();
    }

    @SuppressWarnings("unchecked")
    private static String readSelectedTreeNodeFileName(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("hierarchyTree");
        field.setAccessible(true);
        TreeElement<NodeRef> hierarchyTree = (TreeElement<NodeRef>) field.get(app);
        TreeNode<NodeRef> selectedTreeNode = hierarchyTree.selectedNode();
        if (selectedTreeNode == null || selectedTreeNode.data() == null || selectedTreeNode.data().path() == null) {
            return null;
        }
        return selectedTreeNode.data().path().getFileName().toString();
    }


    @SuppressWarnings("unchecked")
    private static int readTreeSelectionIndex(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("hierarchyTree");
        field.setAccessible(true);
        TreeElement<NodeRef> hierarchyTree = (TreeElement<NodeRef>) field.get(app);
        return hierarchyTree.selected();
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
