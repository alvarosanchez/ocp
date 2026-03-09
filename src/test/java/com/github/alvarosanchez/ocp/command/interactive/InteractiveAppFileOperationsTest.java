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
import dev.tamboui.widgets.input.TextAreaState;
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

class InteractiveAppFileOperationsTest {

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
    void createFileFromInheritedSelectionCreatesSiblingInSelectedProfileAndOpensEditor() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path baseProfilePath = repositoryPath.resolve("base");
        Path childProfilePath = repositoryPath.resolve("child");
        Path inheritedFile = baseProfilePath.resolve("nested").resolve("base.json");
        Path createdFile = childProfilePath.resolve("nested").resolve("child.json");
        Files.createDirectories(inheritedFile.getParent());
        Files.createDirectories(childProfilePath);
        Files.writeString(
            repositoryPath.resolve("repository.json"),
            "{\"profiles\":[{\"name\":\"base\"},{\"name\":\"child\",\"extends_from\":\"base\"}]}"
        );
        Files.writeString(inheritedFile, "{\"base\":true}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.inheritedFile("repo-a", "child", inheritedFile, "base"));

        PromptState prompt = PromptState.single(PromptAction.CREATE_FILE, "Create file", "File name");
        prompt.values.set(0, "child.json");
        setPrompt(app, prompt);

        invokeApplyPrompt(app);

        assertTrue(Files.isRegularFile(createdFile));
        assertEquals("", Files.readString(createdFile));
        assertEquals(NodeKind.FILE, readSelectedNode(app).kind());
        assertEquals(createdFile.toAbsolutePath().normalize(), readSelectedNode(app).path().toAbsolutePath().normalize());
        assertTrue(readEditMode(app));
        assertEquals(Pane.DETAIL, readActivePane(app));
        assertEquals("", readEditorText(app));
        assertEquals("Editing `child.json`. Ctrl+S to save, Esc to cancel.", readStatus(app));
    }

    @Test
    void deleteFileRemovesEditableSelectionAfterConfirmation() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("opencode.json");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(filePath, "{\"theme\":\"dark\"}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.file("repo-a", "default", filePath));

        PromptState prompt = PromptState.single(PromptAction.DELETE_FILE, "Delete file", "Type file name to confirm: opencode.json");
        prompt.expectedConfirmation = "opencode.json";
        prompt.values.set(0, "opencode.json");
        setPrompt(app, prompt);

        invokeApplyPrompt(app);

        assertFalse(Files.exists(filePath));
        assertEquals("Deleted `opencode.json` from profile `default`.", readStatus(app));
    }

    @Test
    void deleteFileKeepsEditableSelectionWhenConfirmationDoesNotMatch() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Path filePath = profilePath.resolve("opencode.json");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(filePath, "{\"theme\":\"dark\"}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        invokeReloadState(app);
        setSelectedNode(app, NodeRef.file("repo-a", "default", filePath));

        PromptState prompt = PromptState.single(PromptAction.DELETE_FILE, "Delete file", "Type file name to confirm: opencode.json");
        prompt.expectedConfirmation = "opencode.json";
        prompt.values.set(0, "different.json");
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

    private static void setSelectedNode(InteractiveApp app, NodeRef nodeRef) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedNode");
        field.setAccessible(true);
        field.set(app, nodeRef);
    }

    private static NodeRef readSelectedNode(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("selectedNode");
        field.setAccessible(true);
        return (NodeRef) field.get(app);
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
        TextAreaState editorState = (TextAreaState) field.get(app);
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
