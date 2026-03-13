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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
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
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.profile("repo-a", "default", profilePath));

        PromptState prompt = PromptState.single(PromptAction.CREATE_FILE, "Create file", "File name");
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "new.json");
        setPrompt(app, prompt);

        invokeApplyPrompt(app);

        Path createdFile = profilePath.resolve("new.json");
        assertTrue(Files.exists(createdFile));
        assertEquals("", Files.readString(createdFile));
        assertEquals(createdFile, readSelectedNode(app).path());
        assertEquals(createdFile, readSelectedTreeNode(app).path());
        assertTrue(readEditMode(app));
        assertEquals(Pane.DETAIL, readActivePane(app));
        assertEquals("Editing new.json. Ctrl+S to save, Esc to cancel.", readStatus(app));
        assertEquals("", readEditorText(app));
    }

    @Test
    void createFileKeepsCreatedFileAsSelectedTreeNode() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.profile("repo-a", "default", profilePath));

        PromptState prompt = PromptState.single(PromptAction.CREATE_FILE, "Create file", "File name");
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "selected.json");
        setPrompt(app, prompt);

        invokeApplyPrompt(app);

        NodeRef selectedNode = readSelectedNode(app);
        assertNotNull(selectedNode);
        assertEquals(NodeKind.FILE, selectedNode.kind());
        assertEquals(profilePath.resolve("selected.json"), selectedNode.path());
        assertEquals("default", selectedNode.profileName());
        assertEquals(profilePath.resolve("selected.json"), readSelectedTreeNode(app).path());
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
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.file("repo-a", "default", existingFile));

        PromptState prompt = PromptState.single(PromptAction.CREATE_FILE, "Create file", "File name");
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "extra.json");
        setPrompt(app, prompt);

        invokeApplyPrompt(app);

        assertTrue(Files.exists(profilePath.resolve("extra.json")));
        assertEquals(profilePath.resolve("extra.json"), readSelectedNode(app).path());
        assertEquals(profilePath.resolve("extra.json"), readSelectedTreeNode(app).path());
        assertTrue(readEditMode(app));
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
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.file("repo-a", "default", filePath));

        PromptState prompt = PromptState.single(PromptAction.DELETE_FILE, "Delete file", "Type file name to confirm: opencode.json");
        prompt.expectedConfirmation = "opencode.json";
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "opencode.json");
        setPrompt(app, prompt);

        invokeApplyPrompt(app);

        assertFalse(Files.exists(filePath));
        NodeRef selectedNode = readSelectedNode(app);
        assertNotNull(selectedNode);
        assertEquals(NodeKind.PROFILE, selectedNode.kind());
        assertEquals("default", selectedNode.profileName());
        assertEquals(profilePath, selectedNode.path());
        assertEquals("Deleted file opencode.json.", readStatus(app));
        assertFalse(readEditMode(app));
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
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.file("repo-a", "default", filePath));

        PromptState prompt = PromptState.single(PromptAction.DELETE_FILE, "Delete file", "Type file name to confirm: opencode.json");
        prompt.expectedConfirmation = "opencode.json";
        prompt.contextRepositoryName = "repo-a";
        prompt.values.set(0, "wrong.json");
        setPrompt(app, prompt);

        invokeApplyPrompt(app);

        assertTrue(Files.exists(filePath));
        assertEquals("Delete cancelled: file name mismatch.", readStatus(app));
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

    private static void invokeReloadState(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("reloadState");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static void invokeApplyPrompt(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("applyPrompt");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static void setPrompt(InteractiveApp app, PromptState prompt) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("prompt");
        field.setAccessible(true);
        field.set(app, prompt);
    }


    private static void setSelectedNode(InteractiveApp app, NodeRef selectedNode) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedNode");
        field.setAccessible(true);
        field.set(app, selectedNode);
    }

    private static NodeRef readSelectedNode(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedNode");
        field.setAccessible(true);
        return (NodeRef) field.get(app);
    }

    private static NodeRef readSelectedTreeNode(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("hierarchyTree");
        field.setAccessible(true);
        TreeElement<NodeRef> hierarchyTree = (TreeElement<NodeRef>) field.get(app);
        populateFlatEntries(app, hierarchyTree);
        TreeNode<NodeRef> selectedTreeNode = hierarchyTree.selectedNode();
        return selectedTreeNode == null ? null : selectedTreeNode.data();
    }

    private static void populateFlatEntries(InteractiveApp app, TreeElement<NodeRef> hierarchyTree) throws Exception {
        Field rootsField = InteractiveApp.class.getDeclaredField("hierarchyRoots");
        rootsField.setAccessible(true);
        List<TreeNode<NodeRef>> roots = (List<TreeNode<NodeRef>>) rootsField.get(app);

        Field lastFlatEntriesField = TreeElement.class.getDeclaredField("lastFlatEntries");
        lastFlatEntriesField.setAccessible(true);
        lastFlatEntriesField.set(hierarchyTree, buildFlatEntries(roots));
    }

    private static List<Object> buildFlatEntries(List<TreeNode<NodeRef>> roots) throws Exception {
        List<Object> flatEntries = new java.util.ArrayList<>();
        Constructor<?> constructor = Class.forName("dev.tamboui.widgets.tree.TreeWidget$FlatEntry")
            .getDeclaredConstructor(Object.class, Object.class, int.class, List.class, boolean.class);
        constructor.setAccessible(true);
        for (TreeNode<NodeRef> root : roots) {
            addFlatEntries(flatEntries, constructor, root, null, 0);
        }
        return flatEntries;
    }

    private static void addFlatEntries(
        List<Object> flatEntries,
        Constructor<?> constructor,
        TreeNode<NodeRef> node,
        TreeNode<NodeRef> parent,
        int depth
    ) throws Exception {
        flatEntries.add(constructor.newInstance(node, parent, depth, List.of(), false));
        if (!node.isLeaf() && node.isExpanded()) {
            for (TreeNode<NodeRef> child : node.children()) {
                addFlatEntries(flatEntries, constructor, child, node, depth + 1);
            }
        }
    }

    private static boolean readEditMode(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("editMode");
        field.setAccessible(true);
        return field.getBoolean(app);
    }

    private static Pane readActivePane(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("activePane");
        field.setAccessible(true);
        return (Pane) field.get(app);
    }

    private static String readStatus(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("status");
        field.setAccessible(true);
        return (String) field.get(app);
    }

    private static String readEditorText(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("editorState");
        field.setAccessible(true);
        dev.tamboui.widgets.input.TextAreaState editorState = (dev.tamboui.widgets.input.TextAreaState) field.get(app);
        return editorState.text();
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
