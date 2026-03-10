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
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveAppSaveFocusTest {

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
    void saveSelectedFileReturnsToTreePaneAndExitsEditMode() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("opencode.json");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(filePath, "original");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.file("repo-a", "default", filePath));
        setEditMode(app, true);
        setActivePane(app, Pane.DETAIL);
        setEditorText(app, "updated");

        invokeSaveSelectedFile(app);

        assertEquals("updated", Files.readString(filePath));
        assertFalse(readEditMode(app));
        assertEquals(Pane.TREE, readActivePane(app));
        assertEquals("Saved opencode.json.", readStatus(app));
    }


    @Test
    void escapeExitsEditModeAndReturnsToTreePane() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("opencode.json");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(filePath, "original");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.file("repo-a", "default", filePath));
        setEditMode(app, true);
        setActivePane(app, Pane.DETAIL);

        invokeExitEditMode(app);

        assertFalse(readEditMode(app));
        assertEquals(Pane.TREE, readActivePane(app));
        assertEquals("Exited edit mode.", readStatus(app));
    }

    @Test
    void saveSelectedDeepMergedFileRefreshesResolvedPreviewContent() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path baseProfilePath = repositoryPath.resolve("base");
        Path childProfilePath = repositoryPath.resolve("child");
        Path filePath = childProfilePath.resolve("opencode.json");
        Files.createDirectories(baseProfilePath);
        Files.createDirectories(childProfilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"base\"},{\"name\":\"child\",\"extends_from\":\"base\"}]}");
        Files.writeString(baseProfilePath.resolve("opencode.json"), "{\"base\":1,\"shared\":1}");
        Files.writeString(filePath, "{\"child\":1,\"shared\":2}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.deepMergedFile("repo-a", "child", filePath));
        setEditMode(app, true);
        setEditorText(app, "{\"child\":1,\"shared\":3,\"new\":4}");

        invokeSaveSelectedFile(app);

        String preview = readSelectedFilePreviewText(app);
        assertEquals(
            objectMapper.readValue("{\"base\":1,\"shared\":3,\"child\":1,\"new\":4}", Object.class),
            objectMapper.readValue(preview, Object.class)
        );
    }

    @Test
    void editOcpConfigFileEntersEditModeForConfigJson() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Files.createDirectories(repositoryPath.resolve("default"));
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);

        invokeEditOcpConfigFile(app);

        assertFalse(readSelectedNode(app).inherited());
        assertEquals(configFilePath(), readSelectedNode(app).path());
        assertEquals(NodeKind.FILE, readSelectedNode(app).kind());
        assertEquals(Files.readString(configFilePath()), readEditorText(app));
        assertTrue(readEditMode(app));
        assertEquals(Pane.DETAIL, readActivePane(app));
        assertEquals("Editing config.json. Ctrl+S to save, Esc to cancel.", readStatus(app));
    }

    @Test
    void saveSelectedOcpConfigFileReloadsStateAfterSave() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Files.createDirectories(repositoryPath.resolve("default"));
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.file(null, null, configFilePath()));
        setEditMode(app, true);
        setEditorText(app, objectMapper.writeValueAsString(new OcpConfigFile(
            new OcpConfigOptions(),
            List.of(new RepositoryEntry("repo-b", null, repositoryPath.toString()))
        )));

        invokeSaveSelectedFile(app);

        assertEquals("repo-b", readRepositories(app).getFirst().name());
        assertEquals("Saved config.json.", readStatus(app));
    }

    @Test
    void editOcpConfigFileReportsMissingConfig() throws Exception {
        InteractiveApp app = createApp();

        invokeEditOcpConfigFile(app);

        assertEquals("OCP config file does not exist yet. Add or create a repository first.", readStatus(app));
        assertFalse(readEditMode(app));
    }

    @Test
    void syncSelectionAndPreviewPreservesOcpConfigEditModeWhenTreeSelectionIsUnchanged() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("opencode.json");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(filePath, "{}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.file(null, null, configFilePath()));
        setEditMode(app, true);
        setEditorText(app, Files.readString(configFilePath()));

        invokeSyncSelectionAndPreview(app);

        assertEquals(configFilePath(), readSelectedNode(app).path());
        assertTrue(readEditMode(app));
        assertEquals(Files.readString(configFilePath()), readEditorText(app));
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

    private static void invokeExitEditMode(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("exitEditModeToTree");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static void invokeSaveSelectedFile(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("saveSelectedFile");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static void invokeEditOcpConfigFile(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("editOcpConfigFile");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static void invokeSyncSelectionAndPreview(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("syncSelectionAndPreview");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static void setSelectedNode(InteractiveApp app, NodeRef nodeRef) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedNode");
        field.setAccessible(true);
        field.set(app, nodeRef);
    }

    private static void setEditMode(InteractiveApp app, boolean value) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("editMode");
        field.setAccessible(true);
        field.setBoolean(app, value);
    }

    private static boolean readEditMode(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("editMode");
        field.setAccessible(true);
        return field.getBoolean(app);
    }

    private static void setActivePane(InteractiveApp app, Pane pane) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("activePane");
        field.setAccessible(true);
        field.set(app, pane);
    }

    private static Pane readActivePane(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("activePane");
        field.setAccessible(true);
        return (Pane) field.get(app);
    }

    private static void setEditorText(InteractiveApp app, String text) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("editorState");
        field.setAccessible(true);
        dev.tamboui.widgets.input.TextAreaState editorState = (dev.tamboui.widgets.input.TextAreaState) field.get(app);
        editorState.setText(text);
    }

    private static String readStatus(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("status");
        field.setAccessible(true);
        return (String) field.get(app);
    }

    private static NodeRef readSelectedNode(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedNode");
        field.setAccessible(true);
        return (NodeRef) field.get(app);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<RepositoryService.ConfiguredRepository> readRepositories(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("repositories");
        field.setAccessible(true);
        return (java.util.List<RepositoryService.ConfiguredRepository>) field.get(app);
    }

    private Path configFilePath() {
        return Path.of(System.getProperty("ocp.config.dir")).resolve("config.json");
    }

    private static String readSelectedFilePreviewText(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedFilePreview");
        field.setAccessible(true);
        dev.tamboui.text.Text text = (dev.tamboui.text.Text) field.get(app);
        StringBuilder builder = new StringBuilder();
        for (var line : text.lines()) {
            for (var span : line.spans()) {
                builder.append(span.content());
            }
        }
        return builder.toString();
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
