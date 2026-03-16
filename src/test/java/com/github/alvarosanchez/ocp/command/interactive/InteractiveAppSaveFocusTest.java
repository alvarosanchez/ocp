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
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.file("repo-a", "default", filePath));
        app.testSetEditMode(true);
        app.testSetActivePane(Pane.DETAIL);
        app.testEditorState().setText("updated");

        app.testSaveSelectedFile();

        assertEquals("updated", Files.readString(filePath));
        assertFalse(app.testEditMode());
        assertEquals(Pane.TREE, app.testActivePane());
        assertEquals("Saved opencode.json.", app.testStatus());
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
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.file("repo-a", "default", filePath));
        app.testSetEditMode(true);
        app.testSetActivePane(Pane.DETAIL);

        app.testExitEditModeToTree();

        assertFalse(app.testEditMode());
        assertEquals(Pane.TREE, app.testActivePane());
        assertEquals("Exited edit mode.", app.testStatus());
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
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.deepMergedFile("repo-a", "child", filePath));
        app.testSetEditMode(true);
        app.testEditorState().setText("{\"child\":1,\"shared\":3,\"new\":4}");

        app.testSaveSelectedFile();

        String preview = selectedFilePreviewText(app);
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
        app.testReloadState();

        app.testEditOcpConfigFile();

        assertFalse(app.testSelectedNode().inherited());
        assertEquals(configFilePath(), app.testSelectedNode().path());
        assertEquals(NodeKind.FILE, app.testSelectedNode().kind());
        assertEquals(Files.readString(configFilePath()), app.testEditorState().text());
        assertTrue(app.testEditMode());
        assertEquals(Pane.DETAIL, app.testActivePane());
        assertEquals("Editing config.json. Ctrl+S to save, Esc to cancel.", app.testStatus());
    }

    @Test
    void saveSelectedOcpConfigFileReloadsStateAfterSave() throws Exception {
        Path repositoryPath = tempDir.resolve("repo-a");
        Files.createDirectories(repositoryPath.resolve("default"));
        Files.writeString(repositoryPath.resolve("repository.json"), "{\"profiles\":[{\"name\":\"default\"}]}");
        writeConfig(new RepositoryEntry("repo-a", null, repositoryPath.toString()));

        InteractiveApp app = createApp();
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.file(null, null, configFilePath()));
        app.testSetEditMode(true);
        app.testEditorState().setText(objectMapper.writeValueAsString(new OcpConfigFile(
            new OcpConfigOptions(),
            List.of(new RepositoryEntry("repo-b", null, repositoryPath.toString()))
        )));

        app.testSaveSelectedFile();

        assertEquals("repo-b", app.testRepositories().getFirst().name());
        assertEquals("Saved config.json.", app.testStatus());
    }

    @Test
    void editOcpConfigFileReportsMissingConfig() throws Exception {
        InteractiveApp app = createApp();

        app.testEditOcpConfigFile();

        assertEquals("OCP config file does not exist yet. Add or create a repository first.", app.testStatus());
        assertFalse(app.testEditMode());
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
        app.testReloadState();
        app.testSetSelectedNode(NodeRef.file(null, null, configFilePath()));
        app.testSetEditMode(true);
        app.testEditorState().setText(Files.readString(configFilePath()));

        app.testSyncSelectionAndPreview();

        assertEquals(configFilePath(), app.testSelectedNode().path());
        assertTrue(app.testEditMode());
        assertEquals(Files.readString(configFilePath()), app.testEditorState().text());
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

    private Path configFilePath() {
        return Path.of(System.getProperty("ocp.config.dir")).resolve("config.json");
    }

    private static String selectedFilePreviewText(InteractiveApp app) {
        dev.tamboui.text.Text text = app.testSelectedFilePreview();
        StringBuilder builder = new StringBuilder();
        for (var line : text.lines()) {
            for (var span : line.spans()) {
                builder.append(span.content());
            }
        }
        return builder.toString();
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
