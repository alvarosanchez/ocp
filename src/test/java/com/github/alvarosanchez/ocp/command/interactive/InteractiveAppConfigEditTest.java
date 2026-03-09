package com.github.alvarosanchez.ocp.command.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class InteractiveAppConfigEditTest {

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
    void editConfigFileLoadsContentAndEntersEditMode() throws Exception {
        writeConfig(new RepositoryEntry("repo-a", null, tempDir.resolve("repo-a").toString()));
        Path configPath = tempDir.resolve("config").resolve("config.json");
        String originalContent = Files.readString(configPath);

        InteractiveApp app = createApp();
        invokeEditConfigFile(app);

        assertTrue(readEditMode(app));
        assertTrue(readEditingConfigFile(app));
        assertEquals(originalContent, readEditorText(app));
        assertEquals("Editing `config.json`. Ctrl+S to save, Esc to cancel.", readStatus(app));
    }

    @Test
    void saveConfigFileWritesContentAndReloadsState() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Path profilePath = repositoryPath.resolve("default");
        Files.createDirectories(profilePath);
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        Files.writeString(profilePath.resolve("opencode.json"), "{}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));
        Path configPath = tempDir.resolve("config").resolve("config.json");

        InteractiveApp app = createApp();
        invokeReloadState(app);
        invokeEditConfigFile(app);

        String updatedContent = "{\"options\":{},\"repositories\":[]}";
        setEditorText(app, updatedContent);

        invokeSaveConfigFile(app);

        assertEquals(updatedContent, Files.readString(configPath));
        assertFalse(readEditMode(app));
        assertFalse(readEditingConfigFile(app));
        assertNull(readConfigFileEditPath(app));
        assertEquals("Saved `config.json`.", readStatus(app));
    }

    @Test
    void editConfigFileReportsErrorWhenConfigFileMissing() throws Exception {
        InteractiveApp app = createApp();
        invokeEditConfigFile(app);

        assertFalse(readEditMode(app));
        assertTrue(readStatus(app).startsWith("OCP config file not found:"));
    }

    @Test
    void detailHintReturnsEditModeForConfigFileEditing() {
        assertEquals(
            "Editing mode: Ctrl+S save, Esc exit",
            DetailPaneRenderer.detailHint(null, true, true)
        );
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

    private static void invokeEditConfigFile(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("editConfigFile");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static void invokeSaveConfigFile(InteractiveApp app) throws Exception {
        Method method = InteractiveApp.class.getDeclaredMethod("saveConfigFile");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static boolean readEditMode(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("editMode");
        field.setAccessible(true);
        return field.getBoolean(app);
    }

    private static boolean readEditingConfigFile(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("editingConfigFile");
        field.setAccessible(true);
        return field.getBoolean(app);
    }

    private static Path readConfigFileEditPath(InteractiveApp app) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("configFileEditPath");
        field.setAccessible(true);
        return (Path) field.get(app);
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

    private static void setEditorText(InteractiveApp app, String text) throws Exception {
        Field field = InteractiveApp.class.getDeclaredField("editorState");
        field.setAccessible(true);
        dev.tamboui.widgets.input.TextAreaState editorState = (dev.tamboui.widgets.input.TextAreaState) field.get(app);
        editorState.setText(text);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
